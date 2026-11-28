# CONTINUITY.md rotation — 2026-11-28 (importer-domain-wiring lane)

The two oldest session entries (2026-11-28 HDR banding fix; 2026-11-28
projection reset=False upsert fix) rotated out of `Fast_Fetch/CONTINUITY.md`
verbatim to bring the index back under its 12,000-byte budget (11,967 bytes
at rotation time, before the importer domain-resolver wiring entry landed).
Nothing deleted, only moved.

## 2026-11-28 - HDR banding fix (backend loop item 14)
- `tiers.band_of`: "hdr" out of the 2160p needles (HDR is a flag, not a resolution; "720p HDRip" read as 2160p); bare "HDR10+" now unspecified. test_tiers 65/65, catalog_api 183, link_health 57, release_descriptor 25, quality 7 — all green.
- §4b residual: `for_release` docstring — a download_only tier's link may be expired, MUST be re-resolved; tests pin expired-only-in-download_only. Branch hdr-banding-fix @ 90e485b0.

## 2026-11-28 - Projection reset=False upsert fix (backend loop Phase 2 item 6)
- `project(reset=False)` no longer crashes on `cat_works.work_key`: works upsert (all ledger columns refresh, first_seen_at only moves earlier); prints reuse by a per-work seeded print index (cat_releases has no UNIQUE key — seeding is one indexed lookup per work on idx_cat_releases_work, descriptor columns refresh on reuse, ids stay stable); episodes upsert via ON CONFLICT DO NOTHING + RETURNING; links upsert ledger-owned columns but preserve probe-owned state/evidence (apply_evidence owns those); work_sites refresh last_seen_at; `_raise_review` dedupes identical queue rows. `project_db(reset=...)` passthrough added. reset=True behaviour unchanged (empty tables → plain inserts). Tests: 3 new in test_catalog_v2.ResetFalseUpsertTests (repeat stable, refresh + probe-state preserved, series episodes/reviews stable); catalog_v2 116/116, catalog_api 183, docs_invariants 22/22, tiers 65/65 — all green. Branch projection-upsert-fix (build only — parent owns the gate/landing).
