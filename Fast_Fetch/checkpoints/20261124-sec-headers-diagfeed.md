# 2026-11-24 — Security audit fixes: nginx headers + /diag-feed/ removal

Branch `muse/phase4-sec-audit-fixes-20261124` from `0bcbb4f9`.
Gate applies the nginx patch live (the live config is NOT repo-managed);
the patch file + test land through the normal gate queue.

## Verdict up front

Two audit items, both fixed as a patch file, not code:

| # | Audit item | Live truth (2026-11-24) | Action |
|---|------------|--------------------------|--------|
| M-headers | CSP / X-Frame-Options / Permissions-Policy missing on the public edge | ABSENT everywhere: 0 occurrences of any of the three headers in the live file (1,465 lines, md5 `73fdcff003edb79b246ac77705f27f3e`). HSTS, nosniff and Referrer-Policy already present at server level on both HTTPS servers | `nginx-headers-diagfeed.patch`: 56 x 3 header insertions (this branch) |
| L-diag-feed | Temporary `/diag-feed/` nginx block still present | PRESENT in both HTTPS servers (lines 747, 1426), proxying a dead service on 127.0.0.1:18081 (probed 404, not serving). The block's own comment (added 2026-11-13) says to delete it | Same patch: both blocks removed |

## The headers gap (the fix)

nginx rule: a location that defines ANY `add_header` inherits NONE from
its server. Both HTTPS servers set nosniff + Referrer-Policy + HSTS at
server level, but 26 locations per server define their own headers, so
their responses would carry no CSP / X-Frame-Options / Permissions-Policy
from a server-level addition alone. The patch therefore adds the three
headers at server level AND after the last `add_header` in every location
that defines its own — the same rule SEC-05 used for HSTS:

- server level: port-80 default + both HTTPS servers (3);
- locations: 26 per HTTPS server + `/download/` on port 80 (53);
- total: 56 insertion points x 3 headers = 168 added lines.

Locations with no `add_header` of their own (`/operator`, `/admin`,
`^~ /api/`, `/`) inherit the server-level headers untouched. NOT touched,
deliberately: the Certbot port-80 redirect server (301s only, no content),
and HSTS anywhere (SEC-05 owns it — see below).

Header values (all with `always`, matching the existing style):

- `Content-Security-Policy "default-src 'self'; script-src 'self'
  'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;
  font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:;
  frame-ancestors 'none'; base-uri 'self'; form-action 'self'"`
- `X-Frame-Options DENY`
- `Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(),
  usb=(), magnetometer=(), gyroscope=(), accelerometer=()"`

CSP note: the `'unsafe-inline'` carve-out for scripts and styles and the
`blob:` workers are honest about the proxied code-server shell, which may
use inline code — the panel templates themselves carry no inline scripts
(0 `<script>` in `AdminV2/admin_v2/templates.py`). The policy still blocks
third-party sources and all framing. Tighten later once the panel's
inline-script inventory is proven empty under the live shell.

## The /diag-feed/ removal (the fix)

Both `location /diag-feed/` blocks (plus their 4-line removal comments)
are deleted by the same patch. The block's own comment says the removal
is two parts: delete the block AND stop the service. The service stop
(`kill $(cat /home/ubuntu/diag-feed.pid)`) is a LIVE step, not a patch
step — it is listed under Gate steps below, not in the patch.

## SEC-05 interaction (read before the gate)

This patch anchors to the CURRENT live file (md5 above), the same anchor
SEC-05 (`nginx-hardening.patch`) uses. Both patches insert after the last
`add_header` in the same 28 locations, so whichever lands second must
rebase its patch: if SEC-05 lands first, re-anchor these 28 hunks after
its HSTS line; if this lands first, SEC-05 re-anchors the same way. The
gate must verify `git apply --check` against the then-current file either
way — a failed check here is a rebase, not a broken patch.

## Local proof (this branch, 2026-11-24)

- Live file fetched read-only from the Oracle VM: 1,465 lines, md5
  `73fdcff003edb79b246ac77705f27f3e` — identical to the SEC-05 anchor, so
  the live config has not moved since SEC-05 measured it.
- `git apply --check` of `nginx-headers-diagfeed.patch` against that
  exact copy: clean. Applied copy diffed against the generated patched
  file: identical.
- Before/after counts on the applied copy: Content-Security-Policy 0 -> 56,
  X-Frame-Options 0 -> 56, Permissions-Policy 0 -> 56, `/diag-feed/` 2 -> 0,
  Strict-Transport-Security unchanged (26).
- `Fast_Fetch/test_nginx_headers.py`: 7/7 pass (counts, exact values,
  only-these-three-headers, no HSTS touched, both diag-feed blocks
  removed, every insertion hunk carries all three headers).

## Gate steps (owed, live — NOT done by this branch)

1. Land SEC-05 first, or re-anchor this patch after its HSTS line if
   SEC-05 is already live — whichever order, `git apply --check`
   against the then-current `/etc/nginx/sites-enabled/radd-stream`.
2. Apply, `sudo nginx -t`, reload.
3. Prove with `curl -sI` that `/api/v2/play`, `/api/v2/resolve` and
   `/api/v1/stream` responses carry Content-Security-Policy,
   X-Frame-Options and Permissions-Policy; and that the admin panel and
   `/operator/` still load (CSP must not break the panel shell).
4. Prove `/diag-feed/` answers 404 from nginx (no proxy), then stop the
   dead service: `kill $(cat /home/ubuntu/diag-feed.pid)`.
