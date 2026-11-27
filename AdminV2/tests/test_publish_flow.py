"""Live publish flow: flags go out at publish, and Save draft tells
the truth. Every live integration runs against TEMP COPIES (real
app_remote_config DDL). No test ever touches a live DB."""
import json
import os
import sqlite3
import sys
import tempfile
import unittest
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import make_service, TEST_TOTP_KEY  # noqa: E402
from admin_v2.errors import ApiError  # noqa: E402
from admin_v2 import fixture_app  # noqa: E402
from admin_v2.live_stores import LiveStores, ensure_live_remote_config_columns  # noqa: E402
from admin_v2.server import AdminApp  # noqa: E402
from admin_v2.services import Service  # noqa: E402

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

# A catalog from before apk_update_url / coins_enabled existed: the
# shape a deployment predating those columns still has.
OLD_DDL = """
CREATE TABLE app_remote_config (id INTEGER PRIMARY KEY CHECK (id = 1),
  pak_till_id TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0,
  maintenance_mode INTEGER NOT NULL DEFAULT 0,
  feature_quality_gate INTEGER NOT NULL DEFAULT 0);
"""


class PublishFlowBase(unittest.TestCase):
    def _live_service(self, ddl=FULL_DDL):
        self.tmp = tempfile.mkdtemp(prefix="adminv2-publish-")
        self.catalog_path = os.path.join(self.tmp, "catalog.db")
        conn = sqlite3.connect(self.catalog_path)
        conn.executescript(ddl)
        conn.execute("INSERT INTO app_remote_config (id) VALUES (1)")
        conn.commit()
        conn.close()
        self.live = LiveStores(catalog_path=self.catalog_path)
        self.svc = Service(os.path.join(self.tmp, "admin"), totp_key=TEST_TOTP_KEY, live=self.live)
        owner_id = self.svc.create_owner("owner", "correct horse battery staple")
        self.owner = {"id": owner_id, "username": "owner", "role": "owner"}
        res = self.svc.login("owner", "correct horse battery staple")
        self.app = AdminApp(self.svc)
        self.session = res["session_token"]
        self.csrf = res["csrf_token"]
        return self.svc

    def _catalog(self):
        conn = sqlite3.connect(self.catalog_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _form(self, path, fields, next_path="/admin/live"):
        body = dict(fields)
        body["csrf_token"] = self.csrf
        body["_next"] = next_path
        data = urllib.parse.urlencode(body).encode()
        headers = {"Content-Type": "application/x-www-form-urlencoded",
                   "Cookie": "admin_session=%s" % self.session,
                   "Host": "127.0.0.1"}
        return self.app.handle("POST", path, {}, headers, data)

    def _get_page(self, path):
        headers = {"Cookie": "admin_session=%s" % self.session,
                   "Host": "127.0.0.1"}
        _q = {}
        if "?" in path:
            path, qs = path.split("?", 1)
            _q = urllib.parse.parse_qs(qs)
        return self.app.handle("GET", path, _q, headers, b"")


class TestDraft(PublishFlowBase):
    def test_draft_empty_brand_and_region_clears_to_null(self):
        """"" means "clear it", never "store an empty string that fails
        validation on the next save"."""
        self._live_service()
        out = self.svc.draft_remote_config(
            self.owner, {"brand_primary": "", "brand_accent": "",
                         "region_order": ""})
        self.assertIsNone(out["brand_primary"])
        self.assertIsNone(out["brand_accent"])
        self.assertIsNone(out["region_order"])

    def test_draft_invalid_brand_still_refused(self):
        self._live_service()
        with self.assertRaises(Exception) as ctx:
            self.svc.draft_remote_config(self.owner, {"brand_primary": "red"})
        self.assertIn("hex colour", str(ctx.exception))

    def test_draft_does_not_touch_live_catalog(self):
        """A draft is panel-only: the app must not see maintenance_mode
        (or the APK URL) until Publish."""
        self._live_service()
        before = self.svc._config_row()["config_version"]
        self.svc.draft_remote_config(
            self.owner, {"maintenance_mode": 1,
                         "apk_update_url": "https://example.test/app.apk"})
        row = self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["maintenance_mode"], 0)
        self.assertEqual(row["apk_update_url"], "")
        self.assertEqual(self.svc._config_row()["config_version"], before)
        live = self.svc.get_live(self.owner)
        self.assertIn("maintenance_mode", live["changed_keys"])
        # PNL-01 F8: changed_keys now names live-row fields (what the
        # app serves), so the APK URL appears under its own field name,
        # not the redacted draft's *_configured flag.
        self.assertIn("apk_update_url", live["changed_keys"])

    def test_draft_on_old_catalog_cannot_half_save(self):
        """Before this fix, a draft carrying a real apk_update_url 500'd
        on `no such column` AFTER the draft row had committed. Now the
        draft never writes the catalog at all, so no catalog state can
        fail it — and the publish-time write adds missing columns."""
        self._live_service(ddl=OLD_DDL)
        out = self.svc.draft_remote_config(
            self.owner, {"apk_update_url": "https://example.test/app.apk",
                         "maintenance_mode": 1})
        # The redacted draft never carries the raw URL — only the
        # configured flag — so check the stored row itself.
        self.assertTrue(out["apk_update_url_configured"])
        self.assertEqual(
            self.svc._config_row()["apk_update_url"],
            "https://example.test/app.apk")
        row = self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["maintenance_mode"], 0)


class TestPublish(PublishFlowBase):
    def test_publish_pushes_live_fields_and_stamps_version(self):
        self._live_service()
        self.svc.draft_remote_config(
            self.owner, {"maintenance_mode": 1,
                         "apk_update_url": "https://example.test/app.apk"})
        out = self.svc.publish_remote_config(self.owner, {"confirm": True})
        row = self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["maintenance_mode"], 1)
        self.assertEqual(row["apk_update_url"], "https://example.test/app.apk")
        self.assertEqual(row["config_version"], out["config_version"])
        live = self.svc.get_live(self.owner)
        self.assertEqual(live["live_catalog_version"], out["config_version"])
        self.assertEqual(live["changed_keys"], [])

    def test_publish_without_live_catalog_still_works(self):
        """§0A N3 fixture branch: no live store CONFIGURED, so publish
        works panel-only — and says so in the response
        ("live": "not_configured"), instead of reporting a success
        that silently skipped the live write. A live store that IS
        configured but unreachable refuses with 503 instead — see
        test_publish_redesign.py."""
        svc, owner, _aud = make_service()
        svc.draft_remote_config(owner, {"maintenance_mode": 1})
        out = svc.publish_remote_config(owner, {"confirm": True})
        self.assertIn("config_version", out)
        self.assertEqual(out["live"], "not_configured")
        self.assertIsNone(svc.get_live(owner)["live_catalog_version"])

    def test_publish_catalog_failure_bumps_nothing(self):
        """A catalog the panel cannot write (no app_remote_config table)
        must not half-publish: the version stays put and the operator
        gets the real reason, not "Something went wrong"."""
        self._live_service()
        self.svc.draft_remote_config(self.owner, {"maintenance_mode": 1})
        before = self.svc._config_row()["config_version"]
        conn = self._catalog()
        conn.execute("DROP TABLE app_remote_config")
        conn.commit()
        conn.close()
        with self.assertRaises(Exception) as ctx:
            self.svc.publish_remote_config(self.owner, {"confirm": True})
        self.assertIn("live catalog", str(ctx.exception))
        self.assertEqual(self.svc._config_row()["config_version"], before)

    def test_monetization_save_stamps_version(self):
        self._live_service()
        before = self.svc._config_row()["config_version"]
        self.svc.put_monetization(self.owner, {"feature_quality_gate": 1})
        row = self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["feature_quality_gate"], 1)
        self.assertEqual(row["config_version"], before + 1)
        self.assertEqual(self.svc._config_row()["config_version"], before + 1)

    def test_monetization_save_does_not_publish_unpublished_live_draft(self):
        """A monetization save must not publish an un-published Live draft.

        The Live draft's v2-only fields (force_update, min_version_code,
        brand, region) reach the app only through the published snapshot,
        and only a Live publish may put them there. Saving monetization
        (e.g. a VIP price) snapshots the draft row today, so the draft's
        force_update/min_version ride out silently — and the Live page
        then reports "nothing to publish" for changes the operator never
        published. The monetization publish must snapshot the last
        published config plus its own fields, never the draft row.
        """
        self._live_service()
        # Baseline: publish once so there is a last-published snapshot.
        self.svc.publish_remote_config(self.owner, {"confirm": True})
        # Draft a force update + minimum version, but do NOT publish it.
        self.svc.draft_remote_config(
            self.owner, {"force_update": 1, "min_version_code": 99,
                         "latest_version_code": 99,
                         "latest_version_name": "9.9.9"})
        # Save monetization (a VIP price change) — this publishes.
        self.svc.put_monetization(self.owner, {"vip_monthly_pkr": 300})
        # The monetization field DID go out.
        row = self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["vip_monthly_pkr"], 300)
        # But the Live draft's v2-only fields must NOT be in the snapshot.
        hist = self.svc.admin.execute(
            "SELECT snapshot_json FROM app_remote_config_history "
            "ORDER BY config_version DESC LIMIT 1").fetchone()
        snap = json.loads(hist["snapshot_json"])["config"]
        self.assertEqual(snap.get("force_update"), 0)
        self.assertEqual(snap.get("min_version_code") or 0, 0)
        self.assertEqual(snap.get("latest_version_code") or 0, 0)
        self.assertEqual(snap.get("latest_version_name") or "", "")
        # And the Live page must still show the draft as waiting to go out.
        live = self.svc.get_live(self.owner)
        self.assertIn("force_update", live["changed_keys"])
        self.assertIn("min_version_code", live["changed_keys"])

    def test_monetization_ads_switch_publishes_to_the_catalog(self):
        """Lane F: the ads switch is a real toggle, not a zone id. The
        draft flip publishes to the live catalog row the app reads — on
        and back off — The ads columns arrive via the startup migration (D7),
        so a catalog predating them still takes the publish."""
        self._live_service()
        before = self.svc._config_row()["config_version"]
        self.svc.put_monetization(self.owner, {"ads_enabled": 1})
        row = self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["ads_enabled"], 1)
        self.assertEqual(row["config_version"], before + 1)
        self.assertEqual(self.svc.get_monetization(self.owner)["ads_enabled"], 1)
        self.svc.put_monetization(self.owner, {"ads_enabled": 0})
        row = self._catalog().execute(
            "SELECT ads_enabled FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["ads_enabled"], 0)

    def test_save_remote_config_refuses_missing_columns_on_old_catalog(self):
        """PNL-01 D7: a publish-time write no longer ALTERs the live
        schema mid-request. On an old catalog a save of a field whose
        column is missing raises schema_drift (500) instead of adding
        the column — the startup migration owns schema changes now.
        A bare LiveStores (no Service construction, which would
        migrate the catalog first) pins the refusal."""
        tmp = tempfile.mkdtemp(prefix="adminv2-oldcat-")
        path = os.path.join(tmp, "catalog.db")
        conn = sqlite3.connect(path)
        conn.executescript(OLD_DDL)
        conn.execute("INSERT INTO app_remote_config (id) VALUES (1)")
        conn.commit()
        conn.close()
        live = LiveStores(catalog_path=path)
        # Mega-wave4: `coins_enabled` is not a v2-writable field
        # (PNL-03 removal), so the drift probe no longer carries it —
        # apk_update_url alone is missing from this old catalog and
        # still triggers schema_drift.
        with self.assertRaises(ApiError) as ctx:
            live.save_remote_config(
                {"apk_update_url": "https://example.test/app.apk",
                 "maintenance_mode": 1},
                config_version=4)
        self.assertEqual(ctx.exception.code, "schema_drift")
        self.assertEqual(ctx.exception.status, 500)

    def test_startup_migration_then_save_works_on_old_catalog(self):
        """After the startup migration (ensure_live_remote_config_columns)
        the same old catalog accepts the save — columns incl.
        config_version are added idempotently, and the stamped version
        lands on the row."""
        tmp = tempfile.mkdtemp(prefix="adminv2-oldcat-")
        path = os.path.join(tmp, "catalog.db")
        conn = sqlite3.connect(path)
        conn.executescript(OLD_DDL)
        conn.execute("INSERT INTO app_remote_config (id) VALUES (1)")
        conn.commit()
        conn.close()
        ensure_live_remote_config_columns(path)
        live = LiveStores(catalog_path=path)
        out = live.save_remote_config(
            {"apk_update_url": "https://example.test/app.apk",
             "maintenance_mode": 1}, config_version=4)
        cols = {r[1] for r in sqlite3.connect(path).execute(
            "PRAGMA table_info(app_remote_config)")}
        self.assertIn("apk_update_url", cols)
        self.assertIn("config_version", cols)
        # Coins removed (PNL-03): the startup migration still adds the
        # dormant `coins_enabled` column (the v1 read path SELECTs all
        # 45, and the fixture client reads the flag) — but a publish
        # never WRITES it, so the saved row keeps the default 0.
        self.assertIn("coins_enabled", cols)
        row = sqlite3.connect(path).execute(
            "SELECT coins_enabled FROM app_remote_config WHERE id=1"
        ).fetchone()
        self.assertEqual(row[0], 0)
        self.assertEqual(out["apk_update_url"], "https://example.test/app.apk")
        self.assertEqual(out["config_version"], 4)

    def test_publish_monetization_repair_path(self):
        """POST /admin/api/v2/monetization/publish re-publishes what the
        draft holds (the repair path for a published-but-not-snapshotted
        failure): the catalog is rewritten and the version bumps."""
        self._live_service()
        self.svc.put_monetization(self.owner, {"vip_monthly_pkr": 250})
        before = self.svc._config_row()["config_version"]
        out = self.svc.publish_monetization(self.owner, {"confirm": True})
        self.assertEqual(out["publish_status"], "ok")
        self.assertEqual(out["config_version"], before + 1)
        row = self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["vip_monthly_pkr"], 250)
        self.assertEqual(row["config_version"], before + 1)

    def test_rollback_stamps_version(self):
        self._live_service()
        self.svc.draft_remote_config(self.owner, {"brand_primary": "#111111"})
        v2 = self.svc.publish_remote_config(self.owner, {"confirm": True})["config_version"]
        self.svc.put_monetization(self.owner, {"feature_quality_gate": 1})
        out = self.svc.rollback_remote_config(
            self.owner, {"version": v2, "confirm": True})
        row = self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        self.assertEqual(row["feature_quality_gate"], 0)
        self.assertEqual(row["config_version"], out["config_version"])


class TestFormErrors(PublishFlowBase):
    def test_form_post_error_redirects_with_real_message(self):
        """A browser form that fails must land back on the page with the
        reason in the query string — never a bare JSON error body."""
        self._live_service()
        status, _ctype, _payload, _cookie, location = self._form(
            "/admin/api/v2/remote-config",
            {"region_order": "mars", "brand_primary": "#2563EB",
             "brand_accent": "#2563EB", "free_quality_cap": "720p",
             "maintenance_mode": "0"})
        self.assertEqual(status, 303)
        self.assertIn("/admin/live?error=", location)
        # quote() encodes spaces as %20, not +.
        self.assertIn("Region%20order%20must%20use%20only", location)

    def test_form_post_success_still_redirects_clean(self):
        self._live_service()
        status, _ctype, _payload, _cookie, location = self._form(
            "/admin/api/v2/remote-config",
            {"brand_primary": "#123456", "brand_accent": "#654321",
             "region_order": "pakistan,india,asia,international",
             "free_quality_cap": "720p", "maintenance_mode": "0"})
        self.assertEqual(status, 303)
        self.assertEqual(location, "/admin/live")

    def test_live_page_renders_error_and_serving_version(self):
        self._live_service()
        self.svc.draft_remote_config(self.owner, {"maintenance_mode": 1})
        self.svc.publish_remote_config(self.owner, {"confirm": True})
        status, ctype, payload, _cookie, _loc = self._get_page(
            "/admin/live?error=Region+order+must+use+only")
        self.assertEqual(status, 200)
        html = payload.decode()
        self.assertIn("Region order must use only", html)
        self.assertIn("app serving v", html)

    def test_json_caller_still_gets_json_error(self):
        """The redirect is for browser forms only; API callers keep JSON."""
        self._live_service()
        data = json.dumps({"region_order": "mars"}).encode()
        headers = {"Content-Type": "application/json",
                   "Cookie": "admin_session=%s" % self.session,
                   "X-CSRF-Token": self.csrf, "Host": "127.0.0.1"}
        status, _ctype, payload, _cookie, location = self.app.handle(
            "PUT", "/admin/api/v2/remote-config", {}, headers, data)
        self.assertEqual(status, 400)
        self.assertIsNone(location)
        self.assertIn("Region order", payload.decode())


class TestFeaturePause(PublishFlowBase):
    """PNL-11: a feature pause must reach the live catalog row the app
    reads — not just the panel's version bump and fixture fold."""

    def _live_row(self):
        return self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()

    def _live_columns(self):
        return {r[1] for r in self._catalog().execute(
            "PRAGMA table_info(app_remote_config)")}

    def test_pause_feature_writes_live_flag_and_stamps_version(self):
        self._live_service()
        before = self.svc._config_row()["config_version"]
        p = self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "downloads",
                                        "reason": "test pause"})
        self.assertTrue(self.svc.is_paused("feature", "downloads"))
        row = self._live_row()
        self.assertEqual(row["feature_downloads"], 0)
        self.assertEqual(row["config_version"], before + 1)
        self.assertEqual(self.svc._config_row()["config_version"], before + 1)
        # The panel fixture still folds the pause too.
        flags = fixture_app.public_remote_config(self.svc)["feature_flags"]
        self.assertFalse(flags["downloads"])

    def test_pause_feature_does_not_publish_unpublished_live_draft(self):
        """A feature pause bumps the version but publishes nothing: an
        un-published Live draft (force_update, min_version) must not
        ride out in the pause's snapshot."""
        self._live_service()
        self.svc.publish_remote_config(self.owner, {"confirm": True})
        self.svc.draft_remote_config(
            self.owner, {"force_update": 1, "min_version_code": 99})
        self.svc.pause(self.owner, {"target_type": "feature",
                                    "target_id": "downloads",
                                    "reason": "test pause"})
        # The pause itself worked.
        self.assertEqual(self._live_row()["feature_downloads"], 0)
        # But the snapshot must not carry the un-published draft.
        hist = self.svc.admin.execute(
            "SELECT snapshot_json FROM app_remote_config_history "
            "ORDER BY config_version DESC LIMIT 1").fetchone()
        snap = json.loads(hist["snapshot_json"])["config"]
        self.assertEqual(snap.get("force_update"), 0)
        self.assertEqual(snap.get("min_version_code") or 0, 0)
        live = self.svc.get_live(self.owner)
        self.assertIn("force_update", live["changed_keys"])
        self.assertIn("min_version_code", live["changed_keys"])

    def test_resume_feature_restores_draft_value(self):
        self._live_service()
        # Draft says the gate is ON (put_monetization publishes it live).
        self.svc.put_monetization(self.owner, {"feature_quality_gate": 1})
        self.assertEqual(self._live_row()["feature_quality_gate"], 1)
        p = self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "quality_gate",
                                        "reason": "test pause"})
        self.assertEqual(self._live_row()["feature_quality_gate"], 0)
        before = self.svc._config_row()["config_version"]
        self.svc.resume_pause(self.owner, p["id"])
        row = self._live_row()
        self.assertEqual(row["feature_quality_gate"], 1)
        self.assertEqual(row["config_version"], before + 1)
        self.assertFalse(self.svc.is_paused("feature", "quality_gate"))
        flags = fixture_app.public_remote_config(self.svc)["feature_flags"]
        self.assertTrue(flags["quality_gate"])

    def test_resume_feature_restores_draft_off(self):
        """Resume restores the DRAFT value, not a hardcoded 1: turn the
        draft off while paused, and the live row must come back 0."""
        self._live_service()
        p = self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "downloads",
                                        "reason": "test pause"})
        self.assertEqual(self._live_row()["feature_downloads"], 0)
        self.svc.admin.execute(
            "UPDATE app_remote_config_v2 SET feature_downloads=0 WHERE id=1")
        self.svc.admin.commit()
        self.svc.resume_pause(self.owner, p["id"])
        self.assertEqual(self._live_row()["feature_downloads"], 0)

    def test_pause_feature_failure_leaves_version_and_row_untouched(self):
        """Catalog-first: a catalog the panel cannot write must not
        half-pause — the version stays put, no pause row exists, and
        the operator gets the real reason, so a retry works."""
        self._live_service()
        before = self.svc._config_row()["config_version"]
        conn = self._catalog()
        conn.execute("DROP TABLE app_remote_config")
        conn.commit()
        conn.close()
        with self.assertRaises(Exception) as ctx:
            self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "downloads",
                                        "reason": "test pause"})
        self.assertIn("live catalog", str(ctx.exception))
        self.assertEqual(self.svc._config_row()["config_version"], before)
        self.assertFalse(self.svc.is_paused("feature", "downloads"))
        self.assertEqual(self.svc.list_pauses(self.owner)["items"], [])

    def test_pause_feature_without_live_catalog_still_works(self):
        svc, owner, _aud = make_service()
        before = svc._config_row()["config_version"]
        p = svc.pause(owner, {"target_type": "feature",
                              "target_id": "downloads", "reason": "test"})
        self.assertTrue(svc.is_paused("feature", "downloads"))
        self.assertEqual(svc._config_row()["config_version"], before + 1)
        svc.resume_pause(owner, p["id"])
        self.assertFalse(svc.is_paused("feature", "downloads"))

    def test_pause_unknown_feature_name_is_snapshot_only(self):
        """A feature name with no live column keeps today's behaviour:
        version + snapshot, no catalog write at all."""
        self._live_service()
        before = self.svc._config_row()["config_version"]
        self.svc.pause(self.owner, {"target_type": "feature",
                                    "target_id": "recommendations",
                                    "reason": "test pause"})
        self.assertEqual(self._live_row()["feature_downloads"], 1)
        self.assertEqual(self.svc._config_row()["config_version"], before + 1)
        # D7: the startup migration already added the config_version
        # column at Service construction, so "no write" is proven by
        # the VALUE, not the column: it is still the 0 the migration
        # seeded, never stamped by a live write.
        self.assertEqual(self._live_row()["config_version"], 0)

    def test_pause_non_feature_target_writes_nothing_live(self):
        self._live_service()
        p = self.svc.pause(self.owner, {"target_type": "section",
                                        "target_id": "movie",
                                        "reason": "test pause"})
        self.assertEqual(self._live_row()["feature_downloads"], 1)
        # D7 (see above): the column exists from startup; the 0
        # value proves no live write happened.
        self.assertEqual(self._live_row()["config_version"], 0)
        self.svc.resume_pause(self.owner, p["id"])

    def test_pause_then_monetization_save_keeps_live_flag_off(self):
        """PNL-11 fixup: a routine monetization save while downloads
        are paused must not re-enable downloads on the live row — the
        draft keeps the operator's value, the live row keeps the 0."""
        self._live_service()
        p = self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "downloads",
                                        "reason": "test pause"})
        self.assertEqual(self._live_row()["feature_downloads"], 0)
        self.svc.put_monetization(self.owner, {"vip_monthly_pkr": 250})
        row = self._live_row()
        self.assertEqual(row["feature_downloads"], 0)
        self.assertEqual(row["vip_monthly_pkr"], 250)
        self.assertTrue(self.svc.is_paused("feature", "downloads"))
        # Even an explicit re-enable in the draft stays overlaid live.
        self.svc.put_monetization(self.owner, {"feature_downloads": 1})
        self.assertEqual(self._live_row()["feature_downloads"], 0)
        self.assertEqual(self.svc._config_row()["feature_downloads"], 1)
        self.svc.resume_pause(self.owner, p["id"])
        self.assertEqual(self._live_row()["feature_downloads"], 1)

    def test_pause_then_publish_keeps_live_flag_off(self):
        """The Publish repair path re-publishes the whole draft: a
        paused flag must stay 0 there too, and resume restores draft."""
        self._live_service()
        p = self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "downloads",
                                        "reason": "test pause"})
        out = self.svc.publish_monetization(self.owner, {"confirm": True})
        self.assertEqual(out["publish_status"], "ok")
        self.assertEqual(self._live_row()["feature_downloads"], 0)
        self.assertTrue(self.svc.is_paused("feature", "downloads"))
        self.svc.resume_pause(self.owner, p["id"])
        self.assertEqual(self._live_row()["feature_downloads"], 1)

    def test_pause_then_rollback_keeps_live_flag_off(self):
        """A rollback restores a snapshot whose flag is 1: while the
        pause is active the live row must still read 0, and the draft
        is restored untouched so resume brings the flag back."""
        self._live_service()
        self.svc.put_monetization(self.owner, {"feature_quality_gate": 1})
        v_gate = self.svc._config_row()["config_version"]
        self.assertEqual(self._live_row()["feature_quality_gate"], 1)
        p = self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "quality_gate",
                                        "reason": "test pause"})
        self.assertEqual(self._live_row()["feature_quality_gate"], 0)
        out = self.svc.rollback_remote_config(
            self.owner, {"version": v_gate, "confirm": True})
        row = self._live_row()
        self.assertEqual(row["feature_quality_gate"], 0)
        self.assertEqual(row["config_version"], out["config_version"])
        self.assertTrue(self.svc.is_paused("feature", "quality_gate"))
        self.assertEqual(self.svc._config_row()["feature_quality_gate"], 1)
        self.svc.resume_pause(self.owner, p["id"])
        self.assertEqual(self._live_row()["feature_quality_gate"], 1)

    def test_pause_coins_keeps_coins_enabled_off(self):
        """The one non-obvious mapping: panel feature "coins" is the
        live column coins_enabled. Coins have no v2 write path
        (PNL-03), so the test seeds the draft + live values
        directly — the state a pre-removal publish would have
        left. Pause, publish while paused, resume — the live
        column must track the pause, not the draft."""
        self._live_service()
        self.svc.admin.execute(
            "UPDATE app_remote_config_v2 SET coins_enabled=1 WHERE id=1")
        self.svc.admin.commit()
        conn = self._catalog()
        conn.execute(
            "UPDATE app_remote_config SET coins_enabled=1 WHERE id=1")
        conn.commit()
        conn.close()
        self.assertEqual(self._live_row()["coins_enabled"], 1)
        p = self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "coins",
                                        "reason": "test pause"})
        self.assertEqual(self._live_row()["coins_enabled"], 0)
        self.svc.put_monetization(self.owner, {"vip_monthly_pkr": 300})
        self.assertEqual(self._live_row()["coins_enabled"], 0)
        self.assertTrue(self.svc.is_paused("feature", "coins"))
        self.svc.resume_pause(self.owner, p["id"])
        self.assertEqual(self._live_row()["coins_enabled"], 1)

    def test_two_serial_pauses_bump_version_twice(self):
        """The version is allocated once per operation and passed
        through to the snapshot (never recomputed): two serial feature
        pauses bump the draft and the live row by exactly 1 each, with
        no lost or duplicated version."""
        self._live_service()
        before = self.svc._config_row()["config_version"]
        p1 = self.svc.pause(self.owner, {"target_type": "feature",
                                         "target_id": "downloads",
                                         "reason": "test pause"})
        p2 = self.svc.pause(self.owner, {"target_type": "feature",
                                         "target_id": "quality_gate",
                                         "reason": "test pause"})
        self.assertEqual(self.svc._config_row()["config_version"], before + 2)
        self.assertEqual(self._live_row()["config_version"], before + 2)
        self.assertEqual(self._live_row()["feature_downloads"], 0)
        self.svc.resume_pause(self.owner, p1["id"])
        self.svc.resume_pause(self.owner, p2["id"])
        self.assertEqual(self._live_row()["feature_downloads"], 1)
        self.assertEqual(self._live_row()["feature_quality_gate"], 0)

    def test_pause_row_failure_after_live_write_is_retryable(self):
        """Inverse failure (PNL-11 fixup): the live write succeeds and
        the pause-row INSERT then fails. The result is live flag 0 +
        version stamped with NO pause row and no UI resume path — but
        the pause was never recorded, so retrying the pause works and
        re-stamps the same live state."""
        self._live_service()
        before = self.svc._config_row()["config_version"]
        real_admin = self.svc.admin

        class _FailPauseInsert:
            """Delegate everything except the pauses INSERT, which
            fails the way a mid-pause admin-commit failure would."""
            def __init__(self, conn):
                self._conn = conn

            def execute(self, sql, *args, **kwargs):
                if str(sql).lstrip().upper().startswith("INSERT INTO PAUSES"):
                    raise sqlite3.OperationalError(
                        "simulated pause-row failure")
                return self._conn.execute(sql, *args, **kwargs)

            def commit(self):
                return self._conn.commit()

        self.svc.admin = _FailPauseInsert(real_admin)
        try:
            with self.assertRaises(Exception) as ctx:
                self.svc.pause(self.owner, {"target_type": "feature",
                                            "target_id": "downloads",
                                            "reason": "test pause"})
            self.assertIn("simulated pause-row failure", str(ctx.exception))
        finally:
            self.svc.admin = real_admin
        # Live row already flipped and stamped; nothing recorded.
        row = self._live_row()
        self.assertEqual(row["feature_downloads"], 0)
        self.assertEqual(row["config_version"], before + 1)
        self.assertEqual(self.svc._config_row()["config_version"], before)
        self.assertFalse(self.svc.is_paused("feature", "downloads"))
        self.assertEqual(self.svc.list_pauses(self.owner)["items"], [])
        # Retry works: the same pause now records and versions once.
        p = self.svc.pause(self.owner, {"target_type": "feature",
                                        "target_id": "downloads",
                                        "reason": "test pause"})
        self.assertTrue(self.svc.is_paused("feature", "downloads"))
        self.assertEqual(self._live_row()["feature_downloads"], 0)
        self.assertEqual(self.svc._config_row()["config_version"], before + 1)
        self.assertEqual(self._live_row()["config_version"], before + 1)
        self.svc.resume_pause(self.owner, p["id"])
        self.assertEqual(self._live_row()["feature_downloads"], 1)



if __name__ == "__main__":
    unittest.main()
