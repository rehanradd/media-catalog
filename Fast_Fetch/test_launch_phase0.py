"""Launch plan Phase 0 — canonical defaults (T-21) + served-body completeness (T-22).

The design (`~/workspace/launch-plan-design/design.md` §6.1) found the same
knob with different defaults in different places, and fixed each one to a
single canonical value:

- G-1 `feature_quality_gate`: the catalog DDL said 1 (ON); the panel v2 DDL,
  the live panel row, the published snapshot and the app default all say 0.
  Canonical: **0** — a fresh deployment must not gate 1080p/4K by surprise.
- G-5 `coin_redeem_weekend` / `coin_redeem_monthly`: the catalog DDL said
  70/200; the panel row and the live catalog say 1200/5000. Canonical:
  **1200/5000**. `engagement.REDEEM_COIN_COSTS` is a fourth source carrying
  the same stale 70/200 as the fallback for a zeroed/missing knob — the
  design's §1.8 does not name it, but T-21 asserts it agrees too, because a
  zeroed knob must fall back to the canonical price, not the pre-audit one.
- G-7 `warmup_count`: the panel store default said 50, `panel_feed` says
  200. Canonical: **200** — the 200 is live-tuned with a recorded reason
  (Player Fix v2: the prefetch selector picks the top 200 titles and the
  old cap throttled the sweep to 50 links), which is exactly the case the
  design's OQ-2 says resolves to "the store changes to 200". `tabs`
  canonical: **home,search,my_list,settings,live_tv** (the store value
  dropped a live tab).
- G-8 `free_quality_cap`: the served cap is the catalog row's value; the
  viewer overlay that replaced it with the `tuning_settings` mirror is
  gone. The column is new on the catalog row — a pre-existing row gains it
  by `_ensure_monetization`'s ALTER, reading back "" until the operator
  sets it, and the served body resolves "" to the field default "720p".

T-21 pins the agreement across all four sources by building both databases
fresh in memory and reading the seeded rows — not by parsing source text.
T-22 pins that `admin_panel.remote_config()` always serves every
`feature_flags.*` key (as real booleans) plus `free_quality_cap`, including
in the `settings_unavailable` fallback body.
"""

from __future__ import annotations

import sqlite3
import sys
import unittest
from pathlib import Path

import admin_panel
import engagement
import panel_feed

# The panel v2 store lives outside Fast_Fetch; the repo root is this file's
# grandparent when run as `python3 -S Fast_Fetch/test_launch_phase0.py`.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent
                       / "AdminV2" / "admin_v2"))
import store as panel_store


def _field_defaults():
    return {key: default
            for key, _label, _kind, default in admin_panel.MONETIZATION_FIELDS}


def _fresh_catalog_conn():
    conn = sqlite3.connect(":memory:")
    admin_panel._ensure_monetization(conn)
    return conn


class CanonicalDefaultsTests(unittest.TestCase):
    """T-21: the defaults sources agree on every shared key."""

    def test_catalog_field_defaults_are_canonical(self):
        fields = _field_defaults()
        self.assertEqual(fields["feature_quality_gate"], 0)  # G-1
        self.assertEqual(fields["coin_redeem_weekend"], 1200)  # G-5
        self.assertEqual(fields["coin_redeem_monthly"], 5000)  # G-5
        self.assertEqual(fields["free_quality_cap"], "720p")  # G-8

    def test_fresh_catalog_row_seeds_the_canonical_values(self):
        values = admin_panel._read_monetization(_fresh_catalog_conn())
        self.assertEqual(values["feature_quality_gate"], 0)
        self.assertEqual(values["coin_redeem_weekend"], 1200)
        self.assertEqual(values["coin_redeem_monthly"], 5000)
        # TEXT columns read back "" on a fresh row; the served body resolves
        # that to the field default (asserted in the T-22 class).
        self.assertEqual(values["free_quality_cap"], "")

    def test_panel_store_defaults_agree(self):
        conn = panel_store.connect(":memory:")
        try:
            panel_store.migrate(conn)
            row = conn.execute(
                "SELECT feature_quality_gate FROM app_remote_config_v2"
                " WHERE id = 1").fetchone()
            self.assertEqual(row["feature_quality_gate"], 0)  # G-1
            tuning = conn.execute(
                "SELECT warmup_count, tabs FROM tuning_settings"
                " WHERE id = 1").fetchone()
            self.assertEqual(tuning["warmup_count"], 200)  # G-7
            self.assertEqual(tuning["tabs"],
                             "home,search,my_list,settings,live_tv")  # G-7
            self.assertEqual(
                panel_store.COIN_REDEEM_DEFAULTS["coin_redeem_weekend"],
                1200)  # G-5
            self.assertEqual(
                panel_store.COIN_REDEEM_DEFAULTS["coin_redeem_monthly"],
                5000)  # G-5
        finally:
            conn.close()

    def test_panel_feed_knob_defaults_agree(self):
        self.assertEqual(panel_feed.TUNING_KNOBS["warmup_count"][0], 200)  # G-7
        self.assertEqual(panel_feed.TABS_DEFAULT,
                         ["home", "search", "my_list", "settings",
                          "live_tv"])  # G-7

    def test_engagement_shipped_fallback_agrees(self):
        # The fallback for a zeroed/missing knob must be the canonical
        # price, not the pre-audit one — otherwise a stored 0 reprices the
        # weekend pass to 70 while a fresh row charges 1200.
        self.assertEqual(engagement.REDEEM_COIN_COSTS["weekend"], 1200)  # G-5
        self.assertEqual(engagement.REDEEM_COIN_COSTS["monthly_vip"], 5000)
        ladder = engagement.resolve_ladder()
        self.assertEqual(ladder["weekend"]["coins"], 1200)
        self.assertEqual(ladder["monthly_vip"]["coins"], 5000)
        values = admin_panel._read_monetization(_fresh_catalog_conn())
        ladder = engagement.resolve_ladder(values)
        self.assertEqual(ladder["weekend"]["coins"], 1200)
        self.assertEqual(ladder["monthly_vip"]["coins"], 5000)


class ServedBodyCompletenessTests(unittest.TestCase):
    """T-22: remote_config() never omits a flag key; every value is 0/1."""

    def test_fresh_row_serves_every_flag_key(self):
        body = admin_panel.remote_config(_fresh_catalog_conn())
        self.assertEqual(body["feature_flags"],
                         {"downloads": True, "pure_cinema": True,
                          "chromecast": True, "quality_gate": False,
                          "coins": False})
        for value in body["feature_flags"].values():
            self.assertIsInstance(value, bool)
        self.assertEqual(body["free_quality_cap"], "720p")

    def test_coins_flag_follows_the_row(self):
        """Coins master switch (2026-11-17): off by default, on when the
        operator flips `coins_enabled` to 1 — the flag the app decodes."""
        conn = _fresh_catalog_conn()
        self.assertFalse(admin_panel.remote_config(conn)["feature_flags"]["coins"])
        conn.execute("UPDATE app_remote_config SET coins_enabled = 1 WHERE id = 1")
        self.assertTrue(admin_panel.remote_config(conn)["feature_flags"]["coins"])

    def test_fresh_row_serves_the_canonical_coin_prices(self):
        body = admin_panel.remote_config(_fresh_catalog_conn())
        passes = body["engagement"]["passes"]
        self.assertEqual(passes["weekend"]["coins"], 1200)
        self.assertEqual(passes["monthly_vip"]["coins"], 5000)

    def test_cap_follows_the_catalog_row(self):
        conn = _fresh_catalog_conn()
        conn.execute("UPDATE app_remote_config"
                     " SET free_quality_cap = '480p' WHERE id = 1")
        self.assertEqual(
            admin_panel.remote_config(conn)["free_quality_cap"], "480p")
        conn.execute("UPDATE app_remote_config"
                     " SET free_quality_cap = '1080p' WHERE id = 1")
        self.assertEqual(
            admin_panel.remote_config(conn)["free_quality_cap"], "720p")
        conn.execute("UPDATE app_remote_config"
                     " SET free_quality_cap = '' WHERE id = 1")
        self.assertEqual(
            admin_panel.remote_config(conn)["free_quality_cap"], "720p")

    def test_unavailable_row_still_serves_every_key(self):
        conn = sqlite3.connect(":memory:")
        conn.close()  # any read raises; remote_config answers the fallback
        body = admin_panel.remote_config(conn)
        self.assertTrue(body["settings_unavailable"])
        self.assertEqual(body["feature_flags"],
                         {"downloads": True, "pure_cinema": True,
                          "chromecast": True, "quality_gate": False,
                          "coins": False})
        for value in body["feature_flags"].values():
            self.assertIsInstance(value, bool)
        self.assertEqual(body["free_quality_cap"], "720p")


if __name__ == "__main__":
    unittest.main()
