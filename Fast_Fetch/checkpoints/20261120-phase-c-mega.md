# Phase C — mega.nz (MEGA) — 2026-11-20

Branch: `muse/phase-c-mega-20261120`, base main `8f7948d2` (H4 R8).
Builder: host-crack lane. No merge, no push to main, no deploy, no live-DB writes.

## Verdict: NOT CRACKED as a playable host — with proof

The public-link API leg works end to end (no account, no CAPTCHA), and the
key derivation is proven correct — but the bytes MEGA serves are AES-CTR
ciphertext, and no URL the server can hand the player plays. The only path to
real playback is a decrypting proxy, which is blocked three independent ways
(see "Blockers"). This is a result, not a failure to probe: the task's own
rule says so ("client-side decryption the server cannot do, say so plainly
with evidence").

## Catalog facts (live DB, read-only, 2026-11-20 ~02:45 PKT)

- `service='mega'`: **68,507** rows — unverified 68,306 / active 201.
- `source_url LIKE '%mega.nz%'`: 7,999 rows (4,211 `/file/`, 62 `/folder/`,
  3,725 old-style `mega.nz/#!...`); the rest carry the mega link in
  `final_url` (68,319 rows).
- Sample probed: `https://mega.nz/file/78QnEaBK#<key>` (active row).
- The app already treats mega as download-only: `GoldenVectors.kt`
  `service="mega"` → `kind=download_only|playability=download|tier=2`.
- `host_classifier.py` already documents the shape gate: any fragment →
  False; mega.nz → False for every path — "all mega downloads require the
  fragment key; the host can NEVER serve a naive byte GET" — and a
  fragment-bearing cache row risks leaking the key through a public play_url.

## Research (done BEFORE any code — sources)

- MEGA public file link: `https://mega.nz/file/{node_id}#{key}`; the key is
  32 bytes, URL-safe base64 without padding. Folder links carry a 16-byte key.
  The fragment is never sent to the server (RFC 3986 §3.5); the key lives
  client-side only.
  - https://deepwiki.com/chanderlud/giga-grabber/3.4-mega-client-integration
  - https://deepwiki.com/chanderlud/giga-grabber/8-security-and-encryption
  - https://shec-labs.com/the-mega-decryption-key-explained-why-the-part-after-the-never-reaches-the-server/
- Public API, no account: `POST https://g.api.mega.co.nz/cs` with
  `[{"a":"g","g":1,"p":"<node_id>"}]` returns `{"g": "<gfs download URL>",
  "at": "<encrypted attrs>", "s": <size>, ...}`. Error codes: -9 not found,
  -11 access violation, -16 banned uploader.
  - https://mega.js.org/docs/1.0/api (gateway defaults to
    `https://g.api.mega.co.nz/`)
  - https://deepwiki.com/3v1n0/mega.py (deprecated; points at official SDK)
  - RapidLeech mega plugin (pastebin raw FdtQ7Dem): same `a=g, g=1, p=<id>`
    call, anonymous until the transfer limit bites.
- Key handling: unmerge by XORing the first 16 bytes with the second 16;
  bytes 0–15 = AES-128 key, bytes 16–23 = CTR IV, bytes 24–31 = MAC.
  Attributes `at` (URL-safe base64) decrypt AES-128-CBC, zero IV, plaintext
  starts with `MEGA` then JSON `{"n": "<filename>"}`. File content decrypts
  AES-128-CTR, counter = IV (8 bytes) + 8 zero bytes, big-endian increment,
  seekable.
  - giga-grabber deepwiki pages above; mega.py `crypto.py` (aes_cbc_decrypt,
    decrypt_attr, CTR download).
- yt-dlp has NO Mega extractor (searched; nothing exists upstream) —
  nothing to reuse there. megatools `megadl` and mega.py both download +
  decrypt client-side; neither yields a playable URL.
  - https://manpages.opensuse.org/Leap-15.6/megatools/megatools.1.en.html
- Transfer quota is per-IP, not per-account: free ~5 GB per 6 h; anonymous
  stricter (reports of 1–2 GB/day). Paid Pro plans are the only permanent
  raise (money = owner-only).
  - https://whoerip.com/blog/download-from-mega-without-limit/
  - https://thugapps.com/posts/3-vpn-tools-that-bypass-mega-download-limits-for-mods/
  - https://www.americantv.com/how-to-bypass-mega-download-limit-quotas-in-2025.php
- Streaming a MEGA video in a browser/app means downloading + decrypting
  client-side (MEGA web client, MEGAsync "stream", VLC via the app); there
  is no plaintext media endpoint.
  - https://www.androidinfotech.com/stream-mega-nz-videos/

## Probe (Oracle VM egress, light: 2 API calls + 2× 64-byte Range GETs)

Scripts: `/tmp/mega-probe.py`, `/tmp/mega-probe2.py` (hatch) — also copied to
the VM `/tmp`; probe code only, not committed. No key or full gfs URL is
recorded anywhere below or in the repo.

Probe 1 (2026-11-20 ~02:47 PKT), link node `78QnEaBK`:
- `POST g.api.mega.co.nz/cs` → HTTP 200, keys `s, at, msd, fa, g, ip, fh`;
  `s = 944,948,612` bytes (~901 MB); `g` host
  `gfs206n481.userstorage.mega.co.nz`.
- `GET <gfs URL>` `Range: bytes=0-63` → HTTP 206,
  `Content-Type: application/octet-stream`, 64 bytes. First 32 bytes are
  random-looking: `04704844…` — NOT `ftyp`, NOT EBML. Ciphertext confirmed.
- Attr decrypt with standard-base64 `at` FAILED ("Incorrect padding") —
  first miss, fixed by research: `at` is URL-safe base64 (it contains `-/_`).

Probe 2 (~02:52 PKT), same link:
- `at` URL-safe-decoded (96 bytes) → AES-128-CBC, zero IV → plaintext
  `MEGA{"c":"1DMyjGcL3qf9jiqhr7nJzQQ0s-lp","n":"Scarlet (2025)
  [RareToonsIndia].mkv"}` (zero padding stripped). Filename + size proven.
- Same 64 ciphertext bytes → AES-128-CTR (key + IV from the unmerged link
  key, counter IV+8 zero bytes) via `openssl enc -d -aes-128-ctr` →
  plaintext starts `1a45dfa3…matroska` — EBML magic + "matroska". The file
  IS a real MKV under AES-CTR. Key derivation proven correct end to end.
- Environment on the VM (system python3 3.12.3, OpenSSL 3.0.13):
  `Crypto` missing, `cryptography` AVAILABLE, `libcrypto.so.3` findable via
  ctypes. (Availability on the system interpreter says nothing about the
  service venv, and none of it is stdlib.)

## Why "not cracked" — the three blockers

1. **No playable URL exists.** The gfs URL serves ciphertext. ExoPlayer
   cannot play AES-CTR-encrypted bytes, and there is no MEGA plaintext
   endpoint. A resolver returning the gfs URL would 302 to garbage the
   player cannot decode — a false crack. (This is also why the classifier
   already bars mega from `kind='file'` banking.)
2. **No AES in the Python stdlib.** A server-side decrypting proxy would
   need AES-CTR at streaming speed. Stdlib has none (`hashlib` is hashes
   only; `ssl` exposes no bulk cipher). Remaining routes are all outside
   the standing rule: pure-Python AES (~1–3 MB/s, far too slow and CPU-hot
   on a 1-vCPU box), ctypes → system libcrypto (fragile, version-pinned,
   not really "stdlib"), or a third-party package (`cryptography` happens
   to be installed on the VM's system Python — not stdlib, not proven
   present in the service venv).
3. **The IP quota kills a shared relay even if (2) were solved.**
   Anonymous/free transfer is ~5 GB per 6 h per egress IP. The Oracle VM
   has ONE egress IP shared by every user; the probed file alone is
   ~0.9 GB, so ~5 plays exhaust mega for everyone until reset. A paid
   MEGA Pro account (money, owner-only) is the only way to raise it
   server-side.

A decrypting relay would also be a new architecture (the service today
302-redirects; a relay would stream full video bytes through the VM), and
banking rules forbid serving a fragment-bearing URL as a play URL (key
leak through a public handle).

## What would unblock it (owner/coordinator decision, not this lane)

- **Client-side (app) decryption — the viable path.** Android has AES
  natively (`javax.crypto`); the app already has a loopback
  `LocalMediaProxy` that serves bytes to Media3. The app could fetch the
  gfs ciphertext, AES-CTR-decrypt on device, and serve plaintext to the
  player over loopback. Quota then applies per user's own IP, not the
  shared server IP. This needs: a new API contract to deliver the fragment
  key to the app without leaking it through a public play_url, an
  ExoPlayer/DataSource-side decryptor (new app feature), and Range/seek
  handling through the CTR counter. Sizeable app + API work — not a
  server-side stdlib resolver.
- **Or a paid MEGA Pro account + a sanctioned decrypting proxy service**
  (money + new service + AES dependency) — owner-only, and still a relay.
- **Not** unblocked by: retrying the API, another sample link, a browser
  replay, or yt-dlp (no extractor exists).

## CI (branch `muse/phase-c-mega-20261120`)

- Commit `9dfe2f51` pushed; Backend CI run **44622054076** FAILED (~02:52 PKT)
  on `test_docs_invariants.py` (2 failures, both mine, not flakes):
  (1) CONTINUITY.md claimed 122 archive files but `checkpoints/` held 123
  after my new file; (2) my one-line CONTINUITY entry put it at 12,417 B,
  over the 12,000 B index ceiling.
- Fix (fixup commit, no amend/force-push): count claim corrected to
  "123 session files (120 dated + 3 undated)"; the long CONTINUITY line
  removed (file back to 11,974 B / 92 lines); the session entry indexed in
  `checkpoints/README.md` (newest first) instead, which is where long
  entries live. `test_docs_invariants.py` 22/22 green locally after the fix.
- Commit `74383a17` pushed; Backend CI run **44623468368** FAILED (~02:58 PKT)
  on `test_ci_manifest.py::test_manifest_is_never_a_subset_of_trunks`:
  `['Fast_Fetch/test_resolver_probes.py'] != []` — NOT my code: H4 R9 had
  merged to main (`44631e10`) while this lane worked, adding that gate step
  to trunk, and my branch (based on `8f7948d2`) lacked it.
- Fix: merged main `44631e10` into the branch (merge commit `7e043911`,
  no rebase — the branch was already pushed and force-push is banned);
  the merge auto-resolved cleanly (both README entries kept, mega first).
  The merged tree holds 124 checkpoint files, so the count claim was
  corrected again to "124 session files (121 dated + 3 undated)"
  (CONTINUITY 11,992 B / 90 lines, still under budget). Local after merge:
  `test_docs_invariants` 22/22, `test_ci_manifest` 10/10,
  `test_host_resolvers` 303/303.

## Files changed

- `Fast_Fetch/checkpoints/20261120-phase-c-mega.md` (this file).
- `Fast_Fetch/importers/host_resolvers.py` — MEGA entry added to the
  "HOSTS DELIBERATELY NOT IMPLEMENTED" section with the measurements, so
  the verdict is auditable in code and not re-argued.
- No resolver, no tests, no CI run: there is no working playable chain to
  build or gate. No live-DB writes; no service restarts; no `/opt/radd-stream`
  contact.
