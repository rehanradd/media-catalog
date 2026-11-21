-- onepiece_identity_fold.sql — One Piece identity cluster fold,
-- investigated 21 Nov 2026 (checkpoint
-- ~/workspace/onepiece-identity-20261121-checkpoint.md). Applies to a
-- STAGING COPY of the catalog. NEVER run against the live database.
--
-- The cluster (all measured live 21 Nov 2026, read-only, mode=ro)
-- ---------------------------------------------------------------
-- The same show was filed as four works, and the anime as two:
--
--   LIVE-ACTION show "One Piece" (2023 TV series, IMDb tt11737520,
--   TMDB 111110 — S1 has 8 episodes, S2 has 8):
--     69654  "One Piece Live Action"  2026  anime   114 prints, 16 eps
--            key tmdb:111110, identity_suspect=0  <- SURVIVOR
--     7344   "One Piece"             2026  series   17 prints, 2 eps
--            key series:one piece:2026, suspect=1 (S02 files)
--     7345   "One Piece"             2023  series   44 prints, 0 eps
--            key imdb:tt11737520, suspect=1 (S01 files)
--     1242   "One Piece (2023)"      2023  anime    89 prints, 18 eps
--            key series:one piece:2023, suspect=1 (S02E01 files filed
--            as anime because is_animated=1; S1 8 eps + S2 8 eps, the
--            live-action shape, not the anime's)
--   ANIME "One Piece" (1999, TMDB 37854, 23 seasons, 1065 eps):
--     58940  "One Piece"             2025  anime  8304 prints, 1065 eps
--            key tmdb:37854, suspect=1  <- SURVIVOR
--     307    "One Piece"             NULL  anime  1291 prints, 157 eps
--            key series:one piece:ny, suspect=1 (year-less anime
--            posts; its season 1 is a flat 157-episode list)
--
-- Why the generic dedup-fold rule does NOT cover this (and is waived
-- here, with evidence, pair by pair): the members differ in title
-- ("One Piece Live Action" vs "One Piece"), year (2023 vs 2026),
-- work_type (anime vs series — the live-action was typed anime
-- wherever is_animated=1), and every member is identity_suspect=1
-- (the guard that normally blocks a fold). The identity evidence is
-- the ids, not the titles: 7345 carries IMDb tt11737520 (the
-- live-action series); 69654 carries TMDB 111110 (the same series);
-- 7344/1242 carry the S02 files (original titles "One Piece 2026 S02
-- Complete ..." and release labels "One Piece (2023) S02E01 ...",
-- URL ".../one-piece-2023-season-2-live-action-..."); 307's posts are
-- the year-1999 anime posts (origin_url join) and 58940 is the same
-- anime under its TMDB id. Two id systems for one show are not a
-- conflict: tt11737520 and 111110 are the live-action's IMDb and TMDB
-- ids; 37854 is the anime's TMDB id.
--
-- What the fold does
-- ------------------
-- Phase A (ledger, durable): stamp the correct ids onto the posts, so
-- the NEXT projection keys them to the survivor keys. Phase B
-- (catalog, post-projection step): fold the four member works into
-- the two survivors, following dedup_fold.py's apply_fold semantics
-- (journal, collision-safe re-pointing, seasons/episodes merged by
-- number, work_key re-keying, blank-metadata fill, roll-ups, bridge
-- invalidate). The fold must be RE-APPLIED after any full projection
-- until the ledger stamps have taken effect everywhere: the
-- projection rebuilds cat_* from the ledger, and a fold the ledger
-- does not reproduce is undone by the next rebuild. The stamps in
-- Phase A make the live-action posts re-key to imdb:tt11737520 and
-- the year-1999 anime posts to tmdb:37854; the imdb-vs-tmdb survivor
-- choice (69654, tmdb:111110) still needs the fold, because the
-- projector keys by IMDb first when a post carries both ids.
--
-- Expected end state on the live catalog (re-measure at gate time;
-- the step-0 guard aborts if the recorded counts drifted):
--   69654: 114+17+44+89 = 264 releases; 16+2 = 18 episodes
--          (S0 2, S1 8, S2 8); year 2023; imdb tt11737520 filled.
--   58940: 8304+1291 = 9595 releases; 1065 + (157-66) = 1156 episodes
--          (307's flat S1 list merges by number into 58940's S1).
--   Members 7344, 7345, 1242, 307 no longer exist as works.
--   The survivors read identity_suspect=1 after the fold: the fold
--   re-points the members' open review rows (season_unresolved /
--   season_pack_span_unresolved) to the survivors, and the flag is
--   derived from open rows. The identity is resolved by the fold; the
--   season-span questions stay open until the review queue is worked
--   (188,111 open rows catalog-wide) — no review row is fake-resolved
--   here.
--
-- What this file does NOT do
-- ---------------------------
-- * 58940's year stays 2025. The anime started 1999, but its posts
--   carry 2025 (a re-release year); changing it is an importer
--   decision, not a fold.
-- * Work 59190 "One Piece Or" (1 print, truncated title) is not
--   folded: one print is not enough evidence to place it.
-- * Works 121001/121002 ("One Piece Remastered S1", "One Piece Season
--   20 Wanno Arc") are not folded: distinct titles, not suspect.
-- * Post 130993 ("One Piece Season 2: Into the Grand Line", 2023,
--   imdb tt11737520 + tmdb 111110) is NOT touched: the title names an
--   anime arc while the ids name the live-action show. Per-post review.
-- * "Lego One Piece" (TMDB 318918) is its own work and stays one.
-- * The 1000+ episode blind spot (the 1..999 parse bound shared by
--   the projector, the read layer and the app) is NOT fixed here.
--
-- Run (on a staging copy made with sqlite3 .backup or VACUUM INTO):
--     sqlite3 -bail <staging-copy.db> < onepiece_identity_fold.sql
-- The -bail flag matters: the guard in step 0 aborts the script on a
-- stale plan, and -bail stops the run there instead of continuing.
-- Re-running on an already-folded copy is a safe no-op: pairs already
-- journaled under the same survivor are skipped by the guard, and
-- every Phase A UPDATE only touches rows still in their pre-state.
--
-- Rollback: the gate backup taken before the fold (the journal table
-- dedup_fold_journal is the audit trail, not the rollback). To undo,
-- restore the gate backup — there is no inverse fold.
--
-- If the step-0 guard aborts: re-measure the recorded counts from the
-- live catalog (the checkpoint lists the read-only probes), update the
-- guard's expected values, and re-run. Do NOT bypass the guard.

PRAGMA foreign_keys = OFF;
BEGIN IMMEDIATE;

-- BEGIN PAIRS
CREATE TEMP TABLE _fold_pairs (
    survivor_id INTEGER NOT NULL,
    member_id INTEGER NOT NULL,
    rule TEXT NOT NULL,
    PRIMARY KEY (member_id)
);
INSERT INTO _fold_pairs (survivor_id, member_id, rule) VALUES
(69654, 7344, 'onepiece-identity-20261121'),
(69654, 7345, 'onepiece-identity-20261121'),
(69654, 1242, 'onepiece-identity-20261121'),
(58940, 307, 'onepiece-identity-20261121');
-- END PAIRS

-- BEGIN STEPS
-- The journal (audit trail; the gate backup is the rollback).
CREATE TABLE IF NOT EXISTS dedup_fold_journal (
    survivor_id INTEGER NOT NULL,
    merged_id INTEGER NOT NULL,
    survivor_work_key TEXT NOT NULL,
    merged_work_key TEXT NOT NULL,
    title TEXT NOT NULL,
    year INTEGER,
    rule TEXT NOT NULL,
    folded_at REAL NOT NULL,
    PRIMARY KEY (merged_id)
);
-- Tables the fold touches that a catalog copy may predate: create
-- them empty if absent so the steps below parse. The live catalog
-- predates cat_media_segments (the backfill never ran); the other two
-- exist live. An empty table makes its step a no-op.
CREATE TABLE IF NOT EXISTS cat_media_segments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    work_id INTEGER NOT NULL,
    tmdb_id INTEGER,
    media_type TEXT NOT NULL DEFAULT 'tv',
    season_number INTEGER,
    episode_number INTEGER,
    segment_type TEXT NOT NULL,
    start_ms INTEGER NOT NULL,
    end_ms INTEGER,
    source TEXT NOT NULL DEFAULT 'theintrodb',
    fetched_at REAL NOT NULL,
    UNIQUE (work_id, season_number, episode_number, segment_type)
);
CREATE TABLE IF NOT EXISTS shared_warm_demand (
    day TEXT NOT NULL, hour INTEGER NOT NULL, work_id INTEGER NOT NULL,
    band TEXT NOT NULL, cold_presses INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, hour, work_id, band)
);
CREATE TABLE IF NOT EXISTS cat_work_languages (
    work_id INTEGER NOT NULL, language TEXT NOT NULL,
    PRIMARY KEY (work_id, language)
);
-- 0. Guard: abort unless every not-yet-journaled pair still matches
-- the recorded identity (id, work_key, title, work_type, year,
-- release/episode counts, suspect flag) and every Phase A post is
-- still in its recorded pre-state. Pairs already journaled under the
-- same survivor are skipped (a re-run is a no-op); a pair journaled
-- under a DIFFERENT survivor still fails the guard, because its
-- member row is gone. The CHECK fails when the bad count is nonzero;
-- run with sqlite3 -bail so the script stops here instead of
-- continuing. The generic fold rule (same title+year, same type,
-- neither member suspect) is waived for this cluster — see the header
-- for the per-pair identity evidence.
CREATE TEMP TABLE _onepiece_guard (ok INTEGER CHECK (ok = 1));
INSERT INTO _onepiece_guard VALUES (
  CASE WHEN (
    (SELECT COUNT(*) FROM _fold_pairs p
     LEFT JOIN cat_works s ON s.id = p.survivor_id
     LEFT JOIN cat_works m ON m.id = p.member_id
     WHERE p.member_id NOT IN (
         SELECT merged_id FROM dedup_fold_journal
         WHERE survivor_id = p.survivor_id)
       AND (s.id IS NULL OR m.id IS NULL
         OR s.work_key NOT IN ('tmdb:111110', 'tmdb:37854')
         OR m.work_key NOT IN ('series:one piece:2026',
                               'imdb:tt11737520',
                               'series:one piece:2023',
                               'series:one piece:ny')
         OR (p.survivor_id = 69654 AND (
                s.title <> 'One Piece Live Action'
                OR s.work_type <> 'anime' OR s.year <> 2026
                OR s.release_count <> 114 OR s.episode_count <> 16
                OR s.identity_suspect <> 0))
         OR (p.survivor_id = 58940 AND (
                s.title <> 'One Piece'
                OR s.work_type <> 'anime' OR s.year <> 2025
                OR s.release_count <> 8304 OR s.episode_count <> 1065
                OR s.identity_suspect <> 1))
         OR (p.member_id = 7344 AND (
                m.title <> 'One Piece' OR m.work_type <> 'series'
                OR m.year <> 2026 OR m.release_count <> 17
                OR m.episode_count <> 2 OR m.identity_suspect <> 1))
         OR (p.member_id = 7345 AND (
                m.title <> 'One Piece' OR m.work_type <> 'series'
                OR m.year <> 2023 OR m.release_count <> 44
                OR m.episode_count <> 0 OR m.identity_suspect <> 1))
         OR (p.member_id = 1242 AND (
                m.title <> 'One Piece (2023)' OR m.work_type <> 'anime'
                OR m.year <> 2023 OR m.release_count <> 89
                OR m.episode_count <> 18 OR m.identity_suspect <> 1))
         OR (p.member_id = 307 AND (
                m.title <> 'One Piece' OR m.work_type <> 'anime'
                OR m.year IS NOT NULL OR m.release_count <> 1291
                OR m.episode_count <> 157 OR m.identity_suspect <> 1))
         OR s.work_key IS NULL OR m.work_key IS NULL))
    + (SELECT COUNT(*) FROM posts WHERE id IN
         (8522, 11439, 100858, 1505, 2046, 11441, 108678, 159116, 159084)
       AND ((imdb_id IS NOT NULL AND imdb_id IS NOT 'tt11737520')
         OR (tmdb_id IS NOT NULL AND tmdb_id IS NOT 111110)))
    + (SELECT COUNT(*) FROM posts WHERE id = 8523
       AND tmdb_id IS NOT NULL AND tmdb_id NOT IN (37854, 111110))
    + (SELECT COUNT(*) FROM posts WHERE id IN (1551, 61197, 146081)
       AND ((imdb_id IS NOT NULL AND imdb_id IS NOT 'tt11737520')
         OR tmdb_id IS NOT 37854))
    + (SELECT COUNT(*) FROM posts WHERE id IN
         (328, 514, 729, 849, 1459, 1756, 146080, 161328)
       AND (imdb_id IS NOT NULL
         OR (tmdb_id IS NOT NULL AND tmdb_id IS NOT 37854)))
    + (21 - (SELECT COUNT(*) FROM posts WHERE id IN
         (8522, 11439, 100858, 1505, 2046, 11441, 108678, 159116, 159084,
          8523, 1551, 61197, 146081,
          328, 514, 729, 849, 1459, 1756, 146080, 161328)))
  ) = 0 THEN 1 ELSE 0 END);
-- Phase A. Ledger stamps (durable — the next projection re-keys
-- these posts to the survivor keys). Each UPDATE only touches rows
-- still in their pre-state, so a re-run is a no-op. The WHERE
-- predicates are the guard's post-state; a post already stamped is
-- skipped, a post in an unexpected state is skipped too (the guard
-- above would already have aborted on that).
-- A1. The nine live-action S01/S02 posts that carry no ids at all
-- (verified 21 Nov via origin_url join to works 7344/7345/1242):
-- stamp the live-action's IMDb + TMDB and mark them not-animated, so
-- the next projection keys them imdb:tt11737520.
UPDATE posts SET imdb_id = 'tt11737520', tmdb_id = 111110, is_animated = 0
WHERE id IN (8522, 11439, 100858, 1505, 2046, 11441, 108678, 159116, 159084)
  AND imdb_id IS NULL AND tmdb_id IS NULL;
-- A2. Post 8523 ("One Piece S01 (2023) ...", hdtvb) is a live-action
-- S01 post whose TMDB id is the anime's 37854: point it at 111110.
-- Its IMDb (tt11737520) is already right and stays.
UPDATE posts SET tmdb_id = 111110 WHERE id = 8523 AND tmdb_id = 37854;
-- A3. Three anime posts carry the live-action's IMDb on an anime
-- TMDB id (1551, 61197, 146081 — all tmdb 37854, verified via
-- origin_url join). Clear the wrong IMDb; the right TMDB stays, so
-- the next projection keys them tmdb:37854 (58940). Do NOT stamp the
-- anime's IMDb tt0388629 here: it is unverified (one unprojected
-- post carries it) and belongs to enrichment, not to this fold.
UPDATE posts SET imdb_id = NULL
WHERE id IN (1551, 61197, 146081)
  AND imdb_id = 'tt11737520' AND tmdb_id = 37854;
-- A4. The eight year-1999 anime posts behind work 307 (328, 514,
-- 729, 849, 1459, 1756, 146080, 161328 — no ids at all): stamp the
-- anime's TMDB id, so the next projection keys them tmdb:37854.
UPDATE posts SET tmdb_id = 37854
WHERE id IN (328, 514, 729, 849, 1459, 1756, 146080, 161328)
  AND imdb_id IS NULL AND tmdb_id IS NULL;
-- Phase B. Catalog fold — steps 1-12 are dedup_fold.py's apply_fold
-- semantics (journal, collision-safe re-pointing, seasons/episodes
-- merged by number, work_key re-keying, blank-metadata fill, roll-ups,
-- bridge invalidate), applied to the four pairs in _fold_pairs.
-- 1. Journal the merge (INSERT OR IGNORE: a re-run skips pairs
-- already journaled under the same survivor).
INSERT OR IGNORE INTO dedup_fold_journal
    (survivor_id, merged_id, survivor_work_key, merged_work_key,
     title, year, rule, folded_at)
SELECT p.survivor_id, p.member_id, s.work_key, m.work_key,
       s.title, s.year, p.rule, CAST(unixepoch('now') AS REAL)
FROM _fold_pairs p
JOIN cat_works s ON s.id = p.survivor_id
JOIN cat_works m ON m.id = p.member_id;
-- 2. Plain re-point (no UNIQUE key on work_id can collide).
-- The review rows re-pointed here stay OPEN (resolved_at untouched):
-- the survivors read identity_suspect=1 until the season-span queue
-- is worked. No review row is fake-resolved by this fold.
UPDATE cat_releases SET work_id = (
    SELECT survivor_id FROM _fold_pairs WHERE member_id = cat_releases.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
UPDATE cat_links SET work_id = (
    SELECT survivor_id FROM _fold_pairs WHERE member_id = cat_links.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
UPDATE cat_entitlements SET work_id = (
    SELECT survivor_id FROM _fold_pairs WHERE member_id = cat_entitlements.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
UPDATE cat_review_queue SET work_id = (
    SELECT survivor_id FROM _fold_pairs WHERE member_id = cat_review_queue.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
-- 3. Collision-safe re-point: drop a member row whose unique key the
-- survivor already has (the survivor's row wins), drop a member row
-- whose key another member folding into the same survivor already
-- has (keep the lowest rowid), then re-point the rest.
DELETE FROM cat_work_aliases AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id
    AND EXISTS (SELECT 1 FROM cat_work_aliases s
        WHERE s.work_id = p.survivor_id AND s.alias IS t.alias
        AND s.source_site IS t.source_site));
DELETE FROM cat_work_aliases AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id
    AND EXISTS (SELECT 1 FROM cat_work_aliases s
        JOIN _fold_pairs p2 ON p2.member_id = s.work_id
        AND p2.survivor_id = p.survivor_id
        WHERE s.alias IS t.alias AND s.source_site IS t.source_site
        AND s.rowid < t.rowid));
UPDATE cat_work_aliases SET work_id = (
    SELECT survivor_id FROM _fold_pairs WHERE member_id = cat_work_aliases.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
DELETE FROM cat_work_sites AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id
    AND EXISTS (SELECT 1 FROM cat_work_sites s
        WHERE s.work_id = p.survivor_id AND s.page_url IS t.page_url));
DELETE FROM cat_work_sites AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id
    AND EXISTS (SELECT 1 FROM cat_work_sites s
        JOIN _fold_pairs p2 ON p2.member_id = s.work_id
        AND p2.survivor_id = p.survivor_id
        WHERE s.page_url IS t.page_url AND s.rowid < t.rowid));
UPDATE cat_work_sites SET work_id = (
    SELECT survivor_id FROM _fold_pairs WHERE member_id = cat_work_sites.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
DELETE FROM cat_media_segments AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id
    AND EXISTS (SELECT 1 FROM cat_media_segments s
        WHERE s.work_id = p.survivor_id
        AND s.season_number IS t.season_number
        AND s.episode_number IS t.episode_number
        AND s.segment_type IS t.segment_type));
DELETE FROM cat_media_segments AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id
    AND EXISTS (SELECT 1 FROM cat_media_segments s
        JOIN _fold_pairs p2 ON p2.member_id = s.work_id
        AND p2.survivor_id = p.survivor_id
        WHERE s.season_number IS t.season_number
        AND s.episode_number IS t.episode_number
        AND s.segment_type IS t.segment_type
        AND s.rowid < t.rowid));
UPDATE cat_media_segments SET work_id = (
    SELECT survivor_id FROM _fold_pairs WHERE member_id = cat_media_segments.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
-- 4. Seasons and episodes merge by number. Canonical target per
-- (survivor, number, season_type): the survivor's own season if it
-- has one, else the lowest-id member season with that key. Episodes
-- merge by number inside the target season; a release that already
-- points at the kept episode keeps its row.
-- Caveat, One Piece only: 307's season 1 is a FLAT 157-episode list
-- (its posts are season-22 Hindi-dubbed episode files numbered
-- 1..157), not the anime's real season 1 (66 episodes). Merging by
-- number puts 307's E1..E66 onto 58940's S1E1..E66 (the survivor's
-- rows win) and adds E67..E157 as new S1 episodes. The show is the
-- same and the files are the same anime; the season-1 numbering is
-- the post's, not the show's. The gate should eyeball the merged
-- S1 rows (LIVE-VERIFY below lists them) before committing.
CREATE TEMP TABLE _fold_season_map AS
SELECT s.id AS old_season_id,
       COALESCE(surv.id, first_member.id) AS new_season_id
FROM cat_seasons s
JOIN _fold_pairs p ON p.member_id = s.work_id
LEFT JOIN cat_seasons surv ON surv.work_id = p.survivor_id
    AND surv.number = s.number AND surv.season_type = s.season_type
LEFT JOIN (SELECT p2.survivor_id, s2.number, s2.season_type,
                  MIN(s2.id) AS id FROM cat_seasons s2
           JOIN _fold_pairs p2 ON p2.member_id = s2.work_id
           GROUP BY p2.survivor_id, s2.number, s2.season_type) first_member
    ON first_member.survivor_id = p.survivor_id
    AND first_member.number = s.number
    AND first_member.season_type = s.season_type
WHERE surv.id IS NOT NULL OR first_member.id <> s.id;
CREATE TEMP TABLE _fold_episode_map AS
SELECT e.id AS old_episode_id, e2.id AS new_episode_id
FROM cat_episodes e
JOIN _fold_season_map m ON m.old_season_id = e.season_id
JOIN cat_episodes e2 ON e2.season_id = m.new_season_id
    AND e2.number = e.number;
DELETE FROM cat_release_episodes WHERE episode_id IN (
    SELECT old_episode_id FROM _fold_episode_map)
AND EXISTS (SELECT 1 FROM cat_release_episodes r2
    WHERE r2.release_id = cat_release_episodes.release_id
    AND r2.episode_id = (SELECT new_episode_id FROM _fold_episode_map
        WHERE old_episode_id = cat_release_episodes.episode_id));
UPDATE cat_release_episodes SET episode_id = (
    SELECT new_episode_id FROM _fold_episode_map
    WHERE old_episode_id = cat_release_episodes.episode_id)
WHERE episode_id IN (SELECT old_episode_id FROM _fold_episode_map);
DELETE FROM cat_episodes WHERE id IN (
    SELECT old_episode_id FROM _fold_episode_map);
UPDATE cat_episodes SET season_id = (
    SELECT new_season_id FROM _fold_season_map
    WHERE old_season_id = cat_episodes.season_id)
WHERE season_id IN (SELECT old_season_id FROM _fold_season_map);
UPDATE cat_episodes SET work_id = (
    SELECT survivor_id FROM _fold_pairs
    WHERE member_id = cat_episodes.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
DROP TABLE _fold_episode_map;
DELETE FROM cat_seasons WHERE id IN (
    SELECT old_season_id FROM _fold_season_map);
UPDATE cat_seasons SET work_id = (
    SELECT survivor_id FROM _fold_pairs
    WHERE member_id = cat_seasons.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
DROP TABLE _fold_season_map;
-- 5. Parts merge by number (the survivor's part wins; among members
-- the lowest rowid wins).
DELETE FROM cat_parts AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id
    AND EXISTS (SELECT 1 FROM cat_parts s
        WHERE s.work_id = p.survivor_id AND s.number = t.number));
DELETE FROM cat_parts AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id
    AND EXISTS (SELECT 1 FROM cat_parts s
        JOIN _fold_pairs p2 ON p2.member_id = s.work_id
        AND p2.survivor_id = p.survivor_id
        WHERE s.number = t.number AND s.id < t.id));
UPDATE cat_parts SET work_id = (
    SELECT survivor_id FROM _fold_pairs
    WHERE member_id = cat_parts.work_id)
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
-- 6. Warm demand: add the members' presses to the survivor rows that
-- already exist, then insert the (day, hour, band) combos the
-- survivor lacks, then drop the member rows. (UPDATE first: the
-- other order double-counts, because the UPDATE would add the
-- member presses to the row the INSERT had just created from them.)
UPDATE shared_warm_demand AS d SET cold_presses = d.cold_presses
    + (SELECT COALESCE(SUM(m.cold_presses), 0)
       FROM shared_warm_demand m JOIN _fold_pairs p
         ON p.member_id = m.work_id
       WHERE m.day = d.day AND m.hour = d.hour AND m.band = d.band
         AND p.survivor_id = d.work_id)
WHERE EXISTS (SELECT 1 FROM shared_warm_demand m
    JOIN _fold_pairs p ON p.member_id = m.work_id
    WHERE m.day = d.day AND m.hour = d.hour AND m.band = d.band
    AND p.survivor_id = d.work_id);
INSERT OR IGNORE INTO shared_warm_demand
    (day, hour, work_id, band, cold_presses)
SELECT d.day, d.hour, p.survivor_id, d.band, d.cold_presses
FROM shared_warm_demand d
JOIN _fold_pairs p ON p.member_id = d.work_id;
DELETE FROM shared_warm_demand
WHERE work_id IN (SELECT member_id FROM _fold_pairs);
-- 7. work_key-keyed durable tables re-keyed to the survivor's key
-- (a member row whose key already exists under the survivor is
-- dropped; the rest are re-keyed).
DELETE FROM cat_offsets AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id
    JOIN cat_works sw ON sw.id = p.survivor_id
    WHERE t.work_key = mw.work_key
    AND EXISTS (SELECT 1 FROM cat_offsets s
        WHERE s.work_key = sw.work_key
        AND s.season_number IS t.season_number
        AND s.episode_number IS t.episode_number
        AND s.release_label IS t.release_label));
UPDATE cat_offsets SET work_key = (
    SELECT sw.work_key FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id
    JOIN cat_works sw ON sw.id = p.survivor_id
    WHERE mw.work_key = cat_offsets.work_key)
WHERE work_key IN (SELECT mw.work_key FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id);
DELETE FROM cat_offset_votes AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id
    JOIN cat_works sw ON sw.id = p.survivor_id
    WHERE t.work_key = mw.work_key
    AND EXISTS (SELECT 1 FROM cat_offset_votes s
        WHERE s.work_key = sw.work_key
        AND s.season_number IS t.season_number
        AND s.episode_number IS t.episode_number
        AND s.release_label IS t.release_label
        AND s.offset_seconds IS t.offset_seconds));
UPDATE cat_offset_votes SET work_key = (
    SELECT sw.work_key FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id
    JOIN cat_works sw ON sw.id = p.survivor_id
    WHERE mw.work_key = cat_offset_votes.work_key)
WHERE work_key IN (SELECT mw.work_key FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id);
DELETE FROM cat_entitlement_rules AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id
    JOIN cat_works sw ON sw.id = p.survivor_id
    WHERE t.work_key = mw.work_key
    AND EXISTS (SELECT 1 FROM cat_entitlement_rules s
        WHERE s.work_key = sw.work_key
        AND s.release_label IS t.release_label
        AND s.model IS t.model AND s.region IS t.region));
UPDATE cat_entitlement_rules SET work_key = (
    SELECT sw.work_key FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id
    JOIN cat_works sw ON sw.id = p.survivor_id
    WHERE mw.work_key = cat_entitlement_rules.work_key)
WHERE work_key IN (SELECT mw.work_key FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id);
DELETE FROM cat_review_resolutions AS t WHERE EXISTS (
    SELECT 1 FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id
    JOIN cat_works sw ON sw.id = p.survivor_id
    WHERE t.work_key = mw.work_key
    AND EXISTS (SELECT 1 FROM cat_review_resolutions s
        WHERE s.work_key = sw.work_key
        AND s.kind IS t.kind AND s.signature IS t.signature));
UPDATE cat_review_resolutions SET work_key = (
    SELECT sw.work_key FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id
    JOIN cat_works sw ON sw.id = p.survivor_id
    WHERE mw.work_key = cat_review_resolutions.work_key)
WHERE work_key IN (SELECT mw.work_key FROM _fold_pairs p
    JOIN cat_works mw ON mw.id = p.member_id);
-- 8. Traceability: member -> survivor in cat_id_map (kind 'work';
-- no current reader consults it — the API resolves only
-- kind 'ledger_release').
INSERT OR REPLACE INTO cat_id_map (kind, old_id, new_id, rev)
SELECT 'work', p.member_id, p.survivor_id,
       (SELECT COALESCE(MAX(source_rev), 0) FROM cat_works)
FROM _fold_pairs p;
-- 9. Fill each survivor's blank metadata fields from its members,
-- richest first (release_count, site_count, lowest id), never
-- overwriting a non-blank value. The member rows still exist here;
-- they are deleted in step 11. (For 69654 this fills imdb_id
-- 'tt11737520' from 7345 — the richest member that carries it.)
UPDATE cat_works SET
  original_title = CASE WHEN cat_works.original_title IS NULL OR cat_works.original_title IN ('', 'Unknown')
    THEN COALESCE((SELECT m.original_title FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.original_title IS NOT NULL AND m.original_title NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.original_title) ELSE cat_works.original_title END,
  synopsis = CASE WHEN cat_works.synopsis IS NULL OR cat_works.synopsis IN ('', 'Unknown')
    THEN COALESCE((SELECT m.synopsis FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.synopsis IS NOT NULL AND m.synopsis NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.synopsis) ELSE cat_works.synopsis END,
  genres = CASE WHEN cat_works.genres IS NULL OR cat_works.genres IN ('', 'Unknown')
    THEN COALESCE((SELECT m.genres FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.genres IS NOT NULL AND m.genres NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.genres) ELSE cat_works.genres END,
  languages = CASE WHEN cat_works.languages IS NULL OR cat_works.languages IN ('', 'Unknown')
    THEN COALESCE((SELECT m.languages FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.languages IS NOT NULL AND m.languages NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.languages) ELSE cat_works.languages END,
  country = CASE WHEN cat_works.country IS NULL OR cat_works.country IN ('', 'Unknown')
    THEN COALESCE((SELECT m.country FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.country IS NOT NULL AND m.country NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.country) ELSE cat_works.country END,
  industry = CASE WHEN cat_works.industry IS NULL OR cat_works.industry IN ('', 'Unknown')
    THEN COALESCE((SELECT m.industry FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.industry IS NOT NULL AND m.industry NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.industry) ELSE cat_works.industry END,
  imdb_rating = CASE WHEN cat_works.imdb_rating IS NULL
    THEN COALESCE((SELECT m.imdb_rating FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.imdb_rating IS NOT NULL ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.imdb_rating) ELSE cat_works.imdb_rating END,
  imdb_id = CASE WHEN cat_works.imdb_id IS NULL OR cat_works.imdb_id IN ('', 'Unknown')
    THEN COALESCE((SELECT m.imdb_id FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.imdb_id IS NOT NULL AND m.imdb_id NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.imdb_id) ELSE cat_works.imdb_id END,
  tmdb_id = CASE WHEN cat_works.tmdb_id IS NULL
    THEN COALESCE((SELECT m.tmdb_id FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.tmdb_id IS NOT NULL ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.tmdb_id) ELSE cat_works.tmdb_id END,
  tvdb_id = CASE WHEN cat_works.tvdb_id IS NULL OR cat_works.tvdb_id IN ('', 'Unknown')
    THEN COALESCE((SELECT m.tvdb_id FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.tvdb_id IS NOT NULL AND m.tvdb_id NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.tvdb_id) ELSE cat_works.tvdb_id END,
  runtime_minutes = CASE WHEN cat_works.runtime_minutes IS NULL
    THEN COALESCE((SELECT m.runtime_minutes FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.runtime_minutes IS NOT NULL ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.runtime_minutes) ELSE cat_works.runtime_minutes END,
  poster_url = CASE WHEN cat_works.poster_url IS NULL OR cat_works.poster_url IN ('', 'Unknown')
    THEN COALESCE((SELECT m.poster_url FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.poster_url IS NOT NULL AND m.poster_url NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.poster_url) ELSE cat_works.poster_url END,
  backdrop_urls = CASE WHEN cat_works.backdrop_urls IS NULL OR cat_works.backdrop_urls IN ('', 'Unknown')
    THEN COALESCE((SELECT m.backdrop_urls FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id AND m.backdrop_urls IS NOT NULL AND m.backdrop_urls NOT IN ('', 'Unknown') ORDER BY m.release_count DESC, m.site_count DESC, m.id ASC LIMIT 1), cat_works.backdrop_urls) ELSE cat_works.backdrop_urls END,
  is_animated = CASE WHEN COALESCE(cat_works.is_animated, 0) = 0
    THEN COALESCE((SELECT MAX(m.is_animated) FROM cat_works m JOIN _fold_pairs p ON p.member_id = m.id WHERE p.survivor_id = cat_works.id), 0)
    ELSE cat_works.is_animated END
WHERE id IN (SELECT survivor_id FROM _fold_pairs);
-- 9b. Survivor year: the live-action show started in 2023 (S01
-- 2023, IMDb tt11737520). 69654's year is 2026 because its one post
-- is the S02 post. Set the survivor to the show's first year. (58940
-- is NOT touched: its year 2025 is a re-release year and changing it
-- is an importer decision.)
UPDATE cat_works SET year = 2023 WHERE id = 69654;
-- 10. Recompute each survivor's roll-ups with the projection's own
-- formulas (release/episode/site counts, max resolution, best
-- quality score, available stream/download, identity_suspect).
UPDATE cat_works SET
    release_count = (SELECT COUNT(*) FROM cat_releases r WHERE r.work_id = cat_works.id),
    episode_count = (SELECT COUNT(*) FROM cat_episodes e WHERE e.work_id = cat_works.id),
    site_count = (SELECT COUNT(DISTINCT s.source_site) FROM cat_work_sites s WHERE s.work_id = cat_works.id),
    max_resolution = (
        SELECT r.resolution FROM cat_releases r
         WHERE r.work_id = cat_works.id AND r.resolution <> 'unknown'
         ORDER BY r.quality_score DESC, r.print_version DESC, r.id DESC LIMIT 1),
    best_quality_score = COALESCE(
        (SELECT MAX(r.quality_score) FROM cat_releases r WHERE r.work_id = cat_works.id), 0),
    available_stream = COALESCE((
        SELECT 1 FROM cat_releases r WHERE r.work_id = cat_works.id
         AND r.streamable_count > 0), 0),
    available_download = COALESCE((
        SELECT 1 FROM cat_releases r WHERE r.work_id = cat_works.id
         AND r.downloadable_count > 0), 0),
    identity_suspect = COALESCE((
        SELECT 1 FROM cat_review_queue q
         WHERE q.work_id = cat_works.id AND q.resolved_at IS NULL), 0),
    updated_at = CAST(unixepoch('now') AS REAL)
WHERE id IN (SELECT survivor_id FROM _fold_pairs);
-- 11. Delete the merged member rows.
DELETE FROM cat_works WHERE id IN (SELECT member_id FROM _fold_pairs);
-- 12. Language bridge (the SQL-only "invalidate" path): drop the
-- merged members' bridge rows and the language_bridge_at stamp, so
-- browse falls back to the correct EXISTS path until the next
-- projection rebuilds the bridge.
DELETE FROM cat_work_languages WHERE work_id IN (SELECT member_id FROM _fold_pairs);
DELETE FROM cat_projection_meta WHERE key = 'language_bridge_at';
DROP TABLE _onepiece_guard;
-- END STEPS

-- BEGIN VERIFY
-- Every query below returns rows only when something is wrong
-- (except the COUNT queries, which always return one row: clean
-- means n = 0). If any row appears, do NOT commit the fold — roll
-- the staging copy back and investigate.
SELECT 'journal-missing' AS problem, COUNT(*) AS n FROM _fold_pairs p
WHERE NOT EXISTS (SELECT 1 FROM dedup_fold_journal j WHERE j.merged_id = p.member_id);
SELECT 'journal-wrong-survivor' AS problem, j.merged_id, j.survivor_id, p.survivor_id
FROM dedup_fold_journal j JOIN _fold_pairs p ON p.member_id = j.merged_id
WHERE j.survivor_id <> p.survivor_id;
SELECT 'orphan-cat_releases' AS problem, COUNT(*) AS n FROM cat_releases WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_links' AS problem, COUNT(*) AS n FROM cat_links WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_entitlements' AS problem, COUNT(*) AS n FROM cat_entitlements WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_review_queue' AS problem, COUNT(*) AS n FROM cat_review_queue WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_work_aliases' AS problem, COUNT(*) AS n FROM cat_work_aliases WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_work_sites' AS problem, COUNT(*) AS n FROM cat_work_sites WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_seasons' AS problem, COUNT(*) AS n FROM cat_seasons WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_episodes' AS problem, COUNT(*) AS n FROM cat_episodes WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_parts' AS problem, COUNT(*) AS n FROM cat_parts WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_media_segments' AS problem, COUNT(*) AS n FROM cat_media_segments WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-shared_warm_demand' AS problem, COUNT(*) AS n FROM shared_warm_demand WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_work_languages' AS problem, COUNT(*) AS n FROM cat_work_languages WHERE work_id IN (SELECT member_id FROM _fold_pairs);
SELECT 'orphan-cat_offsets' AS problem, COUNT(*) AS n FROM cat_offsets WHERE work_key IN (SELECT merged_work_key FROM dedup_fold_journal);
SELECT 'orphan-cat_offset_votes' AS problem, COUNT(*) AS n FROM cat_offset_votes WHERE work_key IN (SELECT merged_work_key FROM dedup_fold_journal);
SELECT 'orphan-cat_entitlement_rules' AS problem, COUNT(*) AS n FROM cat_entitlement_rules WHERE work_key IN (SELECT merged_work_key FROM dedup_fold_journal);
SELECT 'orphan-cat_review_resolutions' AS problem, COUNT(*) AS n FROM cat_review_resolutions WHERE work_key IN (SELECT merged_work_key FROM dedup_fold_journal);
SELECT 'duplicate-title-year' AS problem, LOWER(TRIM(title)) AS t, year, COUNT(*) AS n
FROM cat_works WHERE id IN (SELECT survivor_id FROM _fold_pairs)
GROUP BY t, year HAVING COUNT(*) > 1;
SELECT 'survivor-counts' AS problem, w.id,
  w.release_count, (SELECT COUNT(*) FROM cat_releases r WHERE r.work_id = w.id),
  w.episode_count, (SELECT COUNT(*) FROM cat_episodes e WHERE e.work_id = w.id),
  w.site_count, (SELECT COUNT(DISTINCT s.source_site) FROM cat_work_sites s WHERE s.work_id = w.id)
FROM cat_works w WHERE w.id IN (SELECT survivor_id FROM _fold_pairs)
AND (w.release_count <> (SELECT COUNT(*) FROM cat_releases r WHERE r.work_id = w.id)
  OR w.episode_count <> (SELECT COUNT(*) FROM cat_episodes e WHERE e.work_id = w.id)
  OR w.site_count <> (SELECT COUNT(DISTINCT s.source_site) FROM cat_work_sites s WHERE s.work_id = w.id));
-- Cluster-specific: the members must be gone, the survivors must
-- carry the right identity, exactly one work titled 'One Piece'
-- must remain (the anime 58940), and the 21 stamped posts must be in
-- their post-state.
SELECT 'member-still-exists' AS problem, id, title FROM cat_works
WHERE id IN (7344, 7345, 1242, 307);
SELECT 'survivor-69654-identity' AS problem, id, title, year, imdb_id, tmdb_id
FROM cat_works WHERE id = 69654
AND (title <> 'One Piece Live Action' OR year <> 2023
  OR imdb_id <> 'tt11737520' OR tmdb_id <> 111110);
SELECT 'survivor-58940-identity' AS problem, id, title, year, work_key, tmdb_id
FROM cat_works WHERE id = 58940
AND (title <> 'One Piece' OR work_key <> 'tmdb:37854' OR tmdb_id <> 37854);
SELECT 'one-piece-title-count' AS problem, COUNT(*) AS n FROM cat_works
WHERE title = 'One Piece' HAVING COUNT(*) <> 1;
SELECT 'posts-not-stamped' AS problem, id, imdb_id, tmdb_id FROM posts
WHERE (id IN (8522, 11439, 100858, 1505, 2046, 11441, 108678, 159116, 159084)
       AND (imdb_id IS NOT 'tt11737520' OR tmdb_id IS NOT 111110))
   OR (id = 8523 AND tmdb_id IS NOT 111110)
   OR (id IN (1551, 61197, 146081)
       AND (imdb_id IS NOT NULL OR tmdb_id IS NOT 37854))
   OR (id IN (328, 514, 729, 849, 1459, 1756, 146080, 161328)
       AND tmdb_id IS NOT 37854);
-- END VERIFY

-- BEGIN LIVE-VERIFY
-- These checks carry the LIVE expected counts (measured 21 Nov 2026)
-- and must be run by hand against the staging copy after the fold:
-- they are NOT part of the automated VERIFY section, because a
-- fixture test runs the file against small numbers.
--   SELECT 'live-69654-counts' AS problem, release_count, episode_count
--     FROM cat_works WHERE id = 69654
--     AND (release_count <> 264 OR episode_count <> 18);
--   SELECT 'live-58940-counts' AS problem, release_count, episode_count
--     FROM cat_works WHERE id = 58940
--     AND (release_count <> 9595 OR episode_count <> 1156);
--   SELECT 'live-69654-seasons' AS problem, number, episode_count
--     FROM cat_seasons WHERE work_id = 69654 ORDER BY number;
--     -- expected: 0:2, 1:8, 2:8
--   SELECT 'live-58940-s1' AS problem, number, episode_count
--     FROM cat_seasons WHERE work_id = 58940 AND number = 1;
--     -- expected: 157 (66 original + 91 from 307's flat list)
--   SELECT 'live-58940-s1-sample' AS problem, e.number, r.label
--     FROM cat_episodes e JOIN cat_release_episodes re ON re.episode_id = e.id
--     JOIN cat_releases r ON r.id = re.release_id
--     WHERE e.work_id = 58940 AND e.season_id IN
--       (SELECT id FROM cat_seasons WHERE work_id = 58940 AND number = 1)
--       AND e.number BETWEEN 60 AND 70 LIMIT 10;
--     -- eyeball: the merged S1 rows must be the anime's episode files.
--   SELECT 'live-posts-untouched' AS problem, id FROM posts
--     WHERE id IN (130993) AND (imdb_id IS NOT 'tt11737520' OR tmdb_id IS NOT 111110);
--     -- post 130993 must be UNCHANGED (ambiguous: not touched).
-- END LIVE-VERIFY

-- _fold_pairs is dropped only now: the VERIFY queries above read it.
DROP TABLE _fold_pairs;

COMMIT;
