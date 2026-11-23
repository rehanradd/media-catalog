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
from helpers import make_service  # noqa: E402
from admin_v2.live_stores import LiveStores  # noqa: E402
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
        self.svc = Service(os.path.join(self.tmp, "admin"), live=self.live)
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
        # The redacted draft renames the raw URL to a configured flag.
        self.assertIn("apk_update_url_configured", live["changed_keys"])

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
        svc, owner, _aud = make_service()
        svc.draft_remote_config(owner, {"maintenance_mode": 1})
        out = svc.publish_remote_config(owner, {"confirm": True})
        self.assertIn("config_version", out)
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

    def test_monetization_ads_switch_publishes_to_the_catalog(self):
        """Lane F: the ads switch is a real toggle, not a zone id. The
        draft flip publishes to the live catalog row the app reads — on
        and back off — and a catalog predating the column gains it on
        publish, the way coins_enabled did before it."""
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

    def test_save_remote_config_adds_missing_columns_on_old_catalog(self):
        """An old catalog gains the columns being written, the way v1
        _ensure_monetization adds them at read time."""
        self._live_service(ddl=OLD_DDL)
        out = self.live.save_remote_config(
            {"apk_update_url": "https://example.test/app.apk",
             "maintenance_mode": 1, "coins_enabled": 0}, config_version=4)
        cols = {r[1] for r in self._catalog().execute(
            "PRAGMA table_info(app_remote_config)")}
        self.assertIn("apk_update_url", cols)
        self.assertIn("coins_enabled", cols)
        self.assertIn("config_version", cols)
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


if __name__ == "__main__":
    unittest.main()
