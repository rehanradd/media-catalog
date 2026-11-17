# mp4moviez + humtv join the merge fleet (2026-11-17)

Branch `muse/mp4moviez-humtv-merge-20261117`, from main `a0caae24`.
Code only — the data merge itself runs at the gate (main chat owns it).

## Why these two were never merged

Both sites were scraped and staged but sat outside `merge_fleet.py`'s 18
sites. The fleet would never have seen them: the default staging path is
`<site>.db`, and neither site has one that merges — `mp4moviez.db` carries
posts+releases but NO `sources` table (the merge raises "neither
download_sources nor sources"), and `humtv.db` does not exist at all.

## Staging measured (read-only, Oracle VM, 2026-11-17)

| staging DB | posts | releases | sources | is_18_plus | source_site |
|---|---|---|---|---|---|
| `mp4moviez_staging.db` (34 MB, Nov 9) | 11,404 | 54,267 | 54,267 | 43 | mp4moviez |
| `mp4moviez_staging2.db` (34 MB, Oct 29) | 11,404 | 54,267 | 54,267 | 43 | mp4moviez |
| `humtv_staging.db` (1 MB, Oct 29) | 64 | 282 | 282 | 0 | humtv |

Both staging DBs are the "staging" family (`posts`/`releases`/`sources`,
no `id` column — rowid-keyed; `merge_catalogs` already handles that).
`mp4moviez_staging2.db` is a content-identical duplicate of
`mp4moviez_staging.db` (same 11,404 canonical keys both ways, 0 diff) —
the fleet reads the first, never both. The duplicate is left on disk;
deleting staging data is not this branch's call.

## The change

- `Fast_Fetch/merge_fleet.py`: `SITES` 18 → 20 (`mp4moviez`, `humtv`
  appended); `SOURCES` maps both to their `*_staging.db` files, with a
  comment recording why the default path cannot work.
- `Fast_Fetch/test_merge_fleet.py`: 3 new pin tests
  (`FleetSiteListTests`) — both sites in `SITES`, both `SOURCES` entries
  point at the `_staging` files, every `SOURCES` entry names a fleet
  site. A silent drop would skip the site forever with only a
  "[!] missing" line in the fleet log.
- No registry change: both importers hardcode their `BASE_URL`
  (`mp4moviez.deal`, `hum.tv`) and never read `domain_registry.json`,
  so no pin moves (stays 27 sites). No `backend-ci.yml` change:
  `test_import_humtv.py` stays in `UNREGISTERED` (deliberate per-site
  exclusion) and `test_convert_mp4moviez.py` is already gated.

## Proof run — full fleet merge into a scratch target (hatch VM)

Copies of both staging DBs merged through the real fleet path
(`merge_fleet.main`, `DATA_DIR` redirected) into a scratch target DB —
never the live catalog. 238.3 s total, exit 0:

- mp4moviez: posts_merged 11,404, posts_created 11,398, releases 54,267,
  sources 54,267, conflicts 0, adult folded 0.
- humtv: posts_merged 64, posts_created 34, releases 282, sources 282,
  conflicts 0. (30 posts collapsed onto sibling works — episode pages
  of the same drama, e.g. Sang-E-Mah; the retargeting logic working.)
- Target verified: `integrity_check` ok, 11,432 posts (11,398 mp4moviez
  + 34 humtv), 54,549 releases, 54,549 sources.

## Adult-flag finding — the merge CORRECTS the staging data

The target holds 268 flagged rows vs 43 flagged in staging. All 43
staged flags survived; the 225 extra are `save_scraped_data`'s shared
rule re-deriving from title+slug, reproduced exactly offline (225/225):

- `ullu` 130, `hotshots` 51, `primeplay` 23, `naughty` 11, `kooku` 10.

The sample looks alarming at first — "Rangeela", "Andhadhundh",
"Graduate", "Nurse", "Ek Haseena Thi" are mainstream film titles — but
every one of them is an Ullu/Hotshots/PrimePlay/Kooku web series reusing
a mainstream name: the slug says so
(`rangeela-(2026)-s01-part-1-hindi-ullu-web-series-hd-52519`,
`andhadhundh-(2026)-season-1-hindi-ullu-complete-web-series-hd-54654`).
The mp4moviez importer under-flags adult content (it never looks at the
slug's studio token); the merge's shared rule catches it. Zero
mainstream over-flagging in the 225. This is pre-existing shared-rule
behavior, not new code — the branch only routes the sites through it.

## Tests run solo on the branch (CI is the gate)

- `test_merge_fleet.py` 17/17 (was 14, +3 pin tests), 37.3 s.
- `test_merge_catalogs.py` 18/18.
- `test_docs_invariants.py` OK (checkpoint count bump checked below).
- `importers/test_import_humtv.py` 23/23; `importers/test_convert_mp4moviez.py`
  15/15. (`test_import_mp4moviez.py` does not exist — only the converter
  test does; the importer itself has no test module.)

## Index bookkeeping

- New checkpoint file: this file. `CONTINUITY.md` archive count
  167 → 168 session files (165 → 166 dated + 2 undated);
  `checkpoints/README.md` gains the newest-first entry above.
- Full proof log + report: `~/workspace/merge-proof-20261117/`
  (`proof.log`, `target.db.report.json`). The scratch target DB and the
  staging copies were deleted from `/tmp` after the run (transport
  scratch; the VM originals are untouched).
