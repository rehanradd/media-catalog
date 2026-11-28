# Domain map — 2026-11-28 (backend loop Phase 1 item 5)

Re-verification of every site's active domain after the importer wiring
(4b2c595b). Sources, all read 28 Nov ~18:10 PKT:

- **Live registry** `/opt/radd-stream/Fast_Fetch/domain_registry.json`
  (`updated_at` 2026-11-28T05:41:01Z — written by today's probe run).
  The repo copy is frozen at 2026-11-23T06:22:36Z and already drifts
  from live on 6 sites (see Moves below); live is the authority.
- **Live posts** `SELECT source_site, COUNT(*) FROM posts GROUP BY
  source_site` on the live catalog (total 461,611 — sums exactly).
- **Hardcoded bases** from the 27-importer audit (17:04 PKT 28 Nov).
- **Today's probe run**: `radd-domain-probe.timer` fired 10:40–10:41 PKT
  (new Branch 4 service, first run): 8 sites came back down, the chain
  resolved 1 (world4ufree, same base), 7 stayed down.

## Item 3 verdict (Phase 1 item 3 — caller/schedule)

**Already done; no code change needed.** `probe_all_sites` has a caller
and a schedule, live since self-healing Branch 4 (27 Nov):

- Daily timer `radd-domain-probe.timer` (10:30, own timer) →
  `domain_recheck.py --db <catalog> --probe-stale` →
  `probe_all_sites(only_stale=True, 7-day window)` + the `resolve_site`
  chain for every site it finds down. Service file
  `Fast_Fetch/systemd/radd-domain-probe.{timer,service}`; the installed
  live service carries the new ExecStart (verified via `systemctl cat`).
- Sweep-loop post-batch hook (`fast-probe-sweep-loop.sh`) drains the
  recheck queue (`domain_recheck.py --limit 5`, best-effort) after every
  batch — queue drain only, no probe, by design.
- The discovery chain also runs from the 0-post crawls: the 7 wired
  importers call `resolve_if_dead` on an empty sync (castle, dramadog,
  javmost, megacartoons, missav, themoviebox, wp_family).
- Module CLI: `domain_resolver.py` no-arg runs a full `probe_all_sites()`.

The plan's "never runs except on a 0-post crawl" premise is stale — same
pattern as the "only castle + dramadog wired" line. Branch 4 closed it.

Observation (optional follow-up, not this item): a no-change probe
success does not advance `last_verified` (`probe_site` only writes the
registry on a move), so healthy sites with old verification dates are
re-probed every day instead of being skipped inside the TTL. Cheap and
harmless; a one-line touch-up if anyone wants the skip to engage.

## Moves since the repo copy froze (23 Nov → live, 25–27 Nov)

| Site | Repo copy | Live now | Verified |
|---|---|---|---|
| 7starhd | 7starhd.surgery | 7starhd.domains | 26 Nov |
| bolly4u | bolly4u.moda | bolly4u.fly | 25 Nov |
| filmyzilla | filmyzilla89.com | filmyzilla90.com | 25 Nov |
| hdhub4u | new16.hdhub4u.cl | new17.hdhub4u.cl | 26 Nov |
| moviesdrive | new4.moviesdrive.cyou | new5.moviesdrive.cyou | 26 Nov |
| ssrmovies | site4.ssrmovies.page | site5.ssrmovies.page | 27 Nov |
| 10xflix | 10xflix.beer/m | 10xflix.beer | (normalised) |

## Registry sites — live state (33)

Status is the LIVE registry status. "Wired" = the importer calls
`get_active_base_url`; generic sites are registry-driven by construction
(`fast_fetch.py` generic sync reads the registry base).

| Site | Registry base (live) | Hardcoded base | Live posts | Status | Note |
|---|---|---|---|---|---|
| 10xflix | https://10xflix.beer | — (generic) | 7,082 | down | Probe+chain failed 28 Nov; re-verify before any verdict |
| 7starhd | https://7starhd.domains | — (generic) | 6,543 | active | Moved 26 Nov (.surgery → .domains) |
| 9xmovies | https://9xmovie.cologne | — (generic) | 11,864 | active | Verified 21 Nov |
| animeworld | https://animeworld.live | https://animeworld.live (wired, wp_family) | 0 | down | Down since 27 Nov; probe today hit WAF (blocked, registry untouched) — re-verify |
| bolly4u | https://bolly4u.fly | — (generic) | 4,253 | active | Moved 25 Nov (.moda → .fly) |
| bollyflix | https://thebollyflix.io.in | https://thebollyflix.gen.in (wired, wp_family) | 0 | unverifiable | Cloudflare challenge from Oracle egress; gen.in 301s to io.in — not a down verdict |
| castle | https://www.bulwik.com | bulwik.com (wired) | 40 | active | Verified 9 Nov — stale date, re-probed daily (see observation) |
| desicinemas | https://www.desicinemas.pk | — (generic) | 5,291 | active | Verified 8 Nov — stale date |
| dramadog | https://dramadog.app | dramadog.app (wired) | 130 | down | Probe+chain failed 28 Nov; re-verify |
| filmyzilla | https://www.filmyzilla90.com | — (generic) | 15,301 | active | Moved 25 Nov (89 → 90) |
| harpalgeo | https://www.harpalgeo.tv | — (generic) | 506 | down | Probe+chain failed 28 Nov; re-verify |
| hdhub4u | https://new17.hdhub4u.cl | — (generic) | 6,559 | active | Moved 26 Nov (new16 → new17) |
| hdtvb | https://hdboss.in | — (generic) | 3,732 | down | Probe+chain failed 28 Nov; re-verify |
| hindianimeszone | https://hindianimeszone.com | same (wired, wp_family) | 0 | active | Verified 21 Nov |
| hindidubbedmovies | https://hindidubbedmovies.pk | same (wired, wp_family) | 0 | active | Verified 21 Nov |
| hindilinks4u | https://www.hindilinks4u.zip | — (generic) | 23,681 | unverifiable | Cloudflare challenge from Oracle egress; checked today |
| javmost | https://www.javmost.ws | same (wired) | 234,049 | active | Verified 11 Nov — stale date |
| katmoviehd | https://katmoviehd.mom | — (generic) | 12,976 | active | Verified 23 Nov |
| megacartoons | https://www.megacartoons.net | same (wired) | 12 | down | Probe+chain failed 28 Nov; re-verify |
| missav | https://missav.ws | same (wired) | 0 | active | Verified 11 Nov — stale date |
| moviehax | https://moviehax.run | same (wired, wp_family) | 0 | active | Verified 21 Nov |
| movienestbd | https://movienestbd.best | — (generic) | 4,264 | down | Probe+chain failed 28 Nov; re-verify |
| moviesdrive | https://new5.moviesdrive.cyou | — (generic) | 1,865 | active | Moved 26 Nov (new4 → new5) |
| moviezguru | https://moviezguru.host | — (generic) | 3,456 | active | Verified 22 Nov |
| multimovies | https://m1.multimovies.courses | — (generic) | 3,735 | active | Verified 8 Nov — stale date |
| rareanimes | https://www.rareanimes.foo | — (generic) | 1,328 | active | Verified 22 Nov |
| rogmovies | https://rogmovies.beauty | — (generic) | 5,554 | active | Verified 20 Nov |
| ssrmovies | https://site5.ssrmovies.page | — (generic) | 6,703 | active | Moved 27 Nov (site4 → site5) |
| themoviebox | https://themoviebox.xyz | same (wired) | 63,367 | down | Probe+chain failed 28 Nov — biggest down site (63k posts); re-verify FIRST |
| toonworld4all | https://toonworld4all.me | — (generic) | 893 | active | Verified 23 Nov |
| vegamovies | https://vegamovies.latino | — (generic) | 8,579 | active | Verified 20 Nov |
| world4ufree | https://new4.world4ufree.watch | — (generic) | 3,382 | active | Probe said down, chain resolved same base — re-verified today 05:41 |
| xprimehub | https://xprimehub.pics | — (generic) | 5 | active | Verified 23 Nov |

Totals: 33 registry sites — 23 active, 8 down, 2 unverifiable.
Down sites hold 79,093 live posts (themoviebox 63,367 dominates).

## Non-registry importers (live posts, no registry entry)

Candidates for registry additions as a separate item, not wiring:
moviesmela 12,489; mp4moviez 4,125; pagalmovies 3,657; moviesflix 1,432;
filmyhit 935; filmiyin 895; moviecok 696; kisskh 544; dramacool 507;
aryplus 481; 5movierulz 284; animahd 161; levidia 73; filmywap 62;
kuttymovies 38; humtv 33; desiserials 30; archive.org 19; fzmovies 0;
iptv_org (Live TV, no site domain).

## Follow-ups (not this branch)

1. **Re-verify the 8 down sites** (themoviebox first — 63,367 posts)
   before any verdict. One failed probe+chain day is not a delete
   verdict: never delete on an outage/maintenance window, and never
   delete a whole service on a homepage-200 alone. If the re-probe
   still fails from Oracle egress, try the chain from a second egress
   or the residential path before concluding. This is Phase 1 item 8
   territory (dead-host verdicts), not a scheduler gap.
2. **Repo registry copy is 5 days stale** (frozen 23 Nov; live moved 6
   sites since). Parent decision: sync the live registry back into the
   repo copy, or leave the repo copy as a snapshot and treat live as
   the only authority. Do not touch the live-tree runtime registry
   files either way.
3. Optional: `probe_site` refresh of `last_verified`/`last_checked` on
   a no-change success, so the 7-day skip engages for healthy sites.
