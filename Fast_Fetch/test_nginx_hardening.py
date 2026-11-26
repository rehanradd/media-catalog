"""Tests for the nginx hardening patches (SEC-05).

The live nginx config is NOT in the repo (it lives at
/etc/nginx/sites-enabled/radd-stream on the Oracle VM), so these tests
validate the repo-tracked patch files themselves plus the audit logic that
produced them:

- ``nginx-real-ip.patch`` (scale fix #1, applied live 2026-11-23):
  the 22 Cloudflare ``set_real_ip_from`` ranges must be exactly
  Cloudflare's published lists (verified against cloudflare.com/ips-v4
  and /ips-v6 on 2026-11-24) plus ``real_ip_header CF-Connecting-IP``.
- ``nginx-hardening.patch`` (SEC-05, 2026-11-24; patch regenerated
  2026-11-27 against the drifted live file — same 14 locations x 2
  servers, still 28 insertions): 28 insertions of the
  identical HSTS line, one per location that defines its own
  ``add_header`` without HSTS. nginx ``add_header`` is NOT inherited: a
  location with any ``add_header`` loses every server-level one, so the
  server-level HSTS (lines 134, 913 of the live file) never reached
  those 28 locations' responses.

No network, no nginx binary, stdlib only. The live-file proof
(``git apply --check`` + the before/after gap count 28 -> 0) is recorded
in Fast_Fetch/checkpoints/20261124-sec05-nginx-hardening.md, not here.
"""

import ipaddress
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
REAL_IP_PATCH = REPO_ROOT / "nginx-real-ip.patch"
HARDENING_PATCH = REPO_ROOT / "nginx-hardening.patch"

HSTS_LINE = (
    'add_header Strict-Transport-Security '
    '"max-age=31536000; includeSubDomains" always;'
)

# Cloudflare's published IP lists, fetched verbatim from
# https://www.cloudflare.com/ips-v4 and /ips-v6 on 2026-11-24.
# If Cloudflare ever publishes a new range, this test MUST be updated
# together with nginx-real-ip.patch and the live config.
CLOUDFLARE_IPV4 = (
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
)
CLOUDFLARE_IPV6 = (
    "2400:cb00::/32",
    "2606:4700::/32",
    "2803:f800::/32",
    "2405:b500::/32",
    "2405:8100::/32",
    "2a06:98c0::/29",
    "2c0f:f248::/32",
)

_BLOCK_OPEN = re.compile(r"(server|location)\b(.*)\{")


def audit_hsts_gaps(config_text):
    """Return [(location, line)] for locations that lose server-level HSTS.

    A location is a gap when it defines at least one ``add_header`` but no
    HSTS one, inside a server that DOES set HSTS at server level. A server
    with no server-level HSTS (plain HTTP, or a Certbot 301 redirect) is
    out of scope: HSTS is ignored over HTTP, so its absence there is
    correct, not a gap. Locations with no ``add_header`` at all inherit
    the server value and are not gaps either.
    """
    stack = []  # [kind, name, start_line, has_hsts, has_other_add_header]
    gaps = []
    for lineno, raw in enumerate(config_text.splitlines(), 1):
        stripped = raw.strip()
        match = _BLOCK_OPEN.search(stripped)
        if match:
            stack.append(
                [match.group(1), match.group(2).strip(), lineno, False, False]
            )
        if "add_header" in stripped and stack:
            if "Strict-Transport-Security" in stripped:
                stack[-1][3] = True
            else:
                stack[-1][4] = True
        for _ in range(stripped.count("}") - stripped.count("{")):
            if not stack:
                break
            block = stack.pop()
            if block[0] == "location" and block[4] and not block[3]:
                server = next(
                    (b for b in reversed(stack) if b[0] == "server"), None
                )
                if server is not None and server[3]:
                    gaps.append((block[1], block[2]))
    return gaps


def _patch_lines(path):
    """Split a patch file into (header_comments, diff_lines)."""
    lines = path.read_text(encoding="utf-8").splitlines()
    diff_start = next(
        i for i, line in enumerate(lines) if line.startswith("--- ")
    )
    return lines[:diff_start], lines[diff_start:]


def _added_lines(diff_lines):
    return [
        line[1:]
        for line in diff_lines
        if line.startswith("+") and not line.startswith("+++")
    ]


def _removed_lines(diff_lines):
    return [
        line[1:]
        for line in diff_lines
        if line.startswith("-") and not line.startswith("---")
    ]


def _hunks(diff_lines):
    """Yield the context+added lines of each @@ hunk."""
    current = None
    for line in diff_lines:
        if line.startswith("@@"):
            if current is not None:
                yield current
            current = []
        elif current is not None and not line.startswith(("---", "+++")):
            current.append(line)
    if current is not None:
        yield current


BEFORE_FIXTURE = """\
server {
    listen 443 ssl;
    server_name example.test;
    add_header X-Content-Type-Options nosniff always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    location = /api/v1/app/remote-config {
        add_header X-Cache $upstream_cache_status always;
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    }
    location = /api/v2/play {
        add_header Cache-Control "private, no-store" always;
        add_header X-Cache $upstream_cache_status always;
    }
    location = /api/v1/posts {
        proxy_pass http://backend;
    }
}
server {
    listen 80;
    server_name example.test;
    return 301 https://$host$request_uri;
}
"""

AFTER_FIXTURE = BEFORE_FIXTURE.replace(
    """    location = /api/v2/play {
        add_header Cache-Control "private, no-store" always;
        add_header X-Cache $upstream_cache_status always;
    }""",
    """    location = /api/v2/play {
        add_header Cache-Control "private, no-store" always;
        add_header X-Cache $upstream_cache_status always;
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    }""",
)


class RealIpPatchTests(unittest.TestCase):
    def test_patch_file_exists(self):
        self.assertTrue(REAL_IP_PATCH.is_file(), "nginx-real-ip.patch missing")

    def test_ranges_are_exactly_cloudflares_published_lists(self):
        _, diff = _patch_lines(REAL_IP_PATCH)
        ranges = [
            line.split(None, 1)[1].rstrip(";")
            for line in _added_lines(diff)
            if line.startswith("set_real_ip_from")
        ]
        self.assertEqual(len(ranges), 22, "expected 15 IPv4 + 7 IPv6 ranges")
        self.assertEqual(
            set(ranges), set(CLOUDFLARE_IPV4) | set(CLOUDFLARE_IPV6)
        )
        for value in ranges:  # every range must be a valid CIDR
            ipaddress.ip_network(value)

    def test_real_ip_header_and_no_recursive(self):
        _, diff = _patch_lines(REAL_IP_PATCH)
        added = _added_lines(diff)
        headers = [
            line for line in added if line.startswith("real_ip_header")
        ]
        self.assertEqual(headers, ["real_ip_header CF-Connecting-IP;"])
        directives = [
            line
            for line in added
            if line and not line.startswith("#")
        ]
        self.assertFalse(
            any(
                line.startswith("real_ip_recursive")
                for line in directives
            ),
            "real_ip_recursive is deliberately unset: "
            "CF-Connecting-IP carries a single address, nothing to recurse",
        )

    def test_patch_only_adds_real_ip_directives(self):
        _, diff = _patch_lines(REAL_IP_PATCH)
        self.assertEqual(_removed_lines(diff), [])
        for line in _added_lines(diff):
            self.assertTrue(
                line.startswith(
                    ("set_real_ip_from", "real_ip_header", "#")
                ),
                "unexpected added line: %r" % line,
            )


class HardeningPatchTests(unittest.TestCase):
    def test_patch_file_exists(self):
        self.assertTrue(
            HARDENING_PATCH.is_file(), "nginx-hardening.patch missing"
        )

    def test_twenty_eight_hsts_insertions_and_nothing_else(self):
        _, diff = _patch_lines(HARDENING_PATCH)
        added = _added_lines(diff)
        self.assertEqual(len(added), 28, "expected 14 locations x 2 servers")
        for line in added:
            self.assertEqual(line.strip(), HSTS_LINE)
        self.assertEqual(_removed_lines(diff), [])

    def test_every_hunk_lands_among_other_add_headers(self):
        # Each insertion must sit inside a block that already defines
        # add_headers (the location whose headers shadow the server HSTS).
        # Which location that is was proven against the live file
        # (git apply + audit 28 -> 0); see the checkpoint note.
        _, diff = _patch_lines(HARDENING_PATCH)
        hunks = list(_hunks(diff))
        self.assertEqual(len(hunks), 28)
        for hunk in hunks:
            context_headers = [
                line.strip()
                for line in hunk
                if line.startswith(" ")
                and line.strip().startswith("add_header")
                and "Strict-Transport-Security" not in line
            ]
            self.assertTrue(
                context_headers,
                "hunk adds HSTS with no other add_header nearby: %r"
                % (hunk,),
            )

    def test_anchor_is_recorded(self):
        header, _ = _patch_lines(HARDENING_PATCH)
        header_text = "\n".join(header)
        # Anchor: md5 of live /etc/nginx/sites-enabled/radd-stream as
        # fetched 2026-11-27 (1,671 lines). The gate re-checks it.
        # (Supersedes the 2026-11-24 anchor 73fdcff0...: the live file
        # drifted, the old patch no longer applied; gap set unchanged.)
        self.assertIn("8378f1196f154179ee300e362fc1145d", header_text)
        self.assertIn("2026-11-27", header_text)

    def test_no_secret_material_in_either_patch(self):
        secretish = re.compile(
            r"(password|api[_-]?key|secret|bearer)\s*[:=]\s*\S{8,}",
            re.IGNORECASE,
        )
        for path in (REAL_IP_PATCH, HARDENING_PATCH):
            self.assertIsNone(
                secretish.search(path.read_text(encoding="utf-8")),
                "secret-like assignment in %s" % path.name,
            )


class HstsAuditTests(unittest.TestCase):
    def test_audit_finds_the_one_gap_in_before_fixture(self):
        self.assertEqual(
            audit_hsts_gaps(BEFORE_FIXTURE),
            [("= /api/v2/play", 10)],
        )

    def test_audit_finds_no_gap_after_the_fix(self):
        self.assertEqual(audit_hsts_gaps(AFTER_FIXTURE), [])

    def test_audit_ignores_plain_http_server_and_inheriting_location(self):
        # /api/v1/posts defines no add_header (inherits server HSTS) and
        # the port-80 redirect server sets no HSTS at all: neither is a gap.
        gaps = audit_hsts_gaps(BEFORE_FIXTURE)
        self.assertEqual(len(gaps), 1)
        self.assertNotIn("= /api/v1/posts", [name for name, _ in gaps])


if __name__ == "__main__":
    unittest.main()
