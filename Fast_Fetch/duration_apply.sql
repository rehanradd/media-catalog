-- duration_apply.sql — apply staged durations to the catalog.
--
-- Source of truth: Fast_Fetch/backfill_durations.py, which stages one row
-- per probed link in duration_probe_results. This script is the gate's
-- half: it writes cat_works.runtime_minutes and posts.duration ONLY
-- where they are NULL, and only for MOVIE works. Series works have no
-- single runtime (episodes vary); their per-episode fill into
-- cat_episodes is follow-up work, not this script. The staged rows for
-- series links stay in duration_probe_results for that later step.
--
-- Run (on a STAGING COPY made with sqlite3 .backup or VACUUM INTO):
--     sqlite3 -bail <staging-copy.db> < duration_apply.sql
-- The -bail flag matters: the guard in step 0 aborts the script on a
-- bad staging table, and -bail stops the run there instead of continuing.
-- NEVER run against the live database. The gate backup taken before the
-- apply is the rollback — there is no inverse apply. Re-running on an
-- already-applied copy is a safe no-op: every target is already non-NULL,
-- so zero rows are filled and zero journal rows are added.
--
-- Requires a PROJECTED copy: cat_works / cat_work_sites must exist, so
-- the apply runs after the projection, not before. A post whose work is
-- not projected yet is skipped, not failed.
--
-- posts.duration is TEXT (the scrapers write site text there), so the
-- applied value is a display duration: H:MM:SS when an hour or more,
-- else M:SS (6102 s -> "1:41:42", 2832 s -> "47:12"). cat_works gets
-- integer minutes (duration_s / 60, rounded) — the value the v2 API
-- serves as duration seconds (api_v2.py: runtime * 60).
--
-- Guards (step 0 aborts the whole script): a staged LIVE playlist with
-- a duration (live playlists are never banked), or a staged duration
-- outside 60 s – 6 h (the job stages neither; either means the staging
-- table was hand-edited or an old job wrote it). Works whose staged
-- durations disagree by more than 60 s are NOT filled — the job's
-- agreement check should already have prevented them, and the INFO query
-- in step 1 lists them for review. They are a deliberate skip, not a
-- verify failure.

PRAGMA foreign_keys = OFF;
BEGIN IMMEDIATE;

-- Step 0: guards. Each guard is a temp table with a CHECK that fails
-- when a bad staged row exists — the dedup_fold.sql pattern (RAISE()
-- only works inside triggers, so it cannot guard a script). Run with
-- sqlite3 -bail so the script stops here instead of continuing; the
-- failing table name says which condition tripped.
CREATE TEMP TABLE _dur_guard_live (ok INTEGER CHECK (ok = 1));
INSERT INTO _dur_guard_live VALUES (
  CASE WHEN (SELECT COUNT(*) FROM duration_probe_results
            WHERE live = 1 AND duration_s IS NOT NULL) = 0
       THEN 1 ELSE 0 END);
CREATE TEMP TABLE _dur_guard_range (ok INTEGER CHECK (ok = 1));
INSERT INTO _dur_guard_range VALUES (
  CASE WHEN (SELECT COUNT(*) FROM duration_probe_results
            WHERE duration_s IS NOT NULL
              AND (duration_s < 60 OR duration_s > 21600)) = 0
       THEN 1 ELSE 0 END);

-- Step 1: agreed durations per movie work and per movie post.
-- A work/post qualifies only when every staged duration for it agrees
-- within 60 s; a spread wider than that is a different cut, a wrong
-- link, or an episode hiding as a movie — it is skipped, not averaged.
CREATE TEMP TABLE _dur_works AS
SELECT s.work_id AS work_id,
       CAST(ROUND(MIN(d.duration_s) / 60.0) AS INTEGER) AS minutes,
       MIN(d.duration_s) AS min_s,
       MAX(d.duration_s) - MIN(d.duration_s) AS spread_s,
       COUNT(*) AS n_links
FROM duration_probe_results d
JOIN download_sources ds ON ds.id = d.link_id
JOIN releases r ON r.id = ds.release_id
JOIN cat_work_sites s ON s.post_id = r.post_id
JOIN cat_works w ON w.id = s.work_id
WHERE d.duration_s IS NOT NULL AND d.live = 0
  AND w.work_type = 'movie'
GROUP BY s.work_id
HAVING MAX(d.duration_s) - MIN(d.duration_s) <= 60.0;

CREATE TEMP TABLE _dur_posts AS
SELECT r.post_id AS post_id,
       MIN(d.duration_s) AS min_s,
       MAX(d.duration_s) - MIN(d.duration_s) AS spread_s,
       COUNT(*) AS n_links
FROM duration_probe_results d
JOIN download_sources ds ON ds.id = d.link_id
JOIN releases r ON r.id = ds.release_id
JOIN cat_work_sites s ON s.post_id = r.post_id
JOIN cat_works w ON w.id = s.work_id
JOIN posts p ON p.id = r.post_id
WHERE d.duration_s IS NOT NULL AND d.live = 0
  AND w.work_type = 'movie'
  AND (p.seasons IS NULL OR TRIM(p.seasons) = '')
GROUP BY r.post_id
HAVING MAX(d.duration_s) - MIN(d.duration_s) <= 60.0;

-- INFO (not a failure): works/posts deliberately skipped because their
-- staged durations disagree. Review them; do not force-fill.
SELECT 'disagreeing-works-skipped' AS info, work_id, spread_s, n_links
FROM (SELECT s.work_id AS work_id,
             MAX(d.duration_s) - MIN(d.duration_s) AS spread_s,
             COUNT(*) AS n_links
      FROM duration_probe_results d
      JOIN download_sources ds ON ds.id = d.link_id
      JOIN releases r ON r.id = ds.release_id
      JOIN cat_work_sites s ON s.post_id = r.post_id
      JOIN cat_works w ON w.id = s.work_id
      WHERE d.duration_s IS NOT NULL AND d.live = 0
        AND w.work_type = 'movie'
      GROUP BY s.work_id
      HAVING MAX(d.duration_s) - MIN(d.duration_s) > 60.0);

-- Step 2: fill cat_works.runtime_minutes where NULL, and journal it.
-- The journal insert runs FIRST, from the NULL set, so it records
-- exactly the rows this apply fills (a re-run inserts zero: the set
-- is empty once the targets are non-NULL).
CREATE TABLE IF NOT EXISTS duration_apply_journal (
    kind TEXT NOT NULL,           -- 'work' or 'post'
    ref_id INTEGER NOT NULL,      -- cat_works.id or posts.id
    value TEXT NOT NULL,          -- minutes ("102") or display ("1:41:42")
    applied_at REAL NOT NULL,
    PRIMARY KEY (kind, ref_id)
);

INSERT OR IGNORE INTO duration_apply_journal (kind, ref_id, value, applied_at)
SELECT 'work', w.work_id, CAST(w.minutes AS TEXT),
       CAST(strftime('%s', 'now') AS REAL)
FROM _dur_works w
JOIN cat_works c ON c.id = w.work_id
WHERE c.runtime_minutes IS NULL;

UPDATE cat_works SET runtime_minutes = (
    SELECT minutes FROM _dur_works WHERE _dur_works.work_id = cat_works.id
) WHERE id IN (SELECT work_id FROM _dur_works)
  AND runtime_minutes IS NULL;

-- Step 3: fill posts.duration where NULL/empty, and journal it
-- (same order: journal the NULL set first, then update).
INSERT OR IGNORE INTO duration_apply_journal (kind, ref_id, value, applied_at)
SELECT 'post', p.post_id,
       CASE WHEN p.min_s >= 3600
           THEN printf('%d:%02d:%02d',
                       CAST(p.min_s / 3600 AS INTEGER),
                       CAST(p.min_s / 60 AS INTEGER) % 60,
                       CAST(p.min_s AS INTEGER) % 60)
           ELSE printf('%d:%02d',
                       CAST(p.min_s / 60 AS INTEGER),
                       CAST(p.min_s AS INTEGER) % 60)
           END,
       CAST(strftime('%s', 'now') AS REAL)
FROM _dur_posts p
JOIN posts q ON q.id = p.post_id
WHERE q.duration IS NULL OR TRIM(q.duration) = '';

UPDATE posts SET duration = (
    SELECT CASE WHEN p.min_s >= 3600
        THEN printf('%d:%02d:%02d',
                    CAST(p.min_s / 3600 AS INTEGER),
                    CAST(p.min_s / 60 AS INTEGER) % 60,
                    CAST(p.min_s AS INTEGER) % 60)
        ELSE printf('%d:%02d',
                    CAST(p.min_s / 60 AS INTEGER),
                    CAST(p.min_s AS INTEGER) % 60)
        END
    FROM _dur_posts p WHERE p.post_id = posts.id
) WHERE id IN (SELECT post_id FROM _dur_posts)
  AND (duration IS NULL OR TRIM(duration) = '');

-- Step 4: VERIFY. Every query below must return zero rows (the gate
-- checks that). If any row appears, do NOT commit the apply — roll the
-- staging copy back and investigate.
SELECT 'fillable-works-left' AS problem, COUNT(*) AS n
FROM _dur_works w JOIN cat_works c ON c.id = w.work_id
WHERE c.runtime_minutes IS NULL;
SELECT 'fillable-posts-left' AS problem, COUNT(*) AS n
FROM _dur_posts p JOIN posts q ON q.id = p.post_id
WHERE q.duration IS NULL OR TRIM(q.duration) = '';
SELECT 'journal-works-mismatch' AS problem, j.ref_id, j.value,
       c.runtime_minutes
FROM duration_apply_journal j JOIN cat_works c ON c.id = j.ref_id
WHERE j.kind = 'work' AND CAST(j.value AS INTEGER) <> c.runtime_minutes;
SELECT 'journal-posts-mismatch' AS problem, j.ref_id, j.value, q.duration
FROM duration_apply_journal j JOIN posts q ON q.id = j.ref_id
WHERE j.kind = 'post' AND j.value <> q.duration;
SELECT 'live-staged-duration' AS problem, COUNT(*) AS n
FROM duration_probe_results WHERE live = 1 AND duration_s IS NOT NULL;
SELECT 'out-of-range-staged' AS problem, COUNT(*) AS n
FROM duration_probe_results
WHERE duration_s IS NOT NULL AND (duration_s < 60 OR duration_s > 21600);
-- END VERIFY

DROP TABLE _dur_works;
DROP TABLE _dur_posts;
DROP TABLE _dur_guard_live;
DROP TABLE _dur_guard_range;

COMMIT;
