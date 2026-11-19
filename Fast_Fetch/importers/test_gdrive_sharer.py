#!/usr/bin/env python3
"""Offline tests for the gdrive-sharer family (Lane C, Phase C, 2026-11-20).

appdrive / gdflix / gdtot share one documented bypass (bipinkrish
Link-Bypasser-Bot ddl.py sharer_scraper + gdtot; sanjit-sinha/PyBypass
gdrivesharer notes): GET the /file/<id> page, POST action=direct with the
page's "key", read the drive link out of the JSON answer, hand it to
unwrap_gdrive. gdtot's own chain goes through /ddl/<id> (myDl('...')) or
the gdbot.xyz token page first.

No live socket, no database, no pip. Every request goes through an
injected `fetch`, so the network code is covered by calling the real
functions with a fake transport. `socket.socket` is replaced with a
raiser so an accidental live request fails the run instead of passing.

The fixtures are shaped after the documented pages, not invented: the
"key", "..." token plus <button id="drc"> shape is ddl.py's
sharer_scraper; the myDl('...') shape is its gdtot fallback; the
gdbot token link class is its gdtot primary. Live proof is still owed:
the VM egress is Cloudflare-challenged on gdflix, TLS-reset on gdtot
and TCP-dead on appdrive.in (audit 2026-11-20), so these tests pin the
chain, not a live mint.
"""

from __future__ import annotations

import json
import socket
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import host_resolvers as hr  # noqa: E402


class _NoLiveSocket:
    def __init__(self, *a, **k):
        raise AssertionError("test attempted a live socket")


socket.socket = _NoLiveSocket  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SHARER_PAGE = """<html><head><title>AppDrive</title></head><body>
<script>var cfg = {"key", "k3y-4bc-def"};</script>
<h1>Movie (2024) 720p</h1>
<button id="drc" class="btn btn-primary">Direct Download</button>
</body></html>"""

SHARER_PAGE_NO_KEY = """<html><body>
<button id="drc" class="btn btn-primary">Direct Download</button>
</body></html>"""

SHARER_PAGE_NO_DRC = """<html><body>
<script>var cfg = {"key", "k3y-4bc-def"};</script>
</body></html>"""

CF_PAGE = """<html><head><title>Just a moment...</title></head><body>
<div>challenges.cloudflare.com/turnstile</div>
<p>Please verify you are a human</p>
</body></html>"""

DRIVE_ID = "1AbC2dEfGhIjKlMnOpQrStUvWx"
DRIVE_VIEW = f"https://drive.google.com/file/d/{DRIVE_ID}/view?usp=sharing"
DRIVE_UC = f"https://drive.google.com/uc?export=download&id={DRIVE_ID}"
DRIVE_BYTES = "https://drive.usercontent.google.com/download?id=" + DRIVE_ID

DIRECT_JSON = json.dumps({"url": DRIVE_VIEW})

INTERMEDIATE_URL = "https://gdflix.top/go/abc123"
INTERMEDIATE_PAGE = """<html><body>
<a class="btn btn-success" href="%s">Download Now</a>
</body></html>""" % DRIVE_VIEW

INTERMEDIATE_PAGE_NO_BTN = "<html><body>No buttons here</body></html>"

GDTOT_DDL_PAGE = """<html><body>
<button onclick="myDl('%s')">Download</button>
</body></html>""" % DRIVE_VIEW

GDBOT_PAGE = """<html><body>
<a class="inline-flex items-center justify-center px-4 py-2"
   href="https://gdbot.xyz/token/xyz789">Get Link</a>
</body></html>"""

GDBOT_TOKEN_PAGE = """<html><body>
<script>window.location.replace("/file/abc123def456");</script>
</body></html>"""

VIDEO_206 = (206, b"\x00\x00\x00\x18ftypisom",
             {"content-type": "application/octet-stream",
              "content-range": "bytes 0-511/1234567"})


class ScriptedFetch:
    """A fake fetch: substring -> (status, body, headers, final_url).

    A POST never matches the page script: when `post_answer` is set, any
    POST returns it, so the direct-download POST is scripted separately
    from the GETs.
    """

    def __init__(self, script, post_answer=None):
        self.script = script
        self.post_answer = post_answer
        self.calls = []

    def __call__(self, url, headers=None, timeout=None, method="GET",
                 follow=True, data=None):
        self.calls.append({"url": url, "headers": dict(headers or {}),
                           "method": method, "data": data})
        if method == "POST" and self.post_answer is not None:
            status, body, hdrs = self.post_answer
            return status, body, hdrs, url
        # Longest needle first: a specific path must beat the host key
        # (gdbot.xyz/file/<id> the token path lands on vs gdbot.xyz/file/
        # the token page itself).
        for needle, answer in sorted(self.script.items(),
                                     key=lambda kv: -len(kv[0])):
            if needle in url:
                status, body, hdrs = answer[0], answer[1], answer[2]
                final = answer[3] if len(answer) > 3 else url
                return status, body, hdrs, final
        return 404, b"", {"content-type": "text/html"}, url


def drive_script(**over):
    """Script for the Drive leg of any chain: uc -> bytes, probe -> 206."""
    script = {
        "drive.google.com/uc": (200, b"", {"content-type": "application/octet-stream"},
                                DRIVE_BYTES),
        "drive.usercontent.google.com": VIDEO_206,
    }
    script.update(over)
    return script


def sharer_script(page=SHARER_PAGE, post_answer=(200, DIRECT_JSON, {}),
                  **over):
    """Script for the full sharer chain on one /file/ page."""
    script = {
        "appdrive.in/file/": (200, page, {"content-type": "text/html"}),
        "gdflix": (200, page, {"content-type": "text/html"}),
        "gd.kmhd.me/file/": (200, page, {"content-type": "text/html"}),
    }
    script.update(drive_script())
    script.update(over)
    return ScriptedFetch(script, post_answer=post_answer)


# ---------------------------------------------------------------------------
# Recognition + pure parsers
# ---------------------------------------------------------------------------

class TestSharerRecognition(unittest.TestCase):
    def test_sharer_hosts(self):
        for url in (
            "https://appdrive.in/file/4aa30d5aac8dccae23e6",
            "https://appdrive.info/file/4aa30d5aac8dccae23e6",
            "https://gd.kmhd.me/file/QmUwCuFJa3HxGte",
            "https://new10.gdflix.io/file/QmUwCuFJa3HxGte",
            "https://gdflix.pro/file/abc123",
            "https://gdflix.top/file/abc123",
            "https://driveapp.in/file/abc123",
            "https://drivehub.in/file/abc123",
        ):
            self.assertTrue(hr.is_gdrive_sharer_url(url), url)
        for url in (
            "https://example.com/file/abc123",
            "https://drive.google.com/file/d/abc/view",
            "https://kmhd.eu/archives/28166",
            "https://new.gdtot.eu/file/1021705764",
            "https://gdfiles.org/file/neu0kk0ajibL41zHrvgU",
            "https://appdrive.in/",
            "https://appdrive.in/file/",
        ):
            self.assertFalse(hr.is_gdrive_sharer_url(url), url)

    def test_gdtot_hosts(self):
        for url in (
            "https://new.gdtot.eu/file/1021705764",
            "https://new2.gdtot.sbs/file/105111102182",
            "https://new5.gdtot.dad/file/7340685321",
            "https://gdtot.re/file/123456",
        ):
            self.assertTrue(hr.is_gdtot_url(url), url)
        for url in (
            "https://new.gdtot.eu/ddl/1021705764",
            "https://new.gdtot.eu/file/",
            "https://appdrive.in/file/abc123",
            "https://notgdtot.eu/file/123",
        ):
            self.assertFalse(hr.is_gdtot_url(url), url)

    def test_file_ids(self):
        self.assertEqual(
            hr.sharer_file_id("https://appdrive.in/file/4aa30d5aac8dccae23e6"),
            "4aa30d5aac8dccae23e6")
        self.assertEqual(
            hr.sharer_file_id("https://gd.kmhd.me/file/QmUwCuFJa3HxGte"),
            "QmUwCuFJa3HxGte")
        self.assertEqual(hr.sharer_file_id("https://appdrive.in/"), "")
        self.assertEqual(
            hr.gdtot_file_id("https://new.gdtot.eu/file/1021705764"),
            "1021705764")
        self.assertEqual(hr.gdtot_file_id("https://new.gdtot.eu/ddl/1021705764"), "")

    def test_key_extraction(self):
        self.assertEqual(hr.sharer_key_from_page(SHARER_PAGE), "k3y-4bc-def")
        self.assertEqual(
            hr.sharer_key_from_page("<script>var k = {'key', 'sq-key'};</script>"),
            "sq-key")
        self.assertEqual(hr.sharer_key_from_page("<html></html>"), "")
        self.assertEqual(hr.sharer_key_from_page(""), "")

    def test_direct_button(self):
        self.assertTrue(hr.sharer_has_direct_button(SHARER_PAGE))
        self.assertFalse(hr.sharer_has_direct_button(SHARER_PAGE_NO_DRC))
        self.assertFalse(hr.sharer_has_direct_button(""))

    def test_btn_link_both_attribute_orders(self):
        self.assertEqual(hr.sharer_btn_link(INTERMEDIATE_PAGE), DRIVE_VIEW)
        rev = '<a href="%s" class="btn">x</a>' % DRIVE_VIEW
        self.assertEqual(hr.sharer_btn_link(rev), DRIVE_VIEW)
        self.assertEqual(hr.sharer_btn_link(INTERMEDIATE_PAGE_NO_BTN), "")

    def test_gdtot_parsers(self):
        self.assertEqual(hr.gdtot_mydl_link(GDTOT_DDL_PAGE), DRIVE_VIEW)
        self.assertEqual(hr.gdtot_mydl_link("<html></html>"), "")
        self.assertEqual(hr.gdbot_token_url(GDBOT_PAGE),
                         "https://gdbot.xyz/token/xyz789")
        self.assertEqual(hr.gdbot_token_url("<html></html>"), "")
        self.assertEqual(hr.gdbot_token_path(GDBOT_TOKEN_PAGE), "/file/abc123def456")
        self.assertEqual(hr.gdbot_token_path("<html></html>"), "")

    def test_multipart_body_shape(self):
        body, content_type = hr._sharer_multipart("k3y-4bc-def")
        self.assertTrue(content_type.startswith(
            "multipart/form-data; boundary=----WebKitFormBoundary"))
        boundary = content_type.split("boundary=", 1)[1]
        text = body.decode("utf-8")
        self.assertIn("--" + boundary + "\r\n", text)
        self.assertIn('name="action"\r\n\r\ndirect\r\n', text)
        self.assertIn('name="key"\r\n\r\nk3y-4bc-def\r\n', text)
        self.assertIn('name="action_token"\r\n\r\n\r\n', text)
        self.assertTrue(text.endswith("--" + boundary + "--\r\n"))
        # A second mint uses a different boundary: no replayed body.
        _, content_type2 = hr._sharer_multipart("k3y-4bc-def")
        self.assertNotEqual(content_type, content_type2)


# ---------------------------------------------------------------------------
# The sharer chain
# ---------------------------------------------------------------------------

class TestUnwrapGdriveSharer(unittest.TestCase):
    def test_full_chain_active_from_the_file_page(self):
        fetch = sharer_script()
        verdict = hr.unwrap_gdrive_sharer(
            "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict["notes"])
        self.assertEqual(verdict["service"], "appdrive")
        self.assertEqual(verdict["final_url"], DRIVE_BYTES)
        self.assertFalse(verdict["is_temporary"])
        self.assertEqual(verdict["size_bytes"], 1234567)
        # The durable identifier is the sharer page, never the minted link.
        self.assertEqual(
            verdict["embed_url"],
            "https://appdrive.in/file/4aa30d5aac8dccae23e6")

    def test_service_name_follows_the_host(self):
        fetch = sharer_script()
        verdict = hr.unwrap_gdrive_sharer(
            "https://gd.kmhd.me/file/QmUwCuFJa3HxGte", fetch=fetch)
        self.assertEqual(verdict["service"], "gdflix")
        fetch = sharer_script()
        verdict = hr.unwrap_gdrive_sharer(
            "https://driveapp.in/file/abc123", fetch=fetch)
        self.assertEqual(verdict["service"], "driveapp")

    def test_post_shape(self):
        fetch = sharer_script()
        hr.unwrap_gdrive_sharer(
            "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch)
        posts = [c for c in fetch.calls if c["method"] == "POST"]
        self.assertEqual(len(posts), 1)
        post = posts[0]
        self.assertEqual(post["headers"].get("x-token"), "appdrive.in")
        self.assertTrue(post["headers"]["Content-Type"].startswith(
            "multipart/form-data; boundary=----WebKitFormBoundary"))
        body = post["data"].decode("utf-8")
        self.assertIn("k3y-4bc-def", body)
        self.assertIn('name="action"\r\n\r\ndirect\r\n', body)
        # The POST goes to the page URL the GET landed on.
        self.assertEqual(post["url"],
                         "https://appdrive.in/file/4aa30d5aac8dccae23e6")

    def test_cloudflare_page_is_blocked_and_named(self):
        # The challenge page is served with the 403, not a 200.
        fetch = sharer_script(page=CF_PAGE)
        fetch.script["gd.kmhd.me/file/"] = (403, CF_PAGE, {})
        verdict = hr.unwrap_gdrive_sharer(
            "https://gd.kmhd.me/file/QmUwCuFJa3HxGte", fetch=fetch)
        self.assertEqual(verdict["status"], "blocked")
        self.assertIn("challenge", json.dumps(verdict["notes"]).lower())
        self.assertEqual(len([c for c in fetch.calls if c["method"] == "POST"]), 0)

    def test_page_failure_shapes(self):
        for status, expected in ((404, "dead"), (410, "dead"), (403, "blocked"),
                                 (429, "blocked"), (500, "unknown")):
            fetch = sharer_script(page="")
            # Override the page answer with the bare status.
            fetch.script["gd.kmhd.me/file/"] = (status, b"", {})
            verdict = hr.unwrap_gdrive_sharer(
                "https://gd.kmhd.me/file/QmUwCuFJa3HxGte", fetch=fetch)
            self.assertEqual(verdict["status"], expected, status)
            self.assertIn(f"sharer page HTTP {status}",
                          json.dumps(verdict["notes"]))

    def test_transport_failure_is_unknown_never_dead(self):
        fetch = sharer_script()
        fetch.script["gd.kmhd.me/file/"] = (
            0, "Connection reset by peer", {})
        verdict = hr.unwrap_gdrive_sharer(
            "https://gd.kmhd.me/file/QmUwCuFJa3HxGte", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("transport", json.dumps(verdict["notes"]).lower())

    def test_page_without_key_stops_before_the_post(self):
        fetch = sharer_script(page=SHARER_PAGE_NO_KEY)
        verdict = hr.unwrap_gdrive_sharer(
            "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("no direct-download key", json.dumps(verdict["notes"]))
        self.assertEqual([c for c in fetch.calls if c["method"] == "POST"], [])

    def test_page_without_direct_button_stops_before_the_post(self):
        fetch = sharer_script(page=SHARER_PAGE_NO_DRC)
        verdict = hr.unwrap_gdrive_sharer(
            "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("no direct download button", json.dumps(verdict["notes"]))
        self.assertEqual([c for c in fetch.calls if c["method"] == "POST"], [])

    def test_post_failures(self):
        for answer, expected, needle in (
            ((403, b"", {}), "blocked", "blocked:403"),
            ((404, b"", {}), "dead", "dead:404"),
            ((200, b"not json", {}), "unknown", "bad_json"),
            ((200, b'{"nope": 1}', {}), "unknown", "no_url_in_json"),
            ((500, b"", {}), "unknown", "http:500"),
        ):
            fetch = sharer_script(post_answer=answer)
            verdict = hr.unwrap_gdrive_sharer(
                "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch)
            self.assertEqual(verdict["status"], expected, answer)
            self.assertIn(needle, json.dumps(verdict["notes"]))

    def test_intermediate_page_route(self):
        fetch = sharer_script(
            post_answer=(200, json.dumps({"url": INTERMEDIATE_URL}), {}),
            **{"gdflix.top/go/": (200, INTERMEDIATE_PAGE, {})})
        verdict = hr.unwrap_gdrive_sharer(
            "https://gdflix.top/file/abc123", fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict["notes"])
        self.assertEqual(verdict["final_url"], DRIVE_BYTES)

    def test_intermediate_page_without_btn_is_unknown(self):
        fetch = sharer_script(
            post_answer=(200, json.dumps({"url": INTERMEDIATE_URL}), {}),
            **{"gdflix.top/go/": (200, INTERMEDIATE_PAGE_NO_BTN, {})})
        verdict = hr.unwrap_gdrive_sharer(
            "https://gdflix.top/file/abc123", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("no drive link", json.dumps(verdict["notes"]))

    def test_verify_off_mints_but_never_probes(self):
        fetch = sharer_script()
        verdict = hr.unwrap_gdrive_sharer(
            "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch,
            verify=False)
        self.assertEqual(verdict["status"], "unverified")
        self.assertEqual(verdict["final_url"], DRIVE_UC)
        self.assertEqual(len(fetch.calls), 2)
        self.assertFalse(any("usercontent" in c["url"] for c in fetch.calls))

    def test_drive_quota_is_unverified_never_dead(self):
        fetch = sharer_script(**{
            "drive.google.com/uc": (403, b"", {}),
        })
        verdict = hr.unwrap_gdrive_sharer(
            "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch)
        self.assertEqual(verdict["status"], "unverified")

    def test_a_non_sharer_url_is_refused_before_any_request(self):
        fetch = sharer_script()
        verdict = hr.unwrap_gdrive_sharer(
            "https://example.com/file/abc123", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("not a gdrive sharer file URL", verdict["notes"])
        self.assertEqual(fetch.calls, [])

    def test_no_stored_presigned_url_reuse(self):
        # Two mints for two different files must return two different fresh
        # links: the resolver never replays a stored signed URL.
        fetch1 = sharer_script()
        v1 = hr.unwrap_gdrive_sharer(
            "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch1)
        other_id = "9ZyX8wVuTsRqPoNmLkJiHgFeDcBa"
        other_view = f"https://drive.google.com/file/d/{other_id}/view"
        other_bytes = ("https://drive.usercontent.google.com/download?id="
                       + other_id)
        fetch2 = sharer_script(
            post_answer=(200, json.dumps({"url": other_view}), {}),
            **{"drive.usercontent.google.com": VIDEO_206})
        # Point the uc leg at the second file's bytes.
        fetch2.script["drive.google.com/uc"] = (
            200, b"", {"content-type": "application/octet-stream"}, other_bytes)
        v2 = hr.unwrap_gdrive_sharer(
            "https://appdrive.in/file/4aa30d5aac8dccae23e6", fetch=fetch2)
        self.assertEqual(v1["final_url"], DRIVE_BYTES)
        self.assertEqual(v2["final_url"], other_bytes)
        self.assertNotEqual(v1["final_url"], v2["final_url"])


# ---------------------------------------------------------------------------
# The gdtot chain
# ---------------------------------------------------------------------------

class TestUnwrapGdtot(unittest.TestCase):
    def ddl_script(self, **over):
        script = {
            "new.gdtot.eu/ddl/": (200, GDTOT_DDL_PAGE, {}),
        }
        script.update(drive_script())
        script.update(over)
        return ScriptedFetch(script)

    def test_ddl_route_active(self):
        fetch = self.ddl_script()
        verdict = hr.unwrap_gdtot(
            "https://new.gdtot.eu/file/1021705764", fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict["notes"])
        self.assertEqual(verdict["service"], "gdtot")
        self.assertEqual(verdict["final_url"], DRIVE_BYTES)
        self.assertEqual(fetch.calls[0]["url"],
                         "https://new.gdtot.eu/ddl/1021705764")

    def test_ddl_without_mydl_falls_through_to_gdbot(self):
        fetch = self.ddl_script(**{
            "new.gdtot.eu/ddl/": (200, "<html></html>", {}),
            "gdbot.xyz/file/": (200, GDBOT_PAGE, {}),
            "gdbot.xyz/token/": (200, GDBOT_TOKEN_PAGE, {}),
            "gdbot.xyz/file/abc123def456": (200, SHARER_PAGE, {}),
        })
        # The gdbot token path lands on a sharer page; the POST mints drive.
        fetch.post_answer = (200, DIRECT_JSON, {})
        verdict = hr.unwrap_gdtot(
            "https://new.gdtot.eu/file/1021705764", fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict["notes"])
        self.assertEqual(verdict["final_url"], DRIVE_BYTES)
        posts = [c for c in fetch.calls if c["method"] == "POST"]
        self.assertEqual([p["url"] for p in posts],
                         ["https://gdbot.xyz/file/abc123def456"])

    def test_ddl_transport_failure_falls_through_to_gdbot(self):
        fetch = self.ddl_script(**{
            "new.gdtot.eu/ddl/": (0, "Connection reset by peer", {}),
            "gdbot.xyz/file/": (200, GDBOT_PAGE, {}),
            "gdbot.xyz/token/": (200, GDBOT_TOKEN_PAGE, {}),
            "gdbot.xyz/file/abc123def456": (200, SHARER_PAGE, {}),
        })
        fetch.post_answer = (200, DIRECT_JSON, {})
        verdict = hr.unwrap_gdtot(
            "https://new.gdtot.eu/file/1021705764", fetch=fetch)
        self.assertEqual(verdict["status"], "active", verdict["notes"])

    def test_gdbot_without_token_link_is_unknown(self):
        fetch = self.ddl_script(**{
            "new.gdtot.eu/ddl/": (404, b"", {}),
            "gdbot.xyz/file/": (200, "<html></html>", {}),
        })
        verdict = hr.unwrap_gdtot(
            "https://new.gdtot.eu/file/1021705764", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("no token link", json.dumps(verdict["notes"]))

    def test_token_page_without_path_is_unknown(self):
        fetch = self.ddl_script(**{
            "new.gdtot.eu/ddl/": (404, b"", {}),
            "gdbot.xyz/file/": (200, GDBOT_PAGE, {}),
            "gdbot.xyz/token/": (200, "<html></html>", {}),
        })
        verdict = hr.unwrap_gdtot(
            "https://new.gdtot.eu/file/1021705764", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("no path", json.dumps(verdict["notes"]))

    def test_both_routes_down_is_unknown_never_dead(self):
        fetch = self.ddl_script(**{
            "new.gdtot.eu/ddl/": (0, "Connection reset by peer", {}),
            "gdbot.xyz/file/": (0, "Connection reset by peer", {}),
        })
        verdict = hr.unwrap_gdtot(
            "https://new.gdtot.eu/file/1021705764", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("routes exhausted", json.dumps(verdict["notes"]))

    def test_a_non_gdtot_url_is_refused_before_any_request(self):
        fetch = self.ddl_script()
        verdict = hr.unwrap_gdtot(
            "https://example.com/file/1021705764", fetch=fetch)
        self.assertEqual(verdict["status"], "unknown")
        self.assertIn("not a gdtot file URL", verdict["notes"])
        self.assertEqual(fetch.calls, [])


# ---------------------------------------------------------------------------
# Wiring: service names, URL shapes, gd-mirror, catalog projection
# ---------------------------------------------------------------------------

class TestGdFamilyWiring(unittest.TestCase):
    def test_resolve_service_names(self):
        self.assertIs(hr.resolver_for_service("appdrive"), hr.unwrap_gdrive_sharer)
        self.assertIs(hr.resolver_for_service("gdflix"), hr.unwrap_gdrive_sharer)
        self.assertIs(hr.resolver_for_service("gdtot"), hr.unwrap_gdtot)
        self.assertIs(hr.resolver_for_service("gd-mirror"), hr.unwrap_iqsmart)
        self.assertIs(hr.resolver_for_service("gdmirror"), hr.unwrap_iqsmart)
        self.assertIsNone(hr.resolver_for_service("kmhd"))

    def test_resolve_url_routes_sharer_and_gdtot(self):
        # Routing is proven through the tuple table (resolve_url itself
        # would use the module default fetch, which is live).
        for service, predicate, fn in hr.SERVICE_RESOLVERS:
            if predicate("https://appdrive.in/file/4aa30d5aac8dccae23e6"):
                self.assertIs(fn, hr.unwrap_gdrive_sharer)
                break
        else:
            self.fail("no SERVICE_RESOLVERS entry matched the sharer URL")
        for service, predicate, fn in hr.SERVICE_RESOLVERS:
            if predicate("https://new.gdtot.eu/file/1021705764"):
                self.assertIs(fn, hr.unwrap_gdtot)
                break
        else:
            self.fail("no SERVICE_RESOLVERS entry matched the gdtot URL")
        # The sharer entry must not swallow drive links themselves.
        for service, predicate, fn in hr.SERVICE_RESOLVERS:
            if predicate(DRIVE_VIEW):
                self.assertIs(fn, hr.unwrap_gdrive)
                break
        else:
            self.fail("no SERVICE_RESOLVERS entry matched the drive URL")

    def test_resolve_url_routes_gd_mirror_to_iqsmart(self):
        embed = ("https://streams.iqsmartgames.com/embed/tv/133064/1/1"
                 "?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4")
        for service, predicate, fn in hr.SERVICE_RESOLVERS:
            if predicate(embed):
                self.assertIs(fn, hr.unwrap_iqsmart)
                break
        else:
            self.fail("no SERVICE_RESOLVERS entry matched the gd-mirror URL")
        # Routing proof with a stub fetch: the chain fails on a page with
        # no FinalID, but the verdict still names the iqsmart service.
        fetch = ScriptedFetch({"streams.iqsmartgames.com": (200, "<html></html>", {})})
        verdict = hr.unwrap_iqsmart(embed, fetch=fetch)
        self.assertEqual(verdict["service"], "iqsmart")

    def test_gd_mirror_is_server_resolvable(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
        import catalog_v2
        self.assertIn("gd-mirror", catalog_v2._SERVER_RESOLVABLE_SERVICES)
        self.assertIn("gdmirror", catalog_v2._SERVER_RESOLVABLE_SERVICES)
        embed = ("https://streams.iqsmartgames.com/embed/tv/133064/1/1"
                 "?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4")
        kind, play = catalog_v2.classify_link(
            "gd-mirror", embed, "", "iframe", 0)
        self.assertEqual((kind, play), ("embed", "resolve_required"))
        # An embed service with no resolver branch stays honestly webview.
        kind, play = catalog_v2.classify_link(
            "vidoza", embed, "", "iframe", 0)
        self.assertEqual((kind, play), ("embed", "webview"))
        # The sharer family stays out: its rows are url-typed cloud pages,
        # already resolve_required without the set.
        self.assertNotIn("gdflix", catalog_v2._SERVER_RESOLVABLE_SERVICES)
        self.assertNotIn("gdtot", catalog_v2._SERVER_RESOLVABLE_SERVICES)
        self.assertNotIn("appdrive", catalog_v2._SERVER_RESOLVABLE_SERVICES)
        self.assertNotIn("kmhd", catalog_v2._SERVER_RESOLVABLE_SERVICES)


if __name__ == "__main__":
    unittest.main()
