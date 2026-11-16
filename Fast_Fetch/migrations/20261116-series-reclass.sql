-- 20261116-series-reclass.sql — reclassify season-marked posts filed as movies.
--
-- WHY: the catalog files series per row, per importer, and several importers
-- never test for season markers: `save_scraped_data` defaults a missing
-- `content_type` to "movie" (`_validate_scraped_data`), and
-- `merge_catalogs._collect_staging_post` defaults a missing staging
-- `media_type`/`content_type` to "movie". So the same title is filed both
-- ways: "Star Trek: Strange New Worlds S04E[01-03]" (post 14691) is a
-- movie while "Star Trek: Strange New Worlds S03 Complete" (post 82844)
-- is a series. The Series filter then returns almost nothing even though
-- the filter itself works.
--
-- RULE (conservative, positive evidence only): a `movie` row becomes
-- `series` only when it carries a season marker — an S-token (S01..S99,
-- S1..S9) as a standalone token, "Season N", a populated `seasons`
-- column, a release with an episode value set, or an "Ep N" marker.
-- Never genre, never year, never the bare word "season"/"episode".
-- The bare words were sampled and rejected: "Season of the Witch"
-- (post 88029) is a movie, and "Episode" fires on Star Wars films
-- ("Episode VI", "Episode I") and on films titled "...Episode N"
-- ("Boonie Bears: Episode 122", "Blue Lock the Movie: Episode Nagi").
-- The S-token needs a word boundary on BOTH sides: the loose pattern
-- matched "Vous Play S3XUS" (post 126011), which the tight pattern
-- below correctly excludes.
--
-- NO REVERSE UPDATE: absence of a season marker is not movie evidence.
-- Sampled 2026-11-16: 5,691 `series` rows carry no marker, but 10 of
-- 12 sampled are genuine series ("Too Hot to Handle", "The Signal",
-- "WWE RAW", "Our Great National Parks"). A reverse pass on absence
-- alone would be ~80% wrong. The only positive movie signal tried,
-- "movie" in the title, is 90% noise from the "Rogmovies" site suffix
-- in title tails. So this migration is forward-only.
--
-- BLAST RADIUS (dry-run 2026-11-16 on a .backup copy of the live DB,
-- run twice — the first run caught two file bugs, a missing ")" in the
-- F4 EXISTS and LIKE used where GLOB was needed for "[0-9]"; both
-- fixed, re-run clean):
--   movie -> series: 5,305 rows (2.35% of 225,483 posts)
--   series -> movie: 0 rows (not attempted, see above)
--   CHANGED == PREDICATE_MATCHES (5,305): the UPDATE changes exactly
--   the predicate set, no more, no less. Known ids: 14691/45528/48275
--   flip to series, 126011 (S3XUS) and 88029 (Season of the Witch) do
--   not. 10 random flipped samples, all genuine series (Season N /
--   S01 / S01E / Ullu Part-01). Rollback on the copy restored the
--   pre-run counts exactly; the copy was deleted after.
--   Under the 5% stop threshold. F2 ("Season N") adds ~0 rows beyond
--   the other channels on this catalog — most Season-N rows already
--   carry season data on their releases — but it is the backstop for
--   rows whose releases carry none.
-- DRY-RUN (do this before applying): copy the live DB with
--   sqlite3 <live.db> ".backup <copy.db>"   (never plain cp on the WAL DB)
-- run this file against the COPY, check `SELECT changes()` is 5,305
-- (dry-run 2026-11-16; re-verify on a fresh copy — the catalog moves)
-- and spot-check posts 14691/45528/48275 (must flip) and 126011/88029
-- (must NOT flip), then delete the copy. Apply to live only through the
-- gate (backup + live test); the statement is idempotent (re-running it
-- changes 0 rows) and runs inside one transaction.
-- NOTE: this fixes the stored rows only. Rows filed after this migration
-- still depend on each importer detecting season markers; the per-site
-- detection gap is separate work.

BEGIN;

UPDATE posts
SET content_type = 'series'
WHERE content_type = 'movie'
  AND (
    -- F1: S-token (S01..S99, S1..S9) as a standalone token in the title
    -- or clean_title. Preceded by space/(/[/- or start of string;
    -- followed by space/E/[/(/)/./- or end of string.
    lower(title) GLOB '*[ ([-]s[0-9][0-9][ e[().-]*'
    OR lower(title) GLOB '*[ ([-]s[0-9][0-9]'
    OR lower(title) GLOB '*[ ([-]s[0-9][ e[().-]*'
    OR lower(title) GLOB '*[ ([-]s[0-9]'
    OR lower(title) GLOB 's[0-9][0-9][ e[().-]*'
    OR lower(title) GLOB 's[0-9][0-9]'
    OR lower(title) GLOB 's[0-9][ e[().-]*'
    OR lower(title) GLOB 's[0-9]'
    OR lower(clean_title) GLOB '*[ ([-]s[0-9][0-9][ e[().-]*'
    OR lower(clean_title) GLOB '*[ ([-]s[0-9][0-9]'
    OR lower(clean_title) GLOB '*[ ([-]s[0-9][ e[().-]*'
    OR lower(clean_title) GLOB '*[ ([-]s[0-9]'
    OR lower(clean_title) GLOB 's[0-9][0-9][ e[().-]*'
    OR lower(clean_title) GLOB 's[0-9][0-9]'
    OR lower(clean_title) GLOB 's[0-9][ e[().-]*'
    OR lower(clean_title) GLOB 's[0-9]'
    -- F2: "Season N" (digit required: "Season of the Witch" is a movie).
    -- GLOB, not LIKE: LIKE has no character classes, so `[0-9]` there
    -- would match the literal string "[0-9]" and nothing else.
    OR lower(title) GLOB '*season [0-9]*'
    OR lower(clean_title) GLOB '*season [0-9]*'
    -- F3: the seasons column was populated (a JSON list or a number).
    OR (seasons IS NOT NULL AND TRIM(seasons) != '')
    -- F4: at least one release carries an EPISODE value. Season alone
    -- is NOT evidence: sampled 2026-11-16, 8 of 12 season-only rows
    -- are movies — a release gets a spurious season="1" (Star Wars
    -- Episodes III and IV both carry one, from a "[1GB]"-style parse),
    -- while episode-set rows sampled 11/12 genuine series (Ullu
    -- "Part-01" parts, Brazzers "Episode 1"). So the test is on
    -- `episode` only; a both-set release qualifies through it.
    OR EXISTS (
      SELECT 1 FROM releases r
      WHERE r.post_id = posts.id
        AND r.episode IS NOT NULL AND TRIM(r.episode) != '')
    -- F5: "Ep N" / "EpN" marker in the title (same boundaries as F1).
    OR lower(title) GLOB '*[ ([-]ep[0-9][0-9][ e[().-]*'
    OR lower(title) GLOB '*[ ([-]ep[0-9][0-9]'
    OR lower(title) GLOB '*[ ([-]ep[0-9][ e[().-]*'
    OR lower(title) GLOB '*[ ([-]ep[0-9]'
    OR lower(title) GLOB '*[ ([-]ep [0-9][0-9][ e[().-]*'
    OR lower(title) GLOB '*[ ([-]ep [0-9][0-9]'
    OR lower(title) GLOB '*[ ([-]ep [0-9][ e[().-]*'
    OR lower(title) GLOB '*[ ([-]ep [0-9]'
    OR lower(title) GLOB 'ep[0-9][0-9][ e[().-]*'
    OR lower(title) GLOB 'ep[0-9][0-9]'
    OR lower(title) GLOB 'ep[0-9][ e[().-]*'
    OR lower(title) GLOB 'ep[0-9]'
    OR lower(title) GLOB 'ep [0-9][0-9][ e[().-]*'
    OR lower(title) GLOB 'ep [0-9][0-9]'
    OR lower(title) GLOB 'ep [0-9][ e[().-]*'
    OR lower(title) GLOB 'ep [0-9]'
  );

COMMIT;
