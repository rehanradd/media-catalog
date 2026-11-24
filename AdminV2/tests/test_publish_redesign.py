"""PNL-01 publish redesign tests — narrowed scope §0A (N1-N4).

The first review's broader shape (unified 28-field publish, intent
journal + reconciler, blanket fail-closed, pause-flag rewriting) is
SUPERSEDED. What this file pins, per the second opinion:

(a) concurrent double-publish -> strictly increasing history versions.
(b) free_quality_cap publish -> the live row carries it (draft path and
    monetization path) + the served body carries it.
(c) live configured-but-unavailable -> 503, panel version unchanged;
    the fixture branch (no live store configured) still publishes,
    labelled "not_configured".
(d) rollback with a failing live write -> panel row and audit
    unchanged (no success rollback audit, no new history row), the
    refusal recorded as a rollback.refused audit row.
(e) rollback restores live fields to the snapshot's values at a new
    higher version (max(live, panel)+1).
(f) draft-column coverage: every draft field maps to exactly one
    route — live-row (in both MONETIZATION_FIELDS and
    REMOTE_CONFIG_FIELDS) or panel-feed (V2_ONLY_SNAPSHOT_FIELDS);
    the APK descriptor trio + feature_link_shorteners are v1-owned,
    not draftable.

Plus the process guards that survive §0A: T7 drift guard, T8 backup
guard, T10 audit diff + hash, T11 staleness pins, F8 get_live diff.

Every live integration runs against TEMP COPIES (real
app_remote_config DDL). No test ever touches a live DB.
"""
import importlib.util
import json
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import make_service  # noqa: E402
from admin_v2.errors import ApiError  # noqa: E402
from admin_v2.live_stores import (  # noqa: E402
    ALL_LIVE_REMOTE_CONFIG_FIELDS, RELEASE_OWNED_LIVE_FIELDS,
    REMOTE_CONFIG_FIELDS, V1_ONLY_LIVE_FIELDS, V2_ONLY_SNAPSHOT_FIELDS,
    LiveStores, ensure_live_remote_config_columns)
from admin_v2.services import DRAFT_REMOTE_CONFIG_FIELDS, Service  # noqa: E402
import admin_v2.services as services_mod  # noqa: E402
from admin_v2.store import MONETIZATION_FIELDS as V2_MONETIZATION_FIELDS  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO_ROOT, "Fast_Fetch"))
import admin_panel  # noqa: E402

FULL_DDL = """
CREATE TABLE app_remote_config (id INTEGER PRIMARY KEY CHECK (id = 1),
  pak_till_id TEXT NOT NULL DEFAULT '', pak_jazzcash_number TEXT NOT NULL DEFAULT '',
  pak_easypaisa_number TEXT NOT NULL DEFAULT '', pak_raast_id TEXT NOT NULL DEFAULT '',
  vip_monthly_pkr INTEGER NOT NULL DEFAULT 200, vip_yearly_pkr INTEGER NOT NULL DEFAULT 1500,
  monetag_zone_id TEXT NOT NULL DEFAULT '', adsterra_tag_id TEXT NOT NULL DEFAULT '',
  shrinkme_api_token TEXT NOT NULL DEFAULT '', droplink_api_token TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL DEFAULT 0, maintenance_mode INTEGER NOT NULL DEFAULT 0,
  apk_update_url TEXT NOT NULL DEFAULT '', feature_downloads INTEGER NOT NULL DEFAULT 1,
  feature_pure_cinema INTEGER NOT NULL DEFAULT 1, feature_chromecast INTEGER NOT NULL DEFAULT 1,
  feature_quality_gate INTEGER NOT NULL DEFAULT 0,
  coin_redeem_day INTEGER NOT NULL DEFAULT 500, coin_redeem_weekend INTEGER NOT NULL DEFAULT 1200,
  coin_redeem_monthly INTEGER NOT NULL DEFAULT 5000, coin_redeem_download INTEGER NOT NULL DEFAULT 100,
  coin_redeem_request INTEGER NOT NULL DEFAULT 300, coin_redeem_theme INTEGER NOT NULL DEFAULT 250);
"""


class RedesignBase(unittest.TestCase):
    def _live_service(self, ddl=FULL_DDL):
        self.tmp = tempfile.mkdtemp(prefix="adminv2-redesign-")
        self.catalog_path = os.path.join(self.tmp, "catalog.db")
        conn = sqlite3.connect(self.catalog_path)
        conn.executescript(ddl)
        conn.execute("INSERT INTO app_remote_config (id) VALUES (1)")
        conn.commit()
        conn.close()
        self.live = LiveStores(catalog_path=self.catalog_path)
        self.svc = Service(os.path.join(self.tmp, "admin"), live=self.live)
        owner_id = self.svc.create_owner("owner", "correct horse battery staple")
        self.owner = {"id": owner_id, "username": "owner", "role": "owner"}
        return self.svc

    def _catalog(self):
        conn = sqlite3.connect(self.catalog_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _live_row(self):
        return self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()

    def _panel(self):
        return self.svc.admin

    def _history(self):
        return [dict(r) for r in self._panel().execute(
            "SELECT * FROM app_remote_config_history ORDER BY config_version"
        ).fetchall()]

    def _audit(self, action=None):
        rows = [dict(r) for r in self._panel().execute(
            "SELECT * FROM admin_audit_log ORDER BY id").fetchall()]
        if action is not None:
            rows = [r for r in rows if r["action"] == action]
        return rows

    def _publish(self, **draft):
        if draft:
            self.svc.draft_remote_config(self.owner, draft)
        return self.svc.publish_remote_config(self.owner, {"confirm": True})


class TestConcurrentPublish(RedesignBase):
    def test_concurrent_publishes_allocate_strictly_increasing_versions(self):
        """(a)/N2: five threads publishing at once land five distinct,
        consecutive versions — no duplicate version, no lost publish.
        The version is computed INSIDE the process-wide publish mutex
        from fresh reads, so the interleaving cannot mint the same
        version twice."""
        self._live_service()
        self.svc.draft_remote_config(self.owner, {"brand_primary": "#111111"})
        results, errors = [], []

        def go():
            try:
                results.append(self.svc.publish_remote_config(
                    self.owner, {"confirm": True})["config_version"])
            except Exception as exc:  # noqa: BLE001 — collected, asserted below
                errors.append(exc)

        threads = [threading.Thread(target=go) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(errors, [])
        self.assertEqual(sorted(results), [1, 2, 3, 4, 5])
        self.assertEqual(self._live_row()["config_version"], 5)
        # The v0 seed row plus one history row per publish.
        self.assertEqual([h["config_version"] for h in self._history()],
                         [0, 1, 2, 3, 4, 5])

    def test_publish_racing_pause_serializes(self):
        """(a)/N2: a publish racing a feature pause both land and
        versions stay strictly increasing with no duplicates. The
        pause bumps the panel version without writing live (pause->live
        is PNL-11's), so the live row may lag the panel by one — that
        lag is legal; a duplicate version is not."""
        self._live_service()
        self.svc.draft_remote_config(self.owner, {"brand_primary": "#222222"})
        errors = []

        def publish():
            try:
                self.svc.publish_remote_config(self.owner, {"confirm": True})
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)

        def pause():
            try:
                self.svc.pause(self.owner, {"target_type": "feature",
                                            "target_id": "downloads",
                                            "reason": "race test"})
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)

        t1, t2 = threading.Thread(target=publish), threading.Thread(target=pause)
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        self.assertEqual(errors, [])
        self.assertTrue(self.svc.is_paused("feature", "downloads"))
        # The pause wrote no live row: the flag is still the draft value.
        self.assertEqual(self._live_row()["feature_downloads"], 1)
        versions = [h["config_version"] for h in self._history()]
        self.assertEqual(len(versions), 3)  # seed v0 + publish + pause
        self.assertEqual(len(set(versions)), 3)
        self.assertEqual(self.svc._config_row()["config_version"], 2)


class TestCapPublish(RedesignBase):
    def test_cap_change_reaches_live_row_and_served_body(self):
        """(b)/N1: a draft free_quality_cap change is published — the
        field no writer pushed before. The live row AND the served
        remote-config body both carry it."""
        self._live_service()
        out = self._publish(free_quality_cap="480p")
        self.assertEqual(out["config_version"], 1)
        self.assertEqual(self._live_row()["free_quality_cap"], "480p")
        with sqlite3.connect(self.catalog_path) as conn:
            body = admin_panel.remote_config(conn)
        self.assertEqual(body["free_quality_cap"], "480p")
        self.assertFalse(body["settings_unavailable"])

    def test_monetization_publish_carries_cap(self):
        """(b)/N1: the monetization path carries the cap too — a
        put_monetization carrying free_quality_cap lands it on the
        live row and in the served body, at a new version."""
        self._live_service()
        out = self.svc.put_monetization(self.owner,
                                        {"free_quality_cap": "480p"})
        self.assertEqual(out["publish_status"], "ok")
        self.assertEqual(out["config_version"], 1)
        self.assertEqual(self._live_row()["free_quality_cap"], "480p")
        with sqlite3.connect(self.catalog_path) as conn:
            body = admin_panel.remote_config(conn)
        self.assertEqual(body["free_quality_cap"], "480p")

    def test_flag_flip_and_force_update_publish_together(self):
        """(b)/N1: one publish carries a flag flip AND a snapshot-only
        force-update block under the same version — the two routes
        stay in step."""
        self._live_service()
        out = self._publish(maintenance_mode=1, force_update=1,
                            force_update_message="Update now")
        snap = json.loads(self._history()[-1]["snapshot_json"])
        self.assertEqual(self._live_row()["maintenance_mode"], 1)
        self.assertEqual(snap["config"]["force_update"], 1)
        self.assertEqual(snap["config"]["force_update_message"],
                         "Update now")
        self.assertEqual(snap["config"]["config_version"],
                         out["config_version"])

    def test_apk_descriptor_fields_are_v1_owned_not_draftable(self):
        """(b)/N1: the APK descriptor trio + feature_link_shorteners
        are v1-owned, NOT draft fields — drafting one is refused as an
        unknown field, and v1 /admin still writes them (the writer
        exists; the app reads them for its update check)."""
        self._live_service()
        for field in ("apk_update_sha256", "apk_update_size_bytes",
                      "apk_update_version_code", "feature_link_shorteners"):
            with self.assertRaises(ApiError):
                self.svc.draft_remote_config(self.owner, {field: 1})
            self.assertNotIn(field, DRAFT_REMOTE_CONFIG_FIELDS)
            self.assertNotIn(field, REMOTE_CONFIG_FIELDS)
        with sqlite3.connect(self.catalog_path) as conn:
            result, errors = admin_panel._save_monetization(conn, {
                "apk_update_sha256": "a" * 64,
                "apk_update_size_bytes": 123456,
                "apk_update_version_code": 42,
                "feature_link_shorteners": 1})
        self.assertIsNone(errors)
        row = self._live_row()
        self.assertEqual(row["apk_update_sha256"], "a" * 64)
        self.assertEqual(row["apk_update_size_bytes"], 123456)
        self.assertEqual(row["apk_update_version_code"], 42)
        self.assertEqual(row["feature_link_shorteners"], 1)


class TestLiveUnavailable(RedesignBase):
    def test_fixture_branch_publishes_labelled_not_configured(self):
        """(c)/N3: no live store CONFIGURED (fixture/test) -> the
        publish still works panel-only, explicitly labelled
        "not_configured" in the response."""
        svc, owner, _aud = make_service()
        svc.draft_remote_config(owner, {"maintenance_mode": 1})
        out = svc.publish_remote_config(owner, {"confirm": True})
        self.assertEqual(out["config_version"], 1)
        self.assertEqual(out["live"], "not_configured")
        hist = [dict(r) for r in svc.admin.execute(
            "SELECT * FROM app_remote_config_history").fetchall()]
        self.assertEqual([h["config_version"] for h in hist], [0, 1])

    def test_configured_but_unavailable_refuses_503(self):
        """(c)/N3: a live store CONFIGURED but unavailable (catalog
        file missing) -> publish refuses with 503 live_unavailable,
        panel version untouched, no history, and a publish.refused
        audit row (with reason, no version fields)."""
        tmp = tempfile.mkdtemp(prefix="adminv2-nolive-")
        missing = os.path.join(tmp, "no-such-catalog.db")
        svc = Service(os.path.join(tmp, "admin"),
                      live=LiveStores(catalog_path=missing))
        owner_id = svc.create_owner("owner", "correct horse battery staple")
        owner = {"id": owner_id, "username": "owner", "role": "owner"}
        svc.draft_remote_config(owner, {"maintenance_mode": 1})
        before = svc._config_row()["config_version"]
        with self.assertRaises(ApiError) as ctx:
            svc.publish_remote_config(owner, {"confirm": True})
        self.assertEqual(ctx.exception.code, "live_unavailable")
        self.assertEqual(ctx.exception.status, 503)
        self.assertEqual(svc._config_row()["config_version"], before)
        # Only the v0 seed row exists: no publish history was created.
        hist = [dict(r) for r in svc.admin.execute(
            "SELECT * FROM app_remote_config_history").fetchall()]
        self.assertEqual([h["config_version"] for h in hist], [0])
        refused = [dict(x) for x in svc.admin.execute(
            "SELECT * FROM admin_audit_log "
            "WHERE action='publish.refused'").fetchall()]
        self.assertEqual(len(refused), 1)
        after = json.loads(refused[0]["after_json"])
        self.assertEqual(after["code"], "live_unavailable")
        self.assertIn("reason", after)
        self.assertNotIn("config_version", after)

    def test_rollback_configured_but_unavailable_refuses_before_staging(self):
        """(c)/N3/N4: a rollback with a configured-but-unavailable
        catalog refuses 503 and leaves even the draft row unstaged."""
        self._live_service()
        self._publish(brand_primary="#111111")
        admin_dir = os.path.join(self.tmp, "admin")
        missing = os.path.join(self.tmp, "no-such-catalog.db")
        svc2 = Service(admin_dir, live=LiveStores(catalog_path=missing))
        owner2 = {"id": self.owner["id"], "username": "owner",
                  "role": "owner"}
        before_draft = dict(svc2._config_row())
        with self.assertRaises(ApiError) as ctx:
            svc2.rollback_remote_config(owner2, {"version": 1,
                                                 "confirm": True})
        self.assertEqual(ctx.exception.code, "live_unavailable")
        self.assertEqual(ctx.exception.status, 503)
        self.assertEqual(dict(svc2._config_row()), before_draft)
        # Seed v0 + the v1 publish: the refused rollback added nothing.
        self.assertEqual(len(self._history()), 2)
        refused = self._audit("rollback.refused")
        self.assertEqual(len(refused), 1)

    def test_run_py_boot_aborts_without_live_env(self):
        """(c)/N3: run.py refuses to start without RADD_CATALOG_DB
        (not configured at all)."""
        run_py = os.path.join(REPO_ROOT, "AdminV2", "run.py")
        tmp = tempfile.mkdtemp(prefix="adminv2-boot-")
        env = dict(os.environ)
        env.pop("RADD_CATALOG_DB", None)
        proc = subprocess.run(
            [sys.executable, run_py, "--base-dir",
             os.path.join(tmp, "data"), "--port", "18099"],
            capture_output=True, text=True, timeout=60, env=env)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("RADD_CATALOG_DB", proc.stderr)

    def test_run_py_starts_with_configured_but_missing_file(self):
        """(c)/N3: a set-but-missing RADD_CATALOG_DB is NOT a boot
        failure — the panel starts and listens; publishes refuse with
        503 at request time (pinned by
        test_configured_but_unavailable_refuses_503)."""
        run_py = os.path.join(REPO_ROOT, "AdminV2", "run.py")
        tmp = tempfile.mkdtemp(prefix="adminv2-boot-")
        env = dict(os.environ)
        env["RADD_CATALOG_DB"] = os.path.join(tmp, "no-such-catalog.db")
        proc = subprocess.Popen(
            [sys.executable, run_py, "--base-dir",
             os.path.join(tmp, "data"), "--port", "18096"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, env=env)
        try:
            deadline = time.time() + 20
            listening = False
            while time.time() < deadline:
                if proc.poll() is not None:
                    break
                try:
                    with socket.create_connection(
                            ("127.0.0.1", 18096), timeout=1):
                        listening = True
                        break
                except OSError:
                    time.sleep(0.5)
            self.assertIsNone(proc.poll(),
                              "run.py exited early instead of serving")
            self.assertTrue(listening, "run.py never bound its port")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()


class TestRollback(RedesignBase):
    def test_rollback_with_failing_live_write_leaves_panel_unchanged(self):
        """(d)/N4: the live write runs BEFORE the panel commit, so a
        failing live write aborts before any panel mutation — panel
        version and draft row unchanged, no new history row, no
        successful rollback audit — and the refusal IS recorded as a
        rollback.refused audit row (that is the "recorded" in N4)."""
        self._live_service()
        self._publish(free_quality_cap="720p")
        self._publish(free_quality_cap="480p")
        self.assertEqual(self._live_row()["config_version"], 2)
        self._catalog().execute("DROP TABLE app_remote_config")
        self._catalog().commit()
        with self.assertRaises(ApiError) as ctx:
            self.svc.rollback_remote_config(
                self.owner, {"version": 1, "confirm": True})
        self.assertEqual(ctx.exception.code, "schema_drift")
        self.assertEqual(ctx.exception.status, 500)
        # Panel untouched: still v2, draft still the v2 values.
        self.assertEqual(self.svc._config_row()["config_version"], 2)
        self.assertEqual(self.svc._config_row()["free_quality_cap"],
                         "480p")
        self.assertEqual(
            [h["config_version"] for h in self._history()], [0, 1, 2])
        self.assertEqual(self._audit("remote_config.rollback"), [])
        refused = self._audit("rollback.refused")
        self.assertEqual(len(refused), 1)
        after = json.loads(refused[0]["after_json"])
        self.assertEqual(after["code"], "schema_drift")
        self.assertIn("reason", after)

    def test_rollback_restores_live_fields_at_new_higher_version(self):
        """(e)/N4: rollback to an older snapshot lands at a NEW higher
        version (max(live, panel)+1 — never regresses) and the live
        fields match the restored snapshot, cap included."""
        self._live_service()
        self._publish(free_quality_cap="720p", maintenance_mode=0)
        self._publish(free_quality_cap="480p", maintenance_mode=1)
        self.assertEqual(self._live_row()["config_version"], 2)
        self.assertEqual(self._live_row()["free_quality_cap"], "480p")
        self.assertEqual(self._live_row()["maintenance_mode"], 1)
        out = self.svc.rollback_remote_config(
            self.owner, {"version": 1, "confirm": True})
        self.assertEqual(out["config_version"], 3)
        self.assertEqual(self._live_row()["config_version"], 3)
        self.assertEqual(self._live_row()["free_quality_cap"], "720p")
        self.assertEqual(self._live_row()["maintenance_mode"], 0)
        self.assertEqual(self.svc._config_row()["config_version"], 3)
        snap = json.loads(self._history()[-1]["snapshot_json"])
        self.assertEqual(snap["config"]["free_quality_cap"], "720p")
        self.assertEqual(snap["config"]["maintenance_mode"], 0)

    def test_rollback_never_restores_tokens(self):
        """(e)/N4: tokens are never restored from a redacted snapshot —
        the live row keeps its current token."""
        self._live_service()
        self.svc.put_monetization(self.owner,
                                  {"shrinkme_api_token": "tok-live-123"})
        self._publish(brand_primary="#111111")
        out = self.svc.rollback_remote_config(
            self.owner, {"version": 1, "confirm": True})
        self.assertEqual(out["config_version"], 3)
        self.assertEqual(self._live_row()["shrinkme_api_token"],
                         "tok-live-123")


class TestPauseAnnotation(RedesignBase):
    def test_publish_annotates_paused_features_without_rewriting(self):
        """(N4)/PNL-11 boundary: is_paused() is consulted ONLY to
        annotate the audit — the publish writes the DRAFT flag value
        to live, never a held 0. A pause is a pauses-table row, not a
        config flag; pause->live propagation is PNL-11's."""
        self._live_service()
        self._publish()  # v1, downloads on
        self.assertEqual(self._live_row()["feature_downloads"], 1)
        self.svc.pause(self.owner, {"target_type": "feature",
                                    "target_id": "downloads",
                                    "reason": "abuse report"})
        # The pause itself wrote no live row.
        self.assertEqual(self._live_row()["feature_downloads"], 1)
        self._publish(brand_primary="#333333")  # publish while paused
        self.assertEqual(self._live_row()["feature_downloads"], 1)
        rows = self._audit("remote_config.publish")
        after = json.loads(rows[-1]["after_json"])
        self.assertIn("downloads", after["paused_active"])

    def test_rollback_annotates_paused_features(self):
        """(N4)/PNL-11 boundary: a rollback writes the snapshot's flag
        values as they stand (a rollback to a pre-pause snapshot does
        not resume anything) and annotates the pause in the audit."""
        self._live_service()
        self._publish()  # v1, downloads on
        self.svc.pause(self.owner, {"target_type": "feature",
                                    "target_id": "downloads",
                                    "reason": "abuse report"})
        out = self.svc.rollback_remote_config(
            self.owner, {"version": 1, "confirm": True})
        self.assertEqual(self._live_row()["feature_downloads"], 1)
        self.assertEqual(out["config_version"],
                         self._live_row()["config_version"])
        rows = self._audit("remote_config.rollback")
        after = json.loads(rows[-1]["after_json"])
        self.assertIn("downloads", after["paused_active"])

    def test_pause_writes_no_live_row(self):
        """(N4)/PNL-11 boundary: creating a feature pause touches
        only the panel (pause row + version bump + audit) — the live
        row, live version included, is byte-identical afterwards."""
        self._live_service()
        self._publish()
        before = dict(self._live_row())
        self.svc.pause(self.owner, {"target_type": "feature",
                                    "target_id": "downloads",
                                    "reason": "abuse report"})
        self.assertEqual(dict(self._live_row()), before)
        self.assertEqual(self.svc._config_row()["config_version"], 2)


class TestCoverageMap(RedesignBase):
    def test_draft_columns_partition(self):
        """(f)/N1: every draft field maps to exactly one route. The
        live-row route is the 3 fields in both MONETIZATION_FIELDS and
        REMOTE_CONFIG_FIELDS; the panel-feed route is the 9
        V2_ONLY_SNAPSHOT_FIELDS. The two routes are disjoint and
        together are exactly the draft allow-list."""
        live_route = {"maintenance_mode", "apk_update_url",
                      "free_quality_cap"}
        panel_feed = set(V2_ONLY_SNAPSHOT_FIELDS)
        self.assertEqual(len(DRAFT_REMOTE_CONFIG_FIELDS), 12)
        self.assertEqual(
            set(DRAFT_REMOTE_CONFIG_FIELDS) & set(V2_MONETIZATION_FIELDS)
            & set(REMOTE_CONFIG_FIELDS), live_route)
        self.assertEqual(
            set(DRAFT_REMOTE_CONFIG_FIELDS) & panel_feed, panel_feed)
        self.assertEqual(len(panel_feed), 9)
        self.assertEqual(live_route & panel_feed, set())
        self.assertEqual(set(DRAFT_REMOTE_CONFIG_FIELDS),
                         live_route | panel_feed)

    def test_43_fields_partition(self):
        """(f)/N1: the live row's 43 fields partition exactly into the
        24 v2-writable fields, the 4 release-owned (v1-edited) fields,
        and the 15 v1-only fields — pinned against the v1 field spec."""
        v1_keys = [row[0] for row in admin_panel.MONETIZATION_FIELDS]
        self.assertEqual(len(v1_keys), 43)
        self.assertEqual(len(REMOTE_CONFIG_FIELDS), 24)
        self.assertEqual(len(RELEASE_OWNED_LIVE_FIELDS), 4)
        self.assertEqual(len(V1_ONLY_LIVE_FIELDS), 15)
        self.assertEqual(
            set(REMOTE_CONFIG_FIELDS) | set(RELEASE_OWNED_LIVE_FIELDS)
            | set(V1_ONLY_LIVE_FIELDS), set(v1_keys))
        self.assertEqual(set(REMOTE_CONFIG_FIELDS)
                         & set(RELEASE_OWNED_LIVE_FIELDS), set())
        self.assertEqual(set(REMOTE_CONFIG_FIELDS)
                         & set(V1_ONLY_LIVE_FIELDS), set())
        self.assertEqual(set(RELEASE_OWNED_LIVE_FIELDS)
                         & set(V1_ONLY_LIVE_FIELDS), set())
        self.assertEqual(set(ALL_LIVE_REMOTE_CONFIG_FIELDS), set(v1_keys))
        # The release-owned set is exactly the trio + the shorteners
        # flag — and none of them is a v2 draft field.
        self.assertEqual(set(RELEASE_OWNED_LIVE_FIELDS),
                         {"apk_update_sha256", "apk_update_size_bytes",
                          "apk_update_version_code",
                          "feature_link_shorteners"})
        for key in RELEASE_OWNED_LIVE_FIELDS:
            self.assertNotIn(key, DRAFT_REMOTE_CONFIG_FIELDS)
            self.assertNotIn(key, REMOTE_CONFIG_FIELDS)

    def test_v2_only_fields_in_snapshot_not_live_row(self):
        """(f)/N1: the 9 snapshot-only fields land in the published
        snapshot, never as live-row columns."""
        self._live_service()
        self._publish(min_version_code=30, latest_version_code=31,
                      latest_version_name="2.0", force_update=1,
                      force_update_message="Update",
                      maintenance_message="Down",
                      brand_primary="#101010", brand_accent="#202020",
                      region_order="pakistan,india")
        snap = json.loads(self._history()[-1]["snapshot_json"])
        for key in V2_ONLY_SNAPSHOT_FIELDS:
            self.assertIn(key, snap["config"])
        cols = {r[1] for r in self._catalog().execute(
            "PRAGMA table_info(app_remote_config)")}
        for key in V2_ONLY_SNAPSHOT_FIELDS:
            self.assertNotIn(key, cols)

    def test_no_publish_intent_table_and_schema_version_5(self):
        """(f)/§0A: the intent journal + reconciler are dropped — no
        publish_intent table exists, and the migration chain ends at
        v5 (the v6 journal migration is gone)."""
        svc, _owner, _aud = make_service()
        tables = {r[0] for r in svc.admin.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertNotIn("publish_intent", tables)
        self.assertEqual(svc.admin.execute(
            "SELECT MAX(version) AS v FROM schema_version"
        ).fetchone()["v"], 5)


class TestDriftGuard(RedesignBase):
    def _load_run_module(self):
        path = os.path.join(REPO_ROOT, "AdminV2", "run.py")
        spec = importlib.util.spec_from_file_location("pnl01_run", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def _layout(self, diverge=False):
        tmp = tempfile.mkdtemp(prefix="adminv2-drift-")
        repo_copy = os.path.join(tmp, "AdminV2", "admin_v2")
        root_copy = os.path.join(tmp, "admin_v2")
        # The whole package: run.py imports every admin_v2 module, not
        # just the four drift-checked files.
        src_pkg = os.path.join(REPO_ROOT, "AdminV2", "admin_v2")
        ignore = shutil.ignore_patterns("__pycache__")
        shutil.copytree(src_pkg, repo_copy, ignore=ignore)
        shutil.copytree(src_pkg, root_copy, ignore=ignore)
        if diverge:
            with open(os.path.join(root_copy, "services.py"),
                      "a") as f:
                f.write("\n# divergent byte\n")
        shutil.copy(os.path.join(REPO_ROOT, "AdminV2", "run.py"),
                    os.path.join(tmp, "AdminV2", "run.py"))
        return tmp, repo_copy, root_copy

    def _temp_catalog(self, tmp):
        path = os.path.join(tmp, "catalog.db")
        conn = sqlite3.connect(path)
        conn.executescript(FULL_DDL)
        conn.execute("INSERT INTO app_remote_config (id) VALUES (1)")
        conn.commit()
        conn.close()
        return path

    def test_drift_guard_detects_one_byte_diff(self):
        """T7/D9: one appended byte in the second copy is divergent."""
        mod = self._load_run_module()
        _tmp, repo_copy, root_copy = self._layout(diverge=True)
        self.assertEqual(mod._check_code_copies(repo_copy, root_copy),
                         ["services.py"])

    def test_drift_guard_passes_identical_copies(self):
        """T7/D9: identical copies pass."""
        mod = self._load_run_module()
        _tmp, repo_copy, root_copy = self._layout(diverge=False)
        self.assertEqual(mod._check_code_copies(repo_copy, root_copy), [])

    def test_drift_guard_single_copy_passes(self):
        """T7/D9: a single-copy layout (in-repo run) skips the check."""
        mod = self._load_run_module()
        tmp = tempfile.mkdtemp(prefix="adminv2-drift-")
        only = os.path.join(tmp, "admin_v2")
        os.makedirs(only)
        self.assertEqual(mod._check_code_copies(only, os.path.join(
            tmp, "no-such-copy")), [])

    def test_run_py_mismatch_abort(self):
        """T7/D9: run.py aborts (exit 2) on divergent copies — with a
        real catalog configured, so the abort is the drift check, not
        the missing-env check."""
        tmp, _repo, _root = self._layout(diverge=True)
        env = dict(os.environ)
        env["RADD_CATALOG_DB"] = self._temp_catalog(tmp)
        proc = subprocess.run(
            [sys.executable, os.path.join(tmp, "AdminV2", "run.py"),
             "--base-dir", os.path.join(tmp, "data"),
             "--port", "18098"],
            capture_output=True, text=True, timeout=60, env=env)
        self.assertEqual(proc.returncode, 2)
        self.assertIn("diverged", proc.stderr)

    def test_run_py_identical_copies_serve(self):
        """T7/D9: identical copies + a configured catalog boots and
        serves."""
        tmp, _repo, _root = self._layout(diverge=False)
        env = dict(os.environ)
        env["RADD_CATALOG_DB"] = self._temp_catalog(tmp)
        proc = subprocess.Popen(
            [sys.executable, os.path.join(tmp, "AdminV2", "run.py"),
             "--base-dir", os.path.join(tmp, "data"),
             "--port", "18097"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, env=env)
        try:
            deadline = time.time() + 20
            served = False
            while time.time() < deadline:
                if proc.poll() is not None:
                    break
                try:
                    with urllib.request.urlopen(
                            "http://127.0.0.1:18097/admin",
                            timeout=2) as resp:
                        served = resp.status in (200, 303, 401, 403)
                        break
                except Exception:
                    time.sleep(0.5)
            self.assertIsNone(proc.poll(),
                              "run.py exited early instead of serving")
            self.assertTrue(served, "no HTTP answer from run.py")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()


class TestBackupGuard(RedesignBase):
    def _stub_guard(self, ok):
        class StubGuard:
            @classmethod
            def from_env(cls):
                return cls()

            def require(self, store, source_path):
                if not ok:
                    raise ApiError("backup_required",
                                   "no verified backup", 409)

            def status(self, store, source_path):
                return {"ok": ok,
                        "reason": "" if ok else "no verified backup"}

        return StubGuard

    def test_backup_guard_refusal_blocks_publish(self):
        """T8/D6: a refusing guard is a 409 pre-flight — nothing is
        written, no version is consumed."""
        self._live_service()
        old = services_mod.BackupGuard
        services_mod.BackupGuard = self._stub_guard(ok=False)
        try:
            self.svc.draft_remote_config(self.owner,
                                         {"maintenance_mode": 1})
            with self.assertRaises(ApiError) as ctx:
                self.svc.publish_remote_config(self.owner,
                                               {"confirm": True})
            self.assertEqual(ctx.exception.code, "backup_required")
            self.assertEqual(ctx.exception.status, 409)
            self.assertEqual(self._live_row()["maintenance_mode"], 0)
            self.assertEqual(self._live_row()["config_version"], 0)
            self.assertEqual(self.svc._config_row()["config_version"], 0)
            # Only the v0 seed row: no publish history was created.
            self.assertEqual(
                [h["config_version"] for h in self._history()], [0])
        finally:
            services_mod.BackupGuard = old

    def test_backup_guard_pass_proceeds(self):
        """T8/D6: a passing guard lets the publish through."""
        self._live_service()
        old = services_mod.BackupGuard
        services_mod.BackupGuard = self._stub_guard(ok=True)
        try:
            out = self._publish(maintenance_mode=1)
            self.assertEqual(out["config_version"], 1)
            self.assertEqual(self._live_row()["maintenance_mode"], 1)
        finally:
            services_mod.BackupGuard = old

    def test_backup_guard_absent_proceeds(self):
        """T8/D6: with PNL-08 not landed yet (BackupGuard None) the
        pre-flight is a documented no-op and publish proceeds."""
        self._live_service()
        old = services_mod.BackupGuard
        services_mod.BackupGuard = None
        try:
            out = self._publish(maintenance_mode=1)
            self.assertEqual(out["config_version"], 1)
        finally:
            services_mod.BackupGuard = old


class TestAuditAndStaleness(RedesignBase):
    def test_audit_carries_diff_and_hash_and_no_tokens(self):
        """T10/D8: audit rows carry the field diff + snapshot hash,
        and a token value never lands in audit JSON."""
        self._live_service()
        self.svc.put_monetization(
            self.owner, {"shrinkme_api_token": "tok-secret-abcdef1234",
                         "vip_monthly_pkr": 250})
        rows = self._audit("monetization.save")
        self.assertEqual(len(rows), 1)
        after = json.loads(rows[0]["after_json"])
        self.assertIn("fields_changed", after)
        self.assertIn("vip_monthly_pkr", after["fields_changed"])
        self.assertTrue(re.fullmatch(r"[0-9a-f]{64}",
                                     after["snapshot_sha256"]))
        blob = rows[0]["before_json"] + rows[0]["after_json"]
        self.assertNotIn("tok-secret-abcdef1234", blob)
        # The publish audit carries the same shape.
        self._publish(brand_primary="#555555")
        rows = self._audit("remote_config.publish")
        self.assertEqual(len(rows), 1)
        after = json.loads(rows[0]["after_json"])
        self.assertIn("fields_changed", after)
        self.assertTrue(re.fullmatch(r"[0-9a-f]{64}",
                                     after["snapshot_sha256"]))

    def test_snapshot_hash_changes_with_content(self):
        """T10/D8: the hash is a content hash — a different publish
        hashes differently, an identical re-publish hashes the same."""
        self._live_service()
        self._publish(brand_primary="#111111")
        h1 = json.loads(self._history()[-1]["snapshot_json"])["config"]
        sha1 = self.svc._snapshot_sha256(h1)
        self._publish(brand_primary="#222222")
        h2 = json.loads(self._history()[-1]["snapshot_json"])["config"]
        sha2 = self.svc._snapshot_sha256(h2)
        self.assertNotEqual(sha1, sha2)
        self.assertEqual(sha2, self.svc._snapshot_sha256(h2))

    def test_staleness_pins(self):
        """T11/D10: the app sees a publish within ~5 min — the 60 s
        HTTP cache and the 5-minute watcher poll are pinned, not
        assumed."""
        viewer = os.path.join(REPO_ROOT, "Fast_Fetch", "viewer.py")
        with open(viewer) as f:
            viewer_src = f.read()
        self.assertIn("public, max-age=60", viewer_src)
        watcher = os.path.join(
            REPO_ROOT, "App", "app", "src", "main", "kotlin",
            "com", "raddstream", "app", "data", "RemoteConfigWatcher.kt")
        with open(watcher) as f:
            watcher_src = f.read()
        self.assertRegex(watcher_src,
                         r"REFRESH_INTERVAL_MS\s*=\s*5L?\s*\*\s*60_000L?")


class TestGetLive(RedesignBase):
    def test_get_live_shows_v1_edit(self):
        """F8/N1: a v1 /admin edit shows up in get_live.changed_keys
        before the operator publishes over it, and the drift alert is
        set. Narrowed-scope semantics (§0A): a GENERIC publish carries
        only the draft fields, so it does NOT overwrite a v1 edit to
        a non-draft field — the edit stays visible in changed_keys
        afterwards (only the version drift clears). A MONETIZATION
        publish is what writes the draft's value back over it."""
        self._live_service()
        self._publish()
        with sqlite3.connect(self.catalog_path) as conn:
            result, errors = admin_panel._save_monetization(
                conn, {"vip_monthly_pkr": 999})
        self.assertIsNone(errors)
        self.assertEqual(result["config_version"], 2)
        live = self.svc.get_live(self.owner)
        self.assertIn("vip_monthly_pkr", live["changed_keys"])
        self.assertIn("vip_monthly_pkr", live["live_changed_keys"])
        self.assertIsNotNone(live["live_drift"])
        self.assertEqual(live["live_drift"]["live_version"], 2)
        self.assertEqual(live["live_drift"]["panel_version"], 1)
        # A generic publish allocates from the live version (v3) but
        # leaves the v1 edit in place — it carries draft fields only.
        out = self._publish(brand_primary="#777777")
        self.assertEqual(out["config_version"], 3)
        self.assertEqual(self._live_row()["vip_monthly_pkr"], 999)
        live = self.svc.get_live(self.owner)
        self.assertIn("vip_monthly_pkr", live["changed_keys"])
        self.assertIsNone(live["live_drift"])
        # A monetization publish writes the draft's value back over
        # the v1 edit (the operator saw it in changed_keys and chose
        # to publish anyway).
        out = self.svc.put_monetization(
            self.owner, {"vip_yearly_pkr": 1500})
        self.assertEqual(out["config_version"], 4)
        self.assertEqual(self._live_row()["vip_monthly_pkr"], 200)
        self.assertNotIn("vip_monthly_pkr",
                         self.svc.get_live(self.owner)["changed_keys"])


if __name__ == "__main__":
    unittest.main()
