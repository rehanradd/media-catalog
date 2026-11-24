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

    python3 -S Fast_Fetch/test_hubcdn_unwrap.py
"""
from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.resolve()))

import fast_fetch  # noqa: E402
import host_classifier as hc  # noqa: E402

R2_ASSET = "https://pub-abc123.r2.dev/movie.mkv"
GOOGLE_ASSET = "https://video-downloads.googleusercontent.com/dl/movie"
PROVEN_R2 = "https://pub-5703108c07374cf4bf82a6a6e2041364.r2.dev/movie.mkv"
FRESH_R2 = "https://pub-423049f6aaaa1111.r2.dev/movie.mkv"
GOFILE_PAGE = "https://gofile.io/d/SAYYBt81"
GOFILE_DIRECT = "https://store-eu-par-1.gofile.io/download/web/abc123/movie.mkv"
TELEGRAM_PAGE = "https://t.me/filesdll_bot"


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
        # Pacing is proved in test_retry_caps.py with a fake clock; these
        # tests prove the unwrap itself, so the pacer is a no-op sleep
        # and the suite stays instant (the process-wide pacer would
        # otherwise sleep ~1 s between hops of every test).
        res = fast_fetch.unwrap_hubcdn(
            url or "https://hubcdn.io/cloud/abc123",
            _fetch=lambda u: (page, cookies), _hop=recorder,
            _pacer=fast_fetch.HubcdnPacer(sleep=lambda s: None))
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
            _fetch=lambda u: (page, ""), _hop=HopRecorder({}))
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
                                       _fetch=boom, _hop=HopRecorder({}))
        self.assertFalse(res["ok"])
        self.assertTrue(res["error"].startswith("cloud_fetch_fail:"), res)

    def test_empty_page_is_reported(self):
        res = fast_fetch.unwrap_hubcdn("https://hubcdn.io/cloud/abc",
                                       _fetch=lambda u: ("", ""),
                                       _hop=HopRecorder({}))
        self.assertEqual(res["error"], "empty_cloud_page")


class BucketTests(unittest.TestCase):
    """The bucket label is parsed per URL; permanence is per-bucket."""

    def test_bucket_parsed_from_r2_url(self):
        self.assertEqual(hc.hubcdn_r2_bucket(R2_ASSET), "abc123")
        self.assertEqual(
            hc.hubcdn_r2_bucket(PROVEN_R2),
            "5703108c07374cf4bf82a6a6e2041364")

    def test_bucket_is_none_for_non_r2_urls(self):
        for url in (GOOGLE_ASSET, GOFILE_PAGE, TELEGRAM_PAGE,
                    "https://abc.r2.dev/a.mkv",
                    "https://pub-abc.r2.dev.evil.cn/y.mkv"):
            with self.subTest(url=url):
                self.assertIsNone(hc.hubcdn_r2_bucket(url))

    def test_only_the_measured_bucket_is_proven(self):
        self.assertTrue(hc.hubcdn_r2_bucket_is_proven(PROVEN_R2))
        self.assertFalse(hc.hubcdn_r2_bucket_is_proven(FRESH_R2))
        self.assertFalse(hc.hubcdn_r2_bucket_is_proven(R2_ASSET))
        self.assertFalse(hc.hubcdn_r2_bucket_is_proven(GOOGLE_ASSET))

    def test_permanence_verdict_follows_the_bucket(self):
        self.assertTrue(fast_fetch.hubcdn_r2_is_permanent(PROVEN_R2))
        self.assertFalse(fast_fetch.hubcdn_r2_is_permanent(FRESH_R2))
        self.assertFalse(fast_fetch.hubcdn_r2_is_permanent(GOOGLE_ASSET))


class MirrorRoleTests(unittest.TestCase):
    """The fan-out's other two mirrors get roles of their own."""

    def test_role_accepts_gofile_page(self):
        self.assertEqual(hc.hubcdn_asset_role(GOFILE_PAGE), "gofile")
        self.assertEqual(hc.hubcdn_asset_service(GOFILE_PAGE), "gofile")

    def test_role_accepts_telegram_bot_as_record_only(self):
        self.assertEqual(hc.hubcdn_asset_role(TELEGRAM_PAGE), "telegram")
        self.assertIsNone(hc.hubcdn_asset_service(TELEGRAM_PAGE))

    def test_role_refuses_gofile_lookalikes(self):
        for hostile in ("https://www.gofile.io/d/SAYYBt81",
                        "https://gofile.io.evil.cn/d/SAYYBt81",
                        "https://gofile.io/d/",
                        "https://gofile.io/download/SAYYBt81"):
            with self.subTest(hostile=hostile):
                self.assertIsNone(hc.hubcdn_asset_role(hostile))

    def test_role_refuses_telegram_lookalikes(self):
        for hostile in ("https://telegram.me/filesdll_bot",
                        "https://t.me.evil.cn/filesdll_bot",
                        "https://t.me/",
                        "https://t.me/filesdll_bot/extra/path"):
            with self.subTest(hostile=hostile):
                self.assertIsNone(hc.hubcdn_asset_role(hostile))


def four_mirror_buttons():
    return [
        trigger("/l.php?id=9&amp;t=a", "480p [500MB]"),
        trigger("https://hubcdn.io/l.php?id=10", "1080p [4.2GB]"),
        trigger("https://hubcdn.io/l.php?id=11", "Download - GoFile.io"),
        trigger("https://hubcdn.io/l.php?id=12", "Telegram File"),
    ]


def four_mirror_hops():
    return {
        "https://hubcdn.io/l.php?id=9&t=a": (302, R2_ASSET),
        "https://hubcdn.io/l.php?id=10": (302, GOOGLE_ASSET),
        "https://hubcdn.io/l.php?id=11": (302, GOFILE_PAGE),
        "https://hubcdn.io/l.php?id=12": (302, TELEGRAM_PAGE),
    }


class FanoutTests(unittest.TestCase):
    """The whole fan-out is collected in one unwrap (API-11)."""

    def unwrap(self, hops, buttons):
        page = gateway_page(buttons)
        recorder = HopRecorder(hops)
        res = fast_fetch.unwrap_hubcdn(
            "https://hubcdn.io/cloud/abc123",
            _fetch=lambda u: (page, "session=xyz"), _hop=recorder)
        return res, recorder

    def test_all_four_mirrors_are_collected(self):
        res, _rec = self.unwrap(four_mirror_hops(), four_mirror_buttons())
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["r2_url"], R2_ASSET)
        self.assertEqual(res["google_url"], GOOGLE_ASSET)
        self.assertEqual(res["gofile_url"], GOFILE_PAGE)
        self.assertEqual(res["telegram_url"], TELEGRAM_PAGE)
        self.assertEqual(res["r2_bucket"], "abc123")

    def test_gofile_only_page_is_ok(self):
        res, _rec = self.unwrap(
            {"https://hubcdn.io/l.php?id=11": (302, GOFILE_PAGE)},
            [trigger("https://hubcdn.io/l.php?id=11", "GoFile")])
        self.assertTrue(res["ok"], res)
        self.assertEqual(res["gofile_url"], GOFILE_PAGE)
        self.assertIsNone(res["r2_url"])
        self.assertIsNone(res["google_url"])

    def test_telegram_only_page_is_not_ok(self):
        res, _rec = self.unwrap(
            {"https://hubcdn.io/l.php?id=12": (302, TELEGRAM_PAGE)},
            [trigger("https://hubcdn.io/l.php?id=12", "Telegram")])
        self.assertFalse(res["ok"], res)
        self.assertEqual(res["error"], "no_trusted_asset_in_any_redirect")
        self.assertEqual(res["telegram_url"], TELEGRAM_PAGE)

    def test_duplicate_href_is_hopped_once(self):
        buttons = [
            trigger("/l.php?id=9&amp;t=a", "Cloud Direct"),
            trigger("/l.php?id=9&amp;t=a", "Cloud Direct copy"),
            trigger("https://hubcdn.io/l.php?id=10", "Direct Download"),
        ]
        res, rec = self.unwrap(happy_hops(), buttons)
        self.assertTrue(res["ok"], res)
        self.assertEqual(len(rec.urls), 2)

    def test_hopping_stops_once_all_slots_are_filled(self):
        hops = four_mirror_hops()
        hops["https://hubcdn.io/l.php?id=13"] = (302, PROVEN_R2)
        buttons = four_mirror_buttons() + [
            trigger("https://hubcdn.io/l.php?id=13", "Extra R2")]
        res, rec = self.unwrap(hops, buttons)
        self.assertTrue(res["ok"], res)
        self.assertEqual(len(rec.urls), 4)

    def test_rate_limit_hop_stops_the_loop(self):
        hops = {"https://hubcdn.io/l.php?id=9&t=a": (429, None)}
        buttons = [
            trigger("/l.php?id=9&amp;t=a", "480p [500MB]"),
            trigger("https://hubcdn.io/l.php?id=10", "1080p [4.2GB]"),
        ]
        res, rec = self.unwrap(hops, buttons)
        self.assertFalse(res["ok"], res)
        self.assertEqual(res["error"], "hubcdn_rate_limited")
        self.assertEqual(len(rec.urls), 1)

    def test_hop_delay_math(self):
        self.assertEqual(fast_fetch._hubcdn_hop_delay(None, 100.0), 0.0)
        self.assertEqual(
            fast_fetch._hubcdn_hop_delay(100.0, 100.5), 0.5)
        self.assertEqual(
            fast_fetch._hubcdn_hop_delay(100.0, 101.5), 0.0)
        self.assertEqual(
            fast_fetch._hubcdn_hop_delay(100.0, 100.0, interval=2.0), 2.0)


def stage2_file(url):
    return {"ok": True, "kind": "file", "mime": "video/x-matroska",
            "resumable": True, "renditions": [], "failure": None}


def stage2_playlist(url):
    return {"ok": True, "kind": "playlist",
            "mime": "application/x-mpegurl",
            "resumable": False, "renditions": [], "failure": None}


def stage2_403(url):
    return {"ok": False, "kind": None, "mime": None, "size": None,
            "resumable": False, "renditions": [], "failure": "http_403"}


class ProbeRecorder:
    """The Stage-2 gate, answering per URL and recording the order."""

    def __init__(self, verdicts):
        self.verdicts = verdicts
        self.urls = []

    def __call__(self, url):
        self.urls.append(url)
        return self.verdicts.get(url, stage2_403(url))


def gofile_active(url):
    return {"status": "active", "final_url": GOFILE_DIRECT,
            "content_type": "video/x-matroska", "size_bytes": 123456,
            "http_status": 206}


def gofile_dead(url):
    return {"status": "unverified", "final_url": None}


class MirrorPickTests(unittest.TestCase):
    """The mirror fall-through: a 403 never fails a press with a live mirror."""

    def test_r2_403_falls_through_to_google(self):
        probe = ProbeRecorder({FRESH_R2: stage2_403(FRESH_R2),
                               GOOGLE_ASSET: stage2_file(GOOGLE_ASSET)})
        res = {"r2_url": FRESH_R2, "google_url": GOOGLE_ASSET}
        url, verdict, embed, temp, bucket = fast_fetch._hubcdn_pick_mirror(
            res, want="watch", _probe=probe)
        self.assertEqual(url, GOOGLE_ASSET)
        self.assertEqual(temp, 1)
        self.assertIsNone(bucket)
        self.assertIsNone(embed)
        self.assertEqual(probe.urls, [FRESH_R2, GOOGLE_ASSET])

    def test_proven_r2_wins_permanent(self):
        probe = ProbeRecorder({PROVEN_R2: stage2_file(PROVEN_R2),
                               GOOGLE_ASSET: stage2_file(GOOGLE_ASSET)})
        res = {"r2_url": PROVEN_R2, "google_url": GOOGLE_ASSET}
        url, _verdict, _embed, temp, bucket = fast_fetch._hubcdn_pick_mirror(
            res, want="watch", _probe=probe)
        self.assertEqual(url, PROVEN_R2)
        self.assertEqual(temp, 0)
        self.assertEqual(bucket, "5703108c07374cf4bf82a6a6e2041364")
        self.assertEqual(probe.urls, [PROVEN_R2])

    def test_fresh_r2_wins_temporary(self):
        probe = ProbeRecorder({FRESH_R2: stage2_file(FRESH_R2),
                               GOOGLE_ASSET: stage2_file(GOOGLE_ASSET)})
        res = {"r2_url": FRESH_R2, "google_url": GOOGLE_ASSET}
        url, _verdict, _embed, temp, bucket = fast_fetch._hubcdn_pick_mirror(
            res, want="watch", _probe=probe)
        self.assertEqual(url, FRESH_R2)
        self.assertEqual(temp, 1)
        self.assertEqual(bucket, "423049f6aaaa1111")
        self.assertEqual(probe.urls, [FRESH_R2])

    def test_r2_and_google_403_fall_through_to_gofile(self):
        probe = ProbeRecorder({})
        res = {"r2_url": FRESH_R2, "google_url": GOOGLE_ASSET,
               "gofile_url": GOFILE_PAGE}
        url, verdict, embed, temp, bucket = fast_fetch._hubcdn_pick_mirror(
            res, want="watch", _probe=probe, _gofile_unwrap=gofile_active)
        self.assertEqual(url, GOFILE_DIRECT)
        self.assertIsNone(verdict)
        self.assertEqual(embed["status"], "active")
        self.assertEqual(temp, 1)
        self.assertIsNone(bucket)
        self.assertEqual(probe.urls, [FRESH_R2, GOOGLE_ASSET])

    def test_dead_gofile_leaves_nothing(self):
        probe = ProbeRecorder({})
        res = {"r2_url": FRESH_R2, "google_url": GOOGLE_ASSET,
               "gofile_url": GOFILE_PAGE}
        got = fast_fetch._hubcdn_pick_mirror(
            res, want="watch", _probe=probe, _gofile_unwrap=gofile_dead)
        self.assertEqual(got, (None, None, None, 1, None))

    def test_gofile_skipped_without_unwrapper(self):
        probe = ProbeRecorder({})
        res = {"r2_url": FRESH_R2, "google_url": GOOGLE_ASSET,
               "gofile_url": GOFILE_PAGE}
        got = fast_fetch._hubcdn_pick_mirror(res, want="watch", _probe=probe)
        self.assertEqual(got, (None, None, None, 1, None))

    def test_download_want_skips_a_playlist_r2(self):
        probe = ProbeRecorder({FRESH_R2: stage2_playlist(FRESH_R2),
                               GOOGLE_ASSET: stage2_file(GOOGLE_ASSET)})
        res = {"r2_url": FRESH_R2, "google_url": GOOGLE_ASSET}
        url, _verdict, _embed, temp, _bucket = fast_fetch._hubcdn_pick_mirror(
            res, want="download", _probe=probe)
        self.assertEqual(url, GOOGLE_ASSET)
        self.assertEqual(temp, 1)

    def test_no_mirrors_returns_nothing(self):
        got = fast_fetch._hubcdn_pick_mirror(
            {"r2_url": None, "google_url": None}, want="watch",
            _probe=ProbeRecorder({}))
        self.assertEqual(got, (None, None, None, 1, None))


if __name__ == "__main__":
    unittest.main(verbosity=2)
