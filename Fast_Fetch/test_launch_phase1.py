"""Launch plan Phase 1 — panel versioning + audit for flag edits (T-19, D-4, D-6).

The design (`~/workspace/launch-plan-design/design.md` §3.1, §3.4, §6.1) found
flag flips bypassed versioning entirely (G-2): `put_monetization()` wrote the
live catalog immediately with no `config_version` bump and no snapshot, so a
published snapshot could go stale and rollback could not restore a flag flip.
The audit mechanism existed but had never been exercised for flags (G-3).

Phase 1 makes a flag edit a publish, in the design's order:

1. the live catalog row first (the single source of truth the app reads);
2. the panel draft row + a `config_version` bump + a published snapshot;
3. the audit row.

A failure at step 2 or 3 leaves the catalog published but not snapshotted:
the catalog is never rolled back silently, the publish reports
`published_but_not_snapshotted`, and the next publish repairs it
(re-snapshot + re-audit). The draft row is the staging area: `put_monetization`
stages the edit there, then publishes it; `publish_monetization` re-publishes
what the draft holds, which is the repair path.

What this module pins:

- T-19 (a/b): a flag publish bumps `config_version` by exactly 1, writes a
  snapshot whose flag set matches the published (live catalog) row, and
  writes one audit row with before → after.
- T-19 (c): a simulated step-2 failure (and a step-3 failure) is reported as
  `published_but_not_snapshotted` while the catalog already carries the new
  value, and the next publish repairs it.
- T-19 (rollback): rolling back to the earlier snapshot restores the flag
  value on the live catalog row.
- D-4 (panel half): creating or clearing a `feature` pause bumps
  `config_version` and writes an audit row. The serve-side half — the served
  flag reading OFF while paused — is NOT asserted here: the app reads the
  catalog row through `viewer.py`, which Phase 1 does not touch; the panel's
  own public contract (`fixture_app.public_remote_config`) already enforces
  the pause. Wiring the pause into the served catalog value is Phase 2.
- D-6: the v1 `/admin` flag toggles are read-only. A v1 save carrying a flag
  key is refused and writes nothing; non-flag keys still save.
- Panel-only mode (no live catalog wired): the publish still versions,
  snapshots and audits the staged draft.

Harness: a real `Service` on temp dirs with a real `LiveStores` over a temp
catalog built by the repo's own `admin_panel._ensure_monetization` DDL — the
same shape `test_live_real.py` builds by hand, but from maintained code.
"""

from __future__ import annotations

import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import admin_panel

# The panel v2 service lives outside Fast_Fetch; the repo root is this file's
# grandparent when run as `python3 -S Fast_Fetch/test_launch_phase1.py`.
# Imported as the admin_v2 package (live_stores/services use relative
# imports, so a bare `import services` fails).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "AdminV2"))
import admin_v2.store as panel_store
from admin_v2.live_stores import LiveStores, REMOTE_CONFIG_FIELDS
from admin_v2.services import Service


# The catalog row's numeric columns: flags, prices and coin costs. Everything
# else in REMOTE_CONFIG_FIELDS is text. Used only to add a column the v1 DDL
# predates, so the fixture catalog always carries every field the v2 staged
# publish writes.
_NUMERIC_FIELDS = {
    "vip_monthly_pkr", "vip_yearly_pkr",
    "maintenance_mode", "feature_downloads", "feature_pure_cinema",
    "feature_chromecast", "feature_quality_gate",
} | set(panel_store.COIN_REDEEM_FIELDS)

# Flags the v1 panel shows read-only after Phase 1 (D-6).
_V1_READ_ONLY_FLAGS = (
    "maintenance_mode", "feature_pure_cinema", "feature_chromecast",
    "feature_downloads", "feature_quality_gate",
)


def _fresh_service(live=True):
    """A Service on temp dirs, with an owner actor. live=False wires no live
    catalog (panel-only mode); otherwise a real LiveStores over a temp
    catalog built by admin_panel's own monetization DDL."""
    tmp = tempfile.mkdtemp(prefix="launch-phase1-")
    live_stores = None
    catalog_path = None
    if live:
        catalog_path = os.path.join(tmp, "catalog.db")
        conn = sqlite3.connect(catalog_path)
        admin_panel._ensure_monetization(conn)
        cols = {r[1] for r in conn.execute("PRAGMA table_info(app_remote_config)")}
        for field in REMOTE_CONFIG_FIELDS:
            if field not in cols:
                kind = "INTEGER" if field in _NUMERIC_FIELDS else "TEXT"
                conn.execute("ALTER TABLE app_remote_config ADD COLUMN %s %s"
                             % (field, kind))
        conn.execute("INSERT OR IGNORE INTO app_remote_config (id) VALUES (1)")
        conn.commit()
        conn.close()
        live_stores = LiveStores(catalog_path=catalog_path)
    admin_dir = os.path.join(tmp, "admin")
    svc = Service(admin_dir, live=live_stores)
    owner_id = svc.create_owner("owner", "correct horse battery staple")
    owner = {"id": owner_id, "username": "owner", "role": "owner"}
    return svc, owner, catalog_path


def _live_row(catalog_path):
    conn = sqlite3.connect(catalog_path)
    conn.row_factory = sqlite3.Row
    row = conn.execute(
        "SELECT * FROM app_remote_config WHERE id=1").fetchone()
    out = dict(row)
    conn.close()
    return out


def _history(svc):
    return [dict(r) for r in svc.admin.execute(
        "SELECT * FROM app_remote_config_history ORDER BY config_version DESC"
        ).fetchall()]


def _audits(svc, action):
    return [dict(r) for r in svc.admin.execute(
        "SELECT * FROM admin_audit_log WHERE action=? ORDER BY id ASC",
        (action,)).fetchall()]


class Phase1Publish(unittest.TestCase):
    def setUp(self):
        self.svc, self.owner, self.catalog_path = _fresh_service(live=True)

    def test_flag_publish_bumps_version_snapshots_and_audits(self):
        """T-19 (a/b): one flag flip → version +1, snapshot matches the
        published row, one audit row with before → after."""
        v0 = self.svc._config_row()["config_version"]
        result = self.svc.put_monetization(
            self.owner, {"feature_quality_gate": 1})
        self.assertEqual(result["publish_status"], "ok")
        self.assertEqual(result["config_version"], v0 + 1)
        self.assertEqual(result["feature_quality_gate"], 1)

        # The draft row moved exactly one version.
        self.assertEqual(self.svc._config_row()["config_version"], v0 + 1)

        # The newest snapshot is the new version, and its flag set is the
        # published (live catalog) row — not the pre-flip draft.
        snap = json.loads(_history(self.svc)[0]["snapshot_json"])
        self.assertEqual(snap["config"]["config_version"], v0 + 1)
        live = _live_row(self.catalog_path)
        for field in panel_store.MONETIZATION_FIELDS:
            if field in ("shrinkme_api_token", "droplink_api_token",
                         "apk_update_url"):
                continue  # redacted out of snapshots by design
            self.assertEqual(snap["config"][field], live[field], field)
        self.assertEqual(snap["config"]["feature_quality_gate"], 1)

        # One audit row, actor + masked IP + before → after with versions.
        rows = _audits(self.svc, "monetization.save")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["actor_id"], self.owner["id"])
        before = json.loads(rows[0]["before_json"])
        after = json.loads(rows[0]["after_json"])
        self.assertEqual(before["feature_quality_gate"], 0)
        self.assertEqual(before["config_version"], v0)
        self.assertEqual(after["feature_quality_gate"], 1)
        self.assertEqual(after["config_version"], v0 + 1)

        # The panel can read the publish state back.
        self.assertEqual(
            self.svc.get_monetization(self.owner)["publish_status"], "ok")

    def test_snapshot_failure_reports_published_but_not_snapshotted(self):
        """T-19 (c): step 2 fails → the catalog is already published, the
        version is not bumped, no snapshot and no audit row exist yet, the
        publish reports the state — and the next publish repairs it."""
        first = self.svc.put_monetization(
            self.owner, {"feature_quality_gate": 1})
        v1 = first["config_version"]

        with mock.patch.object(
                self.svc, "_record_versioned_snapshot",
                side_effect=RuntimeError("simulated snapshot failure")):
            result = self.svc.put_monetization(
                self.owner, {"feature_quality_gate": 0})
        self.assertEqual(result["publish_status"],
                         "published_but_not_snapshotted")
        # Step 1 already happened: the catalog carries the new value.
        self.assertEqual(_live_row(self.catalog_path)["feature_quality_gate"], 0)
        # Step 2 did not: no bump, no new snapshot, no audit row.
        self.assertEqual(self.svc._config_row()["config_version"], v1)
        self.assertEqual(len(_history(self.svc)), 2)  # seed + first publish
        self.assertEqual(len(_audits(self.svc, "monetization.save")), 1)
        self.assertEqual(
            self.svc.get_monetization(self.owner)["publish_status"],
            "published_but_not_snapshotted")

        # The next publish repairs it: re-snapshot + re-audit, one bump.
        repair = self.svc.publish_monetization(
            self.owner, {"confirm": True})
        self.assertEqual(repair["publish_status"], "ok")
        self.assertEqual(repair["config_version"], v1 + 1)
        snap = json.loads(_history(self.svc)[0]["snapshot_json"])
        self.assertEqual(snap["config"]["config_version"], v1 + 1)
        self.assertEqual(snap["config"]["feature_quality_gate"], 0)
        self.assertEqual(
            _live_row(self.catalog_path)["feature_quality_gate"], 0)
        rows = _audits(self.svc, "monetization.save")
        self.assertEqual(len(rows), 2)
        self.assertEqual(json.loads(rows[1]["after_json"])["config_version"],
                         v1 + 1)
        self.assertEqual(
            self.svc.get_monetization(self.owner)["publish_status"], "ok")

    def test_partial_snapshot_failure_leaves_a_version_gap_the_repair_closes(self):
        """T-19 (c), second shape: the version bump lands but the snapshot
        insert fails. The repair bumps again and snapshots — history has a
        gap at the failed version, and that is honest: history rows are
        never edited, and the newest snapshot still matches the live row."""
        first = self.svc.put_monetization(
            self.owner, {"feature_quality_gate": 1})
        v1 = first["config_version"]

        def _bump_then_fail(published_by):
            from admin_v2.services import WRITE_LOCK, NOW
            row = self.svc._config_row()
            new_version = row["config_version"] + 1
            with WRITE_LOCK:
                self.svc.admin.execute(
                    "UPDATE app_remote_config_v2 SET config_version=?, "
                    "updated_by=?, updated_at=? WHERE id=1",
                    (new_version, published_by, NOW()))
                self.svc.admin.commit()
            raise RuntimeError("simulated snapshot insert failure")

        with mock.patch.object(
                self.svc, "_record_versioned_snapshot",
                side_effect=_bump_then_fail):
            result = self.svc.put_monetization(
                self.owner, {"feature_quality_gate": 0})
        self.assertEqual(result["publish_status"],
                         "published_but_not_snapshotted")
        # The bump landed without a snapshot: version v1+1, no history row.
        self.assertEqual(self.svc._config_row()["config_version"], v1 + 1)
        versions = [r["config_version"] for r in _history(self.svc)]
        self.assertNotIn(v1 + 1, versions)

        repair = self.svc.publish_monetization(
            self.owner, {"confirm": True})
        self.assertEqual(repair["publish_status"], "ok")
        self.assertEqual(repair["config_version"], v1 + 2)
        snap = json.loads(_history(self.svc)[0]["snapshot_json"])
        self.assertEqual(snap["config"]["config_version"], v1 + 2)
        self.assertEqual(snap["config"]["feature_quality_gate"],
                         _live_row(self.catalog_path)["feature_quality_gate"])

    def test_audit_failure_also_reports_and_repairs(self):
        """T-19 (c), third shape: steps 1–2 land (catalog + snapshot) but
        the audit insert fails. Same status, same repair — the repair
        re-audits, so the publish ends with its audit row."""
        self.svc.put_monetization(self.owner, {"feature_quality_gate": 1})
        v1 = self.svc._config_row()["config_version"]

        real_audit = self.svc.audit

        def _fail_once(*args, **kwargs):
            if args[1] == "monetization.save":
                raise RuntimeError("simulated audit failure")
            return real_audit(*args, **kwargs)

        with mock.patch.object(self.svc, "audit", side_effect=_fail_once):
            result = self.svc.put_monetization(
                self.owner, {"feature_quality_gate": 0})
        self.assertEqual(result["publish_status"],
                         "published_but_not_snapshotted")
        # Snapshot exists for the new version; the audit row does not.
        self.assertEqual(self.svc._config_row()["config_version"], v1 + 1)
        self.assertEqual(
            json.loads(_history(self.svc)[0]["snapshot_json"])
            ["config"]["config_version"], v1 + 1)
        self.assertEqual(len(_audits(self.svc, "monetization.save")), 1)

        repair = self.svc.publish_monetization(
            self.owner, {"confirm": True})
        self.assertEqual(repair["publish_status"], "ok")
        rows = _audits(self.svc, "monetization.save")
        self.assertEqual(len(rows), 2)
        self.assertEqual(json.loads(rows[1]["after_json"])["config_version"],
                         v1 + 2)

    def test_rollback_restores_a_flag(self):
        """T-19 (rollback): two publishes, then roll back to the first —
        the live catalog row carries the old flag value again, as a new
        version, with a rollback audit row."""
        on = self.svc.put_monetization(self.owner, {"feature_quality_gate": 1})
        self.svc.put_monetization(self.owner, {"feature_quality_gate": 0})
        self.assertEqual(_live_row(self.catalog_path)["feature_quality_gate"], 0)

        rolled = self.svc.rollback_remote_config(
            self.owner, {"version": on["config_version"], "confirm": True})
        self.assertEqual(rolled["config_version"], on["config_version"] + 2)
        self.assertEqual(_live_row(self.catalog_path)["feature_quality_gate"], 1)
        self.assertEqual(self.svc._config_row()["feature_quality_gate"], 1)
        self.assertEqual(len(_audits(self.svc, "remote_config.rollback")), 1)

    def test_feature_pause_bumps_version_and_audits(self):
        """D-4 (panel half): a feature pause is a versioned change — bump +
        snapshot + audit on create and on resume. A non-feature pause does
        not bump the version."""
        v0 = self.svc._config_row()["config_version"]
        pause = self.svc.pause(self.owner, {
            "target_type": "feature", "target_id": "quality_gate",
            "reason": "test pause"})
        self.assertEqual(self.svc._config_row()["config_version"], v0 + 1)
        self.assertEqual(
            json.loads(_history(self.svc)[0]["snapshot_json"])
            ["config"]["config_version"], v0 + 1)
        self.assertEqual(len(_audits(self.svc, "pause.create")), 1)

        v1 = self.svc._config_row()["config_version"]
        self.svc.resume_pause(self.owner, pause["id"])
        self.assertEqual(self.svc._config_row()["config_version"], v1 + 1)
        self.assertEqual(len(_audits(self.svc, "pause.resume")), 1)

        v2 = self.svc._config_row()["config_version"]
        self.svc.pause(self.owner, {
            "target_type": "source", "target_id": "multimovies",
            "reason": "test source pause"})
        self.assertEqual(self.svc._config_row()["config_version"], v2)

    def test_publish_requires_confirm(self):
        from admin_v2.errors import ApiError
        with self.assertRaises(ApiError):
            self.svc.publish_monetization(self.owner, {})
        with self.assertRaises(ApiError):
            self.svc.publish_monetization(self.owner, {"confirm": False})


class Phase1PanelOnly(unittest.TestCase):
    def test_publish_without_a_live_catalog_still_versions(self):
        """No live catalog wired: step 1 is skipped, steps 2–3 still run —
        the draft is versioned, snapshotted and audited."""
        svc, owner, _ = _fresh_service(live=False)
        v0 = svc._config_row()["config_version"]
        result = svc.put_monetization(owner, {"feature_quality_gate": 1})
        self.assertEqual(result["publish_status"], "ok")
        self.assertEqual(result["config_version"], v0 + 1)
        snap = json.loads(_history(svc)[0]["snapshot_json"])
        self.assertEqual(snap["config"]["feature_quality_gate"], 1)
        self.assertEqual(len(_audits(svc, "monetization.save")), 1)


class Phase1V1ReadOnly(unittest.TestCase):
    def test_v1_flag_save_is_refused_and_writes_nothing(self):
        """D-6: the v1 save path refuses flag keys — the operator edits
        flags in the v2 panel, where the flip is versioned, snapshotted
        and audited. Non-flag keys still save through the same path."""
        conn = sqlite3.connect(":memory:")
        admin_panel._ensure_monetization(conn)
        conn.commit()
        before = dict(admin_panel._read_monetization(conn))
        saved, rejected = admin_panel._save_monetization(
            conn, {"feature_quality_gate": 1})
        self.assertIsNone(saved)
        self.assertTrue(rejected)
        self.assertIn("v2 panel", " ".join(rejected))
        after = dict(admin_panel._read_monetization(conn))
        self.assertEqual(after, before)

        saved, rejected = admin_panel._save_monetization(
            conn, {"vip_monthly_pkr": 250})
        self.assertIsNone(rejected, rejected)
        self.assertIsNotNone(saved)
        self.assertEqual(
            dict(admin_panel._read_monetization(conn))["vip_monthly_pkr"], 250)

    def test_v1_flag_toggles_render_disabled(self):
        """D-6 (display half): the v1 feature matrix shows the live values
        but its flag toggles cannot be flipped — disabled inputs plus a
        pointer to the v2 panel."""
        conn = sqlite3.connect(":memory:")
        admin_panel._ensure_monetization(conn)
        conn.commit()
        data = admin_panel.screen_monetization(conn)
        html = admin_panel._config_card(data)
        for key in _V1_READ_ONLY_FLAGS:
            self.assertIn('data-key="%s"' % key, html)
        self.assertIn("disabled", html)
        self.assertIn("v2 panel", html)


if __name__ == "__main__":
    unittest.main(verbosity=2)
