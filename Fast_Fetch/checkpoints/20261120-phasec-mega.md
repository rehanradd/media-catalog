# 2026-11-20 — Phase C mega.nz (Lane F) `[PHASEC-MEGA]`

Branch `muse/phasec-mega-20261120` from `8f7948d2` (main re-checked 02:32 PKT:
`78c66197`; H4 R8 `8f7948d2` landed while this lane ran, worktree reset to it
before any commit). **VERDICT: NOT VIABLE as a stdlib-only resolver. No
resolver code was built, no tests were registered, no CI run exists — by the
task's own stop rule.** The chain itself works end-to-end (proven live below);
what kills it is (1) pure-Python AES speed on the serving box and (2) the
resolver contract: the server 302s the player straight to `final_url`
("zero bytes through this box"), and mega's URL serves AES-CTR ciphertext,
so a plain `unwrap_mega` could never hand the player a playable row.

## 1. Research — the chain (sources at §6)

- Share link: `https://mega.nz/file/<id>#<key>` (old form `/#!<id>!<key>`).
  Fragment key = 32 bytes base64url: 16-byte AES key + 8-byte IV + 8-byte MAC.
  Real AES key = XOR-fold of the 8 big-endian 32-bit words into 4
  (`w0^w4, w1^w5, w2^w6, w3^w7`).
- API: `POST https://g.api.mega.co.nz/cs?id=<seq>` body
  `[{"a":"g","g":1,"p":"<id>"}]` → `[{"g": <download URL>, "s": size,
  "at": <base64 attrs>, "fa": ..., "fh": ..., "ip": ..., "msd": ...}]`.
  Anonymous, no account, no key. Errors are a bare list of negative ints:
  `-9 ENOENT` (gone), `-6 ETOOMANY` (too many IPs on this resource — retry,
  not dead), `-4 ERATELIMIT`, `-17 EOVERQUOTA` (per-IP bandwidth cap).
- Attributes: AES-128-CBC, zero IV, no padding; plaintext is
  `MEGA{"n":"<filename>",...}` (NUL-padded).
- Content: AES-128-CTR, IV = 8-byte IV + 8 zero bytes, counter incremented
  as a 128-bit integer; chained CBC-MAC per chunk for integrity (not needed
  to play, only to verify). CTR decrypt needs only the AES FORWARD cipher.
- No public API returns already-decrypted bytes — mega is zero-knowledge by
  design; every client (mega.py, megadown, megafetch, Kodi addon) decrypts
  locally. Nothing to call instead.
- Stdlib has no AES (`hashlib`/`ssl` expose none). The only stdlib-only
  route is a vendored pure-Python AES. pyaes is ~300x slower than PyCrypto
  for CTR in CPython (its own README); purepycrypto claims ~1.1 MB/s for
  AES-GCM-SIV but is a large third-party vendoring, not a small module.
  openssl CLI (`openssl enc -d -aes-128-ctr/-cbc`) is how megadown and
  megafetch do it — it is NOT a pip install, but it is a subprocess call to
  an OS binary, and there is NO precedent for `subprocess`/`openssl`
  anywhere in `fast_fetch.py`, `viewer.py` or `host_resolvers.py` (grep,
  20 Nov). Whether that violates the stdlib-only rule is the coordinator's
  call — this lane did NOT assume it either way.

## 2. Audit — live DB, read-only, from the Oracle VM

- `download_sources WHERE final_url LIKE '%mega.nz%'`: **68,319 rows**
  (brief said 68,507; re-measured 20 Nov) — 194 `active`, 68,125
  `unverified`. No standalone resolver exists; the only "mega" hits in the
  repo are a hubcloud *button label* (`"mega": "Mega"`, host_resolvers.py
  ~4054–4111) — a different host's UI text, not mega.nz.
- `78QnEaBK`: API 200, size 944,948,612 (~901 MB), `g` URL present
  (`http://gfs206n481.userstorage.mega.co.nz/dl/...`), `at` 128 chars.
- `2rgSQQaK`: size 842,388,783, `g` present — live.
- `Z1YyWD6C`: API answered `[-6]` ETOOMANY — rate/concurrency signal,
  NOT dead; retry later.
- Bogus id `AAAAAAA1`: `[-9]` ENOENT — clean dead signal for a future
  resolver's error table.

## 3. Proof — decrypt works; speed does not (Oracle VM, the serving box)

openssl CLI used here as a MEASUREMENT tool only, never shipped code:

- Attributes decrypted (CBC, zero IV): `MEGA{"n":"Scarlet (2025)
  [RareToonsIndia].mkv",...}` — real filename, key derivation confirmed.
- First 512 bytes of the `g` URL (HTTP 206) decrypted (CTR):
  `1a45dfa3a3428681…6d6174726f736b61` — EBML/matroska magic + the literal
  string `matroska`. Video bytes confirmed from a real stored file.
- Pure-Python AES-128 CTR (T-table implementation, FIPS-197 vector
  `69c4e0d86a7b0430d8cdb78070b4c55a` verified) decrypts the SAME first
  512 bytes byte-identically to openssl (`MATCH True`). The crypto is
  right; only speed kills it.

Throughput, 1 MiB CTR decrypt, CPython 3.12.3, best-case T-table code:

| box | MB/s | ms/MiB |
|---|---|---|
| hatch VM (shared, 4 vCPU) | 0.399 | 2,507 |
| Oracle VM (1 vCPU, the serving box) | **0.060** | **16,614** |

Video bitrate floor: 480p ~0.19 MB/s, 720p ~0.38 MB/s, 1080p ~0.75 MB/s
(typical 1.5 / 3 / 6 Mbps). On the serving box pure-Python AES runs at
**3x slower than 480p real-time** — a 480p stream would stall constantly
— 6x slower than 720p, 12x slower than 1080p, while pinning the box's
single vCPU at 100% for ONE stream and starving the API itself. A naive
(pyaes-style, non-T-table) implementation would be slower still. Streaming
CTR never loads the whole file in RAM — that half of the constraint is
satisfiable; throughput is the blocker, not memory.

## 4. The two blockers, stated separately

1. **Speed.** No stdlib-only AES can stream-decrypt video on the 1-vCPU
   serving box. Measured, not asserted: 0.060 MB/s vs 0.19 MB/s minimum.
2. **Architecture.** Even at infinite AES speed, a resolver in
   `host_resolvers.py` returns a URL the server 302-redirects to. Mega's
   URL is ciphertext. Playing mega needs bytes decrypted somewhere: either
   a NEW server-side decrypting proxy endpoint (bytes through the box,
   new route + per-stream CPU + gate + load analysis — a separate lane,
   not a resolver), or app-side decrypt (Kotlin `javax.crypto` is native
   AES; ExoPlayer custom DataSource — needs an app lane and a way to pass
   the key without exposing the upstream URL, which the redact/provenance
   law currently forbids).

## 5. What is owed / coordinator decisions

- **Decision A (recommended): close mega.nz as not-viable for server-side
  resolve.** Keep the 68,319 rows `unverified`; do not re-dispatch this
  lane. The research + POC above is the evidence.
- **Decision B:** approve a decrypting-proxy design using the openssl CLI
  via subprocess (native speed; `/usr/bin/openssl` confirmed on the VM).
  Needs a fresh lane + owner-visible call on the stdlib-only rule, a new
  endpoint, and capacity analysis (one vCPU, per-stream decrypt cost).
- **Decision C (long-term, best fit): app-side decrypt.** Native AES on
  the phone, zero server CPU. Needs an app lane; key transport must be
  designed against the provenance law (never an upstream URL to the
  client).
- If B or C is ever built, the error table from §1–§2 applies: `-9`/`-16`
  dead, `-6`/`-4`/`-17` retry-with-backoff, `-3` retry; fetch the `g` URL
  fresh every resolve, never serve a stored presigned URL (it expires and
  the key is per-link).

## 6. Sources

- MEGAJS API reference (gateway `https://g.api.mega.co.nz/`, File.fromURL):
  https://mega.js.org/docs/1.0/api
- JS mega.nz resolver + AES decryptor (API `a='g', g=1`, key = 32 bytes):
  https://app.gistify.web.id/s/43348489-3746-470d-840f-8290269f8e27
- megafetch.sh (openssl CBC attrs + CTR content, key XOR):
  https://gist.github.com/Bijman/b791993783e2fb80d3e98a3a19b6e6e8
- megadown (openssl-based CLI): https://github.com/tonikelope/megadown/blob/master/megadown
- oc-proxy-downloader mega_crypto.py (protocol ref odwyersoftware/mega.py,
  Apache-2.0; uses PyCryptodome, NOT stdlib):
  https://github.com/jshsakura/oc-proxy-downloader/commit/8a81ce7e0462dc37da333e5bd5f77886a350c799
- pyaes performance (CTR ~300x slower than PyCrypto in CPython):
  https://pypi.org/project/pyaesfu/1.6.1/ and https://libraries.io/pypi/pyaes
- purepycrypto (pure-Python, ~1.1 MB/s AES-GCM-SIV claim):
  https://pypi.org/project/purepycrypto/
- MEGA error codes (`ERATELIMIT=-4`, `ETOOMANY=-6`, `ENOENT=-9`):
  https://docs.rs/mega/0.4.0/src/mega/error.rs.html
- mega.py (Python lib, PyCryptodome-based): https://pypi.org/project/mega.py/1.0.6/

## Appendix — benchmark script (reproducibility)

`/tmp/mega_aes_bench.py` on both VMs (ephemeral `/tmp`; preserved here so
the measurement can be re-run). T-table AES-128 forward cipher + CTR,
FIPS-197 self-check, 1 MiB throughput. The S-box is generated from GF(2^8)
math at import; key expansion is standard AES-128; the counter is a
128-bit big-endian integer starting at `(iv8 || 00*8)`.

```python
import os, struct, sys, time

def _xtime(a):
    a <<= 1
    if a & 0x100:
        a ^= 0x11B
    return a & 0xFF

def _gmul(a, b):
    r = 0
    while b:
        if b & 1:
            r ^= a
        a = _xtime(a)
        b >>= 1
    return r

def _inv(a):
    if a == 0:
        return 0
    r = 1
    for _ in range(254):
        r = _gmul(r, a)
    return r

def _rotl8(a, n):
    return ((a << n) | (a >> (8 - n))) & 0xFF

SBOX = []
for i in range(256):
    s = _inv(i)
    s = s ^ _rotl8(s, 1) ^ _rotl8(s, 2) ^ _rotl8(s, 3) ^ _rotl8(s, 4) ^ 0x63
    SBOX.append(s & 0xFF)

def _t_tables():
    te = [[], [], [], []]
    for x in range(256):
        s = SBOX[x]
        b = [_gmul(s, 2), s, s, _gmul(s, 3)]
        for r in range(4):
            k = (4 - r) % 4
            w = (b[k % 4] << 24) | (b[(k + 1) % 4] << 16) | (b[(k + 2) % 4] << 8) | b[(k + 3) % 4]
            te[r].append(w & 0xFFFFFFFF)
    return te

TE0, TE1, TE2, TE3 = _t_tables()
RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36]

def expand_key(key16):
    w = list(struct.unpack(">4I", key16))
    for i in range(4, 44):
        t = w[i - 1]
        if i % 4 == 0:
            t = ((SBOX[(t >> 16) & 0xFF] << 24) | (SBOX[(t >> 8) & 0xFF] << 16)
                 | (SBOX[t & 0xFF] << 8) | SBOX[(t >> 24) & 0xFF])
            t ^= RCON[i // 4 - 1] << 24
        w.append((w[i - 4] ^ t) & 0xFFFFFFFF)
    return w

def encrypt_block(rk, s0, s1, s2, s3):
    s0 ^= rk[0]; s1 ^= rk[1]; s2 ^= rk[2]; s3 ^= rk[3]
    for rnd in range(1, 10):
        o0 = TE0[s0 >> 24] ^ TE1[(s1 >> 16) & 0xFF] ^ TE2[(s2 >> 8) & 0xFF] ^ TE3[s3 & 0xFF] ^ rk[4 * rnd]
        o1 = TE0[s1 >> 24] ^ TE1[(s2 >> 16) & 0xFF] ^ TE2[(s3 >> 8) & 0xFF] ^ TE3[s0 & 0xFF] ^ rk[4 * rnd + 1]
        o2 = TE0[s2 >> 24] ^ TE1[(s3 >> 16) & 0xFF] ^ TE2[(s0 >> 8) & 0xFF] ^ TE3[s1 & 0xFF] ^ rk[4 * rnd + 2]
        o3 = TE0[s3 >> 24] ^ TE1[(s0 >> 16) & 0xFF] ^ TE2[(s1 >> 8) & 0xFF] ^ TE3[s2 & 0xFF] ^ rk[4 * rnd + 3]
        s0, s1, s2, s3 = o0, o1, o2, o3
    f0 = ((SBOX[s0 >> 24] << 24) | (SBOX[(s1 >> 16) & 0xFF] << 16)
          | (SBOX[(s2 >> 8) & 0xFF] << 8) | SBOX[s3 & 0xFF]) ^ rk[40]
    f1 = ((SBOX[s1 >> 24] << 24) | (SBOX[(s2 >> 16) & 0xFF] << 16)
          | (SBOX[(s3 >> 8) & 0xFF] << 8) | SBOX[s0 & 0xFF]) ^ rk[41]
    f2 = ((SBOX[s2 >> 24] << 24) | (SBOX[(s3 >> 16) & 0xFF] << 16)
          | (SBOX[(s0 >> 8) & 0xFF] << 8) | SBOX[s1 & 0xFF]) ^ rk[42]
    f3 = ((SBOX[s3 >> 24] << 24) | (SBOX[(s0 >> 16) & 0xFF] << 16)
          | (SBOX[(s1 >> 8) & 0xFF] << 8) | SBOX[s2 & 0xFF]) ^ rk[43]
    return struct.pack(">4I", f0, f1, f2, f3)

def ctr_crypt(key16, iv16, data):
    rk = expand_key(key16)
    ctr = int.from_bytes(iv16, "big")
    out = bytearray()
    for off in range(0, len(data), 16):
        chunk = data[off:off + 16]
        ks = encrypt_block(rk, *struct.unpack(">4I", ctr.to_bytes(16, "big")))
        out += bytes(a ^ b for a, b in zip(chunk, ks))
        ctr = (ctr + 1) & ((1 << 128) - 1)
    return bytes(out)
```

Key derivation used in the POC (from the link fragment, 32 base64url
bytes → 8 big-endian words → XOR-fold to the 16-byte AES key; CTR IV =
bytes 16:24 + 8 zero bytes); API call
`POST https://g.api.mega.co.nz/cs?id=<seq>` with
`[{"a":"g","g":1,"p":"<id>"}]`. Nothing in this file was committed as
repo code — it is a measurement appendix only.
