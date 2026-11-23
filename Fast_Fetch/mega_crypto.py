#!/usr/bin/env python3
"""Pure-stdlib AES for MEGA file decryption (prototype, 2026-11-23).

Why this file exists
--------------------
MEGA serves AES-CTR ciphertext, never plaintext: the public ``g`` API mints
a download URL and the bytes behind it are encrypted with a key that lives
only in the ``mega.nz/file/<node_id>#<key>`` URL fragment (Phase C,
``Fast_Fetch/checkpoints/20261120-phase-c-mega.md`` — key derivation proven
live with ``openssl`` there: the same 64 ciphertext bytes decrypted to the
EBML magic ``1a45dfa3`` + ``matroska``). The Android app already decrypts
on-device (``App/.../data/cache/MegaCrypto.kt``, ``MegaApi.kt``,
``MegaDecryptProxy.kt`` — client-side decrypt, merged to main), and the
server already verifies liveness and hands the app a decrypt descriptor
(``importers/host_resolvers.py``: ``mega_parse_url`` / ``verify_mega_live``;
``fast_fetch.py`` mega branch in ``resolve_live_source``). What the Python
side never had is AES itself — the stdlib has no bulk cipher
(``hashlib`` is hashes only, ``ssl`` exposes none) — so the server could
not decrypt even one byte to check a key, a file, or a magic number.

This module closes exactly that gap, with nothing but the stdlib: a
straightforward FIPS-197 AES (128/192/256-bit keys, encrypt + decrypt
block), AES-CTR as MEGA uses it, AES-CBC for the ``at`` attributes blob,
the link-key unmerge, and the container-magic check. Every function mirrors
``MegaCrypto.kt`` semantics byte for byte (same unmerge, same counter
layout, same ``MEGA{`` attrs check) so server and client can never disagree
about what a key means; the unit tests pin that agreement with the same
known vectors the Kotlin side was verified against.

Scope — a PROTOTYPE, not a production unwrapper
-----------------------------------------------
* Proves: a minted URL + a capped Range decrypts to real media (1 KB head
  -> container magic), and the stored fragment key matches the live node.
* Does NOT verify chunk integrity: MEGA authenticates each 128 KiB chunk
  with a CBC-MAC folded into a meta-MAC (``mac_key`` from the unmerge is
  carried but unused, exactly as in ``MegaCrypto.kt``). A 1 KB head proves
  the key is right; it cannot prove bytes 1 KiB..end are intact. Any
  production use that serves decrypted bytes must verify chunk MACs over
  full chunks first — that code is owed, not here.
* Does NOT wire into ``resolve_live_source``: the dispatch already returns
  the decrypt descriptor to the app, and playback stays client-side (see
  "Integration shape" below). No dispatch change, no ``RESOLVER_VERSION``
  bump in this patch.
* Speed: pure Python, no tables beyond the S-boxes — measure before
  assuming anything (see ``_throughput_note`` in the tests). It is fast
  enough for a 1 KB head check and far too slow to stream a film: that is
  a property of the approach, not a bug to fix here.

Integration shape (sketch — NOT implemented here)
-------------------------------------------------
Two shapes were considered; only the first fits this codebase:

* A — server-side *verification only* (fits): on a mega press, after
  ``verify_mega_live`` says the node is alive, the server mints its own
  ``g`` URL, fetches a capped Range (first 1 KiB), decrypts it with this
  module, and checks ``is_container_magic`` before returning the decrypt
  descriptor. Cost is ~1 KiB of server-IP quota per press (MEGA's free
  budget is ~5 GB / 6 h per egress IP) plus one API round-trip; it catches
  a stale or wrong stored key before the app spends the user's own quota
  on garbage. Needs a mint that returns the ``g`` URL server-side
  (``verify_mega_live`` deliberately does NOT return it) and a decision
  about the extra round-trip — coordinator call, not this prototype.

* B — decrypt-on-the-fly proxy (does NOT fit, named so it is not
  re-proposed blindly): the server fetches ciphertext ranges and streams
  decrypted bytes to the player. It would need native-speed AES (pure
  Python is orders of magnitude too slow on this 1-vCPU box), full-chunk
  CBC-MAC verification, and it would burn the shared server-IP quota
  (~5 GB / 6 h for ALL users; the probed sample file alone is ~0.9 GB).
  A paid MEGA Pro account (money = owner-only) is the only way to raise
  the quota server-side. The client-side proxy the app already ships
  (``MegaDecryptProxy`` — loopback, Range, 2 MB window, quota on the
  user's own IP) is the playback path; a server proxy is not its
  replacement.

* Re-encrypt is a non-starter: the player cannot play AES-CTR bytes, and
  wrapping them in another cipher the player also cannot play changes
  nothing. The choice is decrypt-on-device (current) vs decrypt-on-server
  (blocked above) — there is no third shape.

Key handling
------------
A mega link key is 32 bytes, URL-safe base64 without padding (43 chars).
Unmerge (Lane F, verified with openssl; same as ``MegaCrypto.kt``)::

    aes_key = link_key[0:16] XOR link_key[16:32]
    iv      = link_key[16:24]
    mac_key = link_key[24:32]

CTR counter for a block: ``iv (8 bytes) || 64-bit big-endian block index``,
incremented per 16-byte block across the whole file — so any byte range
decrypts once its block's counter is known, which is what makes seeking
work. Ciphertext handed to :func:`mega_ctr_crypt` must start on a 16-byte
block boundary of the file (``start_block = byte_offset // 16``); a
mid-block offset raises :exc:`ValueError` instead of silently decrypting
garbage.

The ``at`` attributes blob is AES-128-CBC, zero IV, zero padding; the
plaintext starts with ``MEGA`` followed by JSON (``{"n": "<filename>"}``)
— the key check that runs before any media byte is fetched.

    python3 -S Fast_Fetch/test_mega_crypto.py
"""

from __future__ import annotations

import base64
import binascii

__all__ = [
    "expand_key",
    "encrypt_block",
    "decrypt_block",
    "ctr_crypt",
    "mega_ctr_crypt",
    "cbc_encrypt",
    "cbc_decrypt",
    "decode_link_key",
    "unmerge_key",
    "decrypt_attrs",
    "decrypt_mega_bytes",
    "is_container_magic",
]

# ---------------------------------------------------------------------------
# FIPS-197 tables (standard values; the tests pin them against the
# FIPS-197 Appendix B/C known-answer vectors).
# ---------------------------------------------------------------------------

_SBOX = (
    0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5, 0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
    0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0, 0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
    0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC, 0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
    0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A, 0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
    0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0, 0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
    0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B, 0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
    0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85, 0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
    0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5, 0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
    0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17, 0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
    0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88, 0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
    0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C, 0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
    0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9, 0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
    0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6, 0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
    0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E, 0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
    0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94, 0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
    0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68, 0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16,
)

_INV_SBOX = (
    0x52, 0x09, 0x6A, 0xD5, 0x30, 0x36, 0xA5, 0x38, 0xBF, 0x40, 0xA3, 0x9E, 0x81, 0xF3, 0xD7, 0xFB,
    0x7C, 0xE3, 0x39, 0x82, 0x9B, 0x2F, 0xFF, 0x87, 0x34, 0x8E, 0x43, 0x44, 0xC4, 0xDE, 0xE9, 0xCB,
    0x54, 0x7B, 0x94, 0x32, 0xA6, 0xC2, 0x23, 0x3D, 0xEE, 0x4C, 0x95, 0x0B, 0x42, 0xFA, 0xC3, 0x4E,
    0x08, 0x2E, 0xA1, 0x66, 0x28, 0xD9, 0x24, 0xB2, 0x76, 0x5B, 0xA2, 0x49, 0x6D, 0x8B, 0xD1, 0x25,
    0x72, 0xF8, 0xF6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xD4, 0xA4, 0x5C, 0xCC, 0x5D, 0x65, 0xB6, 0x92,
    0x6C, 0x70, 0x48, 0x50, 0xFD, 0xED, 0xB9, 0xDA, 0x5E, 0x15, 0x46, 0x57, 0xA7, 0x8D, 0x9D, 0x84,
    0x90, 0xD8, 0xAB, 0x00, 0x8C, 0xBC, 0xD3, 0x0A, 0xF7, 0xE4, 0x58, 0x05, 0xB8, 0xB3, 0x45, 0x06,
    0xD0, 0x2C, 0x1E, 0x8F, 0xCA, 0x3F, 0x0F, 0x02, 0xC1, 0xAF, 0xBD, 0x03, 0x01, 0x13, 0x8A, 0x6B,
    0x3A, 0x91, 0x11, 0x41, 0x4F, 0x67, 0xDC, 0xEA, 0x97, 0xF2, 0xCF, 0xCE, 0xF0, 0xB4, 0xE6, 0x73,
    0x96, 0xAC, 0x74, 0x22, 0xE7, 0xAD, 0x35, 0x85, 0xE2, 0xF9, 0x37, 0xE8, 0x1C, 0x75, 0xDF, 0x6E,
    0x47, 0xF1, 0x1A, 0x71, 0x1D, 0x29, 0xC5, 0x89, 0x6F, 0xB7, 0x62, 0x0E, 0xAA, 0x18, 0xBE, 0x1B,
    0xFC, 0x56, 0x3E, 0x4B, 0xC6, 0xD2, 0x79, 0x20, 0x9A, 0xDB, 0xC0, 0xFE, 0x78, 0xCD, 0x5A, 0xF4,
    0x1F, 0xDD, 0xA8, 0x33, 0x88, 0x07, 0xC7, 0x31, 0xB1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xEC, 0x5F,
    0x60, 0x51, 0x7F, 0xA9, 0x19, 0xB5, 0x4A, 0x0D, 0x2D, 0xE5, 0x7A, 0x9F, 0x93, 0xC9, 0x9C, 0xEF,
    0xA0, 0xE0, 0x3B, 0x4D, 0xAE, 0x2A, 0xF5, 0xB0, 0xC8, 0xEB, 0xBB, 0x3C, 0x83, 0x53, 0x99, 0x61,
    0x17, 0x2B, 0x04, 0x7E, 0xBA, 0x77, 0xD6, 0x26, 0xE1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0C, 0x7D,
)

_RCON = (0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36, 0x6C, 0xD8, 0xAB, 0x4D)


def _xtime(a: int) -> int:
    """Multiply by x in GF(2^8) (reduction polynomial x^8+x^4+x^3+x+1)."""
    return ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else (a << 1) & 0xFF


# Multiply-by-2 table; the other MixColumns coefficients are built from it
# (3 = 2^1, 9 = 8^1, 11 = 8^2^1, 13 = 8^4^1, 14 = 8^4^2) so the hot
# path is table lookups, not a bit loop per byte.
_XT = tuple(_xtime(i) for i in range(256))


def _gmul(a: int, b: int) -> int:
    """Multiply two bytes in GF(2^8); ``b`` is a MixColumns coefficient."""
    if b == 1:
        return a
    if b == 2:
        return _XT[a]
    if b == 3:
        return _XT[a] ^ a
    # InvMixColumns coefficients, via repeated xtime: x2 = 2a, x4 = 4a, x8 = 8a.
    x2 = _XT[a]
    x4 = _XT[x2]
    x8 = _XT[x4]
    if b == 9:
        return x8 ^ a
    if b == 11:
        return x8 ^ x2 ^ a
    if b == 13:
        return x8 ^ x4 ^ a
    if b == 14:
        return x8 ^ x4 ^ x2
    raise ValueError("unsupported MixColumns coefficient: %r" % (b,))


# ---------------------------------------------------------------------------
# Key expansion + block cipher (FIPS-197 §5).
# The state is a flat 16-byte list in column-major order:
# state[row + 4*col] = input[4*col + row].
# ---------------------------------------------------------------------------

def expand_key(key: bytes) -> list:
    """Expand a 16/24/32-byte key into its ``Nr + 1`` 16-byte round keys."""
    if len(key) not in (16, 24, 32):
        raise ValueError("AES key must be 16, 24 or 32 bytes, got %d" % len(key))
    nk = len(key) // 4
    nr = nk + 6
    words = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    for i in range(nk, 4 * (nr + 1)):
        temp = list(words[i - 1])
        if i % nk == 0:
            temp = temp[1:] + temp[:1]  # RotWord
            temp = [_SBOX[b] for b in temp]  # SubWord
            temp[0] ^= _RCON[i // nk - 1]
        elif nk > 6 and i % nk == 4:
            temp = [_SBOX[b] for b in temp]
        words.append([words[i - nk][j] ^ temp[j] for j in range(4)])
    return [bytes(sum(words[4 * r:4 * r + 4], [])) for r in range(nr + 1)]


def _add_round_key(state, round_key: bytes) -> None:
    for i in range(16):
        state[i] ^= round_key[i]


def _sub_bytes(state) -> None:
    for i in range(16):
        state[i] = _SBOX[state[i]]


def _inv_sub_bytes(state) -> None:
    for i in range(16):
        state[i] = _INV_SBOX[state[i]]


def _shift_rows(state) -> None:
    # Row r shifts left by r: new[r + 4c] = old[r + 4((c + r) % 4)].
    old = list(state)
    for r in range(4):
        for c in range(4):
            state[r + 4 * c] = old[r + 4 * ((c + r) % 4)]


def _inv_shift_rows(state) -> None:
    old = list(state)
    for r in range(4):
        for c in range(4):
            state[r + 4 * c] = old[r + 4 * ((c - r) % 4)]


def _mix_columns(state) -> None:
    for c in range(4):
        i = 4 * c
        s0, s1, s2, s3 = state[i], state[i + 1], state[i + 2], state[i + 3]
        state[i] = _gmul(s0, 2) ^ _gmul(s1, 3) ^ s2 ^ s3
        state[i + 1] = s0 ^ _gmul(s1, 2) ^ _gmul(s2, 3) ^ s3
        state[i + 2] = s0 ^ s1 ^ _gmul(s2, 2) ^ _gmul(s3, 3)
        state[i + 3] = _gmul(s0, 3) ^ s1 ^ s2 ^ _gmul(s3, 2)


def _inv_mix_columns(state) -> None:
    for c in range(4):
        i = 4 * c
        s0, s1, s2, s3 = state[i], state[i + 1], state[i + 2], state[i + 3]
        state[i] = _gmul(s0, 14) ^ _gmul(s1, 11) ^ _gmul(s2, 13) ^ _gmul(s3, 9)
        state[i + 1] = _gmul(s0, 9) ^ _gmul(s1, 14) ^ _gmul(s2, 11) ^ _gmul(s3, 13)
        state[i + 2] = _gmul(s0, 13) ^ _gmul(s1, 9) ^ _gmul(s2, 14) ^ _gmul(s3, 11)
        state[i + 3] = _gmul(s0, 11) ^ _gmul(s1, 13) ^ _gmul(s2, 9) ^ _gmul(s3, 14)


def encrypt_block(round_keys, block: bytes) -> bytes:
    """AES encrypt one 16-byte block under pre-expanded ``round_keys``."""
    if len(block) != 16:
        raise ValueError("AES block must be 16 bytes, got %d" % len(block))
    nr = len(round_keys) - 1
    state = list(block)
    _add_round_key(state, round_keys[0])
    for rnd in range(1, nr):
        _sub_bytes(state)
        _shift_rows(state)
        _mix_columns(state)
        _add_round_key(state, round_keys[rnd])
    _sub_bytes(state)
    _shift_rows(state)
    _add_round_key(state, round_keys[nr])
    return bytes(state)


def decrypt_block(round_keys, block: bytes) -> bytes:
    """AES decrypt one 16-byte block under pre-expanded ``round_keys``."""
    if len(block) != 16:
        raise ValueError("AES block must be 16 bytes, got %d" % len(block))
    nr = len(round_keys) - 1
    state = list(block)
    _add_round_key(state, round_keys[nr])
    for rnd in range(nr - 1, 0, -1):
        _inv_shift_rows(state)
        _inv_sub_bytes(state)
        _add_round_key(state, round_keys[rnd])
        _inv_mix_columns(state)
    _inv_shift_rows(state)
    _inv_sub_bytes(state)
    _add_round_key(state, round_keys[0])
    return bytes(state)


# ---------------------------------------------------------------------------
# Modes: CTR (MEGA file bytes) and CBC (the ``at`` attributes blob).
# ---------------------------------------------------------------------------

def _counter_block(counter: bytes, block_index: int) -> bytes:
    """``counter`` incremented by ``block_index`` as a 128-bit big-endian int."""
    value = int.from_bytes(counter, "big") + block_index
    if value >= (1 << 128):
        raise ValueError("CTR counter overflow")
    return value.to_bytes(16, "big")


def ctr_crypt(key: bytes, counter: bytes, data: bytes) -> bytes:
    """AES-CTR encrypt/decrypt (the two are the same operation).

    ``counter`` is the 16-byte counter for the FIRST block of ``data``;
    it increments by one per 16-byte block. ``data`` may be any length —
    a trailing partial block takes the leading bytes of its keystream
    block. ``key`` is 16/24/32 bytes.
    """
    if len(counter) != 16:
        raise ValueError("CTR counter must be 16 bytes, got %d" % len(counter))
    round_keys = expand_key(key)
    out = bytearray()
    for offset in range(0, len(data), 16):
        chunk = data[offset:offset + 16]
        keystream = encrypt_block(round_keys, _counter_block(counter, offset // 16))
        out.extend(b ^ k for b, k in zip(chunk, keystream))
    return bytes(out)


def mega_ctr_crypt(key: bytes, iv: bytes, data: bytes, start_block: int = 0) -> bytes:
    """AES-CTR decrypt for MEGA file bytes.

    ``key`` is the 16-byte AES key from :func:`unmerge_key`, ``iv`` the
    8-byte CTR IV from the same unmerge, ``data`` ciphertext starting on a
    16-byte block boundary of the file, ``start_block`` that block's index
    (``byte_offset // 16``). The counter for a block is
    ``iv || 64-bit big-endian block index`` — the same layout as
    ``MegaCrypto.counterFor`` on the app side. Raises :exc:`ValueError`
    on a bad IV or a negative start block.
    """
    if len(iv) != 8:
        raise ValueError("MEGA CTR IV must be 8 bytes, got %d" % len(iv))
    if start_block < 0:
        raise ValueError("start_block must be >= 0, got %d" % start_block)
    counter = iv + start_block.to_bytes(8, "big")
    return ctr_crypt(key, counter, data)


def cbc_encrypt(key: bytes, iv: bytes, data: bytes) -> bytes:
    """AES-CBC encrypt; ``data`` must be a multiple of 16 bytes (no padding
    added — callers pad, e.g. with zero bytes, as MEGA's attrs do)."""
    if len(iv) != 16:
        raise ValueError("CBC IV must be 16 bytes, got %d" % len(iv))
    if len(data) % 16:
        raise ValueError("CBC data must be a multiple of 16 bytes, got %d" % len(data))
    round_keys = expand_key(key)
    out = bytearray()
    prev = iv
    for offset in range(0, len(data), 16):
        block = bytes(b ^ p for b, p in zip(data[offset:offset + 16], prev))
        prev = encrypt_block(round_keys, block)
        out.extend(prev)
    return bytes(out)


def cbc_decrypt(key: bytes, iv: bytes, data: bytes) -> bytes:
    """AES-CBC decrypt; ``data`` must be a multiple of 16 bytes."""
    if len(iv) != 16:
        raise ValueError("CBC IV must be 16 bytes, got %d" % len(iv))
    if len(data) % 16:
        raise ValueError("CBC data must be a multiple of 16 bytes, got %d" % len(data))
    round_keys = expand_key(key)
    out = bytearray()
    prev = iv
    for offset in range(0, len(data), 16):
        block = data[offset:offset + 16]
        out.extend(b ^ p for b, p in zip(decrypt_block(round_keys, block), prev))
        prev = block
    return bytes(out)


# ---------------------------------------------------------------------------
# MEGA link-key handling (mirrors MegaCrypto.kt exactly).
# ---------------------------------------------------------------------------

def decode_link_key(text) -> bytes | None:
    """Decode a 43-char URL-safe base64 link key (no padding) to 32 bytes.

    Returns None when the string is not a key at all — a malformed
    descriptor must fail before any media byte is fetched, never mid-play.
    """
    if not text or not isinstance(text, str):
        return None
    padded = text.strip()
    padded += "=" * ((4 - len(padded) % 4) % 4)
    try:
        raw = base64.urlsafe_b64decode(padded.encode("ascii"))
    except (binascii.Error, ValueError, UnicodeEncodeError):
        return None
    return raw if len(raw) == 32 else None


def unmerge_key(link_key: bytes):
    """Unmerge a 32-byte link key into ``(aes_key, iv, mac_key)``.

    ``aes_key = link_key[0:16] XOR link_key[16:32]``; ``iv = link_key[16:24]``;
    ``mac_key = link_key[24:32]`` (the MAC key is carried for the future
    chunk-MAC verification and is unused for playback — same as
    ``MegaCrypto.unmergeKey``). Raises :exc:`ValueError` unless 32 bytes.
    """
    if len(link_key) != 32:
        raise ValueError("a mega link key is 32 bytes, got %d" % len(link_key))
    aes_key = bytes(a ^ b for a, b in zip(link_key[:16], link_key[16:]))
    return aes_key, link_key[16:24], link_key[24:32]


def decrypt_attrs(attrs_b64url, aes_key: bytes):
    """Decrypt the ``at`` attributes blob; return the plaintext or None.

    AES-128-CBC, zero IV, zero padding: the plaintext starts with ``MEGA``
    followed by JSON. None on a bad key, a bad blob, or a non-``MEGA``
    plaintext — a wrong key must fail fast here, never play garbage.
    Same contract as ``MegaCrypto.decryptAttrs``.
    """
    if not attrs_b64url or not isinstance(attrs_b64url, str):
        return None
    padded = attrs_b64url.strip()
    padded += "=" * ((4 - len(padded) % 4) % 4)
    try:
        ciphertext = base64.urlsafe_b64decode(padded.encode("ascii"))
    except (binascii.Error, ValueError, UnicodeEncodeError):
        return None
    if not ciphertext or len(ciphertext) % 16:
        return None
    try:
        plain = cbc_decrypt(aes_key, bytes(16), ciphertext)
    except ValueError:
        return None
    end = plain.find(b"\x00")
    text = plain if end < 0 else plain[:end]
    try:
        decoded = text.decode("utf-8")
    except UnicodeDecodeError:
        return None
    return decoded if decoded.startswith("MEGA{") else None


def decrypt_mega_bytes(ciphertext: bytes, link_key_b64: str, start_block: int = 0) -> bytes:
    """One-call decrypt: decode + unmerge ``link_key_b64``, AES-CTR-decrypt
    ``ciphertext`` (which must start on a 16-byte block boundary, see
    :func:`mega_ctr_crypt`). Raises :exc:`ValueError` on a bad key."""
    link_key = decode_link_key(link_key_b64)
    if link_key is None:
        raise ValueError("not a 32-byte mega link key")
    aes_key, iv, _mac_key = unmerge_key(link_key)
    return mega_ctr_crypt(aes_key, iv, ciphertext, start_block)


# ---------------------------------------------------------------------------
# Container magic (same three checks as MegaCrypto.isContainerMagic).
# ---------------------------------------------------------------------------

def is_container_magic(head: bytes) -> bool:
    """True when decrypted bytes look like a media container, not random
    bytes: EBML (``1a45dfa3``), MP4 (``ftyp`` at offset 4), WebM/AVI
    (``RIFF``)."""
    if len(head) >= 4 and head[:4] == b"\x1a\x45\xdf\xa3":
        return True
    if len(head) >= 8 and head[4:8] == b"ftyp":
        return True
    if len(head) >= 4 and head[:4] == b"RIFF":
        return True
    return False
