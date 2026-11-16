# 2026-11-16 — Ledger fingerprint fix (merge_fleet.py)

## What broke
`merge_fleet.py` skipped any site whose report-ledger entry carried a truthy
`posts_merged`. Once a site had merged once, it never merged again. Proven
live at the 08:00 PKT autocrawl on 2026-11-16: it synced 30,015 posts and
merged 0 in 0.0s — all 18 sites "already merged, skipping". Staging was
already ahead on 6+ sites (multimovies +362) with nowhere to go.

## The fix (branch `ledger-fingerprint-fix-20261116`, off main 956d2a0c)
- `staging_fingerprint(src)`: per table present in the staging DB
  (`posts`, `releases`, `download_sources`, `sources`, `post_sources` —
  probed from `sqlite_master`, so both the "unified" and the "staging" source
  families work): row count, max id, max `updated_at` / `created_at` where
  those columns exist, plus file size. Opens the DB read-only (`mode=ro`).
  Returns None when unreadable — callers fail open to merging.
- `_needs_merge(prev, fingerprint)`: merges on no entry, error entry, legacy
  entry (posts_merged but no fingerprint — one catch-up merge), changed
  fingerprint, or unreadable staging. Skips only on an unchanged fingerprint.
- Fingerprint is computed BEFORE the merge and stored on success only, so
  rows that land mid-merge re-merge next cycle, and a failed merge retries
  instead of skipping forever.
- The hardcoded `sys.path.insert(0, "/opt/radd-stream/Fast_Fetch")` became
  file-relative (`Path(__file__).resolve().parent`) — identical on the VM,
  and the module is now importable (and testable) anywhere else.
- Log line changed: `[=] {site} already merged, skipping` →
  `[=] {site} unchanged since last merge, skipping`.

## Deliberate non-choices
- **No mtime** in the fingerprint: a sync that rewrites rows without new data
  would bump it and force a full re-merge every cycle on a 1-vCPU box.
- **No content hash**: a full-table hash per site per cycle costs more than
  the aggregates and buys nothing over them for this detector.
- `releases` has no `updated_at` column, so an in-place release edit that
  changes no aggregate is invisible — same as today, and the same as the
  known crawler-level invisibility of in-place print upgrades. The
  fingerprint detects what the sync actually wrote, nothing more.

## First cycle after this lands
All 18 sites have legacy entries (posts_merged, no fingerprint), so the first
post-fix cycle re-merges every site once — the catch-up — then gains
fingerprints and goes incremental. That full merge is expected, not a
regression. It must run after the maintenance tick finishes (heavy DB jobs
wait for the tick; the 09:40 projection lock was the same window).

## Evidence
- New `Fast_Fetch/test_merge_fleet.py`: 14 tests, all green locally
  (`python3 -S Fast_Fetch/test_merge_fleet.py`, 34s): fingerprint stable /
  append / in-place edit / missing file / staging family; `_needs_merge`
  no-entry / error / legacy / unchanged / changed / unreadable; end-to-end
  `main()` skip → re-merge on change, legacy catch-up, error retry.
- Neighbours green: `test_merge_catalogs.py` 12/12, `test_fleet_worker.py`
  16/16, `test_docs_invariants.py` 22/22 (the registration ratchet holds
  with the new suite step in `backend-ci.yml`).
- Pre-push checks passed on the 3 staged files.
- Live report/ledger was read only as a COPY (`/tmp/ledger-report-copy.json`);
  the live DB, the timer, and the live report were never touched.

## Owed
Parent merges after CI green on the branch. Nothing here touches the live
ledger — no operational reset was performed (gate rules).
