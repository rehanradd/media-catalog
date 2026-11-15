# Needle cache: text-vocabulary snapshot + cat_work_sites preference [NEEDLE-TEXT-SNAPSHOT]

Date: 2026-11-16 (self-check 24h loop). Branch: needle-cache-20261116, from main d5537b19, rebased onto 817262a4.

## Problem

`_ensure_schema()` still paid ~14 s on a fresh process even after Session 147's
service snapshot: `site_text_needles()` ran `SELECT DISTINCT source_site FROM
posts` at every process start — the other half of the startup cost. Measured
on the live catalog (2026-11-16, read-only): the live DB has no
`site_needle_values` table at all (no projection has run since Session 147
landed), so `site_needles()` also still pays its live scans there; and
`cat_work_sites` exists (413,313 rows) while `posts` holds 4.45M.

## Fix (`Fast_Fetch/redact.py`, comment in `Fast_Fetch/catalog_v2.py`)

- `rebuild_site_needle_snapshot()` now also stores the `source_site`
  vocabulary in a new `site_text_needle_values` table — from `cat_work_sites`
  when that table exists, else from `posts` — under the same
  `needle_snapshot_at` stamp. Return value unchanged (service count only).
- `site_text_needles()` reads the text snapshot when fresh; otherwise it
  prefers `cat_work_sites` (413k rows) over `posts` (4.45M), the same
  preference `site_needles()` already makes; the posts scan runs only when
  `cat_work_sites` is absent. The `UNATTRIBUTED` ('vegamovies') default is
  still excluded, at read time like the live scan.
- `_snapshot_needle_values()` refactored into a shared `_snapshot_values(conn,
  table)` helper with `_snapshot_text_needle_values()` alongside; same stamp
  check, same None-on-absent-or-stale contract.

## Rollout

First restart after the gate deploy still pays the live scans (no snapshots
yet — the live DB has neither table); the next projection builds both, and
restarts after that read ~1,300 + ~30 rows. NOT live — gate deploy is the
supervisor's call.

## Tests

- New `TextNeedleSnapshotTests` (5 tests): snapshot matches the live scan,
  serves after the rows are gone, stale stamp falls back, `cat_work_sites`
  preferred over `posts`, rebuild takes the site vocabulary from
  `cat_work_sites`.
- `NeedleSnapshotTests` extended: the empty-DB rebuild also yields no text
  snapshot.
- Local: snapshot classes 9/9 OK; `test_redact.PublicEdgeHttpTests` 27/27 OK;
  `test_redact.AdultDenialOnBothPortsTests` 11/11 OK;
  `test_catalog_v2.py` 109/109 OK. Full `test_redact.py` (114 tests) shows 2
  errors on repeated runs — both `TimeoutError` in local-server HTTP tests
  (`test_a_safe_link_still_plays_on_the_operator_port`,
  `test_stream_handle_redirects_and_reports_nothing_extra`); both pass in
  isolation on this branch, the documented load flake (same tests time out
  on the clean base tree under load).
