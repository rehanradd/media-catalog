# Language bridge table: Hindi browse 113s cold → indexed EXISTS (2026-11-15)

## The problem (measured)

`browse(languages=["Hindi"])` step-1 on the live catalog, read-only:

- Full query (instr on `w.languages` OR correlated EXISTS over 1.7M
  `cat_releases` rows): **113.3s cold** (I/O-wait), 4–6s warm.
- `w.languages`-only variant: 0.169s warm.
- `sqlite3 .expert`: **(no new indexes)** — no index fixes the correlated
  EXISTS. The EXISTS cannot be dropped: work 245 is EXISTS-only in the top
  21 (Hindi audio on a print whose work-level languages don't list Hindi).
- Design note: `~/workspace/language-browse-expert-20261115.md`;
  full design: `~/workspace/language-bridge-design-20261115.md`.

## What changed

- `catalog_api.rebuild_language_bridge(conn) -> int` — builds
  `cat_work_languages(work_id, language)` + `idx_work_languages_lang
  (language, work_id)` from `cat_works.languages` and every release's
  `audio_languages`, split with `_json_list`. For each language item it
  stores the normalized item AND each word inside it, so
  `bl.language = :lang` is exactly `_list_has` — "Hindi Dubbed" matches
  Hindi, "Hindistani" does not (the old `instr()` substring matched it).
  Runs inside the caller's transaction; WAL readers never see a half-built
  bridge. Returns the row count.
- `catalog_api._bridge_usable(conn)` — table exists AND stamp
  `language_bridge_at` == `projected_at`; any error → False (same stamp
  discipline as the needle snapshot).
- `catalog_api._facet_clauses(..., use_language_bridge=False)` — when True,
  the language clause is one indexed EXISTS over the bridge; when False,
  the old `instr()` + correlated-EXISTS shape, unchanged. All three call
  sites (browse, search, mood fallback) pass
  `use_language_bridge=_bridge_usable(conn)`, mirroring the FTS pattern.
- `catalog_v2._finalize` — after the needle block, a mirrored try/except
  rebuilds the bridge and stamps `language_bridge_at` with the same `now`
  as `projected_at`. A build failure is a warning, never a gate. New lazy
  `_catalog_api()` helper (mirrors `_redact()`; `catalog_api` imports
  `catalog_v2` at module level, so the import must stay lazy).

## Tests

- `test_catalog_api.LanguageBridgeUnitTests` (2): builder stores items +
  words exactly (`(2, "hindi dubbed")`, `(2, "hindi")`, `(2, "dubbed")`;
  no `(3, "hindi")` for "Hindistani"); usable only with a matching stamp.
  Lesson: `_meta_value` uses `row["value"]` — the unit conn needs
  `row_factory = sqlite3.Row` like production, or the lookup raises and
  `_bridge_usable` silently returns False.
- `test_catalog_api.LanguageBridgeTests` (3): end-to-end — projection
  builds the bridge; `browse(languages=["Hindi"])` equals the `_list_has`
  set (Hindi Film + dubbed-only Dubbed Film + "Hindi Dubbed" film in;
  "Hindistani" Near Miss Film out — the proof the bridge, not the old
  shape, answered); stale stamp and missing table both fall back to the
  old shape (which still serves Hindi, over-matching the near miss as it
  always did).
- `test_catalog_v2.LanguageBridgeProjectionTests` (1): projection builds
  the bridge + stamp == `projected_at`; a second projection does not
  duplicate rows (PK holds).
- Suites green locally: test_catalog_api + test_catalog_v2 = **251 OK**
  (was 245; +6 new); test_redact + test_serve_verify +
  test_search_forgiving + test_viewer_search_forgiving + test_api_v2 =
  **283 OK**. Backend-ci suite count stays 102 (new tests went into
  existing suites).

## Verification still owed (after the gate deploy)

- Cold + warm step-1 for Hindi browse, in-process on the live catalog:
  target <1s warm, no 100s+ cold tail.
- `EXPLAIN QUERY PLAN` on the new step-1: indexed search on
  `idx_work_languages_lang`, no SCAN w.
- The first restart after the gate still pays the old cost until the next
  projection builds the bridge (same rollout shape as the needle snapshot).

## Out of scope

- Genre bridge: the genre `instr()` already answers in 0.26s — do not
  build it until a measurement says otherwise.
