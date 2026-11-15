# 2026-11-15 — search speed: FTS is the text match, total is capped, facets go C-level `[SEARCH-FTS-COUNT]`

## What was slow (measured, in-process, read-only live catalog)

- `search("love")`: **8.67 s** — but "love" is a mood word, so it never ran a
  text search at all: `mood_search` maps it to genres romance+drama and the
  query answers as a genre browse. The 8.67 s was step-1 (genre UDF scan,
  ~4.4 s) + an exact COUNT re-scan (~4.3 s).
- `search("the")`: 4.4 s by the same shape (audit number); `search("salaar")`:
  0.03 s. The second COUNT(*) cost ~half of every slow search: "love" with
  the COUNT skipped measured 4.38 s vs 8.67 s with it.
- `browse(genres=["romance"])`: **7.9 s** — same UDF scan.
- `browse(languages=["Hindi"])`: **133 s** — the correlated `EXISTS` over
  `cat_releases` per work (see below; fixed separately in this session).

## The fix (`Fast_Fetch/catalog_api.py`, branch `search-fts-count-20261115`)

1. **FTS is the text match.** When `cat_works_fts` exists, the per-token
   LIKEs are dropped for execution — FTS answers the same tokens from the
   index (~0.03 s) instead of scanning the match set with a per-row haystack
   UDF plus a correlated alias subquery. The FTS table indexes the same
   normalised fields the haystack covers (title, original_title, genres,
   aliases), so the only recall lost is substring-inside-a-word matches
   ("glove" for "love") — token-prefix matching is what every other search
   box does, and the relevance re-sort already ranked prefix hits first. The
   fingerprint is still computed from the LIKE shape, so cursors minted
   before this change keep paging (the FTS set is a subset of the LIKE set,
   in the same sort order). Without the index the LIKE scan runs exactly as
   before, and an FTS `OperationalError` still degrades to the LIKE scan.
   Live FTS is healthy: 197,530 rows = exactly `cat_works`; the projector
   rebuilds it (`catalog_v2.py:1863`).
2. **The total is capped for a search.** `total_estimate` above
   `DEFAULT_TOTAL_CAP` (1000) is reported as the cap with
   `total_capped=True`; a plain browse keeps the exact count (its WHERE is
   cheap — measured 0.53 s for the whole plain browse). The cap is a
   parameter (`total_cap`) threaded through `search()`/`browse()`/
   `_run_listing()` so tests can set it small.
3. **Facet predicates go C-level.** `_facet_clauses` emitted the
   `radd_list_has` Python UDF (JSON-parse + normalise per row): measured
   4.62 s for a `romance` predicate over the live catalog. It now emits
   `instr(lower(col), :needle) > 0` with the needle normalised at bind
   time: **0.26 s, 18x faster**, over-match 8 rows in 23,852 (0.03% —
   longer words containing the genre name). The genres column is mixed
   format (JSON arrays AND comma-separated plain text, e.g.
   `'123Movies, A-Z List, ...'`), which is why `json_each` was rejected:
   it throws "malformed JSON" on the plain-text rows.
4. **Language facet: the EXISTS stays, the COUNT caps.** (Measured after
   the probe found the 133 s.) The language clause's correlated `EXISTS
   (SELECT 1 FROM cat_releases lr WHERE lr.work_id = w.id AND ...)` ran
   one index lookup per work. An IN-subquery replacement measured WORSE
   (119.80 s vs 4.58 s for the EXISTS COUNT — the planner materialises
   the 1.7 M-row scan), and a bare releases scan is 34.6 s, so the EXISTS
   is the best shape this schema offers. The recheck (warm cache) put the
   whole Hindi browse at 4–6 s; the 133 s was a cold-cache first touch of
   `cat_releases` under contention. The remaining protection is the cap:
   the total caps for ANY listing with a text query or a facet (not just
   searches), so a cold language browse is step-1 (~7.7 s cold) + a
   capped COUNT (~0.3 s) instead of step-1 + a 2.6 s exact COUNT. A plain
   unfiltered browse keeps the exact count.

## Measured after the fix (in-process, read-only live catalog)

- search "love" (mood browse): **0.65 s** (was 8.67 s), total now capped
  at 1000 (the mood browse is faceted)
- search "the": **0.24 s**, total 1000 capped
- search "salaar": **0.01 s**; search "cafe": **0.04 s**
- browse genre romance: **0.60 s** (was 7.9 s), total now capped at 1000
- browse language Hindi: **4–6 s warm** (was 133 s cold on first touch of
  `cat_releases`; the warm step-1 is ~4 s of correlated EXISTS lookups —
  the best shape this schema offers, since an IN-subquery measured 119.8 s
  and a bare releases scan 34.6 s; the cap keeps the cold total under the
  15 s edge)
- plain browse: 0.53 s, total 182,078 exact

## App side (same branch)

- `WireBrowseResponse` gains `total_capped`; `V2BrowsePage` carries it;
  `SearchViewModel.resultsSummary` prints "Showing N of 1000+ results"
  when capped; the v1 fallback and fresh-search resets set the flag false
  (v1 totals are exact). Tests: capped wording + wire deserialisation.

## Tests (local, this VM)

- `test_catalog_api.py`: **138 OK** (2 new: capped total; FTS answers the
  pinned queries — cafe, tommy the movie, dune 2024, hindi dubbed, nova —
  plus a genre/language facet equivalence test against `_list_has`).
- `test_fts_search.py` 9 OK, `test_redact.py` 104 OK, `test_search_forgiving.py`
  11 OK, `test_viewer_search_forgiving.py` 20 OK, `test_catalog_v2.py` 106 OK.
- `test_api_v2.py` **83 OK** (2 new assertions: a search reply carries
  `total_capped` on the wire; the manifest's pagination `carries` lists it).
- Full backend gate (101 suites) runs in CI on the branch push.

## NOT live

Gate deploy is the supervisor's call, as always. The live service still
runs the old code until then.
