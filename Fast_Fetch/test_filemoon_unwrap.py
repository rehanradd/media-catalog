"""Filemoon REST resolver tests (Fix 4, 2026-11-23).

Offline/mocked only: a fake `_filemoon_api_request` serves canned
API-shaped responses built with the module's own AES-GCM encryptor, so
the tests prove the REST call shape, the version/key_parts selection,
and the decrypt — no network, no production DB. Live proof (real file
`fihge8dsk4wd`, PoW solve, decrypt matching `cryptography`, master 200
with a real #EXTM3U) is recorded in the Fix 4 section of
`~/workspace/resolver-build-20261123.md`, not re-run here.

Run: python3 -m pytest test_filemoon_unwrap.py -q   (from Fast_Fetch/)
"""

import base64
import json
import os
import sqlite3
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fast_fetch as ff


def _b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _make_playback(key, sources, *, version="12", nparts=30, iv=None,
                   tracks=None, expires_at=None):
    """A canned encrypted `playback` object in the live shape: version v
    selects 1-based key_parts [v, 31-v]; the two selected parts are the
    key halves, the rest are dummy parts. Decrypt succeeds ONLY when the
    resolver selects exactly those two parts."""
    iv = iv or os.urandom(12)
    parts = [_b64u(os.urandom(16)) for _ in range(nparts)]
    v = int(version)
    parts[v - 1] = _b64u(key[:16])
    parts[31 - v - 1] = _b64u(key[16:])
    plain = json.dumps({
        "sources": sources,
        "tracks": tracks or [],
        "poster_url": "https://img-place.com/testcode12.jpg",
        "expires_at": expires_at,
        "generated_at": 1795429000,
    }).encode()
    payload = ff._filemoon_aes_gcm_encrypt(key, iv, plain)
    return {"algorithm": "AES-256-GCM", "version": version,
            "key_parts": parts, "iv": _b64u(iv), "payload": _b64u(payload)}


MASTER_URL = ("https://edge1.test-cdn.example/hls2/04/06796/testcode12_x/"
                "index-v1-a1.m3u8?s=%d&e=10800" % int(time.time()))
MASTER_BODY = (b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1654158,"
               b"RESOLUTION=1920x1080\n"
               b"https://edge1.test-cdn.example/hls2/04/06796/"
               b"testcode12_x/index-v1-a1.m3u8")
SOURCES_1080 = [{"quality": "x", "label": "1080p",
                 "mime_type": "application/vnd.apple.mpegurl",
                 "url": MASTER_URL, "bitrate_kbps": 1619, "height": 1080,
                 "size_bytes": 123456789}]
TRACKS = [{"language": "en", "title": "English",
           "url": "https://sub.test-cdn.example/en.vtt",
           "kind": "subtitles", "default": True}]


class _FakeFilemoon:
    """Canned filemoon.sx API. `captcha=True` makes the first playback
    POST answer 428 until a verified captcha token is presented."""

    def __init__(self, *, captcha=False, difficulty=4, dead=False,
                 tamper=False):
        self.captcha = captcha
        self.difficulty = difficulty
        self.dead = dead
        self.tamper = tamper
        self.calls = []
        self.captcha_started = 0
        self.key = os.urandom(32)
        self.playback = _make_playback(self.key, SOURCES_1080,
                                       tracks=TRACKS)
        if tamper:
            payload = self.playback["payload"]
            self.playback["payload"] = payload[:-2] + ("AA" if payload[-2:] != "AA" else "BB")
        self.captcha_token = "test-captcha-token"

    def __call__(self, method, url, headers, data, timeout_s=15,
                 _request=None):
        self.calls.append((method, url))
        if url.endswith("/embed/details"):
            if self.dead:
                return (404,
                        b'{"error":"video record missing: video not found"}',
                        "application/json")
            return (200, json.dumps({
                "id": 33982145, "code": "testcode12", "title": "Test File",
                "poster_url": "https://img-place.com/testcode12.jpg",
                "description": "", "created_at": "2024-08-29T17:49:12Z",
                "owner_private": False}).encode(), "application/json")
        if url.endswith("/embed/settings"):
            return (200, json.dumps({
                "code": "testcode12", "premium_only": False,
                "download_allowed": True, "captcha_required": self.captcha,
            }).encode(), "application/json")
        if url.endswith("/embed/playback"):
            if self.captcha and headers.get("X-Captcha-Token") != self.captcha_token:
                return (428, b'{"error":"captcha_required"}',
                        "application/json")
            return (200, json.dumps({
                "playback": self.playback, "cache_status": "hit",
            }).encode(), "application/json")
        if url.endswith("/embed/captcha"):
            self.captcha_started += 1
            return (200, json.dumps({
                "pow_nonce": "a" * 32, "pow_difficulty": self.difficulty,
                "pow_token": "pow-token", "expires_in": 1800,
                "algorithm": "sha256-leading-zero-bits",
            }).encode(), "application/json")
        if url.endswith("/embed/captcha/verify"):
            body = json.loads((data or b"{}").decode())
            words = ff._filemoon_pow_gr(
                ("a" * 32 + ":" + str(body.get("solution", ""))).encode())
            ok = (body.get("pow_token") == "pow-token"
                  and ff._filemoon_pow_leading_zero_bits(words) >= self.difficulty)
            if ok:
                return (200, json.dumps({
                    "status": "ok", "token": self.captcha_token,
                    "expires_in": 1800}).encode(), "application/json")
            return (200, json.dumps({"status": "error",
                                     "reason": "bad_solution"}).encode(),
                    "application/json")
        if url == MASTER_URL:
            return (200, MASTER_BODY, "application/vnd.apple.mpegurl")
        return (404, b"{}", "application/json")


class CryptoTests(unittest.TestCase):
    def test_aes_block_fips197(self):
        key = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
        pt = bytes.fromhex("00112233445566778899aabbccddeeff")
        self.assertEqual(
            ff._filemoon_aes_encrypt_block(key, pt).hex(),
            "69c4e0d86a7b0430d8cdb78070b4c55a")

    def test_aes_gcm_nist_vectors(self):
        # NIST GCM test cases 1-2 (zero key/IV): empty plaintext tag, and
        # the 16-zero-byte plaintext ciphertext + tag.
        self.assertEqual(
            ff._filemoon_aes_gcm_encrypt(bytes(16), bytes(12), b"").hex(),
            "58e2fccefa7e3061367f1d57a4e7455a")
        self.assertEqual(
            ff._filemoon_aes_gcm_encrypt(bytes(16), bytes(12), bytes(16)).hex(),
            "0388dace60b6a392f328c2b971b2fe78"
            "ab6e47d42cec13bdf53a67b21257bddf")
        # NIST case with AAD (key 000102..0f, IV 000102..0b, AAD feedfa..).
        key = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
        iv = bytes.fromhex("000102030405060708090a0b")
        got = ff._filemoon_aes_gcm_encrypt(
            key, iv, bytes.fromhex("00112233445566778899aabbccddeeff"),
            bytes.fromhex("feedfacedeadbeeffeedfacedeadbeef"))
        self.assertEqual(
            got.hex(),
            "937d85fd224e9123c34bcb31fa7e9ef7"
            "296e13fa389fcb8a5c057dbd00473edd")

    def test_aes_gcm_roundtrip_and_bad_tag(self):
        for klen, plen in [(16, 0), (16, 1), (16, 17), (32, 64), (24, 100)]:
            key = os.urandom(klen)
            iv = os.urandom(12)
            pt = os.urandom(plen)
            aad = os.urandom(7)
            ct = ff._filemoon_aes_gcm_encrypt(key, iv, pt, aad)
            self.assertEqual(
                ff._filemoon_aes_gcm_decrypt(key, iv, ct, aad), pt)
        bad = bytearray(ct)
        bad[-1] ^= 1
        with self.assertRaises(ValueError):
            ff._filemoon_aes_gcm_decrypt(key, iv, bytes(bad), aad)

    def test_pow_hash_matches_js_bundle(self):
        # Byte-identical to the `gr` function in pow-DEJGtdh2.js run
        # under node: gr("test") and gr("9a57...:0").
        self.assertEqual(
            [hex(w) for w in ff._filemoon_pow_gr(b"test")],
            ["0xbbb50559", "0xc5b03394", "0xec649756", "0x563ab331",
             "0x1379f4eb", "0x48803a56", "0x6f202bfa", "0xb641a8e4"])
        self.assertEqual(ff._filemoon_pow_leading_zero_bits(
            ff._filemoon_pow_gr(b"test")), 0)

    def test_pow_solve_low_difficulty(self):
        sol = ff._filemoon_pow_solve("a" * 32, 4, timeout_s=30)
        self.assertIsNotNone(sol)
        words = ff._filemoon_pow_gr(("a" * 32 + ":" + sol).encode())
        self.assertGreaterEqual(
            ff._filemoon_pow_leading_zero_bits(words), 4)


class DecryptTests(unittest.TestCase):
    def test_decrypt_selects_version_parts(self):
        key = os.urandom(32)
        enc = _make_playback(key, SOURCES_1080, version="12")
        dec = ff._filemoon_decrypt_playback(enc)
        self.assertEqual(dec["sources"][0]["label"], "1080p")
        self.assertEqual(dec["sources"][0]["height"], 1080)
        self.assertEqual(dec["sources"][0]["bitrate_kbps"], 1619)

    def test_decrypt_wrong_version_fails_tag(self):
        # Same object, wrong version -> different key parts -> tag fail.
        key = os.urandom(32)
        enc = _make_playback(key, SOURCES_1080, version="12")
        enc = dict(enc, version="7")
        with self.assertRaises(ValueError):
            ff._filemoon_decrypt_playback(enc)

    def test_decrypt_invalid_version_uses_all_parts(self):
        # Version outside 1..20 falls back to concatenating all parts.
        key = os.urandom(32)
        parts = [_b64u(key[:16]), _b64u(key[16:])]
        iv = os.urandom(12)
        plain = json.dumps({"sources": SOURCES_1080}).encode()
        enc = {"version": "99", "key_parts": parts, "iv": _b64u(iv),
               "payload": _b64u(ff._filemoon_aes_gcm_encrypt(key, iv, plain))}
        self.assertEqual(
            ff._filemoon_decrypt_playback(enc)["sources"][0]["label"],
            "1080p")

    def test_code_from_url(self):
        self.assertEqual(
            ff.filemoon_code_from_url("https://filemoon.sx/e/abc123?t=x"),
            "abc123")
        self.assertEqual(
            ff.filemoon_code_from_url("https://filemoon.sx/d/xyz789"),
            "xyz789")
        self.assertEqual(ff.filemoon_code_from_url("https://example.com/e/abc"), "")
        self.assertEqual(ff.filemoon_code_from_url(""), "")


class UnwrapTests(unittest.TestCase):
    def setUp(self):
        self._orig = ff._filemoon_api_request
        with ff._FILEMOON_CAPTCHA_LOCK:
            ff._FILEMOON_CAPTCHA["token"] = None
            ff._FILEMOON_CAPTCHA["expires_at"] = 0.0

    def tearDown(self):
        ff._filemoon_api_request = self._orig
        with ff._FILEMOON_CAPTCHA_LOCK:
            ff._FILEMOON_CAPTCHA["token"] = None
            ff._FILEMOON_CAPTCHA["expires_at"] = 0.0

    def test_unwrap_no_captcha_active(self):
        fake = _FakeFilemoon()
        ff._filemoon_api_request = fake
        verdict = ff.unwrap_filemoon("https://filemoon.sx/e/testcode12")
        self.assertEqual(verdict["status"], "active")
        self.assertEqual(verdict["final_url"], MASTER_URL)
        self.assertEqual(verdict["content_type"],
                         "application/vnd.apple.mpegurl")
        self.assertEqual(verdict["file_id"], "testcode12")
        self.assertTrue(verdict["is_temporary"])
        self.assertEqual(verdict["size_bytes"], 123456789)
        # Signed s=<epoch>&e=10800 on the master -> ~3 h expiry.
        self.assertIsNotNone(verdict["expires_in"])
        self.assertGreater(verdict["expires_in"], 0)
        self.assertLessEqual(verdict["expires_in"], 10800)
        # Subtitle tracks ride the verdict for the resolver ledger.
        self.assertEqual(len(verdict["subtitles"]), 1)
        self.assertEqual(verdict["subtitles"][0]["language"], "en")
        # Call shape: details -> settings -> playback -> master probe.
        paths = [u.split("/embed")[-1] if "/embed" in u else u
                 for _m, u in fake.calls]
        self.assertIn("/details", paths)
        self.assertIn("/settings", paths)
        self.assertIn("/playback", paths)
        self.assertIn(MASTER_URL, [u for _m, u in fake.calls])

    def test_unwrap_captcha_pow_flow_and_token_reuse(self):
        fake = _FakeFilemoon(captcha=True, difficulty=4)
        ff._filemoon_api_request = fake
        verdict = ff.unwrap_filemoon("https://filemoon.sx/e/testcode12")
        self.assertEqual(verdict["status"], "active")
        self.assertEqual(verdict["final_url"], MASTER_URL)
        self.assertEqual(fake.captcha_started, 1)
        # Second call reuses the cached token: no second captcha start.
        verdict2 = ff.unwrap_filemoon("https://filemoon.sx/e/testcode12")
        self.assertEqual(verdict2["status"], "active")
        self.assertEqual(fake.captcha_started, 1)

    def test_unwrap_dead_file(self):
        fake = _FakeFilemoon(dead=True)
        ff._filemoon_api_request = fake
        verdict = ff.unwrap_filemoon("https://filemoon.sx/d/testcode12")
        self.assertEqual(verdict["status"], "dead")
        self.assertEqual(verdict["final_url"], "")

    def test_unwrap_tampered_payload_is_unknown_not_dead(self):
        fake = _FakeFilemoon(tamper=True)
        ff._filemoon_api_request = fake
        verdict = ff.unwrap_filemoon("https://filemoon.sx/e/testcode12")
        self.assertEqual(verdict["status"], "unknown")
        self.assertEqual(verdict["final_url"], "")

    def test_unwrap_not_a_filemoon_url(self):
        verdict = ff.unwrap_filemoon("https://example.com/e/abc123")
        self.assertEqual(verdict["status"], "unknown")

    def test_unwrap_verify_false_catalogs_without_probe(self):
        fake = _FakeFilemoon()
        ff._filemoon_api_request = fake
        verdict = ff.unwrap_filemoon("https://filemoon.sx/e/testcode12",
                                     verify=False)
        self.assertEqual(verdict["status"], "unverified")
        self.assertEqual(verdict["final_url"], MASTER_URL)
        self.assertNotIn(MASTER_URL, [u for _m, u in fake.calls])


class DispatchTests(unittest.TestCase):
    """The filemoon branch inside resolve_live_source, on a temp DB."""

    def _fresh_db(self):
        fd, path = tempfile.mkstemp(suffix=".db")
        os.close(fd)
        old_env = os.environ.get("RADD_DB_PATH")
        saved = {name: sys.modules.get(name)
                 for name in ("fast_fetch", "config")}
        for name in saved:
            sys.modules.pop(name, None)
        os.environ["RADD_DB_PATH"] = path
        return path, old_env, saved

    def _restore(self, old_env, saved):
        for name, mod in saved.items():
            if mod is not None:
                sys.modules[name] = mod
            else:
                sys.modules.pop(name, None)
        if old_env is None:
            os.environ.pop("RADD_DB_PATH", None)
        else:
            os.environ["RADD_DB_PATH"] = old_env

    def _insert_row(self, ffmod, service, source_url, final_url, status):
        conn = sqlite3.connect(os.environ["RADD_DB_PATH"])
        conn.execute("INSERT INTO posts (id, url, title, is_18_plus) "
                     "VALUES (1, 'https://qa.local/p/1', 'QA', 0)")
        conn.execute("INSERT INTO releases (id, post_id) VALUES (1, 1)")
        conn.execute(
            "INSERT INTO download_sources (id, release_id, service, "
            "source_url, final_url, playback_type, is_temporary, status) "
            "VALUES (42, 1, ?, ?, ?, 'iframe', 1, ?)",
            (service, source_url, final_url, status))
        conn.commit()
        conn.close()

    def test_dispatch_routes_filemoon_to_master(self):
        path, old_env, saved = self._fresh_db()
        try:
            import fast_fetch as ffmod
            ffmod.init_db()
            self._insert_row(ffmod, "filemoon",
                             "https://filemoon.sx/e/testcode12",
                             "https://filemoon.sx/e/testcode12", "expired")
            fake = _FakeFilemoon()
            orig_api = ffmod._filemoon_api_request
            orig_ttl = ffmod.probe_url_ttl
            ffmod._filemoon_api_request = fake
            ffmod.probe_url_ttl = lambda url, **kw: (
                None, {"observed": False, "reason": "no_directive"})
            try:
                with ffmod._FILEMOON_CAPTCHA_LOCK:
                    ffmod._FILEMOON_CAPTCHA["token"] = None
                    ffmod._FILEMOON_CAPTCHA["expires_at"] = 0.0
                res = ffmod.resolve_live_source(42, v2=True)
            finally:
                ffmod._filemoon_api_request = orig_api
                ffmod.probe_url_ttl = orig_ttl
            self.assertTrue(res.get("success"), res)
            self.assertEqual(res.get("url"), MASTER_URL)
            self.assertFalse(res.get("cached"))
            conn = sqlite3.connect(path)
            row = conn.execute(
                "SELECT final_url, status FROM download_sources WHERE id=42"
            ).fetchone()
            conn.close()
            self.assertEqual(row[0], MASTER_URL)
            self.assertEqual(row[1], "active")
        finally:
            self._restore(old_env, saved)
            os.unlink(path)

    def test_dispatch_dead_file_is_honest_failure(self):
        path, old_env, saved = self._fresh_db()
        try:
            import fast_fetch as ffmod
            ffmod.init_db()
            self._insert_row(ffmod, "filemoon",
                             "https://filemoon.sx/d/testcode12",
                             "https://filemoon.sx/d/testcode12", "expired")
            fake = _FakeFilemoon(dead=True)
            orig_api = ffmod._filemoon_api_request
            ffmod._filemoon_api_request = fake
            try:
                res = ffmod.resolve_live_source(42, v2=True)
            finally:
                ffmod._filemoon_api_request = orig_api
            self.assertFalse(res.get("success"))
            self.assertFalse(res.get("unprobed"),
                            "the filemoon branch ran: this is unwrap_failed, "
                            "not no_unwrapper")
            self.assertEqual(res.get("code"), "no_playable_source")
        finally:
            self._restore(old_env, saved)
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
