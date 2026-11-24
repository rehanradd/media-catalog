# DB-08 — Projection gaps: diagnosis (Part A) + fix plan (Part B)

**Lane:** Phase 4 build DB-08 · **Date:** 2026-11-24 ~06:49–08:15 PKT
**Branch:** `muse/phase4-db08-projection-gaps-20261124` (base `a6e27ad6`, FIX1)
**Method:** all live reads via `sqlite3 "file:…?mode=ro"` over SSH; no live writes,
no gate, no restart. Key inputs dumped to `~/workspace/phase4/db08_{allposts,newposts}.tsv`
+ `db08_works.txt`; keys recomputed locally with the repo's own
`catalog_v2.work_key_for` / `work_type_for`.

## A. Fresh census (live, 2026-11-24 ~06:52 PKT — DB unmoved since db-read)

| measure | fresh | db-read (~04:37 PKT) |
|---|---|---|
| posts / releases / download_sources | 227,101 / 2,006,320 / 4,421,713 | identical |
| cat_works / cat_releases / cat_links | 192,210 / 1,678,982 / 4,041,076 | identical |
| releases with no sources | **46,586** | 46,586 ✓ |
| releases with no post (orphan) | **0** | 0 ✓ |
| posts with no releases | **1,201** | 1,201 ✓ |
| posts − cat_works (arithmetic gap) | **34,891** | 34,891 ✓ |
| cat_works with no cat_releases | 392 | 392 ✓ |

**Correction to the TODO wording:** "1,201 post-less releases" is a mislabel.
Releases without a post = 0 (FK orphans 0, twice). The 1,201 is **posts
without releases** (release-less posts).

Last projection: `cat_projection_meta.projected_at` = 1795295339.33 =
**2026-11-22 02:08:59 PKT**, at which the run recorded `posts=225,593`,
`works=192,210`, `seconds=3658.89` (~61 min full rebuild). **1,508 posts
arrived since** (227,101 − 225,593; also `created_at > '2026-11-22 02:08:59'`
counts 1,508; first new id 225,594).

## A1. Sourceless releases (46,586) — importer-side silent unwrap failure

By site: bolly4u 26,430 (56.7%), katmoviehd 18,916 (40.6%), desicinemas 984,
world4ufree 138, toonworld4all 47, rogmovies 36, vegamovies 26, kisskh 5,
filmyhit 4. By month: 2026-10: 8,427; 2026-11: 38,159. Single-day spike:
**2026-11-16 = 25,948 bolly4u + 10,995 katmoviehd** (bolly4u filed 40,250
releases that day; its own crawl log ends `28280 scraped, 7735 failed …
releases=40,970 sources=21,875` — the crawler knew and finished anyway).

**Staging proves the merge is innocent:** bolly4u staging has 26,475
sourceless / 41,004 releases (live 26,430); katmoviehd staging 19,528 /
146,244 (live 18,916); desicinemas staging 987 / 5,411 (live 984).
`merge_catalogs` maps staging `sources` → `download_sources` 1:1.

**Code mechanism (all three importers share it):** the release row is
inserted unconditionally; sources are inserted only for the unwrap's return
list; every unwrap returns `[]` silently on any failure
(`unwrap_linkskeep`: non-200 / no `<form>` / no inputs / exception →
`return []`; `resolve_file_sources` kmhd path: REST API answers but
`upload_links` empty → falls through all six mirror sections with no
fallback; `unwrap_trembed`: same silent `[]`). No failure is recorded
anywhere — a never-attempted release and a failed unwrap look identical.

**Bridges survive:** 45,606 sourceless releases keep a usable
`intermediate_url` (bolly4u: new2.linkskeep.lat 19,382, photojin.study 7,041;
katmoviehd: links.kmhd.eu 11,150, links.kmhd.me 4,465, kmhd.eu 2,506,
gd.kmhd.eu 451, inks.kmhd.eu 227, + others). **980 have NULL
`intermediate_url`** (973 desicinemas — pages with no trembed/iframe links
at all — plus 7 others); those need a re-scrape, not a bridge fallback.
Live single-probe: linkskeep `302` (alive); links.kmhd.me `523` (erroring
from Oracle egress at probe time — one probe, not a verdict).

## A2. Release-less posts (1,201) — foreign pages filed as 'vegamovies'

By filed site × URL host: vegamovies×moviesflix.casa 786 (all created
2026-11-22 19:47–19:51), vegamovies×www.mp4moviez.deal 157 (2026-11-09),
vegamovies×filmyhit.mobile 129, vegamovies×filmywap.site 1;
same-site: themoviebox×themoviebox.xyz 60, aryplus×aryplus.tv 45,
multimovies×m1.multimovies.courses 23. **All 1,201 have a URL host outside
their filed site; 0 share a canonical_key with a release-having sibling
(only 26 share a key at all)** — dedupe is not the cause.

**Label source:** the unified posts DDL carries
`source_site TEXT DEFAULT 'vegamovies'` (`fast_fetch.py:1962`) AND the
validator does `data.get("source_site") or "vegamovies"`
(`fast_fetch.py:7381`). The vegamovies importer's own save path REFUSES
release-less posts (`no_releases` skip), and the surviving vegamovies
scraper log mentions moviesflix 0 times — so these arrived via the generic
save/merge funnel, which accepts zero-release posts (the funnel only parks
the sitemap row as `parse_no_releases`; the post row still lands). The true
origin survives only in `post_sources` (1,215 rows over these posts, with
the correct site: moviesflix / mp4moviez). **Scale warning:** 105,109 of
113,639 vegamovies-labelled live posts (92.5%) have non-vegamovies URLs —
the label is systematically unreliable, not 1,201 isolated rows.
(The `UPDATE` path in `_save_scraped_data` never touches `url` or
`source_site`, so these were filed as NEW posts that way; the exact Nov-22
writer is not proven from surviving logs.)

Latent sibling: multimovies staging DDL defaults `source_site` to
'vegamovies' (its DB was initialised from the unified DDL) — no live
vegamovies-labelled multimovies-URL rows today, but the default is live.

## A3. Unprojected posts (34,891) — folding + staleness, NOT a projector defect

`catalog_v2.project()` iterates **every** post (`SELECT … FROM posts ORDER
BY id`, no skip) and groups by `work_key_for(clean_title, year, work_type,
imdb_id, tmdb_id)`. Independently reproduced with the repo's own functions
over a full key-input dump: 225,593 posts (id ≤ 225,593) → **192,208**
distinct keys vs 192,210 live works; the 2-key residual is fully explained
by post-projection year backfills (4 computed `:YYYY` keys vs 6 stale `:ny`
keys in live works — same titles, year filled after the run). **Zero posts
dropped by the projector.**

Decomposition of 34,891: **33,383 legitimate folding** at projection time
(33,385 by recomputation; multi-post keys 23,958, largest
`series:bigg boss hdtv:2018` ×116) **+ 1,508 posts arrived after the last
run**, of which **1,503 would mint new works and 5 fold** into existing
works (computed with the repo's own key function against the live
`cat_works` key set). True "never projected" set = 1,508 posts.
Expected after a re-projection: works 192,210 → **193,713 (+1,503)**;
cat_releases ≈ +15.5k mapped (18,427 ledger releases arrived since the run,
minus the dup-collapse rate). The real defect is operational: no scheduled
or incremental re-projection and no staleness alert since 2026-11-22.

## B. Fix (this branch)

1. **Bridge fallback at scrape time** (new releases never sourceless when a
   pointer exists; mirrors the existing bbupload/gofile/1xplayer gateway
   fallbacks): `import_bolly4u` (linkskeep/photojin unwrap → `[]` ⇒ store the
   bridge URL as an `unverified` source), `import_katmoviehd`
   (`resolve_file_sources` kmhd path: REST data but zero mirrors ⇒ append
   the KatMovieHD Gateway fallback), `import_desicinemas` (trembed unwrap →
   `[]` with trembed URLs present ⇒ store the trembed bridge).
2. **Backfill script** `Fast_Fetch/backfill_sourceless_bridges.py`
   (dry-run default; `--apply` is gate-only; idempotent via NOT EXISTS):
   one bridge-fallback source per sourceless release with a non-empty
   `intermediate_url` — **45,606 rows** in scope; 980 bridgeless + unmapped
   hosts are reported, never fabricated.
3. **Gap-2 guard** in `fast_fetch.py`: `source_site_from_url()` host→site
   map; `_validate_scraped_data` no longer defaults a missing label to
   'vegamovies' (host-derived, else `'unknown'`); `_save_scraped_data`
   overrides a label the URL host contradicts; unified DDL default
   `'vegamovies'` → NULL for fresh DBs. Zero-release posts are still
   accepted at the choke point (a refuse would drop legit metadata-only
   saves as merge conflicts); the repair for the 1,201 is re-label from
   `post_sources` + re-parse under the correct site — see gate note.
4. **Projection-gap auditor:** `catalog_v2.projection_gap(conn)` +
   `Fast_Fetch/projection_audit.py` CLI (mode=ro, JSON, exit 2 when
   unprojected works exceed the threshold); `project()` now also stamps
   `posts_max_id` in `cat_projection_meta` so the auditor can use the id
   watermark instead of wall-clock comparison.
5. **Tests:** `Fast_Fetch/test_db08_projection_gaps.py`, registered in
   `.github/workflows/backend-ci.yml` (163 → 164 🧪 suites).

## Gate-ready note (NOT executed — gate owns the live runs)

Pre-conditions: gate backup verified first (integrity `ok`, counts exact),
sweep stopped per the gate procedure. All three runs are independent and
may run in one gate, in this order:
1. **Backfill:** `python3 -S Fast_Fetch/backfill_sourceless_bridges.py --db
   /home/ubuntu/radd-stream-data/merged_catalog_20261025.db --apply`
   (dry-run first without `--apply`). Expected: **45,292 inserted from the
   three mapped sites** (bolly4u 26,423, katmoviehd 18,858, desicinemas 11)
   **plus whatever the dry-run maps of the remaining 314** other-site
   bridges (unmapped hosts are reported, 0 inserted for them) — ceiling
   45,606. Verify: sourceless releases 46,586 → **≤ 1,294** (980 bridgeless
   + unmapped remainder). Rollback: restore the gate backup (script is insert-only,
   one row per release; no deletes/updates).
2. **Re-projection:** full `catalog_v2.project()` rebuild (~61 min on the
   live box at last run; run from the gate's work copy, never while the
   sweep runs). Expected: works 192,210 → **193,713**; `posts` in meta
   227,101; verify with `python3 -S Fast_Fetch/projection_audit.py --db
   <db>` → `new_works: 0`. Rollback: restore the gate backup (rebuild
   re-allocates every `cat_*` id — there is no incremental undo).
3. **Re-label repair (1,201 posts):** `UPDATE posts SET source_site =
   <post_sources site> WHERE id IN (…)` for the 1,073 foreign rows, then
   re-run the correct site's importer over those URLs to attempt release
   extraction; the 128 same-site rows stay as-is (parser found nothing —
   re-scrape only). Exact statement + row list to be generated from the
   live DB at gate time (this branch does not ship a live UPDATE).
   Rollback: restore the gate backup.
