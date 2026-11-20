# 2026-11-20 — Scale fix #1: per-user rate-limit keys (real IP behind Cloudflare)

Branch `muse/scale-real-ip-20261120` from `6199929e`. NOT merged — parent merges;
gate deploys (the nginx half is a gate-time apply, not a code deploy).

## Why

Scale verdict (`~/workspace/scale-100k-verdict-20261120.md`, bottleneck #1):
nginx `limit_req` (10 r/s) + `limit_conn` (32) and the app token buckets
(browse 60/min, play 30/min, in `Fast_Fetch/redact.py`) were ALL keyed on the
Cloudflare edge IP — nginx had no `real_ip` config and the app's `client_ip()`
trusted `X-Real-IP` (= the edge IP). Browse was 1 req/s SHARED across every
user behind an edge; routine 429s from ~3,000–10,000 daily users. Binding
constraint for 100k users. No limit numbers changed — the fix is the KEY.

## App (committed)

- `redact.client_ip()` reads `CF-Connecting-IP` FIRST, but ONLY when the socket
  peer is loopback (nginx) — same trust boundary as `X-Real-IP` today. A
  non-loopback peer still returns the peer and trusts no forwarded header.
  Absent header falls back to `X-Real-IP`, then `X-Forwarded-For` as today.
  Docstring carries the Cloudflare reasoning; once the nginx `real_ip` patch
  lands, `$remote_addr` — and therefore `X-Real-IP` — is the same user address
  and the header order stops mattering.
- `accounts.py`: `_trial_ip_hash` docstring updated (same key change).
- New test `test_redact.py::test_cf_connecting_ip_is_the_per_user_key_behind_cloudflare`:
  CF honoured from loopback, ignored from a non-loopback peer, absent header
  falls back to `X-Real-IP`, two users behind one edge get separate buckets.
  Negative control confirmed: fails on unfixed `viewer.py`/`redact.py`
  (git-stash check), passes fixed. `RateLimiterTests` 4/4.
- Diff: 3 files, +55/−2. Python stdlib only. No live DB touched, no restart.

## nginx (patch file, NOT applied live)

`nginx-real-ip.patch` (42 lines, in the branch root): 22 `set_real_ip_from`
(15 IPv4 + 7 IPv6, fetched verbatim from cloudflare.com/ips-v4 and /ips-v6 on
2026-11-20) + `real_ip_header CF-Connecting-IP;` in the http context, before
the first `server {`, so it covers the DuckDNS (#2) and ccwu.cc (#3) API
servers and the default server (#1). `real_ip_recursive` deliberately NOT set
(single address, nothing to recurse over). `_PROXY_MARKERS` in `viewer.py`
left alone. Anchored to the live file (md5
`504e39fc09a1659399fffa31d078e964`, verified via `nginx -T` that the running
config contains it; the file header says "DRAFT 2026-11-16 NOT LIVE" — stale
prose, it IS live). Live facts used: zones in `conf.d` (`limit_req_zone
$binary_remote_addr … rate=10r/s`, `limit_conn_zone $binary_remote_addr`);
`proxy_params` sets `X-Real-IP $remote_addr` via include; `CF-Connecting-IP`
passes through untouched; direct-IP/DuckDNS backup path unaffected (peer
outside Cloudflare ranges keeps its own address).

Verified on the VM against a COPY of the live config (live file untouched,
md5 unchanged after): patch applies clean (`git apply --check` + apply),
patched copy has 23 real_ip lines, `nginx -t` on the PATCHED copy is "syntax
is ok / test is successful" (only the pre-existing `proxy_headers_hash`
warnings). An earlier attempt ran `nginx -t` on the UNPATCHED copy after a
failed `git apply` (absolute paths in the diff vs the cwd) — that result was
discarded, not trusted; the patch was regenerated with git-style labels and
re-verified properly.

GATE STEP (owed): apply the patch to `/etc/nginx/sites-enabled/radd-stream`
(the ENABLED file is live, not sites-available), `nginx -t`, reload, then
prove per-user buckets live (two users behind one edge no longer share a
bucket; a single user can still burst to the documented limits).

## 503 finding (report only, no route changed)

The 56×503 on `/api/v1/app/remote-config` in the last 3000 log lines are NOT
from the 03:40 gate restart. They cluster at 01:21 (3), 01:31 (16), 01:32 (27),
01:56 (10) PKT 20 Nov, from 3 IPs (20.221.70.237: 43, 3.42.157.142: 10,
52.156.110.225: 3), UA `RaddStream/0.1.0 (Android Media3)`. Overall
remote-config in that window: 1367 requests, 1311×200, 56×503. nginx error
log: "upstream timed out (110: Connection timed out) while reading response
header from upstream" (also 01:05–01:06 and 02:43–02:53). `journalctl` for
`radd-stream.service` 01:00–02:10 shows NO entries — no restart; the app was
UP but SLOW (single Python process starved under load: backfill v1 + lanes).
remote-config IS micro-cached (60s TTL, stale-if-error incl. 503,
`proxy_cache_lock on`) — the 503s happen on cold-cache refills under load when
the origin doesn't answer within the 15s `proxy_read_timeout`. Follow-up for a
separate lane: find why the read stalls / warm the cache. Do NOT raise the
timeout (standing rule).

## Local verification

Full backend gate `run-backend-gate.py`: **111/111 PASS, 0 failed modules**
(~8 min, log `~/workspace/scale-gate-20261120.log`). `test_redact.py` 117/117
(116 + the 1 new test), `test_database.py` 239/239, `test_docs_invariants.py`
22/22, `test_download_endpoint.py` 27/27. Pre-push checks: run before commit
(see CONTINUITY entry). Backend CI pending on the pushed tip.

Environment note: the gate took three attempts. The hatch VM's sandbox blocked
`write()` to freshly created temp files with EPERM (and killed Python at
startup with silent exit 120) from ~04:47 PKT 21 Nov for ~35 min — every
tempfile-dependent test failed in setUp, unrelated to this change (all
`TemporaryDirectory` errors in untouched classes). Two 10-try wait loops
never saw a pass; a hatch VM reboot at ~05:21 PKT cleared it and the gate then
ran clean first try. The Oracle VM was not a fallback at the time: the
post-gate link sweep was running (48 workers, load ~6) plus backfill v2.

## Not done / follow-ups

- Scale bottlenecks #2–#5 are separate lanes (Cloudflare Cache Rules for safe
  GETs is scale fix #2, already decided under the 04:42 all-decisions order;
  trending precompute + more serve workers; disk/WAL; origin lockdown).
- Live proof of per-user buckets rides the gate deploy (see GATE STEP above).
- Safe load-test design is in the scale verdict doc — run only after fixes #1–#2,
  backup finished, sweep paused, load1 < 1.0.
