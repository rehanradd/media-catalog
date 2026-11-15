# 2026-11-15 — Needle-vocabulary snapshot: _ensure_schema 14s -> ~1.5s [NEEDLE-SNAPSHOT]

## The problem

`viewer._ensure_schema()` runs at every service start. It calls
`redact.site_needles(DB_PATH)`, whose two `SELECT DISTINCT` scans over the
4.45M-row `download_sources` (`service`, `source_name`) cost **~12.8s**
(measured 2026-11-15, `ensure-parts.py`: `site_needles` 12.80s of a 14s
`_ensure_schema`; `init_db` 1.45s). The live process pays it once at startup,
so the **first detail request after any restart costs ~21s and 504s** at the
15s nginx edge — the detail-504 fix (6–9.5s per request) does not cover it.

## The fix (SQLite research report, Option A — snapshot table, rebuilt on merge)

- `redact.rebuild_site_needle_snapshot(conn) -> int`: creates
  `site_needle_values(value TEXT PRIMARY KEY)` if missing, repopulates it
  from the same two DISTINCT queries, returns the row count. Returns 0 when
  there is no `download_sources` table. The length/generic-token filter
  still applies at read time, in `site_needles()` itself, so the snapshot
  holds exactly the ledger's contribution.
- `redact._snapshot_needle_values(conn) -> set | None`: reads the snapshot
  only when its stamp matches the projection's `projected_at`. Both are
  stamped with the same `now` in the same projection run, so a mismatch
  means a merge ran without the rebuild (or the rebuild failed and warned)
  and the live scan is the honest answer. Any error — missing table, missing
  meta key — also means None, never a crash.
- `redact.site_needles()`: prefers the snapshot; falls back to the existing
  live scan (gated on `idx_sources_service` or <100k rows) unchanged.
  `site_text_needles()` is untouched — its posts-only scan is 0.04s.
- `catalog_v2._finalize()` (the projection): after the FTS rebuild block,
  rebuilds the snapshot and stamps `needle_snapshot_at` in
  `cat_projection_meta`, wrapped in the same try/except-warning pattern as
  FTS ("an accelerator, not a gate"). Uses the existing `_redact()` lazy
  import. The stamp is written with the same `now` the outer projection
  stamps `projected_at` with (line 1135), so the equality check is exact.

## Why not the other options

- Option B (trigger-kept counts): highest risk — a trigger per row on the
  hot ingest path of a 4.45M-row table.
- Option C (drop the scans, registry only): two-line change, but a real
  recall loss — a brand-new hoster would survive in display titles until
  someone adds it to the registry. The snapshot keeps today's exact
  vocabulary.

## Tests

- `test_redact.py::NeedleSnapshotTests` (4 new): snapshot matches the live
  scan exactly; snapshot serves after the rows are deleted; a stale stamp
  (projected_at != needle_snapshot_at) falls back to the live scan;
  rebuild on a DB without download_sources returns 0. Uses a distinctive
  service name (`zzsnapshotprobe`) that exists nowhere else — the fixture's
  own services are all in BASE_NEEDLES.
- `test_catalog_v2.py::NeedleSnapshotProjectionTests` (1 new): a full
  `project()` builds the snapshot, stamps `needle_snapshot_at` ==
  `projected_at`, and `site_needles()` then serves the planted value.
- Local: test_redact **109 OK** (was 105), test_catalog_v2 **107 OK** (was
  106), test_serve_verify OK, test_docs_invariants OK, `import catalog_v2` OK.

## Rollout (honest)

The snapshot is built by the projection, not by `_ensure_schema`. After the
gate deploy, the first restart still pays ~14s (no snapshot exists yet);
the next projection builds it, and restarts after that pay ~1.5s. No live
action is needed from anyone — the gate deploy carries it. The snapshot is
detection-only data: a stale entry can only let a brand name survive in a
display title for one merge cycle, never a wrong play decision.

## Follow-ups (not in this branch)

- SQLite report items #2 (widen nginx micro-cache + stale-while-revalidate),
  #3 (language bridge table — 4–6s warm), #4 (PRAGMA optimize on close +
  ANALYZE after bulk loads), #5 (warm caches at service startup — the
  trending first-load failure is the same family as this one).
- The linkcheck report's presigned-expiry parsing (classify expired
  presigned URLs offline, no probe) belongs in the sweep runner's next
  iteration, not here.
