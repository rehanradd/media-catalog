# 2026-11-20 — Phase C lane E (hubcdn): stored bridges, the octet-stream gate, and the backfill

Branch: `muse/phasec-hubcdn-20261120` (base main `44631e10`, H4 R9; rebased
to `c27890db`, H4 R10). Goal: find out why 16,855 hubcdn rows are still
uncracked despite `unwrap_hubcdn` existing, fix/wire whatever is broken,
and crack the stored bridges in bulk.

## 0. Rotation (done first, before any other edit)

CONTINUITY.md was at 11,535 B / 86 lines. Rotated out the 2 oldest
"Rotated session history" entries (2026-11-12 self-check live-tv-503;
2026-11-16 project quick summary) — both still indexed in
`checkpoints/README.md`, nothing deleted. Corrected the H4 R10 entry from
"WIP, Backend CI pending" to merged `c27890db` (Backend CI 44625202961
SUCCESS). Added this lane's entry and bumped the archive count 124 → 125
(122 dated + 3 undated). Also added the missing H4 R10 entry to
`checkpoints/README.md` (newest first). After rotation: 11,737 B / 84 lines.

## 1. Diagnosis (measured live, Oracle VM, read-only queries only)

- The unwrapper is NOT broken and NOT unwired. `unwrap_hubcdn`
  (`fast_fetch.py`, with `_hubcdn_page` / `_hubcdn_hop`) works live: two
  stored gateway URLs unwrapped to both `pub-*.r2.dev` (R2) and
  `video-downloads.googleusercontent.com` (Google) assets. It is wired
  into `resolve_live_source` (hubcdn `elif` branch),
  `is_bridge_url()` returns True for all `hubcdn.io` URLs (permanent rows
  get `needs_refresh=True`), `current_hosts.classify_backend` returns
  `"unknown"` for hubcdn (stage-1 never claims it), and the host registry
  has no hubcdn entry (no rewrite misroute).
- Live DB profile (2026-11-20): 16,859 rows match hubcdn (service
  `hubcdn` 16,855; `greenmotors` 2; `hubcdn.fans` 2); status `active`
  16,857, `unverified` 2; `is_temporary=0` for ALL 16,859; source_url is
  `hubcdn.io/cloud/…` for 16,855; final_url is also the cloud page for
  16,846; ALL 16,855 source_urls are `/cloud/eyJ…` blobs; URL length
  179–402. `last_checked_at` NULL for 16,725, set for 130;
  `last_failed_at` set for 0 rows.
- The stored `/cloud/<id>` is a base64 JSON blob `{iv, tag, data}` —
  AES-GCM shape. Decoded sample (id 289156): iv 12 bytes, tag 16 bytes,
  data 40 bytes ciphertext (40-byte plaintext). No client-side decryption
  is needed or attempted: the gateway decrypts server-side and renders
  `dl-trigger` buttons. Python stdlib has no AES-GCM; inventing a
  pure-python one would be wrong.
- The rows are uncracked only as stored: the hdtvb importer
  (`import_hdtvb.py`) files every cloud link as `source_url == final_url
  == https://hubcdn.io/cloud/<id>`, `status='active'`,
  `is_temporary=0`, and `--resolve-direct` defaults off (only with it
  does the importer store the R2 asset as a separate `cloudflare_r2` row
  via `resolve_hubcdn_direct`, which itself still works live — it
  returned the same R2 URL as `unwrap_hubcdn`).
- 9 live rows already prove the press path: service `hubcdn`,
  source_url = cloud page, final_url = `pub-*.r2.dev/…`,
  `last_checked_at` 2026-11-18/19 (e.g. ids 6170620, 6174604, 6180757).

## 2. Research (online, before building)

- Searched several phrasings + typo variants for hubcdn.io cloud blob
  decryption / hubcdn downloader / hdhub4u. No public hubcdn blob-decrypt
  implementation found; the blob is server-side.
- Found via DeepWiki: `tapframe/NuvioStreamsAddon` has
  `hubCdnExtractor(url, referer)` in `providers/hdhub4u.js` (dispatched by
  hostname containing `hubcdn`), but DeepWiki does not document its body.
  The live gateway page itself is the authoritative source and matches
  `unwrap_hubcdn`'s model.
- Sources:
  - https://deepwiki.com/tapframe/NuvioStreamsAddon/4.3-web-scraping-providers:-4khdhub-moviesdrive-hdhub4u
  - https://deepwiki.com/tapframe/NuvioStreamsAddon/7.1-standalone-scrapers

## 3. Second gateway shape: "Preparing Download" → filesdl (browser-only)

- ~8% of the sample: the gateway answers a "Preparing Download" countdown
  page (10 s, no `dl-trigger` buttons) whose inline JS carries
  `var targetUrl="https://new1.filesdl.in/drive/<id>"`. Re-fetching after
  12 s returns the same page (countdown is client-side; the target is
  already in the HTML).
- The filesdl target is uncrackable with urllib: GET
  `https://new1.filesdl.in/drive/nm27vVUjJT` (2026-11-20) returned 202
  HTML, redirected to `new8.filesdl.top`, containing a Cloudflare JS
  challenge (`__CF$cv$params`, slowAES cookie, redirect to `?d=1`). Needs
  a real browser — same class as VidCore (per memory). One probe only; do
  not blind-retry with urllib.
- Fix landed: `host_classifier.hubcdn_preparing_target(html)` extracts
  the target as evidence only; `unwrap_hubcdn` now returns
  `error="preparing_download"` with `preparing_target` attached when a
  buttonless page carries one (otherwise still `no_dl_trigger_buttons`);
  the target is never served as a playable address. The backfill counts
  these separately as `preparing`.

## 4. The R2 404 scare — a truncation artifact, not dead objects (corrected)

- probe10 (2026-11-20 ~03:07 PKT): ranged GETs of R2 URLs returned 404
  for 294973, 6169709, 6169755 — but those URLs were printed by probe9
  with a `[:60]` truncation, so I probed truncated URLs, not the real
  ones. Do NOT cite probe10's 404s as evidence of anything.
- probe11 correction: re-unwrap 294973 → same key prefix, full 32-hex
  URL 206 `video/mkv`; re-unwrap 289156 → the identical URL as 40+ min
  earlier; 5/5 live R2 final_urls stored 18–19 Nov → 206 `video/mkv` +
  EBML magic now. R2 URLs are STABLE per cloud id and DURABLE ≥1–2 days.
  The unwrap docstring's "durable Category A bytes" stands.

## 5. The real blocker: the Stage-2 gate refuses octet-stream R2 assets

- probe12: 6169709 / 6169755 / 290696 R2 URLs → 206,
  `application/octet-stream`, EBML magic `1a45dfa3`, sizes 999,872,522 /
  3,519,196,100 / 16,211,009,041 → `_stage2_probe` verdict
  `failure="unverified"`.
- Root cause: `current_hosts.classify_probe`'s file branch accepts
  `application/octet-stream` ONLY when the URL path ends with a media
  suffix (`_MEDIA_SUFFIXES`); R2 URLs are extension-less
  (`/f67a6cd2…`). The whole 6.1M id block (~80 rows) + scattered low-id
  rows failed the gate this way.
- The hubcdn branch in `resolve_live_source` sets no `embed_verdict`,
  so `_gate_from_verdict` is False and every fresh hubcdn URL goes
  through the generic `_stage2_probe` — the gate is the press path, not
  just the backfill.
- Press baseline (probe13, pre-fix, staging): 6169709 → `success: False`,
  `code: no_playable_source`. 290696 succeeded via a failover sibling
  (`pub-423049f6…/5845d335…`, `video/mkv`), NOT its own unwrap — its own
  unwrap (probe14, twice) deterministically returns
  `pub-5703108c…/aed3443e…` (octet-stream).

## 6. The fix

- `current_hosts._video_mime_from_magic(body)`: EBML (`1a45dfa3`) →
  `video/x-matroska` (WebM shares the EBML shell; the catalog's hubcdn
  assets are .mkv, so the Matroska mime is reported), `ftyp` at offset 4
  → `video/mp4`, `RIFF` + `AVI ` → `video/x-msvideo`, else None.
  `classify_probe`: `application/octet-stream` passes when the suffix
  matches (unchanged) OR the magic proves a container; the verdict mime
  becomes the container mime so the H4 R6 tier ranking reads the copy as
  video, not as an unknown octet-stream. Precedent: `unwrap_uploadflix`
  already treats "206 octet-stream with EBML magic" as a video
  (`test_database.py`, Session 104b).
- `fast_fetch.RESOLVER_VERSION` 5 → 6 with a comment entry: rows that
  failed under v5 with `unverified` deserve one fresh press — the version
  law clears their stale failure records on the next press.
- Press proof after the fix (probe15, staging, id 6169709): `success:
  True`, URL `https://pub-5703108c07374cf4bf82a6a6e2041364.r2.dev/f67a6cd2ba8d4be51a6e5530be7edb7c`,
  `mime: video/x-matroska`, `resumable: True`; row persisted as
  final_url R2, `mime_type` video/x-matroska, `http_status` 206,
  `is_resumable` 1, `status` active, `last_failed_at` NULL (v6 cleared the
  v5 record — the bump works as designed).

## 7. The backfill (`backfill_hubcdn.py`)

- Report mode by default (counts bridges, no network, no writes);
  `--apply` writes; `--limit N`, `--delay` (default 0.3 s), `--timeout`
  (default 15 — an 8 s unwrap timed out once in 25 staging rows and
  succeeded on retry). Per row: `unwrap_hubcdn` → prefer `r2_url` →
  gate-probe via the resolver's own `_stage2_probe` +
  `_stage2_accept(..., "either")` → UPDATE mirroring
  `resolve_live_source`'s persist shape (`final_url`, `is_temporary=0`,
  `status='active'`, `last_checked_at`/`updated_at` stamped, failure
  cleared, `mime_type`/`http_status` 206-or-200/`is_resumable` from the
  verdict, `expires_at=NULL`, plus guarded `cat_links.expires_at=NULL`
  mirror where the table exists). R2-only: Google-only rows are counted
  (`google_only`) and left for JIT presses. Failures record nothing (must
  not poison the negative cache); re-runs resume (already-direct rows are
  never selected). Stats keys: `scanned, rewritten, google_only,
  unwrap_failed, probe_failed, preparing, skipped_direct,
  projection_mirrored`.
- Full press proof on a staging mini-DB (real schema + 3 real rows, ids
  289156–289158): `resolve_live_source(289156)` → success, url
  `https://pub-423049f6bce8462094efe157cfd20ea1.r2.dev/8e72f435bf2b41888491995366dcd6a2`,
  `refreshed=True`, `is_temporary=False`, `is_resumable=True`,
  `mime_type='video/mkv'`; row after: final_url rewritten to R2, status
  active, http_status 206, is_resumable 1, `last_checked_at` set. Ranged
  GET on the returned URL: 206, `Content-Type: video/mkv`,
  `Content-Range: bytes 0-511/371153173` (353.96 MB — matches the gateway's
  label exactly), 512 bytes, magic `1a45dfa3` (EBML/matroska).

## 8. Staging 200-row backfill (live rows, `/tmp/hubcdn-stage200.db`)

- Slice 1 (pre-fix, `--limit 25`): `scanned=25 rewritten=22
  google_only=0 unwrap_failed=2 probe_failed=1`, 92 s (~3.7 s/row). The 2
  unwrap failures were both Preparing pages; the probe failure (id
  289898) later unwrapped fine on retry.
- Slice 2 (pre-fix, `--limit 45`): `scanned=45 rewritten=38
  google_only=0 unwrap_failed=0 probe_failed=5 preparing=2
  skipped_direct=22 projection_mirrored=38`. All 22 rewritten rows in
  slice 1 were `video/mkv`, http 206.
- Slice 3 (post-fix, `--limit 45`): `scanned=45 rewritten=43
  google_only=0 unwrap_failed=0 probe_failed=0 preparing=2
  skipped_direct=61 projection_mirrored=43`.
- Slice 4 (post-fix, `--limit 45`): `scanned=45 rewritten=43
  google_only=0 unwrap_failed=0 probe_failed=0 preparing=2
  skipped_direct=104 projection_mirrored=43`.
- Slice 5 (post-fix, `--limit 60`): `scanned=53 rewritten=50
  google_only=0 unwrap_failed=0 probe_failed=0 preparing=3
  skipped_direct=147 projection_mirrored=50`.
- Final tally (200 rows): **197 direct, 3 still bridges.** The 196
  backfill rewrites + 1 press rewrite (6169709, §6) = 197 R2 URLs; the 3
  remaining bridges are exactly the 3 Preparing rows (ids 289899, 290311,
  6169782 — the same three probe9 found), all browser-only filesdl. A
  final report-mode run confirms `scanned=3` (report mode does no
  network, so its `preparing=0` is a count artefact, not a verdict).
  Post-fix slices: 0 probe failures, 0 unwrap failures, 0 google_only
  across 143 scanned rows. Pre-fix slices: 60 rewritten of 70 scanned,
  5 probe failures — all the octet-stream gate (§5).
- Full live run cost: 16,846 bridge rows × ~3.7 s ≈ 17 h at measured
  pace; sliceable via `--limit`. The live run is a gate/operator step,
  NOT run by this lane against the live DB.

## 9. Tests + gate

- New `test_probe_classify.py` (18 tests): magic sniff (EBML/ftyp/AVI/
  random/short/empty/None), `classify_probe` octet-stream cases (pass
  with magic, `unverified` without, suffix rule preserved, video-mime
  preserved, html_shell/404/403 precedence, 200 + accept-ranges
  resumable), `probe_media` wiring (Range `(0, 512)`, ok + mime /
  `unverified`). 18/18 OK.
- `test_backfill_hubcdn.py` 10/10, `test_hubcdn_unwrap.py` 37/37 (+3
  preparing), `test_host_classifier.py` 74/74, `test_network_intercept.py`
  OK (H4 R10 suite, on the rebased tree), `test_resolver_probes.py` /
  `test_link_health.py` / `test_direct_file.py` OK, `test_database.py`
  239 OK (skipped 1), `test_api_v2.py` 90/90, `test_ci_manifest.py`
  10/10 (after commit — pre-commit it flags the untracked file, the same
  failure the backfill suite hit before its commit).
- Gate: 128 suites (127 on main `c27890db` + `test_probe_classify`).
  Registered in `backend-ci.yml` + `test_ci_manifest.py` MANDATORY_RUN.
- Rebase: branch rebased `44631e10` → `c27890db` (H4 R10) clean; the
  stashed gate fix popped with an auto-merge on the workflow + manifest.

## 10. What is still owed

(a) The live backfill run (gate/operator decision; ~17 h at measured pace,
sliceable via `--limit`). (b) filesdl/Preparing rows (~8%) need a
real-browser lane. (c) Optional follow-up — flip the hdtvb importer
`--resolve-direct` default so new rows are born cracked (policy/cost call
for the coordinator, +2 HTTP requests per cloud link at scrape time). (d)
The importer's `resolve_hubcdn_direct` also stores raw `/l.php` hrefs as
`final_url` for pixeldrain/hubcloud/gdflix/gofile mirror rows — observed,
not fixed (other lanes' services). (e) Live verify at gate time: press an
octet-stream hubcdn title on the live service (the v6 press proof here is
staging-only so far); press a title whose banked winner is an octet-stream
R2 URL (H4 R9 re-probe path with the new gate).

## 11. Files

- `Fast_Fetch/current_hosts.py` (+34/−2): `_video_mime_from_magic` +
  `classify_probe` branch.
- `Fast_Fetch/fast_fetch.py`: `RESOLVER_VERSION` 5 → 6 + comment entry.
- `Fast_Fetch/test_probe_classify.py` (new, 18 tests).
- `Fast_Fetch/backfill_hubcdn.py` (new) + `Fast_Fetch/test_backfill_hubcdn.py`
  (new, 10 tests) — commit `f37fe2f5`.
- `Fast_Fetch/fast_fetch.py` `unwrap_hubcdn` preparing-page branch +
  `Fast_Fetch/host_classifier.py` `hubcdn_preparing_target` +
  `Fast_Fetch/test_hubcdn_unwrap.py` +3 tests — commit `f37fe2f5`.
- `.github/workflows/backend-ci.yml` + `Fast_Fetch/test_ci_manifest.py`:
  `test_backfill_hubcdn.py` + `test_probe_classify.py` steps /
  MANDATORY_RUN.
- NOT merged — parent merges; no live deploy; no live DB writes (all
  staging). VM-side artefacts (Oracle VM `/tmp`, ephemeral):
  `/tmp/hubcdn-code3/Fast_Fetch/` (rebased + fixed code),
  `/tmp/hubcdn-stage200.db` (200-row staging DB, fully backfilled),
  `/tmp/hubcdn-mini.db` (3-row staging DB), probe scripts
  `/tmp/hubcdn-probe1.py` … `/tmp/hubcdn-probe15.py`.
