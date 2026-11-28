# CONTINUITY.md rotation — 2026-11-28 (sweep-hardening lane)

The oldest session entry (2026-11-28 importer domain-resolver wiring)
rotated out of `Fast_Fetch/CONTINUITY.md` verbatim to bring the index
back under its 12,000-byte budget (11,863 bytes at rotation time,
before the sweep-load hardening entry landed). Nothing deleted,
only moved.

## 2026-11-28 - importer domain-resolver wiring (backend loop Phase 1 item 1)
- import_wp_family wired to domain_resolver: _resolved_config re-points base/sitemaps/rest at the registry base (bollyflix gen.in -> io.in; other 4 unchanged); sync_catalog/crawl take --base-url/--registry; empty enumeration retries once via resolve_if_dead; parse_page/parse_rest_post take an optional config. Audit: 27 importers, 6 already wired (castle/dramadog/themoviebox/javmost/megacartoons/missav, 297,598 posts, 64.5%); wp_family was the only wirable remainder (5 registry sites, 0 live posts). Tests: 7 new ResolverWiringTests; wp_family 55/55, javmost 23, megacartoons 13, missav 19, domain_resolver 81/81 — all green. Branch importer-domain-wiring (build only — parent owns the gate/landing).
