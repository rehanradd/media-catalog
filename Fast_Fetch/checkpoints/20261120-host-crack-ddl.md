# 2026-11-20 — Host-crack DDL family: vcloud / fastdl / filepress / gdflix / hubdrive / megaup

Branch `muse/host-crack-ddl-20261120` (base main b9b08862, rebased onto
78dff25f before push). Port the vegamovies-API / vega-app / FZBypassBot
extractors to stdlib Python and prove them live from the Oracle VM.
Read-only probes only; never wrote to the live DB. Do NOT merge — the
parent merges on green. WARP was connected only for the probe windows
(~2 min each) and disconnected immediately after every run.

## Verdicts (all from real stored rows, Oracle VM egress)

| host | rows | verdict | path |
| --- | --- | --- | --- |
| vcloud.beer | 237k | CRACKED — existing `unwrap_vcloud` works, 3/3 with retry | direct |
| fastdl.beer | 126k | CRACKED — existing `unwrap_fastdl` works, 3/3 with retry | direct |
| filepress | 171k | CRACKED — new4 base + tgfiles retry; 2 independent mints, both 206 Matroska | WARP only |
| gdflix | 81k | CRACKED — existing `unwrap_gdflix` Instant DL works, 2/2, 206 Matroska | WARP only |
| hubdrive | 71k | NOT cracked — ajax direct-download refuses (code 408) on every sample | browser-only |
| megaup.net | 82k | NOT cracked — token URL is JS-constructed, absent from the page | browser-only |

## What this lane changed

- `fast_fetch.unwrap_vcloud`: the token page's "cfstorage" button is
  `<hash>.r2.cloudflarestorage.com`, not the `r2.dev` alias the old
  regex matched — capture both hosts. Also capture the PixelDrain
  button (`/u/<id>` -> `/api/file/<id>` via the existing
  `unwrap_pixeldrain`, same transform gdflix already uses). Every
  vcloud token page sampled carries all three buttons (R2, PixelDrain,
  HubCloud), so one unwrap now returns up to three direct links.
- `host_resolvers.FILEPRESS_API_BASE`: new3 -> new4. new3's API route
  now serves the site's SPA shell (200, non-JSON) and new2 does the
  same; new4 answers real JSON (verified live 2026-11-20 via WARP).
  Referer moves with the base (the API rejects a mismatched Referer).
- `host_resolvers.unwrap_filepress`: retry the first downlaod call
  once when it answers a Telegram deep-link
  (`https://tgfiles.space?start=...`) instead of a mint token —
  downlaod2 rejects that URL (400 Cast to ObjectId). The same file
  returns a bare token on a later call (measured on one filebee.top
  row: tgfiles, token, tgfiles, token across four calls).
- Tests: `test_unwrap_vcloud_captures_cfstorage_r2_and_pixeldrain`
  (fake fetches), filepress Referer new3 -> new4 (deliberate, see
  above), `test_filepress_unwrap_retries_when_first_call_returns_tgfiles`.

## Evidence per host

- vcloud: page GET 200 (28,941 B), redirects vcloud.beer -> vcloud.my
  (registry-move candidate — note only, not changed here). Double-atob
  decodes to the token page; token page carries cfstorage (R2),
  pixeldrain, hubcloud buttons. HubCloud leg: 302 -> workers.dev ->
  302 -> googleusercontent, Range 200 video/mp4 ~310 MB ftyp. One
  sample failed the first unwrap attempt and succeeded on retry —
  transient fetch flake, not a page variant (both pages byte-identical
  in structure).
- fastdl: page 200 (2,922 B), `var reurl` -> `dl.php?link=<googleusercontent
  URL>`. Range 200 video/mp4 ~310 MB and video/mkv ~1.07 GB. One
  sample failed once (probe2) then minted fine on re-run — same
  transient flake as vcloud.
- filepress: direct Oracle egress 403 on every base/method (6 calls).
  Via WARP (egress confirmed `warp=on`, India): new4 downlaod/
  returns a bare token or a tgfiles deep-link; downlaod2/ swaps a
  bare token for a 1-link list (origin rotates: flapdoodle-space
  hosts); Range probe 206 video/x-matroska, magic 1a45dfa3 — twice,
  two different minted links. new2/new3 rows return False/400 or
  non-JSON (file not in that mirror's index / API route dead).
  filebee.top rows mint; new2 filepress rows do not.
- gdflix: direct 403 + Cloudflare challenge page (5,668 B) on the
  first GET. Via WARP: page 200 (43,799 B), Instant DL button
  (`busycdn.instadl.rest/<token>::<token>`) -> 302 ->
  fastdl-one.pages.dev -> googleusercontent; Range 200 video/mkv,
  1.78 GB and 787 MB, Matroska magic — 2/2. The existing resolver
  already implements this path; only the first-page clearance is
  missing from direct egress. The clearance-loop lane (merged
  78dff25f) owns the egress fix; no gdflix code changed here.
- hubdrive: POST `/ajax.php?ajax=direct-download` -> 200, JSON
  `code=408`, "We couldn't generate a direct download link." on all
  samples, with and without a prior page GET (the page sets no
  cookies at all). `ajax=download` returns HTML, not JSON. Needs a
  browser-harvested HUBDRIVE_CRYPT cookie (FZBypassBot fallback) —
  browser-only for now.
- megaup: page loads (200, 768 KB) but the primary button href is
  `#` (inert), `download_token` and `fNN.megaup.net` appear NOWHERE
  in the full page, no `btndownload` element, the timer div is
  `display:none`. The token URL is constructed by obfuscated JS at
  runtime. Connection resets from direct egress are intermittent
  (2 of 3 samples reset, 1 loaded); via WARP the same rows 404
  (decayed stored rows) or load the same JS shell. Browser-only.

## Boundaries (do NOT re-open without an owner decision)

- filepress/gdflix serve from production only with a WARP egress or
  stored clearance — WARP-in-production is undecided. The resolvers
  are correct; the egress is the gap.
- Never copy the hardcoded `cf_clearance` / `PHPSESSID` literals
  found in the upstream vega extractors — someone else's session
  material. The flows were ported; the literals were not.
- vcloud.beer -> vcloud.my redirect: registry move is a parent call,
  not this lane's.
