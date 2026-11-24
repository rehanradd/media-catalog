"""Tests for the nginx security-headers patch (M-headers + L-diag-feed).

The live nginx config is NOT in the repo (it lives at
/etc/nginx/sites-enabled/radd-stream on the Oracle VM), so these tests
validate the repo-tracked patch file itself plus the audit logic that
produced it:

- ``nginx-headers-diagfeed.patch`` (M-headers + L-diag-feed, 2026-11-24):
  56 insertions of each of Content-Security-Policy, X-Frame-Options and
  Permissions-Policy — server level on the port-80 default and both HTTPS
  servers, plus every location that defines its own ``add_header`` (26 per
  HTTPS server, /download/ on port 80). nginx ``add_header`` is NOT
  inherited: a location with any ``add_header`` loses every server-level
  one, so the server-level headers alone would never reach those 53
  locations' responses — the same rule SEC-05 used for HSTS. Locations
  with no ``add_header`` of their own inherit the server-level headers.
  The patch also deletes both temporary ``/diag-feed/`` locations (one
  per HTTPS server, added 2026-11-13, service dead — probed 404).
  It touches no HSTS line: HSTS is SEC-05's patch, and the two patches
  share an anchor (see the patch header for the rebase rule).

No network, no nginx binary, stdlib only. The live-file proof
(``git apply --check`` + the before/after header count 0 -> 56 per
header, diag-feed 2 -> 0) is recorded in
Fast_Fetch/checkpoints/20261124-sec-headers-diagfeed.md, not here.
"""

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PATCH = REPO_ROOT / "nginx-headers-diagfeed.patch"

CSP_LINE = (
    'add_header Content-Security-Policy "default-src \'self\'; '
    "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
    "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
    "worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'self'; "
    'form-action \'self\'" always;'
)
XFO_LINE = "add_header X-Frame-Options DENY always;"
PP_LINE = (
    'add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), '
    'payment=(), usb=(), magnetometer=(), gyroscope=(), accelerometer=()" always;'
)

# The live file this patch anchors to (md5 + line count, fetched 2026-11-24).
ANCHOR_MD5 = "73fdcff003edb79b246ac77705f27f3e"
ANCHOR_LINES = "1,465"

# 3 server levels + 53 locations with their own add_headers:
# 2 on the port-80 default, 27 per HTTPS server.
EXPECTED_INSERTIONS_PER_HEADER = 56


class TestNginxHeadersPatch(unittest.TestCase):
    def setUp(self):
        self.patch = PATCH.read_text()

    def _added(self, header):
        return [ln for ln in self.patch.splitlines()
                if ln.startswith("+") and header in ln]

    def _removed(self, text):
        return [ln for ln in self.patch.splitlines()
                if ln.startswith("-") and text in ln]

    def test_patch_anchors_to_the_current_live_file(self):
        self.assertIn(ANCHOR_MD5, self.patch)
        self.assertIn(ANCHOR_LINES, self.patch)

    def test_each_header_is_added_exactly_56_times(self):
        for header, expected in (
            ("Content-Security-Policy", EXPECTED_INSERTIONS_PER_HEADER),
            ("X-Frame-Options", EXPECTED_INSERTIONS_PER_HEADER),
            ("Permissions-Policy", EXPECTED_INSERTIONS_PER_HEADER),
        ):
            self.assertEqual(
                len(self._added(header)), expected,
                "%s must be added once per server level + once per location "
                "with its own add_header" % header,
            )

    def test_the_added_header_values_are_exact(self):
        for expected in (CSP_LINE, XFO_LINE, PP_LINE):
            added = [ln[1:].strip() for ln in self.patch.splitlines()
                     if ln.startswith("+") and "add_header" in ln
                     and expected.split()[1] in ln]
            self.assertTrue(added, "no added line carries %s" % expected)
            for line in added:
                self.assertEqual(line, expected)

    def test_the_patch_only_adds_these_three_headers(self):
        added_names = set()
        for ln in self.patch.splitlines():
            if ln.startswith("+") and "add_header" in ln:
                # "+        add_header Content-Security-Policy ..." — the
                # name is the third token, after "+" and "add_header".
                added_names.add(ln.split()[2])
        self.assertEqual(
            added_names,
            {"Content-Security-Policy", "X-Frame-Options", "Permissions-Policy"},
        )

    def test_the_patch_touches_no_hsts_line(self):
        self.assertEqual(self._added("Strict-Transport-Security"), [])
        self.assertEqual(self._removed("Strict-Transport-Security"), [])

    def test_both_diag_feed_blocks_are_removed(self):
        self.assertEqual(len(self._removed("location /diag-feed/ {")), 2)
        self.assertEqual(
            len(self._removed("proxy_pass http://127.0.0.1:18081/;")), 2)
        self.assertEqual(
            len(self._removed("TEMPORARY live diagnostics feed")), 2)
        self.assertEqual(len(self._removed("diag-feed.pid")), 2)
        added_diag = [ln for ln in self.patch.splitlines()
                      if ln.startswith("+") and "diag-feed" in ln]
        self.assertEqual(added_diag, [])

    def test_no_location_is_left_without_the_three_headers(self):
        # Every hunk that adds headers must add all three together: a
        # location that got CSP but not X-Frame-Options would be a half-fix
        # the count test above cannot see (56 could be 56 CSP-only hunks).
        hunks = re.split(r"(?m)^@@", self.patch)
        for hunk in hunks[1:]:
            added = [ln for ln in hunk.splitlines() if ln.startswith("+")]
            headers = [ln for ln in added if "add_header" in ln]
            if headers:
                self.assertEqual(len(headers), 3, hunk[:200])


if __name__ == "__main__":
    unittest.main()
