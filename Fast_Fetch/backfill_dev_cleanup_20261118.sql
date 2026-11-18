-- backfill_dev_cleanup_20261118.sql
--
-- Dev-cleanup backfill (audit: dev-cleanup-db-audit-20261118.md, C7).
-- GATE-TIME ONLY. Do NOT run against the live database directly:
--   1. Take a gate backup first (sqlite3 <live.db> ".backup <copy.db>").
--   2. Run this file against the BACKUP copy, verify the counts below,
--      then swap the copy in through the normal gate deploy.
--   3. It is idempotent — running it twice changes nothing the second time.
--
-- Only the bogus years are corrected here. The placeholder language /
-- country / industry values ("Unknown", "N/A", …) and the junk genre
-- tokens ("NETFLIX", "COMPLETED", "Hindi Dub") are INTENTIONALLY left
-- in the stored rows: the house rule is zero data loss — redaction is
-- a serialization decision at the edge, never a rewrite of the ledger —
-- and the serve-time fixes in this branch already hide them from every
-- client (catalog_api placeholder nulling + genre curation + the facet
-- filter). The merge now writes NULL instead of "Unknown" for a missing
-- value (merge_catalogs.py, catalog_v2.py), so the junk stops
-- accumulating; the stored tokens stay available for any future
-- mining (e.g. a dub-language backfill from "Hindi Dub" tokens).
--
-- Expected row counts are from the 2026-11-18 audit (live DB, ~20:40 PKT):
--   posts year > 2026: 53              works year > 2026: 50
--   the single year = 1900 row: post 67796 ("Paris Police 1900")
-- Verify with: SELECT changes() after each statement, and re-run the
-- audit counts afterwards — every count above must read 0.

-- ── Bogus future years (C7) ─────────────────────────────────────────────
-- Year parsed from title junk ("(2020) (2067)" → 2067). The importer
-- now validates at scrape time (fast_fetch._normalize_scraped_data via
-- backfill_year_from_title.validate_year); these stored rows predate it.
UPDATE posts     SET year = NULL WHERE year > 2026;
UPDATE cat_works SET year = NULL WHERE year > 2026;

-- The single year = 1900 row is the show's SETTING, not its release
-- year: post 67796, "Download Paris Police 1900 (Season 1)…".
UPDATE posts     SET year = NULL WHERE id = 67796 AND year = 1900
    AND title LIKE '%Paris Police 1900%';
UPDATE cat_works SET year = NULL WHERE year = 1900
    AND title LIKE '%Paris Police 1900%';
