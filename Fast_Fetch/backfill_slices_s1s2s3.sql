-- Fast_Fetch/backfill_slices_s1s2s3.sql — gate-time backfill for the
-- server data-cleanup slices S1/S2/S3 + genre curation (2026-11-20).
--
-- RUN AT GATE TIME ONLY, after the S1/S2/S3 code has landed in main and
-- BEFORE the gate deploys it (same gate as the code, backup first, like
-- every live data job). Do NOT run against the live DB from a builder
-- lane. It is idempotent: re-running it changes nothing.
--
-- What it does (Zero Data Loss — only the ABSENCE of a value is filed
-- as NULL; nothing real is ever deleted):
--   * Scalar placeholder columns: posts.language / country / industry
--     and cat_works.languages / country / industry. A stored literal
--     "Unknown" (62,654 posts.language), "N/A", "None", "TBD", "" or
--     "[]" becomes NULL, so the API stops serving the placeholder and
--     the app stops having to hide it. The match is the same word list
--     as redact.PLACEHOLDER_VALUES (case-insensitive, trimmed).
--     List-shaped values ("Hindi, Unknown", '["Unknown"]') are NOT
--     handled here — backfill_slices_s1s2s3_genres.py cleans those
--     with redact.null_placeholders, which keeps the stored shape.
--   * Year sanity (validate_year rule, 1900..current year): a year
--     outside that range is never a release year — "(2020) (2067)"
--     was filed as 2067, "(2024) Unofficial (2029)" as 2029. Those
--     rows (53 posts / 50 works carried a future year) become NULL.
--     A missing year is honest; a future year is a lie.
--
-- What it deliberately does NOT do:
--   * Junk genre tokens ("NETFLIX", "Hindi Dub", "COMPLETED") are
--     NEVER deleted from stored rows. They are curated at serve time
--     only (redact.curate_genres / GENRE_DENYLIST); the stored data
--     stays so a wrong denylist entry hides a chip, never deletes data.
--   * Year 1900 is NOT blanket-nulled: it is inside the valid range
--     and the single year-1900 post (id 67796, "Paris Police 1900")
--     is a story setting, not a release year — it needs a human look,
--     not a rule. The verification query at the end counts year-1900
--     rows for that review.
--
-- The companion script cleans the list-shaped fields:
--   python3 -S Fast_Fetch/backfill_slices_s1s2s3_genres.py --db <catalog.db>
--   python3 -S Fast_Fetch/backfill_slices_s1s2s3_genres.py --db <catalog.db> --apply
--
-- Usage (gate time, on the VM, after a verified backup copy exists):
--   sqlite3 <catalog.db> < Fast_Fetch/backfill_slices_s1s2s3.sql
-- The trailing SELECTs are verification: every placeholder / bad-year
-- count must be 0 after the run (except the year-1900 review count).
--
-- Predicate note: the placeholder test is `TRIM(col) = '' OR
-- LOWER(TRIM(col)) IN (...)`. TRIM is NULL-safe (NULL stays NULL and
-- never matches), so already-NULL rows are neither rewritten by the
-- UPDATEs nor counted by the verification SELECTs. Do NOT "simplify"
-- it to TRIM(COALESCE(col, '')) — that counts every NULL row as a
-- remaining placeholder and makes the verification lie.

BEGIN IMMEDIATE;

-- S2: scalar placeholders -> NULL (posts).
UPDATE posts SET language = NULL
 WHERE TRIM(language) = ''
    OR LOWER(TRIM(language)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

UPDATE posts SET country = NULL
 WHERE TRIM(country) = ''
    OR LOWER(TRIM(country)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

UPDATE posts SET industry = NULL
 WHERE TRIM(industry) = ''
    OR LOWER(TRIM(industry)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

-- S2: scalar placeholders -> NULL (cat_works). `languages` is
-- list-shaped, so this only catches a whole-value placeholder
-- ("Unknown"); the list shapes are the companion script's job.
UPDATE cat_works SET languages = NULL
 WHERE TRIM(languages) = ''
    OR LOWER(TRIM(languages)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

UPDATE cat_works SET country = NULL
 WHERE TRIM(country) = ''
    OR LOWER(TRIM(country)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

UPDATE cat_works SET industry = NULL
 WHERE TRIM(industry) = ''
    OR LOWER(TRIM(industry)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

-- C7: year sanity — the validate_year rule (1900..current year).
-- A year outside it is never a release year; NULL it.
UPDATE posts SET year = NULL
 WHERE year IS NOT NULL
   AND (year < 1900 OR year > CAST(strftime('%Y', 'now') AS INTEGER));

UPDATE cat_works SET year = NULL
 WHERE year IS NOT NULL
   AND (year < 1900 OR year > CAST(strftime('%Y', 'now') AS INTEGER));

COMMIT;

-- Verification: every count below must be 0 after the run, except the
-- last one (year-1900 rows are kept for human review — see header).
SELECT 'posts.language placeholder' AS check_name,
       COUNT(*) AS remaining FROM posts
 WHERE TRIM(language) = ''
    OR LOWER(TRIM(language)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

SELECT 'posts.country placeholder' AS check_name,
       COUNT(*) AS remaining FROM posts
 WHERE TRIM(country) = ''
    OR LOWER(TRIM(country)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

SELECT 'posts.industry placeholder' AS check_name,
       COUNT(*) AS remaining FROM posts
 WHERE TRIM(industry) = ''
    OR LOWER(TRIM(industry)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

SELECT 'cat_works.languages placeholder' AS check_name,
       COUNT(*) AS remaining FROM cat_works
 WHERE TRIM(languages) = ''
    OR LOWER(TRIM(languages)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

SELECT 'cat_works.country placeholder' AS check_name,
       COUNT(*) AS remaining FROM cat_works
 WHERE TRIM(country) = ''
    OR LOWER(TRIM(country)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

SELECT 'cat_works.industry placeholder' AS check_name,
       COUNT(*) AS remaining FROM cat_works
 WHERE TRIM(industry) = ''
    OR LOWER(TRIM(industry)) IN ('unknown', 'unknown print', 'n/a', 'n.a.',
        'null', 'none', 'tbd', 'undefined', 'not specified',
        'not available', 'not applicable', '[]', '{}');

SELECT 'posts.year out of range' AS check_name,
       COUNT(*) AS remaining FROM posts
 WHERE year IS NOT NULL
   AND (year < 1900 OR year > CAST(strftime('%Y', 'now') AS INTEGER));

SELECT 'cat_works.year out of range' AS check_name,
       COUNT(*) AS remaining FROM cat_works
 WHERE year IS NOT NULL
   AND (year < 1900 OR year > CAST(strftime('%Y', 'now') AS INTEGER));

-- Human review, NOT a failure: year-1900 rows are inside the valid
-- range and are kept. Known case: post 67796 "Paris Police 1900"
-- (the 1900 is a story setting, not a release year).
SELECT 'posts.year = 1900 (human review)' AS check_name,
       COUNT(*) AS remaining FROM posts WHERE year = 1900;

SELECT 'cat_works.year = 1900 (human review)' AS check_name,
       COUNT(*) AS remaining FROM cat_works WHERE year = 1900;
