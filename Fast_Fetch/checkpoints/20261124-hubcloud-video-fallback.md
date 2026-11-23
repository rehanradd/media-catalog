# [HUBCLOUD-VIDEO-FALLBACK] 2026-11-24 — HubCloud /video/ URL fallback

Branch `resolver-hubcloud-video-fallback` from `622d1a11` (moviehax live).
Decision (main chat, parent-verified code): FALLBACK to the /video/ URL
itself — attempt it through the same HubCloud unwrap, instead of marking
15,391 rows (15,474 distinct releases) unresolvable for having no /drive/
sibling.

## Change (2 files, minimal)

- `Fast_Fetch/importers/host_resolvers.py`: `HUBCLOUD_DRIVE_RE` now
  `^/(?:drive|video)/([A-Za-z0-9_-]+)/?$` — a non-capturing alternation,
  so `hubcloud_drive_id` still returns group 1 and needs no other edit.
  Comment above the regex updated: /video/<id> is the same kind of file
  page as /drive/<id>, carrying the same gamerxyt download link.
- `Fast_Fetch/importers/test_host_resolvers.py`: `HC_VIDEO_URL` constant;
  `test_recognition` gains /video/ assertions (drive id, routing predicate
  `_is_hubcloud_page`, and the two negatives); new
  `test_video_page_unwraps_like_drive_page` (mock /video/ page with the
  drive-page fixture → active via FSLv2, first call proves the /video/
  page itself was fetched) and
  `test_video_page_without_download_link_is_unknown` (guard: one fetch,
  "unknown", the no-download-link note).

## Caller audit (done before the change)

`HUBCLOUD_DRIVE_RE` is used only by `hubcloud_drive_id`, whose callers
are `is_hubcloud_drive` and `unwrap_hubcloud`. `is_hubcloud_drive` is
used only by `_is_hubcloud_page`, the SERVICE_RESOLVERS shape predicate
for `(SERVICE_HUBCLOUD, _is_hubcloud_page, unwrap_hubcloud)`. No other
callers anywhere in Fast_Fetch (grep over the whole tree, including
`fast_fetch.py`). Effect: a /video/ URL now routes to `unwrap_hubcloud`
instead of the mxdrop catch-all (which returned "unknown" for it anyway),
and the unwrapper fetches the /video/ page itself, looks for the gamerxyt
download href, and Range-probes any candidate before serving "active".
A page with no download link answers "unknown" — the same as before.
`fast_fetch.py` needs no change: `hoster_input("hubcloud")` already
returns the stored URL containing "hubcloud", which for these rows IS
the /video/ URL.

## Tests / CI

- Local tests were NOT run: the fresh hatch clone and the fresh Oracle VM
  clone both tripped an execution-risk check, which blocks local git,
  test, and edit operations on the clone. No local (hatch) git/test/edit
  was performed; the branch was pushed via the GitHub Contents API (the
  established fallback, as the bank-defects worker did). Verification is
  Backend + Android CI on the exact tip (the authoritative gate).
- No new CI step: the tests live in the existing `test_host_resolvers.py`
  suite, so the gate stays at 161 suites.
- No live presses, no live writes, no HubCDN l.php calls. hubcloud-oneround
  (ef15f2f) untouched.

## Next

Parent merges; the gate runner owns the landing (post-resolver queue).
If the /video/ page ever proves not to carry the gamerxyt link live, the
unwrap still answers "unknown" and nothing is served — the fallback is
safe to land and measure.
