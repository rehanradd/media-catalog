# 2026-11-15 — One Piece detail 504: three hot spots, 63 s → ~7 s [DETAIL-504]

Symptom (loop, 06:35 PKT): `GET /api/v1/posts/61197` → HTTP 504 at ~15.1 s,
3/3 attempts; `/api/v1/posts/1551` → 200 instantly. Identity correction:
post 1551 IS "One Piece" (360 releases); post 61197 is a Vegamovies pack
post "Download One Piece: East Blue (Season 1) Complete …" with **9,480
releases, 9,554 sources**. The slow one is 61197.

## The route (all measured in-process on the Oracle VM, live catalog)

`api_v1_get_post_detail(post_id, ladder=True, warm=False, verify=True)` —
the route shape. The app needs the full releases list
(`ReleaseLayoutBuilder` builds the season/episode layout from it), so the
fix is speed, not capping the payload. Response JSON for 61197 is ~7.2 MB;
option_groups = 1,180 groups, 2,372 tiers.

## Hot spot 1 — `redact.scrub_text`: 34.26 s → 3.33 s

cProfile of `_fetch_releases_with_sources`: `scrub_text` 19.1 s of 22.5 s
(85%). Per call it did `sorted(_TEXT_NEEDLES)` + `out.lower()` ONCE PER
NEEDLE + a fresh `re.sub` per needle: 28,440 scrub calls × 2
`_drop_needles` calls × 71 needles ≈ 4.27M `.lower()` calls.

Fix (`redact.py`): `_rebuild_needle_indexes()` builds `_NEEDLES_BY_LENGTH`
(sorted once), `_NEEDLE_PATTERNS` (one compiled regex per needle),
`_GLUED_NEEDLES` + `_GLUED_RE` (one alternation replacing the per-word
`any(n in core …)` scan); called once at module init AND at the end of
`set_needles()`. `_drop_needles` rewritten: ONE `out.lower()` per call
(sound because substitutions only DELETE text), compiled patterns, glued
check via `_GLUED_RE.search(core)`. `scrub_text` split into a thin
wrapper + `@functools.lru_cache(maxsize=4096) _scrub_cached(text)`;
`set_needles()` also calls `_scrub_cached.cache_clear()` so a vocabulary
swap can never serve stale scrubs. Behavior-preserving by construction.

Test: `test_scrub_text_memoizes_repeated_titles_and_follows_vocabulary_changes`
in `test_redact.py` — repeats the real 61197-shaped title 200×; swaps
needles to {"rogmovies"} and asserts "VegaMovies" now SURVIVES (a bare
brand word; a domain tail like "| Vegamovies.is" is dropped by the
domain-shape rules whatever the vocabulary says — the first version of
the test failed on exactly that). `test_redact`: 105 OK (104 + 1 new).

## Hot spot 2 — `_option_groups` verify: 7.35 s → 2.26 s, and it was a silent no-op

cProfile (verify=True): 9.97 s profiled; 7.57 s is `get_db()` — 2,360
calls (`_option_groups` opened `with get_db()` INSIDE the per-scope loop
→ 1,180 connections) + one ThreadPoolExecutor per scope.

Worse — a correctness bug: `get_db()` uses `sqlite3.connect(DB_PATH)`
with DEFAULT `check_same_thread=True`, so the main-thread connection
**cannot be used from pool workers at all** (proven empirically:
worker-thread execute → ProgrammingError, swallowed by
`except sqlite3.Error` in `_verify_tier_link` → row=None → falls
through to verify_candidate → "unchecked"). **The cached verify pass was
a silent no-op in production: dead bands were never hidden,
`verified_at` was never stamped.** Existing tests missed it because they
call `_verify_tier_link` directly on the main thread or inject fake
verifiers.

Fix (`viewer.py`): `_verify_option_tiers` gains `inline: bool = False` —
when True, verifications run on the caller's thread filling the same
`results` dict, then the SHARED tail (poison guard, evidence recording,
kept-tier building) runs unchanged; the pool path (else branch) is
byte-identical. `_option_groups` hoists ONE verify connection for the
whole call and passes `inline=_verifier is None` (production cached
verifier → inline; an injected verifier keeps the threaded path).

**My own bug, found + fixed (~30 min):** the flag was first named
`concurrent: bool = True` with `if not concurrent:` — but the function
begins with `import concurrent.futures`, and an import inside a function
makes that name LOCAL for the whole body, so `concurrent` always
resolved to the (truthy) module object and the pool path ran regardless.
Proven by a thread-print test (verifier ran on ThreadPoolExecutor-0_0
with concurrent=False). Fix: renamed the param to `inline` AND moved
`import concurrent.futures` into the else branch. A docstring note in the
function records the trap. **Lesson: never name a parameter the same as a
module the function imports.**

Test: `test_cached_verify_applies_real_ledger_verdicts_in_option_groups`
in `test_serve_verify.py` — real tokens, id 111 active/200/now and id 112
dead/404/'2026-11-13' → bands `["720p", "sd"]` (the 360p release's band
is "sd", not "360p"), 480p dead band hidden, winner is the 111 token,
`verified_at` stamped. `test_serve_verify`: 47 OK.

## Hot spot 3 — `_preresolve_for_detail`: 7.01 s → 0.93 s

`_preresolve_for_detail` iterates EVERY group × tier (2,372 tiers for
61197) and per tier called `_preresolve_is_warm(source_id)`, which opened
a fresh `get_db()` per call. Also note: 998 warmed / 1,374 warming — the
1,374 cold bands each spawn a live-resolve daemon thread (a background
storm; capping which scopes warm is a PRODUCT change — only if a later
measurement forces it).

Fix (`viewer.py`): `_preresolve_is_warm(source_id, conn=None)` — when a
connection is passed it is used directly, else the old per-call open.
`_shared_warm_conn()` context manager opens ONE `get_db()` for the
whole pass, yields None when the catalog file does not exist yet (a test
process — opening it would create an empty file) or when the open fails.
`_preresolve_for_detail` runs its loop inside it and passes
`conn=shared`. No new test: the existing WarmAllBandsTests
(`test_serve_verify.py`) and DetailWarmTests (`test_home_rails.py`)
mock `_preresolve_is_warm` and pass unchanged; the guard was verified by
`ls fast_fetch.db` after the runs — no file was created.
`test_home_rails`: 47 OK.

## Measured end to end (route shape, ladder=True, verify=True)

| post | fixed, warm process | fixed, fresh process | live tree, fresh process |
|---|---|---|---|
| 61197 | **6.14 s / 9.41 s** (two builds, load 2.26) | 20.62 s | 63.34 s |
| 1551 | 0.70–1.23 s | — | 3.19 s |

Same output both ways: 1,180 groups / 2,372 tiers / 9,480 releases
(61197); 72 / 216 / 360 (1551). Fixed JSON 7,202,882 bytes vs live
7,139,662 — slightly bigger because the fixed verify stamps
`verified_at` on tiers it verifies. Old live per-request cost ≈ 48 s
(fetch 34 s + verify 7.35 s + preresolve 7 s) — that is the 504. New live
per-request ≈ 6–9.5 s, under the 15 s nginx edge with margin.

## Two measurement traps, recorded so the next probe does not repeat them

1. **Fresh-process numbers include `_ensure_schema` (~14 s).** The live
   process pays it once at startup (cached via `_SCHEMA_READY_FOR`); it
   is NOT per-request. Split measured 2026-11-15: `init_db` 1.45 s,
   `site_needles` 12.80 s, `site_text_needles` 0.04 s. The 12.8 s is the
   two `SELECT DISTINCT … FROM download_sources` scans (service +
   source_name over 4.45M rows). **Follow-up, not in this branch:** the
   first detail request after any service restart pays ~14 s + ~7 s
   build ≈ 21 s → 504s. Fix options: cache the needle set, or drop the
   long-tail scans (the registry already carries the canonical hosters —
   see the comment at `redact.py` ~1657). A `database is locked` hit
   during one probe (init_db's INSERT collided with a live writer) is a
   probe artifact, not a code bug — retry after a pause.
2. **`ladder=None` means `option_groups = []`.** Two of my own "final"
   probes passed `ladder=None` and measured a build with NO groups and
   NO preresolve (3.47 s / 17.21 s) — those numbers were wrong in both
   directions. The route passes `ladder=True`. Always probe with the
   route's argument shape.

## What this branch does NOT change

- No API shape change: the response carries the same fields; groups,
  tiers and releases are identical counts before/after (verified live).
- The 1,374-thread warm storm is unchanged (see hot spot 3).
- `_ensure_schema` startup cost is unchanged (follow-up above).
- NOT live — the live tree moves only through the gate (backup + live
  test), decided/done by the supervisor.

## Local verification

`test_redact` 105 OK; `test_serve_verify` 47 OK; `test_home_rails`
47 OK; `import viewer` OK. Stdlib only (functools, already imported
os/contextmanager). Gate count on this branch: 101 suites.
