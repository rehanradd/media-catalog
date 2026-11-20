# 2026-11-20 — Simkl director

Follow-up to the Simkl enricher (20261120-simkl-enricher.md): the movie
payload carries a top-level `director` string, verified live for The
Matrix (`"Lana Wachowski"`). It was the one field the enricher's list
left unwired.

## Change

- `simkl_enricher.extract_metadata` now returns `director` when the
  payload carries a non-blank string; blank / non-string / missing
  stays absent, never invented.
- `metadata_backfill.FIELD_SOURCES["director"]` gains `simkl` right
  after `tmdb`: `("tmdb", "simkl", "omdb", "cinemeta")`.
- `SimklSource.lookup` maps it (`out.set("director", ...)`), so a
  backfill run writes `posts.director` from Simkl when TMDb has none.
  Cast stays TMDb-only — Simkl summaries carry no cast.
- Header doc order line updated to match.

## Proof

- Local: `test_simkl_enricher.py` 24/24 (was 23, +1 director test),
  `test_metadata_backfill.py` 90/90, `test_docs_invariants.py` 22/22.
- Live (Oracle VM, key read from the VM-local keys file, never
  printed): `/movies/tt0133093` returns `director: 'Lana Wachowski'`
  (str); the new `extract_metadata` returns it with all 7 previously
  wired fields (rating 8.7, year 1999, tmdb_id 603).

## Notes

- Branch `muse/simkl-director-20261120` from `620453a3`. NOT merged —
  parent merges; no live deploy, no DB touched.
- CONTINUITY byte budget: the file sat at 11,941 B of 12,000, so the
  H4 R7 index line was trimmed to its core claim (full text lives in
  its checkpoint file) to fit this entry.
