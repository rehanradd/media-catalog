# MX #5 → MX #4 research: Skip Intro / Skip Recap

## Question
How do intro/credits timings get detected and served, and what fits Radd Stream
(a server-aggregated catalog with no local files, stdlib-only Python backend,
Android app on Media3)?

## What the industry does

### Plex — server-side audio fingerprinting (needs local files + Plex Pass)
- Server analyses the beginning of each episode, finds the audio that recurs
  across episodes of a season (the theme song) via an audio fingerprint
  (histogram of the audio, per-season matching). Intros < 20 s ignored;
  intros ending past the halfway mark ignored. Markers served as
  `<Marker type="intro" startTimeOffset endTimeOffset>` in the episode XML.
- Needs the actual media files on the server. Radd Stream has no files —
  only links to remote hosts. Not applicable as a detection method here.
- Source: support.plex.tv/articles/skip-content, Plex forums.

### Jellyfin Intro Skipper — local analysis plugin (needs local files)
- Open source (ConfusedPolarBear/intro-skipper). Analyses files with FFmpeg:
  chapter markers naming an intro, recurring audio clips near the start of
  episodes, black-frame runs for segment ends. Also recaps, previews, credits.
- Same blocker as Plex: needs local files + heavy CPU per episode.
  Not applicable to our catalog.

### Audio fingerprinting in general (Shazam/Wang 2003)
- Spectrogram → local energy peaks ("constellation map") → peak-pair hashes
  (f1, f2, Δt) → offset voting. Implementations (dejavu, AudioFP) need numpy
  or native libs; our backend is stdlib-only, and again there are no local
  files to fingerprint. Rejected for this slice. A future "download first
  10 min + theme from YouTube" approach (bw-plex) is out of scope and would
  still need a fingerprint library.

### SponsorBlock — crowdsourced, YouTube-only
- Community timestamps keyed by YouTube video ID, categories incl. intro /
  outro / preview. Free, open API. Our catalog has no YouTube IDs.
  Model (crowdsourced timestamps) is the right shape; the keying is not.

### TheIntroDB — crowdsourced, keyed by TMDb/IMDb — THE FIT
- Community-verified timestamps for intro / recap / credits / preview,
  keyed by TMDb ID (+ season + episode for shows). Free (price 0 USD).
- Used by P-Stream, Youplex, Infuse, Plozz, GridStreamr, Jellyfin plugin.
- **Verified live 2026-11-19 from the Oracle VM** (see probes below):
  `GET https://api.theintrodb.org/v3/media?tmdb_id=1396&season=1&episode=1`
  → HTTP 200
  `{"tmdb_id":1396,"type":"tv","season":1,"episode":1,`
  `"intro":[{"start_ms":228665,"end_ms":246498}],`
  `"credits":[{"start_ms":3432884,"end_ms":null}]}`
  (Breaking Bad S1E1: ~18 s theme after a cold open; credits at ~57 min —
  plausible, real data.)
- API shape (from P-Stream `src/components/player/hooks/useSkipTime.ts`,
  production branch, verified by reading the source):
  - Base `https://api.theintrodb.org/v3`
  - `GET /media?tmdb_id=<id>` (movies) + `&season=<n>&episode=<n>` (shows)
    + optional `&duration_ms=<ms>` (disambiguates cuts)
  - Optional `Authorization: Bearer <tidbKey>` — reads work without a key;
    the key is only for submissions.
  - Response: `{intro, recap, credits, preview}` arrays of
    `{start_ms, end_ms}` (end may be null, e.g. credits to end of file).
    HTTP 200 even when empty; **404 = no segment data for this title**.
  - Fallbacks P-Stream uses (NOT built here): Fed-Skips (TV only, needs
    Turnstile token + a key — dead for automation) and IntroDB
    `https://api.introdb.app/intro?imdb_id=..&season=..&episode=..`
    returning `{end_ms}` (intro end only). The IntroDB fallback probed
    404 for Breaking Bad S1E1 — thin coverage, fallback only.
- **Cloudflare note (important): the OLD v1 path
  `api.theintrodb.org/v1/get` is Cloudflare-403 from BOTH the hatch VM
  and the Oracle VM (any User-Agent). The v3 `/media` endpoint is NOT
  blocked — 200 from the Oracle VM. Youplex's warning about server-side
  blocks applies to the old endpoint. Server-side fetch on v3 works.**
- Caveats (documented, not hidden):
  - One timing per episode: different cuts/prints of the same episode can
    mismatch (Stremio issue). Our catalog has many prints per title, so
    segments are a *suggestion*: manual Skip button only, never auto-skip.
  - Coverage varies: popular Western shows good; obscure / regional titles
    may have nothing. No data → no button, never a guessed time.
  - Community data can be wrong; a wrong button is a tap away from the
    truth (user just doesn't tap, or seeks back). No thumbs voting in
    this slice.

## Decision (recommended approach — adopted)
1. **Server is the source.** A backfill job queries TheIntroDB v3 for
   works with a `tmdb_id`, stores segments in a new `cat_media_segments`
   table, and the v2 play bundle serves them (`segments` + `identity`).
   One fetch per episode, cached for all users; no per-device network
   dependency; no Cloudflare problem on v3.
2. **App shows a manual Skip button only** when the bundle carries a
   segment whose window contains the current playback position. Tap seeks
   to the segment end. No auto-skip. No button when there is no data.
3. **Pack-offset rule (correctness):** segments are per-episode. A season
   pack file starts at the pack's beginning, so the served window is
   shifted by the episode's `offset_seconds` inside the pack (the bundle
   already computes this). If the offset is unknown
   (`needs_offset_calibration`), segments are OMITTED — a wrongly placed
   button is worse than none.
4. **Movies included:** same table/wire/button; movies mostly yield
   `credits` (Skip Credits → next title). Intro for movies is rare.
5. **Not built in this slice:** client-side live lookup (kept as the
   documented fallback if v3 ever blocks datacenter IPs), Fed-Skips /
   IntroDB fallbacks, crowd submission from the app, thumbs voting,
   auto-skip setting.

## Population plan
- `Fast_Fetch/backfill_segments.py` (stdlib only, urllib): iterate works
  with `tmdb_id` (+ enumerated episodes for series), GET v3 `/media`,
  store segments + a lookup-status row (found / not_found / error) so
  404s are not re-queried every run. Polite rate (~1 req/s), 429
  backoff, `--limit` / `--dry-run`, resumable. Output: staging SQL +
  JSON summary (same safety pattern as the metadata backfill — never
  writes the live DB directly).
- Serve: `play_bundle()` gains `segments` (shifted by pack offset, or
  omitted when the offset is unknown) and `identity` (`tmdb_id`,
  `season`, `episode`). Additive wire change; app tolerates absence.

## Attribution
- Segment data: TheIntroDB (community project). Credit in code comments
  and the session log. P-Stream credits "Nemo" for the API; we credit the
  project, not a person.
