#!/usr/bin/env python3
"""Offline tests for the pure-stdlib MEGA AES prototype (``mega_crypto``).

Stdlib only, zero network: every vector below is either a published
known-answer vector (FIPS-197, NIST SP 800-38A) or a fixture built with the
module's own encrypt path — never a recorded live byte, never a real link
key. The live proof (minted URL + capped Range + decrypted container
magic) is the probe script in ``~/workspace/resolver-work/mega/``, not
this file.

    python3 -S Fast_Fetch/test_mega_crypto.py
    python3 -S -m unittest test_mega_crypto   (from Fast_Fetch/)
"""

from __future__ import annotations

import base64
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.resolve()))

import mega_crypto as mc  # noqa: E402


def _h(s: str) -> bytes:
    return bytes.fromhex(s)


# FIPS-197 Appendix B (AES-128) / Appendix C (AES-192, AES-256).
FIPS_PT = _h("00112233445566778899aabbccddeeff")
FIPS_VECTORS = [
    (128, "000102030405060708090a0b0c0d0e0f",
     "69c4e0d86a7b0430d8cdb78070b4c55a"),
    (192, "000102030405060708090a0b0c0d0e0f1011121314151617",
     "dda97ca4864cdfe06eaf70a0ec0d7191"),
    (256, "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
     "8ea2b7ca516745bfeafc49904b496089"),
]

# NIST SP 800-38A §F.5.1, AES-128-CTR (four blocks; each checked on its own
# so a single mis-copied vector is diagnosable instead of masking the rest).
CTR_KEY = _h("2b7e151628aed2a6abf7158809cf4f3c")
CTR_IV = _h("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
CTR_PT = [
    "6bc1bee22e409f96e93d7e117393172a",
    "ae2d8a571e03ac9c9eb76fac45af8e51",
    "30c81c46a35ce411e5fbc1191a0a52ef",
    "f69f2445df4f9b17ad2b417be66c3710",
]
CTR_CT = [
    "874d6191b620e3261bef6864990db6ce",
    "9806f66b7970fdff8617187bb9fffdff",
    "5ae4df3edbd5d35e5b4f09020db03eab",
    "1e031dda2fbe03d1792170a0f3009cee",
]

# A fixed synthetic link key (32 bytes) for the canned MEGA fixtures. This
# is NOT a real link key — it is the FIPS-197 Appendix C 256-bit example
# key, reused here only because it is already a published constant.
LINK_KEY = _h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
LINK_KEY_B64 = base64.urlsafe_b64encode(LINK_KEY).rstrip(b"=").decode("ascii")


class AesBlockTests(unittest.TestCase):
    def test_fips197_encrypt_vectors(self):
        for bits, key_hex, ct_hex in FIPS_VECTORS:
            with self.subTest(bits=bits):
                rk = mc.expand_key(_h(key_hex))
                self.assertEqual(len(rk), bits // 32 + 7)
                self.assertEqual(mc.encrypt_block(rk, FIPS_PT).hex(), ct_hex)

    def test_fips197_decrypt_vectors(self):
        for bits, key_hex, ct_hex in FIPS_VECTORS:
            with self.subTest(bits=bits):
                rk = mc.expand_key(_h(key_hex))
                self.assertEqual(mc.decrypt_block(rk, _h(ct_hex)), FIPS_PT)

    def test_encrypt_decrypt_roundtrip_all_key_sizes(self):
        # A random-looking fixed plaintext; encrypt then decrypt must be
        # the identity for every key size, including multi-block data.
        pt = bytes((i * 37 + 11) % 256 for i in range(64))
        for bits, key_hex, _ in FIPS_VECTORS:
            with self.subTest(bits=bits):
                rk = mc.expand_key(_h(key_hex))
                ct = b"".join(mc.encrypt_block(rk, pt[i:i + 16])
                              for i in range(0, 64, 16))
                self.assertNotEqual(ct, pt)
                rt = b"".join(mc.decrypt_block(rk, ct[i:i + 16])
                              for i in range(0, 64, 16))
                self.assertEqual(rt, pt)

    def test_bad_key_and_block_lengths_raise(self):
        with self.assertRaises(ValueError):
            mc.expand_key(b"short")
        rk = mc.expand_key(_h("000102030405060708090a0b0c0d0e0f"))
        with self.assertRaises(ValueError):
            mc.encrypt_block(rk, b"15 bytes only!!")
        with self.assertRaises(ValueError):
            mc.decrypt_block(rk, b"")
        with self.assertRaises(ValueError):
            mc.ctr_crypt(_h("000102030405060708090a0b0c0d0e0f"), b"15-byte-counter", b"x")
        with self.assertRaises(ValueError):
            mc.cbc_decrypt(_h("000102030405060708090a0b0c0d0e0f"), bytes(16), b"17 bytes.........")


class CtrModeTests(unittest.TestCase):
    def test_nist_sp800_38a_f51_vectors(self):
        pt = b"".join(_h(x) for x in CTR_PT)
        ct = mc.ctr_crypt(CTR_KEY, CTR_IV, pt)
        for i, want in enumerate(CTR_CT):
            with self.subTest(block=i):
                self.assertEqual(ct[16 * i:16 * i + 16].hex(), want)
        # CTR decrypt is the same operation: ciphertext back to plaintext.
        self.assertEqual(mc.ctr_crypt(CTR_KEY, CTR_IV, ct), pt)

    def test_ctr_roundtrip_odd_length(self):
        key = _h("000102030405060708090a0b0c0d0e0f")
        counter = bytes(range(16))
        pt = bytes((i * 7 + 3) % 256 for i in range(1000))  # not a block multiple
        ct = mc.ctr_crypt(key, counter, pt)
        self.assertNotEqual(ct, pt)
        self.assertEqual(mc.ctr_crypt(key, counter, ct), pt)
        self.assertEqual(mc.ctr_crypt(key, counter, b""), b"")

    def test_ctr_counter_increments_per_block(self):
        # Two single-block encrypts with counters one apart must equal the
        # first two blocks of one two-block encrypt.
        key = _h("000102030405060708090a0b0c0d0e0f")
        counter = bytes(16)
        pt = bytes(32)
        whole = mc.ctr_crypt(key, counter, pt)
        first = mc.ctr_crypt(key, counter, pt[:16])
        second = mc.ctr_crypt(key, (1).to_bytes(16, "big"), pt[16:])
        self.assertEqual(whole, first + second)


class MegaKeyTests(unittest.TestCase):
    def test_decode_link_key_valid(self):
        self.assertEqual(mc.decode_link_key(LINK_KEY_B64), LINK_KEY)
        self.assertEqual(len(LINK_KEY_B64), 43)  # 32 bytes, no padding

    def test_decode_link_key_rejects_non_keys(self):
        for bad in ("", None, "short", "a" * 44, "a" * 42,
                    "!!!not-base64!!!" + "a" * 43):
            with self.subTest(bad=repr(bad)[:20]):
                self.assertIsNone(mc.decode_link_key(bad))
        # 43 chars of valid alphabet that decode to 32 zero bytes ARE a key
        # (all-zero, but well-formed) — length, not entropy, is the check.
        self.assertEqual(mc.decode_link_key("A" * 43), bytes(32))

    def test_unmerge_key_matches_kotlin_semantics(self):
        # Same rule as MegaCrypto.unmergeKey: AES = K[0:16] ^ K[16:32],
        # IV = K[16:24], MAC = K[24:32].
        aes_key, iv, mac_key = mc.unmerge_key(LINK_KEY)
        self.assertEqual(aes_key, bytes(a ^ b for a, b in zip(LINK_KEY[:16], LINK_KEY[16:])))
        self.assertEqual(iv, LINK_KEY[16:24])
        self.assertEqual(mac_key, LINK_KEY[24:32])
        self.assertEqual(len(aes_key), 16)
        self.assertEqual(len(iv), 8)
        with self.assertRaises(ValueError):
            mc.unmerge_key(b"31 bytes is not a link key....")

    def test_decrypt_mega_bytes_rejects_bad_key(self):
        with self.assertRaises(ValueError):
            mc.decrypt_mega_bytes(b"\x00" * 16, "not-a-key")
        with self.assertRaises(ValueError):
            mc.decrypt_mega_bytes(b"\x00" * 16, LINK_KEY_B64, start_block=-1)
        with self.assertRaises(ValueError):
            mc.mega_ctr_crypt(b"\x00" * 16, b"7-byte!", b"\x00" * 16)


class CannedMegaSampleTests(unittest.TestCase):
    """A canned MEGA-encrypted sample, built with the same encrypt path
    the decryptor uses — the offline stand-in for the live 1 KB proof."""

    def _sample(self, plaintext: bytes):
        aes_key, iv, _ = mc.unmerge_key(LINK_KEY)
        ct = mc.mega_ctr_crypt(aes_key, iv, plaintext)
        return aes_key, iv, ct

    def test_ebml_head_decrypts_to_container_magic(self):
        pt = (b"\x1a\x45\xdf\xa3\x42\x82\x84\x01\x02\x03matroska"
              + bytes(64 - 19))
        aes_key, iv, ct = self._sample(pt)
        self.assertNotEqual(ct[:4], b"\x1a\x45\xdf\xa3")  # ciphertext hides it
        out = mc.mega_ctr_crypt(aes_key, iv, ct)
        self.assertEqual(out, pt)
        self.assertTrue(mc.is_container_magic(out))
        self.assertEqual(out[:4].hex(), "1a45dfa3")
        # The one-call path agrees.
        self.assertEqual(mc.decrypt_mega_bytes(ct, LINK_KEY_B64), pt)

    def test_ftyp_head_decrypts_to_container_magic(self):
        pt = (32).to_bytes(4, "big") + b"ftypisom" + bytes(32 - 12)
        aes_key, iv, ct = self._sample(pt)
        out = mc.mega_ctr_crypt(aes_key, iv, ct)
        self.assertEqual(out, pt)
        self.assertTrue(mc.is_container_magic(out))

    def test_seek_decrypt_matches_full_decrypt_slice(self):
        # Seeking works: decrypting bytes 512..1024 with start_block=32
        # equals the same slice of a from-zero decrypt (the CTR counter is
        # iv || big-endian block index, as in MegaCrypto.counterFor).
        pt = bytes((i * 13 + 5) % 256 for i in range(1024))
        aes_key, iv, ct = self._sample(pt)
        whole = mc.mega_ctr_crypt(aes_key, iv, ct)
        self.assertEqual(whole, pt)
        self.assertEqual(mc.mega_ctr_crypt(aes_key, iv, ct[512:], start_block=32),
                         pt[512:])
        self.assertEqual(mc.decrypt_mega_bytes(ct[768:], LINK_KEY_B64, start_block=48),
                         pt[768:])

    def test_wrong_key_decrypts_to_garbage_not_magic(self):
        pt = (b"\x1a\x45\xdf\xa3" + bytes(60))
        aes_key, iv, ct = self._sample(pt)
        # A genuinely different key: flip one byte of the link key before
        # the unmerge (XORing two 16-apart ranges would collide at 0x10).
        wrong_link = bytearray(LINK_KEY)
        wrong_link[0] ^= 0xFF
        wrong_key, _, _ = mc.unmerge_key(bytes(wrong_link))
        self.assertNotEqual(wrong_key, aes_key)
        self.assertFalse(mc.is_container_magic(mc.mega_ctr_crypt(wrong_key, iv, ct)))


class AttrsTests(unittest.TestCase):
    def _attrs_blob(self, text: str, aes_key: bytes) -> str:
        raw = text.encode("utf-8")
        padded = raw + b"\x00" * ((16 - len(raw) % 16) % 16)
        ct = mc.cbc_encrypt(aes_key, bytes(16), padded)
        return base64.urlsafe_b64encode(ct).rstrip(b"=").decode("ascii")

    def test_decrypt_attrs_roundtrip(self):
        aes_key, _, _ = mc.unmerge_key(LINK_KEY)
        blob = self._attrs_blob('MEGA{"n":"test.mkv"}', aes_key)
        self.assertEqual(mc.decrypt_attrs(blob, aes_key), 'MEGA{"n":"test.mkv"}')

    def test_decrypt_attrs_rejects_wrong_key_and_bad_blobs(self):
        aes_key, _, _ = mc.unmerge_key(LINK_KEY)
        blob = self._attrs_blob('MEGA{"n":"test.mkv"}', aes_key)
        wrong_link = bytearray(LINK_KEY)
        wrong_link[0] ^= 0xFF
        wrong_key, _, _ = mc.unmerge_key(bytes(wrong_link))
        self.assertNotEqual(wrong_key, aes_key)
        self.assertIsNone(mc.decrypt_attrs(blob, wrong_key))
        # Non-MEGA plaintext, empty blob, odd length, bad base64.
        self.assertIsNone(mc.decrypt_attrs(
            self._attrs_blob("plain-json-no-mega", aes_key), aes_key))
        self.assertIsNone(mc.decrypt_attrs("", aes_key))
        self.assertIsNone(mc.decrypt_attrs(None, aes_key))
        self.assertIsNone(mc.decrypt_attrs("AAE", aes_key))  # 2 bytes, not a block
        self.assertIsNone(mc.decrypt_attrs("!!!", aes_key))

    def test_cbc_encrypt_decrypt_roundtrip(self):
        key = _h("000102030405060708090a0b0c0d0e0f")
        pt = bytes(range(48))
        ct = mc.cbc_encrypt(key, bytes(16), pt)
        self.assertNotEqual(ct, pt)
        self.assertEqual(mc.cbc_decrypt(key, bytes(16), ct), pt)
        with self.assertRaises(ValueError):
            mc.cbc_encrypt(key, bytes(16), b"not a block multiple")


class MagicTests(unittest.TestCase):
    def test_is_container_magic(self):
        self.assertTrue(mc.is_container_magic(b"\x1a\x45\xdf\xa3" + bytes(60)))
        self.assertTrue(mc.is_container_magic(b"\x00\x00\x00\x20ftypisom" + bytes(20)))
        self.assertTrue(mc.is_container_magic(b"RIFF" + bytes(60)))
        self.assertFalse(mc.is_container_magic(b""))
        self.assertFalse(mc.is_container_magic(b"\x04\x70\x48\x44" + bytes(60)))
        self.assertFalse(mc.is_container_magic(b"ftyp"))  # ftyp must sit at offset 4
        self.assertFalse(mc.is_container_magic(b"\x1a\x45\xdf"))  # truncated EBML


class ThroughputNoteTests(unittest.TestCase):
    def test_throughput_report_only(self):
        """No assertion on speed (CI boxes vary) — just the honest number
        the integration sketch rests on: pure-Python AES is a head-check
        tool, not a streaming proxy. Prints one line to the test log."""
        aes_key, iv, _ = mc.unmerge_key(LINK_KEY)
        data = bytes(64 * 1024)
        t0 = time.monotonic()
        out = mc.mega_ctr_crypt(aes_key, iv, data)
        dt = time.monotonic() - t0
        self.assertEqual(len(out), len(data))
        print("\n[mega_crypto throughput] 64 KiB CTR decrypt in %.2fs -> %.1f KiB/s"
              % (dt, 64.0 / max(dt, 1e-9)))


if __name__ == "__main__":
    unittest.main()
