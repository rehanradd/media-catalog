# Mega client-side decryption — design

Branch `muse/mega-client-decrypt-20261120`, base main `6199929e`.
Decision: owner-approved 04:42 PKT 20 Nov — client-side decrypt, NO paid Pro.

## Problem

68,507 `service='mega'` rows. Lane F (Phase C, `Fast_Fetch/checkpoints/20261120-phase-c-mega.md`)
proved the whole chain to *bytes* and that none of it plays:

- `POST https://g.api.mega.co.nz/cs` `[{"a":"g","g":1,"p":"<node_id>"}]` → 200, no account,
  no CAPTCHA: `{"g": "<gfs URL>", "at": "<attrs>", "s": <size>}`. Errors: -9 not found,
  -11 access violation, -16 banned uploader.
- The gfs URL serves AES-CTR ciphertext (Range GET 206 proven). First bytes decrypt to
  EBML magic `1a45dfa3` + "matroska" — a real MKV under encryption. No plaintext endpoint exists.
- Server stdlib has no AES (pure-Python AES measured 0.060 MB/s — not viable), and the
  free quota (~5 GB / 6 h per egress IP) would exhaust on ~5 plays of a 900 MB file if the
  server relayed bytes. So the server can never decrypt or relay; the app can: Android has
  `javax.crypto` natively, and it already runs a loopback media proxy (`LocalMediaProxy`)
  that serves bytes to Media3 with Range support.

## Proven crypto (Lane F, verified live with openssl)

- Link key: 32 bytes, URL-safe base64, no padding, from the `#` fragment (never sent to any server).
- Unmerge: AES key = K[0:16] XOR K[16:32]; CTR IV = K[16:24]; MAC key = K[24:32] (unused for playback).
- Content: AES-128-CTR, counter = IV (8 bytes) || 64-bit big-endian block index. Seekable:
  byte offset N lives in block N/16, counter IV || (N/16). No padding, no trailer;
  ciphertext size == plaintext size == `s` from the API.
- Attributes `at`: URL-safe base64, AES-128-CBC, zero IV → `MEGA{"n": "<filename>", ...}`
  (zero padding stripped). Used here only as a key check, never displayed.

## Decisions

1. **The app mints the download URL; the server only verifies liveness.** At press time the
   server POSTs the g API (stdlib urllib, ~5 s timeout) to prove the file exists: -9/-11/-16
   → dead → sibling failover via the existing ladder. The server does NOT return the g URL:
   whether g URLs are bound to the minting IP is unproven, and discarding the server's mint
   makes the question moot. The app mints its own g URL per press. Quota then accrues on the
   *user's* IP, not the shared server IP — the whole point of client-side.
2. **The key travels only in the per-press stream descriptor** (`GET /api/v1/stream?redirect=0`):
   `decrypt: "aes-ctr"`, `decrypt_node_id`, `decrypt_key`, plus `file_size_bytes` / `mime_type`.
   No `mega.nz` URL appears in any public body (sweep-safe: no upstream host, and
   `redact.PROVENANCE` has no such keys). **The wire is scheme-neutral on purpose: the
   value names the decryption scheme ("aes-ctr"), never the hoster, and the field names
   carry no hoster name either.** The first build used `decrypt: "mega"` /
   `mega_node_id` / `mega_key` and broke the project's own provenance rule: the bare
   word "mega" is a needle (`test_api_v2.assert_clean` does substring matching over
   the reply, and the ledger's `mega.nz` host puts "mega" in the needle set), so an
   existing download-only test failed on the marker. The neutral wire passes both
   the sweep's word-boundary check and the stricter substring check. The request
   flag stays `mega_decrypt` — it is the client's declaration, sent BY the client,
   and never appears in a reply.
   The descriptor is a GET response body, never a URL.
   Never banked: a mega resolve carries no `url`, so `record_resolved_cache` never fires,
   plus an explicit refuse-to-bank guard. Never logged, never persisted client-side
   (memory only — no Room, no DataStore, no cache file).
3. **Discovery is capability-flagged, not version-gated.** Detail/play wire gains
   `decrypt: "aes-ctr"` on mega links, derived from `cat_links.service` (no schema change).
   Old apps ignore the unknown field and keep download-only. New apps treat
   `decrypt == "aes-ctr"` as playable. `POST /api/v2/play` gains `mega_decrypt: true` in the
   request body; the server ranks mega candidates as playable ONLY when the flag is set —
   an old client can never be served a mega winner.
4. **The proxy mirrors `LocalMediaProxy`.** New `MegaDecryptProxy` (same package, loopback
   only): fetches ciphertext from the g URL with Range, AES-CTR-decrypts on the fly, serves
   clear bytes with `Content-Length` / `Accept-Ranges` / `Content-Range`. Seeking = CTR
   counter at the block index (see above). A 2 MB aligned sliding-window cache keeps
   ExoPlayer's sequential reads from becoming one g request per chunk.
5. **Key check before any media byte.** After minting, decrypt `at` (AES-CBC, zero IV) — it
   must start `MEGA{`; then the first decrypted window must start with a container magic
   (EBML `1a45dfa3`, `ftyp`, `RIFF`). A bad key fails fast with a decrypt error — never
   plays garbage.
6. **Failure taxonomy (distinct on purpose).** Quota (g API -18 / HTTP 509 on the ciphertext
   fetch) → "MEGA's free limit for your connection is used up — try again later or pick
   another quality"; no dead-marking, no failover (a sibling mega copy shares the same
   quota). Dead (-9/-11/-16, 404/403 on the g URL) → report via the existing failover path,
   sibling tried. Bad key → fail fast + report.
7. **No 302 to ciphertext, ever.** `redirect=1` on a mega handle answers the JSON descriptor,
   never a redirect. Old apps never request it (download-only); new apps always use
   `redirect=0` for mega.
8. **Downloads reuse the same proxy.** The download descriptor carries the same decrypt
   fields; the Keep flow downloads clear bytes from the loopback URL instead of following a 302.
9. **What the server does NOT do:** no decryption, no AES dependency, no key storage in the
   DB, no g URL in any response, no banking of mega rows, no relay of video bytes through
   the VM.
10. **Negative-cache trap (found while building).** A mega success must clear the resolve
    failure for the row (`_clear_resolve_failure`), and the Stage-2 cached-probe branch
    must skip mega rows — otherwise one successful play records a `probe_failed` (the
    cached mega page classifies as `html_shell`) and the 12 h negative cache skips the
    whole dispatch, mega branch included, on the next press.
11. **App wiring (the seam).** The detail screen stages only the SCHEME for the winner's
    handle (`PlayerSessionStore.stageDecrypt`, the same in-memory pattern as failover /
    coverage / tracks / segments — never a node id, never a key). The player peeks it in
    `startStreamHandle`; when it says `aes-ctr` it resolves the descriptor and starts
    `MegaProxyHolder`, playing the loopback URL. No marker is staged for a plain copy, so
    no extra round-trip is paid on a normal press. The holder stops on player release
    (`onCleared`), which drops the key. The download enqueue resolves the same descriptor
    and downloads through the proxy.

## Known v1 limits

- **One proxy per process.** Starting a different decrypting copy stops the first one's
  proxy. A mega download in progress is interrupted that way (it fails and can be resumed,
  never silently saved as garbage); playing the SAME file reuses the proxy.
- **A decrypting backup handle reached by mid-play failover has no staged marker**, so the
  player treats it as plain and the redirector 404s — failover then continues to the next
  handle. The winner (the common case) always plays through the proxy.
- The quota message is honest but coarse: the app cannot tell the user how much of their
  ~5 GB / 6 h budget remains, only that it is spent.

## Files to change

Server (`Fast_Fetch/`, stdlib only):
- `importers/host_resolvers.py` — `verify_mega(url)`: parse node id + key, g API verify;
  update the DELIBERATELY-NOT-IMPLEMENTED mega note to point at this design.
- `fast_fetch.py` `resolve_live_source` — mega branch returning the mega fields, no `url`.
- `viewer.py` stream route — mega descriptor fields; `redirect=1` → JSON for mega; banking guard.
- `catalog_api.py` `_link_view` — `decrypt` marker; `play_bundle` / sort — mega eligible only
  when the request flag is set.
- `api_v2.py` `route_play` — read `mega_decrypt` from the body, thread it through.
- `resolved_cache.py` — explicit refuse to bank a mega resolve (defense in depth).

App (`App/app/src/main/...`):
- `network/models/Models.kt` `StreamResponse` += decrypt fields; `V2Wire.kt` `WirePlayCandidate`
  / `WireDetailLink` += `decrypt`; `WirePlayRequest` += `mega_decrypt`; `CatalogV2.kt`
  `Rendition` / `network/models/QualityLadder.kt` `LadderLink` += `decrypt`;
  `V2CatalogWire.kt` `WireResolveResponse` += decrypt fields.
- `data/cache/MegaDecryptProxy.kt` (+ `MegaCrypto.kt`: unmerge, CTR, attrs check,
  `MegaApi.kt`: the app-side mint, `MegaProxyHolder.kt`: one proxy per play session).
- `network/models/V2CatalogWire.kt` `buildOptionGroups` — `decrypt == "aes-ctr"` ⇒ playable.
- `repository/CatalogRepository.kt` — `playV2` sends `mega_decrypt: true`; `resolveV2`
  carries the decrypt fields into `StreamResponse`.
- `player/PlayerSessionStore.kt` — `stageDecrypt` / `peekDecrypt` (scheme only).
- `viewmodel/DetailViewModel.kt` — stages the marker on the v2-bundle, ladder and
  fallback link paths; `player/QualityLadder.kt` `QualityOption` += `decrypt`;
  `viewmodel/ReleaseLayoutBuilder.kt` `RankedLink` += `decrypt`.
- `viewmodel/PlayerViewModel.kt` — `startStreamHandle` resolves the descriptor and starts
  the holder for a marked handle; `onCleared` stops it; quota/dead/bad-key/network map to
  plain-words messages.
- `data/downloads/DeviceDownloadsRepository.kt` — decrypting enqueue downloads through
  the proxy instead of the download endpoint.
- `GoldenVectors.kt` — untouched: mega's link-level classification stays download-only;
  the ladder's client-side playable is a separate layer.

## Tests

- Server (existing files, no new suites): resolver fixture tests in
  `importers/test_host_resolvers.py` (stubbed g API: ok / -9 / -11 / quota); descriptor tests
  in `test_download_endpoint.py` / `test_redact.py` (fields present, no upstream host, no
  provenance keys, key absent from logs); no-banking test in `test_resolved_cache.py`.
- App: proxy unit tests with a fake ciphertext source — runtime-generated CTR vectors plus a
  hard-coded Lane-F-proven vector (first bytes decrypt to EBML); seek test (Range at a
  non-zero block boundary returns the right plaintext); bad-key test; quota mapping test.
- CI: full backend gate locally; Android CI on the branch. No merge — main chat merges on green.

## Caveats the user should know

- Quota is per user IP (~5 GB / 6 h free; anonymous stricter) — a 900 MB film is ~1/5 of a
  6 h budget. Heavy mega users will hit it; the UX must say so plainly.
- Every mega press costs one small g API call server-side (verify) + one app-side (mint).
- First-frame latency includes mint + first 2 MB window; seeks cost one g Range fetch per
  2 MB window outside the cache.
- If MEGA ever IP-binds g URLs, this design still works — the app always mints its own.
