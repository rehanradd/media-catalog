# Catalog pipeline fixes — 2026-11-23 (drought root cause)

Branch `muse/catalog-pipeline-fixes-20261123`, base `origin/main` `0144304e`.
Worktree `~/workspace/catalog-pipeline-work` (the old clone
`~/workspace/radd-stream-app-work` holds a staged JAVMost revert — untouched).
NOT merged, NOT gated; no bulk merge run, no service touched, no live DB
write. CI verdict on the pushed tip is recorded in the session report to
the parent.

## Rotated out of CONTINUITY.md

The oldest session-history bullet, moved verbatim to bring the index back
under its 12 KB budget. Nothing was reworded or summarized.

- **2026-11-21 — 9xmovies base move (camera → cologne):** live probe 21 Nov: 9xmovie.cologne wp-json 200, posts dated 2026-11-21. domain_registry 9xmovies active_base_url → https://9xmovie.cologne; camera + bayern kept as mirrors; rotation history extended (camera seen_until 2026-11-21, cologne seen_from 2026-11-21). Importer already registry-derived (Session 106), no code change; no host_registry entry exists (49 hosts pinned), none added. Tests: test_domain_resolver 53/53, test_host_registry 35/35, test_fast_fetch_domains 30/30, test_9xmovies 6/6, test_docs_invariants OK.

## The fix

1. **Fleet registration (the drought root cause).** `merge_fleet.py` SITES
   20 → 26: xprimehub, themoviebox, javmost, animeworld, dramadog,
   megacartoons. All six use the default `<site>.db` name (no SOURCES
   override — verified present on the VM, written daily 05:02–06:32 on
   23 Nov). xprimehub is the "unified" family (`download_sources`); the
   other five are the older "staging" family (`sources` table). Both merge
   via `merge_catalogs.merge_site_db`. Three new tests in
   `test_merge_fleet.py::DroughtSiteListTests` pin the six sites in SITES,
   the default-DB resolution, and the producer map (xprimehub →
   `import_xprimehub.py`; themoviebox/javmost/dramadog/megacartoons →
   `importers/import_<site>.py`; animeworld → `import_wp_family.py` entry).
2. **Sitemap sync loud failures.** `sync_sitemaps` gained `_report_feed`
   + a `[!] Sync problems (N)` summary line: a non-200 fetch or a
   200-with-0-records parse is printed unconditionally and collected, so a
   dead feed can never hide inside a successful-looking "Global sync
   complete!" total again. Return contract (int total) unchanged; a dead
   feed does not abort the cycle (the autocrawl still runs the merge step
   after a partial sync); the cron script's grep for "Global sync
   complete!" is untouched. Eight new tests in
   `test_fast_fetch_domains.py::TestSyncSitemapsReportsEmptyFeeds`
   (stubbed `http_get`, temp DB, no network).
3. **Feed-shape fixes, probed live from Oracle egress:**
   - 7starhd: the §3 regex hardcoded `7starhd\.rich` after the host moved
     to `.surgery` — a live 61 KB homepage full of post links parsed to 0
     records. The regex now matches the host that was actually fetched.
   - katmoviehd: `katmoviehd.mom` serves no `slug:"..."` embeds (the old
     frontend's shape; `.top` still has them). Homepage-link fallback
     added, mirroring hdhub4u: same-host single-segment links, excluding
     /category/ /tag/ /page/ /genre/ /about/ /app/.
   - 10xflix: `10xflix.beer/wp-json/` 404s — the WP install now lives
     under `/m` (`/m/wp-json/` answers 200 with posts dated 2026-11-23).
     The /m base is owned by the 10xflix-beer branch; this branch only
     reports the failure loudly (comment in code, no hardcoded /m).
   - bollyshares: sitemap 404 + homepage is a RunCloud "Website
     Unavailable" parking page (1,376 bytes) — the site is DOWN, not just
     a moved feed. Reported loudly; registry status is the
     registry-corrections lane's call.
   - Correction to the earlier diagnosis: the live `domains_config` table
     is stale (.rich/.top/.help) but is NOT what the sync uses —
     `get_domains()` overlays the registry (surgery/mom/beer), and the
     autocrawl log confirms the sync fetched surgery/mom/beer.
4. **Backlog (checks only, nothing merged)** — full numbers in
   `~/workspace/catalog-backlog-20261123.md`: javmost 234,057 rows, ALL
   `is_18_plus=1`, 281,107 releases, 281,107 sources, 0 null keys — safe
   shape is `adult_fold=True` (default) + `adult_exposures_remaining == 0`
   before any swap; themoviebox 3,487 staging, only 22 new by canonical
   key (3,465 overlap; the merge also attaches sources to existing works);
   dramadog 109, megacartoons 31 (all non-adult); animeworld staging empty;
   xprimehub 5 = 5 live, caught up.

## Tests (local, hatch VM)

- `test_merge_fleet.py` 20/20 OK (was 17, +3 new).
- `test_fast_fetch_domains.py` 38/38 OK (was 30, +8 new).
- `test_docs_invariants.py` 22/22 OK (after the CONTINUITY rotation below).
- `test_merge_catalogs.py` 20/20 OK, `test_auto_crawl_cron.py` 9/9 OK.
- `test_merge_fleet.py` took 88 s locally — the known slow-box page-cache
  artifact, not a test property; CI decides.

## Tension to resolve (parent / main chat)

A sibling lane has an UNCOMMITTED staged revert in the old workspace clone
(`~/workspace/radd-stream-app-work`, 13 files) that deletes
`import_javmost.py`, its test, the javmost registry entry, and javmost from
`ADULT_SOURCE_SITES`. That revert is NOT in `origin/main` and is NOT part
of this branch. If it lands, the existing `javmost.db` still merges (this
branch registers the file, not the importer), but future javmost crawls
stop and the staging file goes stale.
