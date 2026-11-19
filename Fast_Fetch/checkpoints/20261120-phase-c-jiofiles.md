# Phase C — jiofiles crack attempt (2026-11-20)

- Branch: `muse/phase-c-jiofiles-20261120`, base `origin/main` 8f7948d2 (H4 R8;
  newer than the task's 78c66197 — main had moved by 02:36 PKT 20 Nov).
- Worktree: `~/workspace/phase-c-jiofiles-20261120`. No code changed, nothing
  merged, nothing pushed, no live service/DB touched.
- Verdict: **NOT CRACKED (server-side).** The chain is fully mapped but stops
  at a Cloudflare JS challenge on the player host. No press proof exists.

## Facts re-verified (live DB, read-only, 02:38 PKT 20 Nov)

- `service='jiofiles'`: active 12,189 / dead 205 / unverified 46,886
  (59,280 rows — matches the brief exactly).
- Fresh samples used (≤5, light probes only, all from Oracle VM egress):
  - `https://embed.jiofiles.pics/68e4ccfabc336a08aa36367c` (active)
  - `https://embed.jiofiles.pics/68d4fd26bc336a08aa361347` (unverified)
  - `https://embed.jiofiles.pics/69ecd1b82ad0063ec3a336b4` (active)
  - `https://player.jiofiles.pics/6acb866845b7a34b8f406d77` (unverified)
  - `https://embed.jiofiles.pics/68fdeaddf184eae4897ab273` (unverified, listed
    but not fetched — 4 fetches were enough; the pattern was identical)

## Research first (10+ phrasings, before any code)

No public extractor exists for jiofiles or its player host. Searches run
20 Nov 2026 (all returned noise — JioCinema, the jIO JS library, generic
JW Player / m3u8 guides, Xbox xCloud):

- `jiofiles embed player extract video source m3u8`
- `embed.jiofiles.pics video extractor`
- `jiofiles downloader github yt-dlp` (yt-dlp has no jiofiles extractor)
- `"jiofiles" file host download` — found only: jiofiles.org is a
  "Free file upload service" / "Easy way to share your files", Cloudflare
  IP 104.31.79.156, domain created 2019-12-29 (hypestat.com)
- `"jiofiles.pics" embed` — nothing relevant
- `jiofiles site github extractor video host` — nothing relevant
- `"jiofiles" m3u8 OR jwplayer OR video` — nothing relevant
- `jiofile download link bypass embed player github` — nothing relevant
- `"xcloud.autos" stream player` — only Xbox Cloud Gaming noise; one
  SEMrush nugget: visitors reach xcloud.autos from pirate blogspots
  (songhye456.blogspot.com journey) — it is a real video host with no
  indexed docs or extractor
- `"xcloud.autos" github` — nothing relevant
- `cloudflare "just a moment" video embed host python urllib challenge` —
  confirms the blocker (see below):
  - quantumproxies.io (29 Sep 2026 test): "'Just a moment...' is
    Cloudflare's challenge page. It asks the client to run JavaScript and
    prove it is a browser before the site answers. Python requests cannot
    run JavaScript… What gets through is a real browser on a clean IP, or
    a web unlocker that runs one." Headers will not fix it.
  - dev.to field notes (2026): "Attention Required! | Cloudflare" (WAF
    1020) is a WAF rule — solving won't help, change fingerprint/exit.
    "The core trick: solve once in a browser, replay over HTTP" — a real
    browser earns a `cf_clearance` cookie, reusable from an HTTP client
    until expiry, same exit IP (Cloudflare ties the cookie to it).
    29 browser solves served 3,155 requests on one site.

Repo history: jiofiles entered via the MovieNestBD importer (Session 118,
2026-11-09) as "NEW host, not cracked, stored as-is"; `host_registry.json`
carries `player.jiofiles.pics` / `embed.jiofiles.pics` entries only as
domain-rotation records. No `xcloud` string anywhere in the repo.

## The chain (mapped, probed from Oracle VM egress)

1. `embed.jiofiles.pics/<hex>` (and `player.jiofiles.pics/<hex>` — same
   wrapper) → **HTTP 200, ~10.2 KB, Cloudflare, no challenge, no
   Set-Cookie.** Thin wrapper page: title is the MovieNestBD filename
   (e.g. "Veergati 1995 WebRip 1080p Hindi AAC 2.0 x264 - MNBD.NET.mkv").
   The page's ONLY video link is one iframe:
   `<iframe id="videoPlayer" src="https://xcloud.autos/stream_<hex>">`.
   The player dropdown has exactly ONE entry ("Player 1", xcloud) — the
   "try another player" toast is aspirational. All URLs in the page:
   Google Fonts, analytics.movienest.cv, and the xcloud stream URL. No
   API endpoint, no download link, no alternate host, no m3u8/mp4.
   Verified identical on 3 samples (stream ids `92b70c821a506f0`,
   `155e1b6e76c477d`, `9f8779f00e87bc3`).
2. `xcloud.autos/stream_<hex>` → **BLOCKED for every non-browser client:**
   - curl HTTP/2, no Referer → 403 "Attention Required! | Cloudflare"
     (WAF block, `cfOrigin;dur=0`, 4,545 B)
   - curl HTTP/2 + Referer + full browser headers (Accept, Accept-Language,
     Sec-Fetch-*) → 403 "Attention Required!" (4,912 B)
   - curl HTTP/1.1 + Referer → 403 "Just a moment..." JS challenge page
     (5,739 B)
   - **stdlib urllib (the resolver's own stack)** + Referer → HTTPError
     403, header `cf-mitigated: challenge`, body "Just a moment..."
     (5,739 B); the embed fetch in the same script returned 200
     (10,564 B, `cf-mitigated: None`).
   - Both hosts are Cloudflare anycast (`2606:4700:...`); there is no
     origin IP to reach, and reaching around Cloudflare would be
     bypassing the host's access control — not attempted.

## Why this is the end of the server-side road

- A stdlib HTTP client cannot execute the Cloudflare JS challenge, and
  the challenge is on the ONLY player behind every jiofiles embed — there
  is no second player, no API, and no direct file link to fall back to.
- The video source (m3u8/mp4) lives behind the xcloud player page, which
  has never been observed: its URL pattern, its CDN host, and whether
  that CDN is itself challenged are all unknown.
- Paid challenge-solver APIs (Peak, CapMonster) exist but are paid
  services and would be solving the host's bot gate for us — out of
  scope under the free-only rule and the no-bypass rule. Not pursued.
- TLS-fingerprint spoofing libraries are non-stdlib and the same kind of
  bypass. Not pursued.

## What would unblock it (in order)

1. **Real-browser network capture (HAR) of the xcloud player loading**
   in a normal browser, from Oracle VM egress if possible: open a
   jiofiles embed (e.g. `https://embed.jiofiles.pics/68e4ccfabc336a08aa36367c`),
   let Cloudflare pass, record what the player page requests — the player
   HTML/JS and the video-source URL (m3u8/mp4 + its host). Then test
   whether that video CDN answers a plain server-side GET. This is the
   VidCore prescription (real-browser only; stream URL not captured,
   resolver not wired). A subagent cannot run a live browser — the
   coordinator must arrange this step.
2. **If the video CDN is also challenged:** a browser-earned
   `cf_clearance` for `xcloud.autos`, replayed server-side through the
   H4 R8 clearance store (`Fast_Fetch/host_clearance.py`, 15-min reuse
   ceiling, same egress IP) — needs step 1's browser pass first, plus a
   way to place the earned clearance into the store. The replay side
   already exists; only the browser solve is missing.
3. **If the capture shows the player needs JS execution beyond the CF
   gate** (e.g. token minted in JS): server-side stdlib unwrap is
   impossible; the option left is client-side playback (app WebView /
   ExoPlayer loading the embed), which is an app decision, not a
   resolver.

## Files changed

- None in the tree (worktree `git status` clean). Probe scripts live only
  in `/tmp` on this box (`jio-probe1.sh`, `jio-probe2.sh`, `jio-probe3.sh`,
  `jio-probe4.py`) and on the VM (`/tmp/jio-probe*.sh|py`,
  `/tmp/jio*.html|hdr`); VM probe outputs were read-only diagnostics.
- This log: `Fast_Fetch/checkpoints/20261120-phase-c-jiofiles.md`
  (committed locally on `muse/phase-c-jiofiles-20261120`, NOT pushed —
  no code, so no CI value; a push would trigger a full backend-CI run
  for a log-only change).
- No resolver, test, `backend-ci.yml`, or `test_ci_manifest.py` change:
  there is no working chain to wire. No CI run. No press proof.
- CONTINUITY.md: one-line entry added to the rotated history list.

## Recommendation to the coordinator

Do not re-probe jiofiles server-side — the CF wall is proven three ways
and the page has no alternate path. Queue the real-browser HAR capture
as the next step (same treatment as VidCore). If the capture shows a
directly fetchable video CDN, the resolver is then a small build:
`unwrap_jiofiles` = fetch embed page → extract
`xcloud.autos/stream_<id>` iframe → (video URL from the captured
pattern) → verify bytes → wire into `resolve_live_source` + tests +
gate registration, with press proof from Oracle egress.
