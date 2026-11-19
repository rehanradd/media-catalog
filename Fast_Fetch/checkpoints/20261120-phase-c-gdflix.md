# Phase C — GDFlix host crack (2026-11-20)

Branch: `muse/phase-c-gdflix-20261120`, base main `8f7948d2` (H4 R8; rebased
to `44631e10` H4 R9 as `bd8b959a`, then to `c27890db` H4 R10). Worktree
`~/workspace/phase-c-gdflix-20261120`. No merge, no deploy, no live writes.

Local verification (post-rebase): `test_gdflix_unwrap.py` 21/21,
`test_ci_manifest.py` 10/10, `test_resolver_probes.py` (H4 R9) 18/18,
`test_docs_invariants.py` 22/22, `test_database.py` 239 tests OK
(skipped=1); gate suites 127 (`grep -c 'name: 🧪'`).

CI round 1 (run 44626718329, tip `0b360e7b`): FAILED on
`test_ci_manifest.py` only — `test_manifest_is_never_a_subset_of_trunks`:
main had moved to `c27890db` (H4 R10, `test_network_intercept.py`) under
the branch. Fixed by rebasing onto `c27890db` (kept both suites in
`backend-ci.yml` + `MANDATORY_RUN`); re-pushed for round 2.

## Verdict (02:5x PKT 20 Nov)

**Chain PROVEN to the signed Google file URL from hatch egress; NOT live-proven
from Oracle VM egress.** The production resolver cannot fetch the GDFlix file
page today: every gdflix host 302s to `new10.gdflix.io`, which answers the
Oracle VM egress with a Cloudflare managed challenge (403 "Just a moment...",
`cType: 'managed'`) to plain and full-browser-header requests alike. The same
URL returns the real file page (200, 43 KB) from hatch egress — the block is
IP/ASN reputation on the Oracle egress, not the URL or the headers.

So this lane builds the real button-flow resolver (the old `unwrap_gdflix`
only looked for embedded Drive IDs that are NOT on the current page) and wires
it to the H4 R8 clearance store, so it works the moment a `cf_clearance` for
`new10.gdflix.io` earned from the Oracle egress is placed. **NOT counted as
cracked**: no real press from Oracle egress, no byte proof (see below).

## Research (before any code)

- SitePoint thread "How Do Sites Like DriveSeed and GDFlix Generate
  Google-Hosted Direct Video Download URLs?" — GDFlix emulates the Drive web
  client: `drive.google.com/uc?export=download&id=…` → virus-scan `confirm`
  token → `drive.usercontent.google.com/download?…` → signed
  `video-downloads.googleusercontent.com` URL. Signed, short-lived, Range-able.
  No public API returns these URLs. Since mid-2023 unauthenticated hits
  increasingly get 403 + per-IP quotas.
- NuvioStreamsAddon `providers/moviesdrive.js` `extractGDFlixLinks` (live
  source, read from a local clone): current domain from
  `SaurabhKaperwan/Utils/urls.json` key `gdflix`; file page has `Name :` /
  `Size :` list items and `div.text-center a` buttons: `DIRECT DL` (href),
  `CLOUD DOWNLOAD [R2]` (href), `PixelDrain DL` (rewrite `/u/` →
  `pixeldrain.dev/api/file/{id}?download`), `Instant DL` (follow redirect,
  parse `url=` param). Confirmed `urls.json` today: `"gdflix":
  "https://new10.gdflix.io"`.
- vega-app `gdflixExtractor.ts` (DeepWiki, Feb 2026; file since removed from
  the repo HEAD): body `onload` `location.replace` redirect wrapper; buttons
  R2 (`.btn-outline-success` / "CLOUD DOWNLOAD"), PixelDrain (`.btn-success`),
  ResumeBot (`.btn-secondary`, bot/indexbot host: fetch resume URL, extract
  token via `formData.append('token', …)`, POST `/download?id=`, JSON `.url`),
  ResumeCloud (`.btn-secondary`, other: `baseUrl + resumeDrive`, `.btn-success`
  href), G-Drive (`.btn-danger` w/o `?url=`: HEAD, split on `?url=`),
  Gdrive-Instant (`.btn-danger` with `?url=`: POST `/api` with `keys`, JSON
  `.url`). Desktop Edge 120 headers.
- Cloudflare challenge research (2026 field notes): a managed challenge is a
  JS proof-of-work + browser-environment probe; only a real browser (or a
  FlareSolverr-style solver) passes it; the issued `cf_clearance` is bound to
  IP + UA + hostname and lives 15–60 min. The working pattern is "solve once
  in a browser, replay the cookie over HTTP from the same exit IP + UA" — which
  is exactly what H4 R8's `host_clearance` store implements. `cloudscraper`
  handles only the old JS challenge, not Turnstile/managed; stdlib-only Python
  cannot solve it.

## Live DB (read-only, 02:38 PKT 20 Nov)

- `source_url LIKE %gdflix% OR %gd.kmhd%`: 98,648 rows — active 16,163 /
  expired 9,298 / unverified 73,187. (Brief's 122,070 counts `service='gdflix'`
  rows incl. other URL shapes; same host family.)
- **0 rows** have `final_url != source_url`, **0** have a googleusercontent
  `final_url` — the old `unwrap_gdflix` has never produced a stored direct
  link. Top hosts: `new3.gdflix.io` 39,482; `gdflix.vip` 32,167;
  `new5.gdflix.io` 6,901; `gdflix.top` 3,194; `gd.kmhd.me` 2,580;
  `gdflix.dev` 2,430; `gd.kmhd.eu` 2,305; `*.gdflix.cfd` ~5k; `gdflix.co/cc`;
  `new.gdflix.dad` 629.
- `host_clearance` table does NOT exist in the live DB yet (H4 R8 merged to
  main, gate deploy pending) — no clearance stored anywhere.

## Probes (light: 2 file IDs, hops of the same chain)

Sample: `https://gdflix.vip/file/N4NC704WLKFJAKL` and
`https://gd.kmhd.me/file/DtMsHkOzd5olqtd`.

Oracle VM egress:
- Both 403, 5,647 B, redirected to `new10.gdflix.io` — "Just a moment..."
  managed challenge (`_cf_chl_opt`, `cRay a5d25ec4beb64226`, `cType:
  'managed'`). Full browser headers (UA + Accept + sec-ch-ua + Sec-Fetch-*)
  on `new10` directly: still 403, 5,924 B. `new3.gdflix.io` answers 302 →
  `new10.gdflix.io/file/…` (Cloudflare front). Same failure twice with
  different header sets → stopped retrying, searched (see above).

Hatch egress (diagnostic — resolver runs on Oracle, so this proves the chain
only, not live play):
- `new10…/file/N4NC704WLKFJAKL` → **200, 43,093 B**, title `GDFlix |
  Final.Table.S01E03.1080p.WEB-DL.HIN-KOR.x265.ESub-MoviesDrives.BID.mkv`,
  `Name : …mkv`, `Size : 894.85MB | Views : 16`. **No** embedded Drive IDs,
  **no** `video-downloads` URLs — the old unwrap's patterns are absent.
- Current buttons (button set changed since Nuvio/vega wrote theirs — no
  DIRECT DL / CLOUD DOWNLOAD [R2] / PixelDrain DL on this page):
  - `Instant DL [10GBPS]` → `https://busycdn.instadl.rest/<302-char token>?bytes=938314815`
  - `FAST CLOUD / ZIPDISK` → `/cflare/1795124562/N4NC704WLKFJAKL`
  - `DRIVEBOT [50MB/S]` → `https://drivebot.sbs/download?id=<b64>&do=…`
  - `Login To DL [10GBPS]` → `/login` (account — skipped), `Telegram File`,
    `GoFile [Generate]` (out of scope).
- `Instant DL` hop (twice, fresh token each time): busycdn → **302** →
  `https://fastdl-one.pages.dev/?url=https://video-downloads.googleusercontent.com/<fresh signed URL>`
  (Google signature re-minted per hop; the busycdn token itself was STABLE
  across fetches ~15 min apart — per-file, not per-load).
- `/cflare/…` GET → 200, 35,735 B, same title, NO download buttons: a
  Turnstile-gated page (`challenges.cloudflare.com/turnstile`, POSTs with
  `x-token`, redirects via `data.url`/`data.visit_url`/`data.Quick_url`) — dead
  end server-side. (HEAD on it 404s; GET is the real answer.)
- Final-byte proof **not obtained** from hatch: direct Range GET on the fresh
  signed Google URL returned HTTP 400 once (2.5 s) and hung twice (7 s / 20 s
  timeouts, 0 bytes); a busycdn hop timed out once at 5 s after succeeding
  twice. Consistent with the known hatch-egress proxy flakiness on Google
  media hosts (AGENTS.md: public timings from this VM are transit artifacts),
  possibly plus short-lived signatures. Same failure class twice → stopped
  blind retries; byte proof is owed, not faked.
- `drivebot.sbs` page: one probe, empty reply (transit?) — implemented as a
  best-effort fallback (documented ResumeBot token-POST flow), unproven.

## What was built

- `Fast_Fetch/fast_fetch.py`: `unwrap_gdflix` rewritten as the real
  button-flow resolver (stdlib only) + `clearance` threading like
  `unwrap_vcloud`/`unwrap_hubcloud`; `resolve_live_source` gdflix branch
  passes `_clearance`. New helper `_gdflix_redirect_location` (no-follow
  Location capture — following the busycdn/fastdl chain with `http_get`
  would read the file body). Priority: embedded video-downloads URL (kept) →
  embedded Drive file id (kept) → `Instant DL` (busycdn 302 → `?url=` Google
  URL, temp=1) → legacy `DIRECT DL` / `CLOUD DOWNLOAD [R2]` (temp=1) →
  `PixelDrain DL` (→ `pixeldrain.dev/api/file/{id}?download`, temp=0) →
  `DRIVEBOT` (token form + POST `/download?id=`, JSON `.url`, temp=1) →
  unchanged (temp=0). Skips login / Telegram / GoFile / `/cflare`
  (Turnstile). Onload `location.replace` wrapper followed once.
- `Fast_Fetch/test_gdflix_unwrap.py`: offline mocked tests for the chain
  (Instant DL, legacy buttons, DriveBot, onload wrapper, dead page, challenge
  page, no-button page). Registered in `backend-ci.yml` gate step +
  `test_ci_manifest.py` MANDATORY_RUN (gate step 127 after the H4 R10
  rebase).
- Existing `test_database.py` gdflix tests still pass (3 targeted + full
  module 239 tests OK, skipped=1, post-rebase).

## What would unblock a live crack

1. A `cf_clearance` for `new10.gdflix.io` earned **from the Oracle VM egress
   IP with the UA the server replays** (clearance is bound to IP + UA +
   hostname), placed via `host_clearance.place_clearance` (`source='operator'`)
   once the H4 R8 table exists live (gate deploy pending). A clearance earned
   in any other browser (hatch egress, user phone) is bound to the wrong IP
   and will not replay from Oracle.
2. Clearances live 15–30 min (store ceiling 15 min), so a one-off placement
   buys one short window — a durable crack needs a re-earning loop on/through
   the Oracle egress (real browser or FlareSolverr-style solver on the VM).
   That is a coordinator/owner decision: it installs browser automation on
   the live box and is NOT built here.
3. Then re-prove: fresh file-page fetch from Oracle egress → Instant DL chain
   → Range GET on the signed Google URL returns 206 video bytes → real press
   302. Only then count gdflix cracked.

## Notes for the coordinator

- Two `muse.exec` probe calls that ran long (>20 s wall: the 26 s Instant DL
  follow and the 23 s fastdl-one/drivebot probe) were flagged
  `userConfirmationRequired: true, userConfirmationStatus: timed_out` — the
  commands still ran and their output is quoted above; I did not retry those
  exact calls. If long probes need approval, they need it from the parent.
- CONTINUITY.md was 11,974 / 12,000 B: rotated the oldest lane bullets
  verbatim into `checkpoints/20261120-continuity-rotation.md` on the first
  edit (per the standing rotation order), then added this lane's bullet.
