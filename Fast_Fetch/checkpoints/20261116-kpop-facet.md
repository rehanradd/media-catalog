# 2026-11-16 — K-pop genre facet `[KPOP-FACET]`

The app's Search KPOP pill was a text-search preset (`q=kpop`, 2 real
titles) because the server had no K-pop facet — `genre=music` answered
the unfiltered total (207,919 rows; unknown genre keys are silently
ignored). This slice adds a real facet, `genre=kpop`, on both API paths.

## Live probe (read-only, 2026-11-16, 225,080 posts / 197,530 works)

`q=kpop` (public API): 2 works — "KPop Demon Hunters (2025)" (genres
null, industry Hollywood, country USA, language Multi) and "KPOPPED"
(2025 series, tmdb 296976). The flagship titles carry NO k-pop token
in `genres` — that is the data gap, not a query gap.

Candidate predicates, measured on the live `posts` table:

| predicate | rows |
|---|---|
| `genres LIKE '%kpop%'` | 0 |
| `genres LIKE '%k-pop%'` | 3 (BTS World Tour "Arirang" ×2, Love Class S3) |
| `clean_title LIKE '%kpop%'` | 10 (KPop Demon Hunters variants + KPOPPED) |
| `clean_title LIKE '%k-pop%'` | 8 (K-Pops! variants ×6, Burning Sun doc, AC Bonifacio) |
| `genres LIKE '%music%'` | 2,356 — too broad |
| korean AND music-genre | 70 — too broad, misses KPop Demon Hunters |
| **union (title OR genres, both spellings)** | **21 posts** (10 + 8 + 3, all disjoint) |

`cat_works`: 14 title works + 3 genre works (disjoint) = **17 works**.

## Predicate chosen (evidence, not guess)

v1 (`GENRE_FILTER_SQL`, alias `p`):
`(lower(p.clean_title) LIKE '%kpop%' OR lower(p.clean_title) LIKE '%k-pop%'
OR lower(p.genres) LIKE '%kpop%' OR lower(p.genres) LIKE '%k-pop%')`.

A genres-only predicate (3 rows) would MISS the flagship titles — wrong.
Korean+music (70 rows) is over-broad and misses KPop Demon Hunters
(industry/language Unknown) — wrong. Title arm uses `clean_title`
(not `title`: 10 vs 9 rows; clean_title is what the app displays).
Precedent for a non-genres arm: animation's `is_animated` arm in the
same dict. The facets invariant holds: the facets aggregate iterates
`GENRE_KEYS`, so the new key appears with its true count (21 live).

v2 (`catalog_api._facet_clauses`): the same data gap exists — the
clause matches genre terms ONLY against `w.genres` via
`instr(lower(w.genres), :genre)` after `normalize_text`, so
`genre=kpop` on v2 returned **0 works** today. The app's Search sends
genre to v2 search + v1 posts (scheduleSearch v2+v1, loadMore v2+v1),
so a v1-only facet would leave the facet split-brained (21 on v1, 0
on the app's primary search path). Added a narrow special case for the
normalised term "kpop" or "k pop" ("k pop" is the normalised form of a
client-sent "k-pop"): genres arms "kpop"/"k-pop" + title arms
"kpop"/"k-pop" against `w.title`, 17 works live. No other key's
predicate changed on either path.

## Changes

- `Fast_Fetch/viewer.py`: `"kpop"` appended to `GENRE_KEYS` (10 → 11
  keys, comment updated); predicate added to `GENRE_FILTER_SQL` with
  the evidence comment; facets docstring "the 10 panel categories" →
  "the 11 panel categories".
- `Fast_Fetch/catalog_api.py`: `_facet_clauses` kpop special case
  (above).
- `Fast_Fetch/test_home_rails.py`: `FacetsTests.test_genre_counts`
  expected dict gains `"kpop": 0` (base fixture has no kpop rows);
  `GenreFilterTests.test_all_ten_categories_filter` renamed to
  `test_all_eleven_categories_filter` with `"kpop": set()`; new
  `KpopGenreTests(Fixture)` — own setUp ingests 3 rows (title-arm
  "KPop Demon Hunters" with genres ["Action","Music"] and no k-pop
  token; genre-arm "BTS World Tour" with genres
  ["Documentary","Music","K-Pop"] and a token-free title; hyphen-title
  "K-Pops!" with genres ["Comedy"]) — `test_kpop_filter_returns_title_and_genre_rows`
  (exactly those 3 ids), `test_kpop_filter_is_case_insensitive`
  (`genre="KPOP"`), `test_kpop_facet_count_matches_filter` (facet
  count 3 == filter total). Rows were NOT added to the shared
  `Fixture.setUp` (it would ripple through pinned counts).
- `Fast_Fetch/test_catalog_api.py`: `add_post` gains an optional
  `genres` kwarg (default `'["Action"]'`, existing behaviour
  unchanged); new `KpopGenreFacetTests(CatalogApiTestCase)` with a
  minimal 4-work corpus (same 3 signal arms + a Dune control) —
  `test_genre_kpop_returns_title_and_genre_works` (browse, all 3),
  `test_genre_kpop_hyphen_spelling_returns_the_same_works`
  (`genres=["k-pop"]`, all 3), `test_genre_kpop_in_search_title_arm`
  (q="kpop" + genre=kpop), `test_genre_kpop_in_search_genre_arm`
  (q="tour" + genre=kpop → BTS only; q="dune" + genre=kpop → []).

## Test failure found and fixed during the slice

The first version of the search title-arm test expected q="kpop" +
genre=kpop to return "K-Pops!" too. It does not: v2 search tokenises
"K-Pops!" to "k pops", so the token "kpop" never matches its haystack.
That is pre-existing search tokenisation (v1 FTS `q=kpop` also misses
"K-Pops!" today — the public API's 2 works are the proof), NOT the
facet: browse with genre=kpop DOES return it. Test expectation
corrected to {"KPop Demon Hunters"} with the reason in a comment.
Fixing search tokenisation for hyphenated titles is a separate slice,
not this one.

## Verification

Local: `test_home_rails.py` 51 OK, `test_catalog_api.py` 151 OK
(`python3 -S`, stdlib only). No other test module pins `GENRE_KEYS`
or the ten-category count (grep). Backend-ci run **43754496819**:
**SUCCESS** on the pushed tip 42d267cc (completed ~00:5x PKT
2026-11-16).

## Not live, not app-switched

The app keeps its `q=kpop` preset until this facet is LIVE after a
gate deploy; the app-side switch to `genre=kpop` is a separate later
slice. Nothing here deploys or restarts anything live.
