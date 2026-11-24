# 2026-11-24 — SEC-05: nginx hardening — HSTS inheritance gaps + 56×503 verdict

Branch `muse/phase4-sec05-nginx-hardening-20261124` from `a6e27ad6`.
Gate deploys the nginx half (the live config is NOT repo-managed);
the patch files + test land through the normal gate queue.

## Verdict up front

Four of the five SEC-05 sub-items were already done live when this lane
looked (the TODO's evidence was Nov 20–23; the live state moved since).
Only one real fix remains — HSTS — and it is a patch file, not code.

| # | TODO claim | Live truth (2026-11-24) | Action |
|---|------------|--------------------------|--------|
| 1 | scale-real-ip patch "not applied" | APPLIED live 2026-11-23 (commit `6c810aa2` message + lines 11–33 of the live file: all 22 `set_real_ip_from` + `real_ip_header CF-Connecting-IP`); app half on main (`1edcf8cf` + `6c810aa2`); Cloudflare lists re-verified current today (ips-v4/v6, 15+7, exact match) | None — record only |
| 2 | "missing HSTS" | Present at server level on both HTTPS servers (lines 128, 807) BUT missing from 28 location responses: nginx `add_header` is not inherited, and 14 locations per HTTPS server define their own `add_header` without HSTS | `nginx-hardening.patch`: 28 insertions (this branch) |
| 3 | allow-list items outstanding (enrich, works, browse, search, resolve, contact) | ALL present live, both servers: enrich 400/1079, works 532/1211, browse 554/1233, search 576/1255, resolve 620/1299, contact 446/1125 (+ feed.m3u, play, suggest, links/, episodes/) | None — record only |
| 4 | dead limits auth/register/api/guest | REMOVED 2026-11-08 (Session 105); `raddflix_security.conf` lines 2–5 document the removal; 0 occurrences in sites-enabled + conf.d (the 2 grep hits are the removal comment itself) | None — record only |
| 5 | 56×503 cluster Nov 20 "unexplained" | EXPLAINED — see below. Cause is `limit_req`, NOT upstream timeouts. The Nov-20 checkpoint's "cold-cache refills under load" explanation is wrong for these 56. | None in config; follow-ups below |

## The HSTS gap (the fix)

nginx rule: a location that defines ANY `add_header` inherits NONE from
its server. Both HTTPS servers set HSTS at server level, but these 14
locations per server define their own headers without it, so their
responses carry no `Strict-Transport-Security` today:

`/api/v1/stream`, `^~ /api/v2/account`, `= /api/v1/search/by-media`,
`= /api/v1/download`, `= /api/v2/play`, `= /api/v2/enrich`,
`= /api/v1/contact`, `^~ /api/v2/links/`, `^~ /api/v2/episodes/`,
`/api/v2/works`, `= /api/v2/browse`, `= /api/v2/search`,
`= /api/v2/feed.m3u`, `/api/v2/resolve` — × 2 servers
(DuckDNS @108, ccwu.cc @787).

The cached GET routes (posts, stats, art, remote-config, trending,
related, facets, v1 works, live-tv) already re-add HSTS; locations with
no `add_header` inherit it. NOT touched, deliberately: the port-80
default server (plain HTTP — HSTS is ignored over HTTP), the two
Certbot port-80 redirect servers (301 to https), and `sites-available`
(543 lines, stale vs the 1,465-line enabled file — the enabled file is
canonical; syncing the available copy is a separate hygiene item, not
part of this patch).

`nginx-hardening.patch` (repo root, next to `nginx-real-ip.patch`):
28 insertions of the identical line
`add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;`
(same value as server level, no preload), one after the last
`add_header` in each gap location. Anchor: live
`/etc/nginx/sites-enabled/radd-stream`, md5
`73fdcff003edb79b246ac77705f27f3e`, fetched 2026-11-24, 1,465 lines.
Verified: `git apply --check` OK, applied copy byte-identical to the
generated one, HSTS occurrences 26 → 54, brace depth 0, inheritance
gaps 28 → 0 (same audit run on both copies).

## The 56×503 cluster (cause, with log evidence)

Window: 01:21–01:56 PKT 20 Nov, route `/api/v1/app/remote-config`.

- Access log: 56×503 in the window — 01:21: 3, 01:31: 16, 01:32: 27,
  01:56: 10 (exact match to the Nov-20 checkpoint's count), from 3 IPs:
  `20.221.70.237`: 43, `3.42.157.142`: 10, `52.156.110.225`: 3.
  Sample:
  `20.221.70.237 - - [20/Nov/2026:01:31:14 +0500] "GET /api/v1/app/remote-config HTTP/1.1" 503 190 "-" "RaddStream/0.1.0 (Android Media3)"`
  (three identical lines in the same second).
- Error log, same second:
  `2026/11/20 01:31:14 [error] 3351770#3351770: *290980 limiting requests, excess: 30.310 by zone "radd_stream_api", client: 20.221.70.237, server: raddstream.duckdns.org, request: "GET /api/v1/app/remote-config HTTP/1.1", host: "raddstream.duckdns.org"`
- Count check: `limiting requests` errors in 01:21–01:39 = 46,
  01:50–01:59 = 10 → 56/56. Zero `upstream timed out` errors in
  01:30–01:39. **The 56 are 100% nginx `limit_req` 503s.**
- Rate: `20.221.70.237` sent 29 requests in the single second 01:31:14
  and 117 in the minute 01:32, against a 10 r/s zone with burst 30.
- Route scope: in 01:00–02:00 the 3 IPs requested ONLY remote-config
  (661 requests: 605×200 + 56×503) — no other route touched.
- Identity: `3.42.157.142` reverse-resolves to
  `ec2-3-42-157-142.us-west-2.compute.amazonaws.com` (AWS EC2); the
  other two have no PTR record. All three are cloud-provider ranges.
  None of the three appears on Nov 19 or Nov 21 for remote-config —
  one-day-only actors. UA is the app's (`RaddStream/0.1.0 (Android
  Media3)`), so this is either an external actor spoofing the UA or a
  cloud test rig / emulator farm — NOT normal app traffic (the app
  fetches remote-config once per launch; 29/s from one client is not a
  launch pattern).
- NOT a restart: no `radd-stream.service` restart 01:00–02:00
  (restarts that day were 03:26 and 07:52 PKT); the 605×200 in the same
  window prove the upstream was answering fine.
- NOT the sweep: the sweep loop was running (it exited 01:33Z =
  06:33 PKT on the STOP file), but the sweep never requests
  remote-config — it probes `download_sources` directly — so it cannot
  produce `limit_req` errors. Sweep overlap is ruled out as a cause.
- NOT the shared-edge-key defect, for these 56: the three clients
  connected DIRECTLY (DuckDNS host, client = real peer IP, not a
  Cloudflare edge IP), so their buckets were already per-IP before the
  real-ip patch. The patch (now live) fixes the shared-key case for
  Cloudflare-path users; it changes nothing for direct-path bursts —
  the same burst today would still 503 at the same 10 r/s + burst 30.

Bigger picture (same day, not the 56): Nov 20 had 8,558 remote-config
503s out of 38,859 requests (22.0%; Nov 19: 104/18,794 = 0.55%;
Nov 21: 22/3,731 = 0.59%) and 13,327 `limiting requests` errors
(Nov 19: 167; Nov 21: 0). The morning cluster 08:00–10:00 (22 distinct
IPs, hundreds per minute, e.g. 444 at 09:05, 465 at 09:19) is the same
`limit_req` cause (08:02 sample: `limiting requests, excess: 30.110`,
client `3.42.157.93`, server `radd-stream.ccwu.cc`). Nov 20 was an
anomalous rate-limit day across the board; the 56 were only its first
small burst. Separately, Nov 20 had 52 `upstream timed out` errors
(Nov 19: 5; Nov 21: 51), peaking 07:xx (13) and 14:xx (16) — a genuine
but distinct upstream-slowness signal, with a `database is locked`
error in the journal at 08:12:15. That is NOT this item's fix.

## Follow-ups (not this branch)

- F1: find the burst source — what sends remote-config 29×/s from one
  client? If it is the app's retry loop, fix the retry; if it is an
  external actor, consider a tighter per-route limit or fail2ban-style
  handling. (App lane; needs client-side evidence.)
- F2: the 52 upstream timeouts on Nov 20 + the 08:12:15 DB-lock error
  remain an open upstream-slowness signal; the Nov-20 checkpoint's
  follow-up ("find the read stall / warm the cache") still applies to
  THAT set, not to the 56. (Infra lane.)
- F3: `sites-available` (543 lines) is stale vs `sites-enabled`
  (1,465 lines) — sync or delete it so nobody edits the wrong file
  (the known sites-enabled trap). (Infra lane, hygiene.)
- F4: re-check the Cloudflare IP lists when the real-ip patch is next
  touched — verified current 2026-11-24 (15 v4 + 7 v6, exact match).

## Branch contents

- `nginx-hardening.patch` (new, repo root): the 28 HSTS insertions.
- `Fast_Fetch/test_nginx_hardening.py` (new): 12 tests, stdlib-only —
  real-ip ranges == Cloudflare's published lists, hardening patch is
  28 identical HSTS insertions + nothing else, every hunk lands among
  other `add_header` lines, anchor md5 recorded, no secret material,
  and the HSTS audit function itself tested on before/after fixtures.
  Local: 12/12. Fail-first: stripping one HSTS re-opens the gap.
- `.github/workflows/backend-ci.yml`: +1 🧪 step (163 → 164),
  after `test_redact.py`.
- This checkpoint file. CONTINUITY.md rotation deliberately skipped
  (sibling branches already collide on it; the gate runner reconciles).

## Local verification

- `git apply --check` on a copy of the fetched live config: OK;
  applied copy byte-identical to the generated patched copy.
- Inheritance audit (same parser both copies): 28 gaps → 0 gaps.
- `test_nginx_hardening.py`: 12/12 (mock repo-root layout).
- Neighbours: `test_ci_manifest.py` 10/10, `test_docs_invariants.py`
  checked by CI (new step satisfies its step/module rules: module on
  disk, run once, name == run basename).
- No live writes: config + logs read-only over SSH; no reload, no
  restart, no live-tree/DB touch. No secrets read or written.

## Gate step (owed, gate owner)

1. Re-fetch `/etc/nginx/sites-enabled/radd-stream`; confirm md5 is
   still `73fdcff003edb79b246ac77705f27f3e` — if it moved, re-anchor the
   patch (regenerate from the new copy; the audit script is in the
   phase-4 note) and re-verify `git apply --check`.
2. Apply the patch to the ENABLED file (not sites-available), run
   `sudo nginx -t`, reload.
3. Prove live: `curl -sI https://raddstream.duckdns.org/api/v2/play`
   (HEAD on a POST-only location returns 403, but the 403 still carries
   that location's `add_header ... always` headers — HSTS on it proves
   the patch) and `curl -sI
   https://raddstream.duckdns.org/api/v2/works` (200, cached GET) —
   each must carry
   `Strict-Transport-Security: max-age=31536000; includeSubDomains`.
4. Prove no regression: posts 200, remote-config 200, panel 303, press 302.
