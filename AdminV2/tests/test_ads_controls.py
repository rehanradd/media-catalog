"""Ads on/off + clean-ads-only controls (PNL-05).

The switches must gate what the app is served, not just what is stored:
ads off serves no unit id, clean-ads-only serves Monetag alone and
withholds the Adsterra id. Every live integration runs against TEMP
COPIES (real app_remote_config DDL). No test ever touches a live DB.
"""
import sqlite3
import sys
import os
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import make_service  # noqa: E402
from admin_v2 import fixture_app  # noqa: E402
from admin_v2.live_stores import LiveStores  # noqa: E402
from admin_v2.server import AdminApp  # noqa: E402
from admin_v2.services import Service  # noqa: E402
from test_publish_flow import FULL_DDL, OLD_DDL, PublishFlowBase  # noqa: E402

MONETAG = "10293847"
ADSTERRA = "0d1a7f3b9c"


class AdsControlsBase(PublishFlowBase):
    def _ads_on(self):
        self.svc.put_monetization(self.owner, {
            "monetag_zone_id": MONETAG, "adsterra_tag_id": ADSTERRA,
            "ads_enabled": 1})

    def _live_row(self):
        return self._catalog().execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()

    def _served_ads(self):
        return fixture_app.public_remote_config(self.svc)["ads"]


class TestDefaults(AdsControlsBase):
    def test_fresh_panel_row_defaults_fail_closed(self):
        """A deployment that never set the switches shows no ads (the
        app's own rule in VipRemoteAds), and clean-ads-only is on per
        the standing order: Monetag first, clean ads only."""
        svc, owner, _aud = make_service()
        row = svc._config_row()
        self.assertEqual(row["ads_enabled"], 0)
        self.assertEqual(row["clean_ads_only"], 1)
        versions = {r[0] for r in svc.admin.execute(
            "SELECT version FROM schema_version").fetchall()}
        self.assertIn(6, versions)

    def test_fresh_panel_serves_no_ad(self):
        svc, owner, _aud = make_service()
        ads = fixture_app.public_remote_config(svc)["ads"]
        self.assertIs(ads["enabled"], False)
        self.assertIs(ads["clean_ads_only"], True)
        self.assertEqual(ads["monetag_rewarded_zone_id"], "")
        self.assertEqual(ads["adsterra_native_banner_id"], "")


class TestGating(AdsControlsBase):
    def test_ads_on_serves_monetag_only_by_default(self):
        """Clean-ads-only is on by default, so with ads on the app gets
        Monetag and nothing else — even though an Adsterra id is stored."""
        self._live_service()
        self._ads_on()
        row = self._live_row()
        self.assertEqual(row["ads_enabled"], 1)
        self.assertEqual(row["clean_ads_only"], 1)
        self.assertEqual(row["monetag_zone_id"], MONETAG)
        self.assertEqual(row["adsterra_tag_id"], ADSTERRA)
        ads = self._served_ads()
        self.assertIs(ads["enabled"], True)
        self.assertIs(ads["clean_ads_only"], True)
        self.assertEqual(ads["monetag_rewarded_zone_id"], MONETAG)
        self.assertEqual(ads["adsterra_native_banner_id"], "")

    def test_ads_off_serves_no_unit_id_but_keeps_stored_ids(self):
        """Turning ads off serves nothing — and does not delete the
        stored ids, so turning ads back on needs no re-typing."""
        self._live_service()
        self._ads_on()
        self.svc.put_monetization(self.owner, {"ads_enabled": 0})
        row = self._live_row()
        self.assertEqual(row["ads_enabled"], 0)
        self.assertEqual(row["monetag_zone_id"], MONETAG)
        self.assertEqual(row["adsterra_tag_id"], ADSTERRA)
        ads = self._served_ads()
        self.assertIs(ads["enabled"], False)
        self.assertEqual(ads["monetag_rewarded_zone_id"], "")
        self.assertEqual(ads["adsterra_native_banner_id"], "")

    def test_clean_ads_off_serves_both_networks(self):
        """The owner can allow Adsterra explicitly; the flip is theirs,
        never a default."""
        self._live_service()
        self._ads_on()
        self.svc.put_monetization(self.owner, {"clean_ads_only": 0})
        ads = self._served_ads()
        self.assertIs(ads["clean_ads_only"], False)
        self.assertEqual(ads["monetag_rewarded_zone_id"], MONETAG)
        self.assertEqual(ads["adsterra_native_banner_id"], ADSTERRA)

    def test_partial_save_keeps_ads_state(self):
        """Saving only a price must not silently flip the ads switches:
        the staged publish carries every monetization field, not just
        the ones in the body."""
        self._live_service()
        self._ads_on()
        self.svc.put_monetization(self.owner, {"vip_monthly_pkr": 250})
        self.assertEqual(self._live_row()["ads_enabled"], 1)
        self.assertEqual(self._served_ads()["monetag_rewarded_zone_id"],
                         MONETAG)


class TestValidationAndMigration(AdsControlsBase):
    def test_invalid_flag_value_refused(self):
        self._live_service()
        with self.assertRaises(Exception) as ctx:
            self.svc.put_monetization(self.owner, {"ads_enabled": 2})
        self.assertIn("0 or 1", str(ctx.exception))
        with self.assertRaises(Exception) as ctx:
            self.svc.put_monetization(self.owner, {"clean_ads_only": 2})
        self.assertIn("0 or 1", str(ctx.exception))

    def test_old_catalog_gains_ads_columns_on_save(self):
        """A catalog predating the columns gains them on the first save
        carrying them — the same ensure the publish flow already does
        for apk_update_url / coins_enabled."""
        self._live_service(ddl=OLD_DDL)
        out = self.live.save_remote_config(
            {"ads_enabled": 1, "clean_ads_only": 0}, config_version=4)
        cols = {r[1] for r in self._catalog().execute(
            "PRAGMA table_info(app_remote_config)")}
        self.assertIn("ads_enabled", cols)
        self.assertIn("clean_ads_only", cols)
        self.assertEqual(out["ads_enabled"], 1)
        self.assertEqual(out["clean_ads_only"], 0)

    def test_old_panel_row_reads_fail_closed_defaults(self):
        """A panel row predating the columns (no v6 yet) still answers
        the fixture with off / clean-only instead of crashing."""
        svc, owner, _aud = make_service()
        svc.admin.execute(
            "ALTER TABLE app_remote_config_v2 RENAME COLUMN ads_enabled "
            "TO ads_enabled_gone")
        svc.admin.execute(
            "ALTER TABLE app_remote_config_v2 RENAME COLUMN clean_ads_only "
            "TO clean_ads_only_gone")
        svc.admin.commit()
        ads = fixture_app.public_remote_config(svc)["ads"]
        self.assertIs(ads["enabled"], False)
        self.assertIs(ads["clean_ads_only"], True)


class TestPageAndRollback(AdsControlsBase):
    def test_monetization_page_renders_ads_switches(self):
        self._live_service()
        status, _ctype, payload, _cookie, _loc = self._get_page(
            "/admin/monetization")
        self.assertEqual(status, 200)
        page = payload.decode("utf-8")
        self.assertIn('name="ads_enabled"', page)
        self.assertIn('name="clean_ads_only"', page)
        self.assertIn("Clean ads only", page)

    def test_rollback_restores_ads_switch(self):
        """A rollback restores the ads switches the same way it restores
        every other monetization field — the live row, not just the
        panel snapshot."""
        self._live_service()
        self._ads_on()
        v_on = self.svc._config_row()["config_version"]
        self.svc.put_monetization(self.owner, {"ads_enabled": 0})
        self.assertEqual(self._live_row()["ads_enabled"], 0)
        out = self.svc.rollback_remote_config(
            self.owner, {"version": v_on, "confirm": True})
        row = self._live_row()
        self.assertEqual(row["ads_enabled"], 1)
        self.assertEqual(row["config_version"], out["config_version"])
        self.assertEqual(
            self._served_ads()["monetag_rewarded_zone_id"], MONETAG)

    def test_old_app_parse_carries_ads_switches(self):
        """The v1 flat parse carries the switches, so an old client
        reading the grouped body still sees them."""
        self._live_service()
        self._ads_on()
        flat = fixture_app.old_app_parse(
            fixture_app.public_remote_config(self.svc))
        self.assertIs(flat["ads_enabled"], True)
        self.assertIs(flat["clean_ads_only"], True)
        self.assertEqual(flat["monetag_zone_id"], MONETAG)
        self.assertEqual(flat["adsterra_tag_id"], "")


if __name__ == "__main__":
    unittest.main()
