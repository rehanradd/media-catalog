#!/usr/bin/env python3
"""Offline tests for the HubCDN gateway unwrap — the ``hubcdn.io/cloud/<id>``
bridge used by hdhub4u and hdtvb (crown order #54, 2026-11-05).

Stdlib only, zero network: every fetch goes through the ``_fetch`` / ``_hop``
seams that ``fast_fetch.unwrap_hubcdn`` already exposes, so each test names the
property it proves and none of them can slow a CI box or knock on a third party.

The point of the file is the trust gate: the gateway is untrusted input, and
every Location it answers with is judged by ``host_classifier`` — never by the
button's label and never by a substring. Tests named ``refuses``/``buy`` are the
counterfactual anchors: sabotage ``hubcdn_asset_role`` to accept anything and
they go red.

Bank-all-4 (speed lane, 2026-11-23): one gateway resolve fans out to four
mirrors — R2 (permanent), Google (minted), Gofile, Telegram — and the tests
below pin that all four are banked from a single round of hops, deduped by
href (the live page's 8 buttons are 4 distinct hrefs), with R2 first and
pacing honoured. Only R2/Google are playable files; Gofile/Telegram alone
are still an honest failure.

    python3 -S Fast_Fetch/test_hubcdn_unwrap.py
"""
from __future__ import annotations

import re
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.resolve()))

import fast_fetch  # noqa: E402
import host_classifier as hc  # noqa: E402

R2_ASSET = "https://pub-abc123.r2.dev/movie.mkv"
GOOGLE_ASSET = "https://video-downloads.googleusercontent.com/dl/movie"
GOFILE_ASSET = "https://gofile.io/d/SAYYBt81"
TELEGRAM_ASSET = "https://t.me/filesdll_bot"


def gateway_page(buttons) -> str:
    """Build a gateway page from ``[(attrs, label), ...]`` button specs."""
    anchors = "\n".join(f'<a {attrs}>{label}</a>' for attrs, label in buttons)
    return f"<html><head><title>Movie 1080p - HubCDN</title></head><body>{anchors}</body></html>"


def trigger(href: str, label: str = "Download") -> tuple[str, str]:
    return (f'class="btn dl-trigger" href="{href}"', label)


class HopRecorder:
    """The ``_hop`` seam: answers fixed statuses and keeps every header it saw.

    ``unwrap_hubcdn`` stamps a fresh ``dl_token`` onto each button href, so the
    lookup strips it before matching — the token itself is asserted separately.
    """

    def __init__(self, hops: dict[str, tuple[int, str]]):
        self.hops = hops
        self.urls: list[str] = []
        self.headers: list[dict] = []

    def __call__(self, url: str, headers: dict) -> tuple[int, str]:
        self.urls.append(url)
        self.headers.append(dict(headers))
        stripped = re.sub(r"[&?]dl_token=[^&]*", "", url)
        return self.hops.get(stripped, (404, ""))


HAPPY_BUTTONS = [
    trigger("/l.php?id=9&amp;t=a", "480p [500MB]"),
    trigger("https://hubcdn.io/l.php?id=10", "1080p [4.2GB]"),
]


def happy_hops() -> dict:
    return {
        "https://hubcdn.io/l.php?id=9&t=a": (302, R2_ASSET),
        "https://hubcdn.io/l.php?id=10": (302, GOOGLE_ASSET),
    }


class BridgeRecognitionTests(unittest.TestCase):
    """is_bridge_url must agree with host_classifier._HOST_TABLE on hubcdn."""

    def test_hubcdn_cloud_page_is_a_bridge(self):
        self.assertTrue(fast_fetch.is_bridge_url("https://hubcdn.io/cloud/abc123"))

    def test_hubcdn_cloud_page_with_query_is_a_bridge(self):
        self.assertTrue(fast_fetch.is_bridge_url("https://hubcdn.io/cloud/abc?x=1"))

    def test_hubcdn_subdomain_is_a_bridge(self):
        self.assertTrue(fast_fetch.is_bridge_url("https://www.hubcdn.io/cloud/abc"))

    def test_lookalike_host_containing_the_hubcdn_name_is_not_a_bridge(self):
        self.assertFalse(fast_fetch.is_bridge_url("https://hubcdnio.attacker.tld/cloud/abc"))

    def test_hubcdn_path_on_another_host_is_not_a_bridge(self):
        self.assertFalse(fast_fetch.is_bridge_url("https://example.com/hubcdn.io/cloud/x"))

    def test_bridge_verdict_agrees_with_the_classifier_category(self):
        verdict = hc.classify("https://hubcdn.io/cloud/abc")
        self.assertEqual(verdict.category, hc.CATEGORY_BRIDGE)
        self.assertTrue(fast_fetch.is_bridge_url("https://hubcdn.io/cloud/abc"))

    def test_a_bridge_is_never_a_direct_media_url(self):
        self.assertFalse(fast_fetch.is_direct_media_url("https://hubcdn.io/cloud/abc"))


class PagePredicateTests(unittest.TestCase):

    def test_cloud_path_is_the_only_unwrappable_page_shape(self):
        self.assertTrue(hc.is_hubcdn_cloud_page("https://hubcdn.io/cloud/abc"))
        self.assertFalse(hc.is_hubcdn_cloud_page("https://hubcdn.io/file/abc"))
        self.assertFalse(hc.is_hubcdn_cloud_page("https://hubcdn.io/"))


class TrustGateTests(unittest.TestCase):
    """Every Location the gateway hands back is judged by the authority table."""

    def test_role_accepts_public_r2_bucket_as_stream(self):
        self.assertEqual(hc.hubcdn_asset_role("https://pub-z9.r2.dev/a.mkv"), "stream")

    def test_role_accepts_google_front_as_download(self):
        self.assertEqual(
            hc.hubcdn_asset_role("https://video-downloads.googleusercontent.com/a"),
            "download")

    def test_role_refuses_raw_r2_api_host_without_the_pub_prefix(self):
        self.assertIsNone(hc.hubcdn_asset_role("https://abc.r2.dev/a.mkv"))

    def test_role_refuses_r2_lookalike_suffixes(self):
        for hostile in ("https://r2.dev.attacker.tld/x.mkv",
                        "https://pub-abc.r2.dev.evil.cn/y.mkv"):
            with self.subTest(hostile):
                self.assertIsNone(hc.hubcdn_asset_role(hostile))

    def test_role_refuses_google_front_lookalike_subdomain(self):
        self.assertIsNone(hc.hubcdn_asset_role(
            "https://video-downloads.googleusercontent.com.evil.cn/z"))

    def test_service_stamp_names_the_serving_cdn_not_the_gateway(self):
        self.assertEqual(hc.hubcdn_asset_service(R2_ASSET), "fsl_r2")
        self.assertEqual(hc.hubcdn_asset_service(GOOGLE_ASSET), "google_cdn")
        self.assertIsNone(hc.hubcdn_asset_service("https://r2.dev.attacker.tld/x.mkv"))


class ButtonParsingTests(unittest.TestCase):

    def test_only_dl_trigger_anchors_become_buttons(self):
        page = gateway_page([
            trigger("/l.php?id=1", "Real"),
            ('href="https://hubcdn.io/l.php?id=2"', "Not a trigger"),
        ])
        buttons = hc.hubcdn_page_buttons(page)
        self.assertEqual(len(buttons), 1)

    def test_ampersand_escaped_href_is_unescaped(self):
        page = gateway_page([trigger("/l.php?id=9&amp;t=a")])
        href, _label = hc.hubcdn_page_buttons(page)[0]
        self.assertEqual(href, "/l.php?id=9&t=a")

    def test_href_survive_reordered_anchor_attributes(self):
        page = '<a href="/l.php?id=3" class="dl-trigger">x</a>'
        self.assertEqual(len(hc.hubcdn_page_buttons(page)), 1)


class TokenTests(unittest.TestCase):

    def test_token_added_to_a_bare_href(self):
        out = hc.hubcdn_with_token("https://hubcdn.io/l.php", "t1")
        self.assertEqual(out, "https://hubcdn.io/l.php?dl_token=t1")

    def test_token_appended_without_disturbing_the_existing_query(self):
        out = hc.hubcdn_with_token("https://hubcdn.io/l.php?id=9", "t1")
        self.assertTrue(out.endswith("id=9&dl_token=t1"), out)
        self.assertIn("?", out)
        self.assertEqual(out.count("?"), 1)

    def test_root_relative_href_resolves_against_the_gateway_host(self):
        self.assertEqual(hc.hubcdn_resolve_href("/l.php?id=9"),
                         "https://hubcdn.io/l.php?id=9")


class UnwrapTests(unittest.TestCase):
    """unwrap_hubcdn end to end, with both network edges injected."""

    def unwrap(self, hops, buttons=None, cookies="session=xyz", url=None):
        page = gateway_page(HAPPY_BUTTONS if buttons is None else buttons)
        recorder = HopRecorder(hops)
        res = fast_fetch.unwrap_hubcdn(
            url or "https://hubcdn.io/cloud/abc123",
            _fetch=lambda u: (page, cookies), _hop=recorder,
            _sleep=lambda s: None)
        return res, recorder

    def test_happy_page_yields_both_assets(self):
        res, _rec = self.unwrap(happy_hops())
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["r2_url"], R2_ASSET)
        self.assertEqual(res["google_url"], GOOGLE_ASSET)

    def test_title_drops_the_gateway_suffix(self):
        res, _rec = self.unwrap(happy_hops())
        self.assertEqual(res["title"], "Movie 1080p")

    def test_referer_and_token_reach_every_hop(self):
        res, rec = self.unwrap(happy_hops())
        self.assertTrue(res["ok"], res)
        self.assertEqual(len(rec.urls), 2)
        for url, headers in zip(rec.urls, rec.headers):
            self.assertEqual(headers.get("Referer"), "https://hubcdn.io/cloud/abc123")
            self.assertIn("dl_token=", url)

    def test_size_is_taken_from_the_button_label(self):
        res, _rec = self.unwrap(happy_hops())
        self.assertEqual(res["size_str"], "500MB")

    def test_slotting_is_by_role_not_button_order(self):
        buttons = [  # google button FIRST this time
            trigger("https://hubcdn.io/l.php?id=10", "1080p [4.2GB]"),
            trigger("/l.php?id=9&amp;t=a", "480p [500MB]"),
        ]
        res, _rec = self.unwrap(happy_hops(), buttons=buttons)
        self.assertEqual(res["r2_url"], R2_ASSET)
        self.assertEqual(res["google_url"], GOOGLE_ASSET)

    def test_dispatch_prefers_the_r2_stream_when_both_exist(self):
        # Mirrors the resolve_live_source refresh (`fresh_url = res.get("r2_url")
        # or res.get("google_url")`): the bucket copy is the streamable one.
        res, _rec = self.unwrap(happy_hops())
        fresh_url = res.get("r2_url") or res.get("google_url")
        self.assertEqual(fresh_url, R2_ASSET)

    def test_download_survives_alone_when_the_bucket_hop_fails(self):
        lone = {"https://hubcdn.io/l.php?id=10": (302, GOOGLE_ASSET)}
        res, _rec = self.unwrap(lone)
        self.assertTrue(res["ok"], res)
        self.assertIsNone(res["r2_url"])
        self.assertEqual(res["google_url"], GOOGLE_ASSET)

    def test_hostile_locations_buy_no_asset(self):
        hostile_buttons = [
            trigger("/l.php?id=9&amp;t=a", "bucket trick"),
            trigger("/l.php?id=10&amp;t=b", "suffix trick"),
        ]
        hops = {
            "https://hubcdn.io/l.php?id=9&t=a": (302, "https://r2.dev.attacker.tld/x.mkv"),
            "https://hubcdn.io/l.php?id=10&t=b": (302, "https://pub-abc.r2.dev.evil.cn/y.mkv"),
        }
        res, _rec = self.unwrap(hops, buttons=hostile_buttons)
        self.assertFalse(res["ok"], res)
        self.assertEqual(res["error"], "no_trusted_asset_in_any_redirect")
        self.assertIsNone(res["r2_url"])
        self.assertIsNone(res["google_url"])

    def test_non_hubcdn_input_is_refused_before_any_fetch(self):
        fetched = []
        recorder = HopRecorder({})
        res = fast_fetch.unwrap_hubcdn(
            "https://example.com/cloud/abc",
            _fetch=lambda u: fetched.append(u) or ("", ""), _hop=recorder)
        self.assertEqual(res["error"], "not_a_hubcdn_cloud_page")
        self.assertEqual(fetched, [], "the fetch edge must not be touched")

    def test_file_page_input_refuses_even_when_hops_would_succeed(self):
        # Guards the dispatch condition's URL-substring arm, which is broader
        # than the strict page predicate: a hubcdn row on /file/ must never be
        # walked as if it were a cloud page.
        res, _rec = self.unwrap(happy_hops(),
                                url="https://hubcdn.io/file/abc")
        self.assertEqual(res["error"], "not_a_hubcdn_cloud_page")
        self.assertFalse(res["ok"])

    def test_buttonless_page_reports_no_buttons(self):
        res, _rec = self.unwrap(happy_hops(), buttons=[])
        self.assertEqual(res["error"], "no_dl_trigger_buttons")

    def test_preparing_page_names_its_filesdl_target(self):
        # Second gateway shape (measured live 2026-11-20): a "Preparing
        # Download" countdown page with no buttons and a filesdl target.
        # The target is evidence, never an asset: filesdl is JS-challenged.
        page = ('<html><head><title>Preparing Download - HubCDN</title></head>'
                '<body><script>var targetUrl="https://new1.filesdl.in/'
                'drive/nm27vVUjJT";</script></body></html>')
        res = fast_fetch.unwrap_hubcdn(
            "https://hubcdn.io/cloud/abc",
            _fetch=lambda u: (page, ""), _hop=HopRecorder({}),
            _sleep=lambda s: None)
        self.assertFalse(res["ok"])
        self.assertEqual(res["error"], "preparing_download")
        self.assertEqual(res["preparing_target"],
                         "https://new1.filesdl.in/drive/nm27vVUjJT")

    def test_preparing_target_parses_without_buttons(self):
        self.assertEqual(
            hc.hubcdn_preparing_target(
                'var targetUrl="https://new1.filesdl.in/drive/abc";'),
            "https://new1.filesdl.in/drive/abc")
        self.assertEqual(
            hc.hubcdn_preparing_target(
                "var targetUrl='https://new1.filesdl.in/drive/abc';"),
            "https://new1.filesdl.in/drive/abc")

    def test_preparing_target_is_absent_on_a_normal_page(self):
        self.assertIsNone(hc.hubcdn_preparing_target(
            gateway_page(HAPPY_BUTTONS)))
        self.assertIsNone(hc.hubcdn_preparing_target(""))

    def test_absent_cookies_send_no_cookie_header_at_all(self):
        # An empty `Cookie: ` header is a different request from none; the
        # gateway must not see one it never got.
        res, rec = self.unwrap(happy_hops(), cookies="")
        self.assertTrue(res["ok"], res)
        for headers in rec.headers:
            self.assertNotIn("Cookie", headers)

    def test_fetch_crash_is_reported_not_raised(self):
        def boom(url):
            raise OSError("gateway is having a day")
        res = fast_fetch.unwrap_hubcdn("https://hubcdn.io/cloud/abc",
                                       _fetch=boom, _hop=HopRecorder({}),
                                       _sleep=lambda s: None)
        self.assertFalse(res["ok"])
        self.assertTrue(res["error"].startswith("cloud_fetch_fail:"), res)

    def test_empty_page_is_reported(self):
        res = fast_fetch.unwrap_hubcdn("https://hubcdn.io/cloud/abc",
                                       _fetch=lambda u: ("", ""),
                                       _hop=HopRecorder({}),
                                       _sleep=lambda s: None)
        self.assertEqual(res["error"], "empty_cloud_page")


# The live page (probe5, 2026-11-23): 8 buttons, 4 distinct hrefs. Five
# labels share the one Google href — the button NAMED HubCloud does not go
# to HubCloud at all.
EIGHT_BUTTONS = [
    trigger("/l.php?file=cf", "Cloud Direct [480MB]"),
    trigger("/l.php?file=plain", "Direct Download (10Gbps)"),
    trigger("/l.php?file=plain", "Download Pixeldrain -1"),
    trigger("/l.php?file=plain", "Download - HubCloud"),
    trigger("/l.php?file=plain", "Login To Download - GDFLIX"),
    trigger("/l.php?file=plain", "Buzz Download"),
    trigger("/l.php?file=go", "Download - GoFile.io"),
    trigger("/l.php?file=tg", "Telegram File"),
]


def eight_hops() -> dict:
    return {
        "https://hubcdn.io/l.php?file=cf": (302, R2_ASSET),
        "https://hubcdn.io/l.php?file=plain": (302, GOOGLE_ASSET),
        "https://hubcdn.io/l.php?file=go": (302, GOFILE_ASSET),
        "https://hubcdn.io/l.php?file=tg": (302, TELEGRAM_ASSET),
    }


class BankAllMirrorsTests(unittest.TestCase):
    """One resolve banks all four mirrors; dedupe is by href, never label."""

    def unwrap_eight(self, hops=None, buttons=None, sleep=None):
        page = gateway_page(EIGHT_BUTTONS if buttons is None else buttons)
        recorder = HopRecorder(eight_hops() if hops is None else hops)
        res = fast_fetch.unwrap_hubcdn(
            "https://hubcdn.io/cloud/abc123",
            _fetch=lambda u: (page, "session=xyz"), _hop=recorder,
            _sleep=sleep or (lambda s: None))
        return res, recorder

    def test_eight_buttons_cost_four_hops_and_bank_four_mirrors(self):
        res, rec = self.unwrap_eight()
        self.assertTrue(res["ok"], res)
        # 5 duplicate Google labels collapse to one hop.
        self.assertEqual(len(rec.urls), 4, rec.urls)
        self.assertEqual(res["mirrors"], {
            "r2": R2_ASSET, "google": GOOGLE_ASSET,
            "gofile": GOFILE_ASSET, "telegram": TELEGRAM_ASSET})
        self.assertEqual(list(res["mirrors"]),
                         ["r2", "google", "gofile", "telegram"])
        self.assertEqual(res["r2_url"], R2_ASSET)
        self.assertEqual(res["google_url"], GOOGLE_ASSET)
        self.assertEqual(res["gofile_url"], GOFILE_ASSET)
        self.assertEqual(res["telegram_url"], TELEGRAM_ASSET)

    def test_slotting_is_by_role_not_label(self):
        # A button labelled "HubCloud" that 302s to Google slots as Google;
        # a button labelled "Pixeldrain" that 302s to R2 slots as R2.
        buttons = [
            trigger("/l.php?file=a", "Download - HubCloud"),
            trigger("/l.php?file=b", "Download Pixeldrain -1"),
        ]
        hops = {
            "https://hubcdn.io/l.php?file=a": (302, GOOGLE_ASSET),
            "https://hubcdn.io/l.php?file=b": (302, R2_ASSET),
        }
        res, _rec = self.unwrap_eight(hops=hops, buttons=buttons)
        self.assertEqual(res["google_url"], GOOGLE_ASSET)
        self.assertEqual(res["r2_url"], R2_ASSET)
        self.assertEqual(list(res["mirrors"]), ["r2", "google"])

    def test_gofile_and_telegram_alone_are_not_playable_but_still_banked(self):
        hops = {
            "https://hubcdn.io/l.php?file=go": (302, GOFILE_ASSET),
            "https://hubcdn.io/l.php?file=tg": (302, TELEGRAM_ASSET),
        }
        buttons = [EIGHT_BUTTONS[6], EIGHT_BUTTONS[7]]
        res, _rec = self.unwrap_eight(hops=hops, buttons=buttons)
        self.assertFalse(res["ok"], res)
        self.assertEqual(res["error"], "no_trusted_asset_in_any_redirect")
        self.assertEqual(res["mirrors"], {
            "gofile": GOFILE_ASSET, "telegram": TELEGRAM_ASSET})

    def test_hostile_gofile_and_telegram_lookalikes_buy_no_mirror(self):
        hops = {
            "https://hubcdn.io/l.php?file=cf": (302, R2_ASSET),
            "https://hubcdn.io/l.php?file=g1": (302, "https://gofile.io.evil.cn/d/x"),
            "https://hubcdn.io/l.php?file=g2": (302, "https://gofile.io/x"),
            "https://hubcdn.io/l.php?file=t1": (302, "https://t.me.evil.cn/x"),
            "https://hubcdn.io/l.php?file=t2": (302, "https://t.me/"),
        }
        buttons = [
            trigger("/l.php?file=cf", "Cloud Direct"),
            trigger("/l.php?file=g1", "GoFile evil suffix"),
            trigger("/l.php?file=g2", "GoFile no /d/ path"),
            trigger("/l.php?file=t1", "Telegram evil suffix"),
            trigger("/l.php?file=t2", "Telegram empty path"),
        ]
        res, _rec = self.unwrap_eight(hops=hops, buttons=buttons)
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["mirrors"], {"r2": R2_ASSET})
        self.assertIsNone(res["gofile_url"])
        self.assertIsNone(res["telegram_url"])

    def test_hop_403_and_404_are_honest_failures(self):
        hops = {
            "https://hubcdn.io/l.php?file=cf": (403, ""),
            "https://hubcdn.io/l.php?file=plain": (404, ""),
        }
        buttons = [EIGHT_BUTTONS[0], EIGHT_BUTTONS[1]]
        res, _rec = self.unwrap_eight(hops=hops, buttons=buttons)
        self.assertFalse(res["ok"], res)
        self.assertEqual(res["error"], "no_trusted_asset_in_any_redirect")
        self.assertEqual(res["mirrors"], {})


class PacingTests(unittest.TestCase):
    """hubcdn.io 429s after ~25 rapid requests: every request is paced."""

    def test_fetch_plus_each_hop_is_spaced_at_least_020s(self):
        sleeps: list[float] = []
        fast_fetch._hubcdn_last_request = time.monotonic()
        try:
            page = gateway_page(EIGHT_BUTTONS)
            res = fast_fetch.unwrap_hubcdn(
                "https://hubcdn.io/cloud/abc123",
                _fetch=lambda u: (page, ""), _hop=HopRecorder(eight_hops()),
                _sleep=sleeps.append)
        finally:
            fast_fetch._hubcdn_last_request = time.monotonic()
        self.assertTrue(res["ok"], res)
        # 1 cloud fetch + 4 deduped hops, each spaced >= 0.20 s.
        self.assertEqual(len(sleeps), 5, sleeps)
        for slept in sleeps:
            self.assertGreaterEqual(slept, 0.19, sleeps)


_BANK_SCHEMA = """
    CREATE TABLE download_sources (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        release_id INTEGER NOT NULL, source_name TEXT, service TEXT,
        resource_id TEXT, source_url TEXT, final_url TEXT,
        playback_type TEXT DEFAULT 'url', is_temporary INTEGER DEFAULT 0,
        status TEXT DEFAULT 'active', is_resumable INTEGER DEFAULT 0,
        http_status INTEGER DEFAULT NULL, file_size_bytes INTEGER DEFAULT NULL,
        mime_type TEXT DEFAULT NULL, last_checked_at TIMESTAMP DEFAULT NULL,
        last_failed_at REAL DEFAULT NULL, failure_class TEXT DEFAULT NULL,
        failure_resolver_v INTEGER DEFAULT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
"""

_ALL_MIRRORS = {"r2": R2_ASSET, "google": GOOGLE_ASSET,
                "gofile": GOFILE_ASSET, "telegram": TELEGRAM_ASSET}


class BankSiblingsTests(unittest.TestCase):
    """_bank_hubcdn_siblings: INSERTs the missing mirrors, never duplicates."""

    def setUp(self):
        self._tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        self._tmp.close()
        self.conn = sqlite3.connect(self._tmp.name)
        self.conn.execute(_BANK_SCHEMA)
        # The pressed row, already rewritten to the R2 winner.
        self.conn.execute(
            "INSERT INTO download_sources (id, release_id, source_name,"
            " service, source_url, final_url, playback_type, is_temporary,"
            " status) VALUES (1, 77, 'HubCDN Cloud Gateway [720p-HEVC]',"
            " 'hubcdn', 'https://hubcdn.io/cloud/abc123', ?, 'url', 0,"
            " 'active')", (R2_ASSET,))
        self.conn.commit()

    def tearDown(self):
        self.conn.close()
        Path(self._tmp.name).unlink(missing_ok=True)

    def rows(self):
        return self.conn.execute(
            "SELECT service, source_url, final_url, is_temporary"
            " FROM download_sources WHERE release_id = 77"
            " ORDER BY id").fetchall()

    def bank(self, mirrors=None):
        n = fast_fetch._bank_hubcdn_siblings(
            self.conn, release_id=77, pressed_id=1,
            cloud_url="https://hubcdn.io/cloud/abc123",
            mirrors=dict(_ALL_MIRRORS if mirrors is None else mirrors),
            source_name="HubCDN Cloud Gateway [720p-HEVC]")
        self.conn.commit()
        return n

    def test_banks_the_three_other_mirrors_not_the_winner(self):
        self.assertEqual(self.bank(), 3)
        rows = self.rows()
        self.assertEqual(len(rows), 4, rows)
        by_service = {r[0]: r for r in rows}
        google = by_service["google_cdn"]
        self.assertEqual(google[2], GOOGLE_ASSET)
        self.assertEqual(google[1], "https://hubcdn.io/cloud/abc123")
        self.assertEqual(google[3], 1, "Google banks temporary")
        gofile = by_service["gofile"]
        self.assertEqual(gofile[1], GOFILE_ASSET)
        self.assertEqual(gofile[2], GOFILE_ASSET)
        self.assertEqual(gofile[3], 0)
        telegram = by_service["telegram"]
        self.assertEqual(telegram[2], TELEGRAM_ASSET)
        self.assertEqual(telegram[3], 0)
        # The R2 winner stays exactly one row.
        self.assertEqual(sum(1 for r in rows if r[2] == R2_ASSET), 1)

    def test_second_run_banks_nothing(self):
        self.assertEqual(self.bank(), 3)
        self.assertEqual(self.bank(), 0)
        self.assertEqual(len(self.rows()), 4)

    def test_already_banked_url_is_skipped(self):
        self.conn.execute(
            "INSERT INTO download_sources (release_id, source_name,"
            " service, source_url, final_url, is_temporary, status)"
            " VALUES (77, 'old google', 'google_cdn',"
            " 'https://hubcdn.io/cloud/abc123', ?, 1, 'active')",
            (GOOGLE_ASSET,))
        self.conn.commit()
        self.assertEqual(self.bank(), 2)
        rows = self.rows()
        self.assertEqual(sum(1 for r in rows if r[2] == GOOGLE_ASSET), 1)

    def test_empty_mirrors_bank_nothing(self):
        self.assertEqual(self.bank(mirrors={}), 0)
        self.assertEqual(len(self.rows()), 1)


#: The gate verdicts the resolve tests fake: an R2 bucket that 403/1010s
#: the datacenter egress (measured live 2026-11-23), and a Google twin
#: that Range-probes clean.
def _blocked_verdict():
    return {"ok": False, "kind": None, "mime": None, "size": 16791,
            "resumable": False, "renditions": [], "failure": "blocked"}


def _file_verdict():
    return {"ok": True, "kind": "file", "mime": "video/x-matroska",
            "size": None, "resumable": True, "renditions": [],
            "failure": None}


class ResolveFallbackTests(unittest.TestCase):
    """resolve_live_source on a scratch DB: R2-first, Google fallback, banking.

    The unwrap and the gate probe are the only network edges and both are
    faked, so no test touches a third party; the DB is a temp file with the
    real schema (``fast_fetch.init_db``), and ``DB_PATH`` is restored after.
    """

    CLOUD_URL = "https://hubcdn.io/cloud/abc123"

    def setUp(self):
        self._tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        self._tmp.close()
        self._old_db_path = fast_fetch.DB_PATH
        self._old_unwrap = fast_fetch.unwrap_hubcdn
        self._old_probe = fast_fetch._stage2_probe
        self._old_ttl = fast_fetch.probe_url_ttl
        fast_fetch.DB_PATH = Path(self._tmp.name)
        fast_fetch.init_db()
        fast_fetch.probe_url_ttl = lambda u, **k: (None, {"reason": "no_directive"})
        conn = sqlite3.connect(self._tmp.name)
        conn.execute(
            "INSERT INTO posts (url, title) VALUES"
            " ('https://example.invalid/hubcdn-probe', 'hubcdn probe')")
        post_id = conn.execute("SELECT id FROM posts").fetchone()[0]
        conn.execute(
            "INSERT INTO releases (post_id, release_title, quality)"
            " VALUES (?, 'hubcdn probe 720p', '720p')", (post_id,))
        self.rel_id = conn.execute("SELECT id FROM releases").fetchone()[0]
        conn.execute(
            "INSERT INTO download_sources (release_id, source_name, service,"
            " source_url, final_url, is_temporary, status)"
            " VALUES (?, 'HubCDN Cloud Gateway [720p]', 'hubcdn', ?, ?, 0,"
            " 'active')", (self.rel_id, self.CLOUD_URL, self.CLOUD_URL))
        self.row_id = conn.execute(
            "SELECT id FROM download_sources").fetchone()[0]
        conn.commit()
        conn.close()

    def tearDown(self):
        fast_fetch.DB_PATH = self._old_db_path
        fast_fetch.unwrap_hubcdn = self._old_unwrap
        fast_fetch._stage2_probe = self._old_probe
        fast_fetch.probe_url_ttl = self._old_ttl
        Path(self._tmp.name).unlink(missing_ok=True)

    def _fake_unwrap(self, res):
        fast_fetch.unwrap_hubcdn = lambda url, **k: dict(res)

    def _fake_probe(self, r2_ok: bool, google_ok: bool):
        def probe(url, *a, **k):
            if "r2.dev" in url:
                return _file_verdict() if r2_ok else _blocked_verdict()
            if "googleusercontent.com" in url:
                return _file_verdict() if google_ok else _blocked_verdict()
            return _blocked_verdict()
        fast_fetch._stage2_probe = probe

    def _rows(self):
        conn = sqlite3.connect(self._tmp.name)
        rows = conn.execute(
            "SELECT service, final_url, is_temporary, status FROM"
            " download_sources WHERE release_id = ? ORDER BY id",
            (self.rel_id,)).fetchall()
        conn.close()
        return rows

    def test_blocked_r2_falls_back_to_google_and_banks_the_rest(self):
        self._fake_unwrap({"ok": True, "r2_url": R2_ASSET,
                           "google_url": GOOGLE_ASSET,
                           "mirrors": dict(_ALL_MIRRORS)})
        self._fake_probe(r2_ok=False, google_ok=True)
        res = fast_fetch.resolve_live_source(self.row_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], GOOGLE_ASSET)
        self.assertTrue(res["is_temporary"], res)
        # Winner is Google, so the other three mirrors bank as siblings.
        self.assertEqual(res["mirrors_banked"], 3, res)
        rows = self._rows()
        self.assertEqual(len(rows), 4, rows)
        # Pressed row rewritten to the Google winner, temporary...
        self.assertEqual(rows[0][1], GOOGLE_ASSET)
        self.assertEqual(rows[0][2], 1)
        # ...and the blocked-but-real R2 asset banked as its sibling.
        self.assertEqual(rows[1][0], "hubcdn")
        self.assertEqual(rows[1][1], R2_ASSET)
        self.assertEqual(rows[1][2], 0)

    def test_r2_winner_banks_the_google_sibling(self):
        self._fake_unwrap({"ok": True, "r2_url": R2_ASSET,
                           "google_url": GOOGLE_ASSET,
                           "mirrors": dict(_ALL_MIRRORS)})
        self._fake_probe(r2_ok=True, google_ok=True)
        res = fast_fetch.resolve_live_source(self.row_id)
        self.assertTrue(res["success"], res)
        self.assertEqual(res["url"], R2_ASSET)
        self.assertFalse(res["is_temporary"], res)
        rows = self._rows()
        self.assertEqual(len(rows), 4, rows)
        by_service = {r[0]: r for r in rows}
        self.assertEqual(by_service["google_cdn"][1], GOOGLE_ASSET)
        self.assertEqual(by_service["google_cdn"][2], 1)
        self.assertEqual(by_service["gofile"][1], GOFILE_ASSET)
        self.assertEqual(by_service["telegram"][1], TELEGRAM_ASSET)

    def test_both_mirrors_blocked_is_an_honest_failure_and_banks_nothing(self):
        self._fake_unwrap({"ok": True, "r2_url": R2_ASSET,
                           "google_url": GOOGLE_ASSET,
                           "mirrors": dict(_ALL_MIRRORS)})
        self._fake_probe(r2_ok=False, google_ok=False)
        res = fast_fetch.resolve_live_source(self.row_id)
        self.assertFalse(res["success"], res)
        self.assertEqual(res["code"], "no_playable_source", res)
        self.assertEqual(len(self._rows()), 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
