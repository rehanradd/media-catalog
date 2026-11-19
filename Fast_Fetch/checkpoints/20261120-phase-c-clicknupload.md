# Phase C — clicknupload: NOT CRACKED (hCaptcha enforced server-side)

Date: 2026-11-20, probes 02:40–03:05 PKT. Builder lane; no merge, no push to main,
no deploy, no live-DB writes. Base: main 8f7948d2 (H4 R8).
Branch: `muse/phase-c-clicknupload-20261120`.

## Verdict

**NOT CRACKED.** The free-download chain works up to the last step, then stops
at a server-side-enforced **hCaptcha** on the `download2` POST. The server
answers `Wrong captcha` when the POST carries no `h-captcha-response` token,
even after the full 15-second wait is respected. A valid token can only be
minted by solving hCaptcha's interactive challenge; there is no legitimate
server-side way to produce one (no solver service, no payment allowed, and
bypassing the host's access control is out of bounds). That IS the result:
the host is uncrackable from the server as of this probe.

## Live-DB facts (read-only queries, Oracle VM, 2026-11-20 ~02:40 PKT)

- `download_sources` rows with a clicknupload URL: **52,869**
  (active 36,835 / unverified 16,034).
- Live domains in the catalog: `clicknupload.co` and `clicknupload.cam`
  (also `.club`, `.cc`, `.to` exist as mirrors per web research).
- Oldest active rows by id are DEAD files (expired/deleted); the newest
  active rows (id ~8,647,xxx, SSRmovies uploads, Oct 2026) are ALIVE.
- Sample URLs probed (≤5 + 5 newest, light probes only):
  - DEAD — `https://clicknupload.co/y02w1tgtb20e`,
    `https://clicknupload.co/sfmks1wjvo8d`,
    `https://clicknupload.cam/womb1xrfudcf`,
    `https://clicknupload.cam/2xyskqnediuz`,
    `https://clicknupload.cam/ayyl9s9ba68t`
    (all 200 with the XFS "File Not Found" page, 13,837/14,204 bytes).
  - ALIVE — `https://clicknupload.cam/kjnm271hgs5c` (480p),
    `https://clicknupload.cam/abbzqy5pohqb` (720p),
    `https://clicknupload.cam/cr0v8emnt2ew` (1080p),
    `https://clicknupload.cam/js6aipq84b03`, `https://clicknupload.cam/aylg8dufe5sz`
    (SSRmovies "Indian Idol Legends Returns" WEB-DL .mkv files).

## Research first (before any code)

Searches run 2026-11-20 (several phrasings + typo variants):
- "clicknupload free download wait timer direct link extraction"
- "clicknupload downloader github yt-dlp support"
- "clicknupload.co bypass free download link extractor 2026"
- "JDownloader clicknupload plugin XFileSharing"
- "\"clicknupload\" extractor github python"
- "clicknupload.org XFileSharing free download form op download1"
- "clicknupload hcaptcha free download captcha required 2026"

Findings:
- **No open-source clicknupload extractor exists.** yt-dlp has no
  Clicknupload extractor (its supported-sites list carries no such entry);
  no Python extractor turned up on GitHub code search.
- Clicknupload is a classic **XFileSharing (XFS)** host: file page →
  `op=download1` (free) → wait timer → `op=download2` → storage-host
  direct link. RapidLeech v2 lists `clicknupload.org` as supported;
  JDownloader handles it through its generic XFS hoster plugin (which
  faces the same captcha a server would).
- Free tier per host listings: 2 GB per file, files expire ~12 days after
  last download (30 days on sign-up) — consistent with the oldest catalog
  rows being dead.
- The only working "bypasses" found are **paid premium-link generators**
  (Cocoleech, FetchPik, Cboxera, Leechall, etc.), which use their own
  premium accounts — money, and not our account to use. No free method.
- hCaptcha itself: proprietary SaaS challenge; free Basic tier for the
  site, no self-hosted/open alternative; a token is verified server-side
  by the protected site (sources: hcaptcha.com docs via coincaptcha.com,
  zeroraised.com reCAPTCHA-alternatives roundup, 13 Nov 2026).

Conclusion of research: nothing fits to reuse; the XFS shape was already
known in-repo (see `unwrap_uploadflix`), so the probe — not more searching —
decided it.

## Probe evidence (Oracle VM egress, urllib, browser UA, cookie jar)

Chain, file `kjnm271hgs5c` (480p), run twice on two files with identical results:

1. `GET https://clicknupload.cam/kjnm271hgs5c` → **200**, 32,581 bytes,
   title `Download SSRmovies.COM - Indian Idol ... 480p WEB-DL x264.mkv`.
   One form: `op=download1`, `id=kjnm271hgs5c`, `fname=<filename>`,
   `usr_login=""`, `referer=""`, submit `method_free="Slow Download"`.
   No captcha markers on this page. Cookie set: `aff=.clicknupload.cam`.
2. `POST download1` (same session) → **200**, 25,065 bytes. Page shows the
   `download2` form (`op=download2`, `id`, `rand=<48-hex>`,
   `referer=<file page>`, `method_free`, `adblock_detected` empty) plus
   `<span id="countdown">Wait <span class="seconds">15</span> Seconds</span>`
   and the hCaptcha widget:
   `<script src="https://hcaptcha.com/1/api.js" async defer></script>`
   `<div class="h-captcha" data-sitekey="d6e335c1-0dab-4bf7-80c0-1ce402e273e6"></div>`
   `/js/countdown.js` only disables the Download button for 15 s (client
   theatre); the wait is ALSO enforced server-side.
3. `POST download2` immediately → **200** with
   `<div class="err">Skipped countdown</div>` and a fresh `rand`.
   (Proves the 15 s wait is server-side, not just JS.)
4. `POST download2` after sleeping the full 15 s, **no captcha token** →
   **200** with `<div class="err">Wrong captcha</div>` and a fresh `rand`.
   No direct link is returned. Second file (`abbzqy5pohqb`, 720p) gave the
   identical `Wrong captcha` after the full wait — the captcha is
   unconditional for free downloads from this egress, not per-file luck.

Decoy noted: the download2 page contains an anchor
`https://clicknupload.cam/<id>/<filename>.mkv`; probing it with Range
returns **200 text/html** (the file page again), not video bytes — it is
not a direct link.

## What would unblock it

1. **A human solving the hCaptcha in a real browser per press** — the only
   legitimate path; not automatable server-side, and the token is
   per-challenge, so it cannot be banked or reused across presses.
2. **A clicknupload premium account** — premium downloads skip wait +
   captcha (host's own "No wait, no ads, no captcha" pitch). Money;
   forbidden by standing rules, and not our account to use.
3. **The host dropping the captcha** — out of our control; re-probe
   occasionally (the newest catalog rows stay alive, so a re-probe is cheap).

## Code notes (for the day the captcha goes away)

- No resolver was built: there is nothing to wire — the chain dies at the
  captcha, and shipping a resolver that can never pass `download2` would
  only mint `unwrap_failed` rows.
- If the captcha disappears, the unwrap is ~30 lines reusing in-repo
  generic XFS pieces in `Fast_Fetch/importers/host_resolvers.py`:
  `_cookie_jar_fetch()` (session), `_xfs_form_fields()` (download1/
  download2 forms), the `download1` → sleep 15 → `download2` POSTs, then
  the storage-host dllink anchor (`_uploadflix_dllink()` shape, `/d/<token>/`
  path) + Range probe. `resolve_live_source` in `Fast_Fetch/fast_fetch.py`
  would gain a `clicknupload` branch beside the `uploadflix` branch, with
  `SERVICE_CLICKNUPLOAD = "clicknupload"`, tests in
  `Fast_Fetch/importers/test_host_resolvers.py`, and registration in
  `backend-ci.yml` + `test_ci_manifest.py` MANDATORY_RUN.
- The 15 s wait would be a real cost per press (respect the free tier);
  the minted link's token rotates per page load, so it must be
  re-unwrapped on every press, never banked durably — same as uploadflix.

## Files changed

- This checkpoint only: `Fast_Fetch/checkpoints/20261120-phase-c-clicknupload.md`.
- Probe scripts (`cnu-probe1/2/3.py`) live in `~/workspace/` on the hatch
  VM and `/tmp/cnu-*.py` on the Oracle VM (scratch, not committed).
- No code, no tests, no CI run (nothing to test — no resolver exists).
