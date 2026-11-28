# Dead-host re-verification — 2026-11-28 (backend loop Phase 2 item 8, first half)

Re-probe of the 8 registry sites the daily domain probe found down this
morning (radd-domain-probe.timer, 10:40 PKT). Sources, all read 28 Nov
~18:50–18:55 PKT from the Oracle VM:

- **Re-probe run**: `domain_recheck.py --db <catalog> --probe-stale`
  (the daily timer's exact command), 18:50–18:52 PKT — 7 sites through
  the chain, all `unresolved:down`, 0 resolved; queue drain 0. Log:
  `/tmp/dead-host-reverify-20261128.out` (VM).
- **Per-site detail**: read-only probe (`probe_endpoint` per candidate +
  `probe_site(auto_update=False)`), 18:52–18:55 PKT. Log:
  `/tmp/probe-8-sites.out` (VM). Script: `~/workspace/probe-8-sites.py`.
- **Registry**: live registry `/opt/radd-stream/Fast_Fetch/domain_registry.json`.
  The re-probe advanced `updated_at` 05:41 → 13:52 UTC by stamping
  `last_checked` on the 7 down sites (the machinery's own write on a
  down result). No base changed, no status changed, no move resolved.
- **Live posts** from the domain map (verified 28 Nov): total 461,611.

## Verdicts

| Site | Live posts | Probe result (Oracle egress) | Chain result | Verdict | Next step |
|---|---|---|---|---|---|
| themoviebox | 63,367 | down — themoviebox.xyz answers HTTP 200 but `homepage-too-thin internal-links=0`; mirror 123moviesfree.club fails slug check | unresolved:down | STILL DOWN — re-verify later | Second-egress / residential check: thin page may be parked or bot-filtered |
| 10xflix | 7,082 | down — all 3 candidates (10xflix.beer/.asia/.bond) answer `http404` | unresolved:down | STILL DOWN — re-verify later | Second-egress check; 404 on every mirror is a strong content-gone signal but not a delete verdict alone |
| movienestbd | 4,264 | down — movienestbd.best answers HTTP 200 but `homepage-too-thin internal-links=0` | unresolved:down | STILL DOWN — re-verify later | Second-egress / residential check |
| hdtvb | 3,732 | down — all 3 candidates (hdboss.in, hdtvb.net, hdutv.com) redirect to **hdhubs.in**, which fails the site-slug check | unresolved:down | STILL DOWN — re-verify later | **Top manual-check candidate**: consistent redirect to one host smells like a real move; verify whether hdhubs.in IS the new hdtvb home. If it is, the slug rule can never accept it without a registry alias — flag for the resolver, do not hand-edit the registry |
| harpalgeo | 506 | down — both candidates answer HTTP 200 but `homepage-too-thin` (0 / 1 internal links) | unresolved:down | STILL DOWN — re-verify later | Second-egress / residential check |
| dramadog | 130 | down — dramadog.app answers HTTP 200 but `homepage-too-thin internal-links=0` | unresolved:down | STILL DOWN — re-verify later | Second-egress / residential check |
| megacartoons | 12 | down — both candidates answer, WP REST works, but `wp-json-no-quality-marker` | unresolved:down | STILL DOWN — re-verify later | Manual check: if the site's posts genuinely carry no quality marker, the marker rule can never pass — possible resolver strictness issue, not a dead site |
| animeworld | 0 | **ACTIVE** — animeworld.live answers, `wp-json 3 post(s), link host ok` | (skipped by the chain run: probe came back blocked, so no chain ran — by design) | **LIVE** | Nothing. Registry still says `down` (this probe ran with auto_update=False); tomorrow's 10:30 daily probe will mark it active on its own. Do NOT hand-edit the registry |

## What this means

- **7 of 8 still down on day 2** (28 Nov morning + 28 Nov evening), but
  **none of them is a dead server**: every one answers from Oracle
  egress — HTTP 200 with a thin/parked-looking page, 404s, or a
  redirect. The failure mode is "answers but no verifiable site
  content", not "server gone". A bot filter serving thin pages to
  datacenter egress would look exactly like this, so **no site is
  marked dead here** — a delete verdict needs a second egress or the
  residential path, per the crack-or-delete rule (never delete on one
  failed probe day, never on an outage/maintenance window).
- **animeworld is back**: the morning WAF block cleared; the evening
  probe verifies it live (wp-json, 3 posts). Its 0 live posts mean the
  79,093-post down-site total is unchanged by this.
- **Down sites still hold 79,093 live posts** (themoviebox 63,367
  dominates). No deletions in this branch — the soft-delete batch
  (backup first, rollback CSV, both tables) is a separate gated step
  and is NOT started until a site is proven dead from a second egress.

## Follow-ups (not this branch)

1. Second-egress / residential re-check of the 7 still-down sites,
   hdtvb first (consistent redirect to hdhubs.in), then themoviebox
   (63,367 posts).
2. If hdhubs.in proves to be the real hdtvb home, the resolver needs a
   registry alias path for it — the slug rule alone cannot accept a
   host that carries no site slug.
3. If megacartoons' posts genuinely lack quality markers, the
   wp-json quality-marker rule needs a fallback for that API shape.
4. Thin-homepage sites (themoviebox, movienestbd, harpalgeo, dramadog):
   a browser check would settle parked vs JS-rendered vs bot-filtered.
