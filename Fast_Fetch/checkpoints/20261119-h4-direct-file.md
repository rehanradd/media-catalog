# H4 R6 direct-file preference — research, audit, build (2026-11-19)

Branch `muse/h4-shared-cache-20261119` → `muse/h4-direct-file-20261119`, base main `3ca6f7f0`.
Builder lane only; the main chat merges.

## 1. Research (online first, 4 searches, before building)

1. **Direct file detection (dev.to, flowpick.net, m3u8-player.net):** a `.mp4` suffix is a
   clue, not proof; the real proof is the response — `Content-Type: video/*`,
   `Content-Length`, `Accept-Ranges: bytes`, 206 on a ranged GET. Some servers
   reject HEAD or answer HEAD differently from GET, so a ranged GET
   (`Range: bytes=0-0`, ≤1 KiB) is the reliable probe. A progressive MP4 is one
   file, range-requestable, seekable, resumable; an HLS/DASH manifest is a
   playlist of segments, often signed with a short expiry.
2. **Source selection (bplugins, mpcore VidPly, Amazon Vega):** players pick by
   content type — flat VOD files (`.mp4`, `.mkv`) by URL, manifests (`.m3u8`/`.mpd`)
   by MSE. Progressive MP4 is the recommended fallback under HLS/DASH.
   Detection by extension alone misfires when a manifest is served from a URL
   that does not end in `.m3u8`.
3. **Direct-link verification (flowpick, copyprogramming):** check URL + status +
   `Content-Type` + whether the body is media. `video/*` is a useful clue, not
   conclusive proof; `text/html` means a login/error page, not a video.
   `Accept-Ranges: bytes` is what makes resume/seek work.
4. **Format selection (yt-dlp / ytdl-service):** ordered selectors with `/`
   fallback — `best[ext=mp4]` first, then combined, then `best`. The pattern
   this slice copies: an ordered preference list with fallback, never a single
   guess.

## 2. Audit — what the code already does (file:line)

Direct-file preference is NOT absent; the ingest label is the weak link.

- `tiers.py` `KIND_WEIGHT`: direct=100, url/resolve=70, download=30,
  iframe/embed/external=0. `candidate_order_sql`: playback_type direct=1, url=2,
  download=3, iframe=4. `score()`: +15 resumable, +15 HTTP 200–399, service
  bonus capped so it never closes the kind gap.
- `resolved_cache.kind` ∈ ('file','playlist','viewer'), derived at bank time
  from measured mime + URL shape (`resolved_cache._derive_kind`; "labels lie,
  shapes don't"). `lookup_cache(want="watch")` accepts file OR playlist — no
  preference between them; `want="download"` requires kind='file'.
- `current_hosts.select_watch` orders by renditions/width/backend — NOT by kind.
  A playlist with renditions sorts before a plain file. `select_download`
  filters kind='file'.
- `link_state` sweep writes measured `mime_type`, `is_resumable`,
  `file_size_bytes`, `status` onto `download_sources` — the measured evidence
  exists in the ledger, but `tiers.score()` never reads `mime_type`.
- `viewer._verify_option_tiers` orders candidates by measured host priority
  first, then ladder (score) order; first verified wins the band.
- `playback_type` is an importer claim written at ingest (default 'url';
  harpalgeo writes 'direct' for 5centscdn, hdtvb writes 'direct'/'download').
  It is never re-verified against what the link actually serves.

### Gaps (the build)

- **G1 — the label lies, twice.** A link labelled `url` whose ledger URL serves
  `video/*` bytes (a direct file an importer called a link) scores 70, while a
  link labelled `direct` whose URL serves `text/html` (the hubcloud fail-open
  case: unwrap hands back the HTML landing page) scores 100. Measured mime
  can correct both directions.
- **G2 — the failover walk ranks by label only.** `candidate_order_sql` is the
  SQL the Stage-1 sibling fetch and Stage-2 ladder use; it never looks at
  measured `mime_type`, which the same table carries.
- **G3 — the verify pass ignores the banked kind.** `_verify_option_tiers`
  tries candidates host-first; a fresh banked kind='file' resolution (bytes
  already proven, no unwrap needed) gets no priority over a playlist or an
  unbanked candidate.

### Dropped / not built (hard stops)

- Demoting a `url` link for serving `text/html`: WRONG — a bridge page is
  `text/html` by definition and still plays via unwrap. Only a `direct` label
  is contradicted by a page mime.
- `application/octet-stream` as video proof: generic binary fallback, not
  proof of video. Left neutral (no promotion, no demotion).
- Rewriting `select_watch` to put file before playlist: out of scope — the
  renditions preference is adaptive-streaming logic, and the brief's contrast
  is file vs bridged/JS, not file vs playlist. The playlist keeps its place;
  only the viewer/bridge kind is demoted.
- No per-URL guessing: promotion/demotion use MEASURED mime (prober) and the
  FRESH banked kind (resolver), never a URL suffix or a host name.

## 3. What changed (branch `muse/h4-direct-file-20261119`)

- **A. `Fast_Fetch/tiers.py` — `score()`: measured-mime kind correction.**
  New pure helper `_measured_kind(mime)`: `video/*` → 'direct', playlist
  mimes (`.m3u8`/`.mpd`, `mpegurl`, `dash+xml`) → 'url', `text/html` and other
  `text/*` → 'page', anything else/unknown → None. The kind weight is then
  the MEASURED kind when it contradicts the label: `direct`+page → 70,
  `url`/`resolve`+video → 100, `direct`+playlist → 70. `download`/`iframe`
  weights are untouched (playability is not this slice's to overturn), and an
  unknown mime changes nothing. Reads `mime_type` (v1/raw rows) or
  `content_type` (v2 projected links, `catalog_v2._measured_evidence`).
  The correction REPLACES the kind weight, so it can never exceed the
  existing 30-point kind gap the service bonus is sized against.
- **B. `Fast_Fetch/tiers.py` — `candidate_order_sql()`: same correction in
  SQL.** The first ORDER BY key is now the measured kind: a `direct` row whose
  `mime_type` is `text/html%` ranks as `url` (2); a `url`/`resolve` row whose
  `mime_type` is `video/%` ranks as `direct` (1); everything else keeps the
  old 1/2/3/4/5 order. SQLite `LIKE` is ASCII case-insensitive, so
  `Video/MP4` matches. `COALESCE(mime_type,'')` covers unprobed rows.
  Shared by v1 and v2, so both rank identically.
- **C. `Fast_Fetch/viewer.py` — `_verify_option_tiers`: fresh banked-kind
  nudge.** New helper `_banked_file_bonus(link, conn)`: one indexed
  `resolved_cache.lookup_cache(..., want="either")` per candidate (reuses the
  exact freshness law: `reverify_after`, fail count, IP-bound); a fresh
  kind='file' row earns +0.10, anything else (playlist, miss, stale, error,
  `conn is None`) earns 0.0. Sort key becomes
  `(-(host_score + kind_bonus), ladder_index)`: file-ness breaks near-ties,
  host reliability still dominates large gaps (a 0.95-host playlist still
  beats a 0.50-host file). The verify still probes every candidate; the
  first VERIFIED in that order wins, and a failed direct falls back to the
  next verified candidate — the brief's fallback, unchanged.
- **D. Tests + CI:** new `Fast_Fetch/test_direct_file.py` (score correction
  table, SQL order on an in-memory DB, verify-pass ordering with banked
  kinds, never-serve-expired guard), a gate step in
  `.github/workflows/backend-ci.yml` (122 🧪 steps now), and the module added
  to `test_ci_manifest.py` `MANDATORY_RUN`. No App files touched —
  backend-only slice, no Android CI.

## 4. How the improvement will be measured

- Ladder level: a title whose band holds both a measured-file `url` link and a
  measured-page `direct` link now serves the file as winner (was: the page).
- Failover level: the Stage-2 walk tries the measured file first; a failed
  direct still falls back to the next verified candidate.
- Live (gate time only): press a title with a known direct-file copy and a
  bridged copy in the same band; the served handle must be the file's, and
  the press must still 302 when the file copy is dead.

## 5. CI status

- Commit: TBD
- Backend CI run: TBD
- Local: TBD

## 6. Still owed

- Live verification only at gate time (see §4). Nothing here touches the live
  tree, the service, or the panel.
