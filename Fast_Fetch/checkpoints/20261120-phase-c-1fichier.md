# Phase C — 1fichier crack attempt — 2026-11-20

**Verdict: NOT CRACKED.** Two independent blockers, either one alone is fatal
from the production egress. No code was built: there is no working chain to
wire, so no resolver, no tests, and no CI gate change in this branch. This
file is the whole deliverable: research + live-probe evidence + what would
unblock it.

- Worktree: `~/workspace/phase-c-1fichier-20261120`, branch
  `muse/phase-c-1fichier-20261120`, based on origin/main `8f7948d2` (H4 R8 —
  main had moved past the brief's `78c66197`).
- Commit author: rehanradd <199005610+rehanradd@users.noreply.github.com>.
- No merge, no push to main, no deploy, no service restart, no live-DB write.

## Scale (live DB, read-only, 20 Nov 2026)

`download_sources` rows whose `source_url`/`final_url` contains `1fichier`:

| status | rows |
|---|---|
| unverified | 90,621 |
| expired | 29,430 |
| active | 25,332 |
| dead | 8,193 |
| **total** | **153,576** |

(Brief said 152,317 / 90,586 / 29,173 / 25,318 / 7,240 — counts drifted
slightly since; dead is up ~950. Same order of magnitude, second-biggest
uncracked host after filepress.)

Sample links probed (all `status='active'`, freshest `last_checked_at`):
1. https://1fichier.com/?qfma73eg8cf8n3mtkqd3 (row 8036201)
2. https://1fichier.com/?xd07ev99yy3xo1hpmqu7 (row 8003997)
3. https://1fichier.com/?bwoovquvz6klxh9c9q1b (row 8003982)
4. https://1fichier.com/?hg9cq4jc4y0l3fe451m0 (row 8003968)
5. https://1fichier.com/?8kna1go1nk6lhgf6qfsy (row 8003953)

## Blocker 1 — Oracle VM egress IP is locked by 1fichier (fatal)

Probe: plain `urllib` GET with a desktop Chrome UA from the Oracle VM
(`ubuntu@raddstream.duckdns.org`, egress IP `92.4.95.252`), 5 sample links,
~03:00 PKT 20 Nov 2026. Results:

- Link 1: **HTTP 403**, 6,023-byte HTML page, title "1fichier.com: Cloud
  Storage". Body: *"For security reasons, following many request errors,
  your IP address (92.4.95.252) is temporarily locked. See the help page for
  more informations. error #350"* (same text in French).
- Link 2: **connection reset by peer** (TCP RST, no HTTP response).
- Link 3: **HTTP 403**, identical 6,023-byte lock page.
- Link 4: **connection reset by peer**.
- Link 5: **connection reset by peer**.

No landing page, no filename/size, no wait timer, no download form, no
direct link was ever served. Re-probe of link 1 ~25 min later: still
**HTTP 403, LOCKED**, same 6,023 bytes. The lock persists; further probing
was stopped (each failed attempt can reset a temp-lock timer, and see
below — the block looks standing, not transient).

Supporting evidence that this is a datacenter-IP block, not a one-off:
- litzler-boissons.fr (2026-05-09), "What to do when 1fichier is blocked":
  *"downloads from a Linux VPS consistently return a small HTML page instead
  of the actual file... 1fichier blocks data center IPs, not just VPNs."*
  That is exactly what we got: a small HTML page instead of the file.
- safhec.fr (Oct 2026): *"If your IP address is detected as coming from a
  datacenter, premium downloads may be denied even with an active
  subscription."*

The server that would have to resolve these links IS the Oracle VM, so the
production egress itself is refused at the front door.

## Blocker 2 — free tier ends at a CAPTCHA (fatal even if the IP worked)

Even on an unblocked IP, the free flow does not yield a direct link to a
server-side caller:

- vortex `vortex-mod-1fichier` v1.0.0 (mpiton/vortex release v0.3.0-beta.1,
  Nov 2026 — the freshest independent implementation found): free mode
  scrapes the landing page for filename/size plus `wait_seconds` (from
  `data-wait="…"`, `class="countdown"`, or `var c = …`) and a
  `requires_captcha` flag (detected via `g-recaptcha` / `h-captcha` /
  `class="captcha"`). Free `resolve_stream_url` surfaces
  `PluginError::CaptchaRequired` — *"until the captcha pipeline ships"*.
  There is no captcha solver in v1. The metadata path succeeds; the
  resolve path does not.
- Premium marketing copy confirms the shape: premium = "No waiting time
  and no captchas" — i.e. free = waiting time AND captchas.
- No open-source downloader found that completes the free flow server-side
  without a captcha solver or a premium key: yt-dlp has no 1fichier
  extractor; JDownloader handles 1fichier with captcha handling (manual /
  paid solver services); `Leinad4Mind/1fichier-dl` (Python GUI downloader,
  v2.3.0, Oct 2026) exists but no free-server-side flow could be confirmed
  from its public material; debrid/premium-link generators (Cocoleech,
  Deepbrid, PremiumDownloader.net, Real-Debrid, AllDebrid) all work by
  spending someone else's premium account, not by unwrapping the free flow.

## The premium API route is closed (no money)

- `POST https://api.1fichier.com/v1/download/get_token.cgi` with
  `Authorization: Bearer <key>` returns a one-shot direct CDN URL
  (plus optional `traffic_used` / `traffic_total`). Confirmed via the
  vortex plugin write-up and rclone's `backend/fichier` implementation.
- API keys are generated from a Premium account's developer settings.
  **Free accounts cannot generate API keys at all** (CloudsLinker, Apr 2026;
  also afebalk.org). Premium is €3/month / €15/year — money, which this
  lane cannot spend, and an owner decision in any case.
- Free-tier limits for context: ~100–150 KB/s, wait timers between
  downloads, daily limit, one download at a time (CloudsLinker / safhec /
  topbottins, 2026).

## What was NOT done, and why

- No `unwrap_1fichier` was written. A resolver built now would be dead code:
  it could never reach the landing page from the production egress (Blocker
  1), and even on a clean IP it would stop at the captcha (Blocker 2).
  The plug-in point for a future attempt is `resolve_live_source` in
  `Fast_Fetch/fast_fetch.py` (~line 7376) dispatching to a new
  `unwrap_1fichier`, alongside `unwrap_vcloud` / `unwrap_hubcloud` /
  `unwrap_filepress` etc.; `host_classifier.py:138,238` already maps
  `1fichier.com` → `CATEGORY_DIRECT_FILE`. `catalog_v2.py:103` lists
  `"1fichier"` as a known playback-type label only.
- No test module, no `backend-ci.yml` gate-step change, no
  `test_ci_manifest.py` `MANDATORY_RUN` change — there is nothing to test.
  Nothing that plays was proven, so nothing is counted cracked.
- No debrid / premium-link-generator service was used: that would spend a
  third party's premium account, is not the host's free flow, and would
  still be unreachable/irrelevant for server-side press-banking at scale.
- No CAPTCHA solving was attempted: solving a captcha to bypass the host's
  access control is outside the standing rules ("never bypass a host's
  access control to steal content — unwrap only what the host freely serves
  to a normal browser/player request").
- Probe budget respected: 5 sample links from the Oracle VM egress + 1
  single-link re-probe 25 min later. `browser.open` on a 1fichier URL was
  refused by policy ("Domain 1fichier.com is blocked by policy") — not
  retried, per the tool's own instruction. The agy2 chat log on the Oracle
  VM was never read.

## What would unblock it (in order)

1. **An egress IP 1fichier accepts.** Either the #350 lock on 92.4.95.252
   expiring AND Oracle's ASN not being in 1fichier's standing datacenter
   block (unproven — the VPS-block reporting suggests the block is
   standing, so expiry alone may not help), or resolving from a
   residential / non-datacenter egress. That is an infrastructure decision,
   not a code change.
2. **A CAPTCHA path.** Either a Premium API key (paid — owner decision +
   money) or a human-in-the-loop / solver-service captcha pipeline
   (owner decision; paid solver services exist, e.g. NopeCHA / 2Captcha
   class tools, but they are outside this lane's authority and the
   standing no-bypass rule).
3. Only then would the remaining free-flow work be small and known:
   GET landing page → parse filename/size + `wait_seconds` (`data-wait` /
   countdown / `var c`) → honour the wait server-side → solve captcha →
   POST the download form → follow to the one-shot CDN URL → bank it with
   a short TTL (free direct links are time-limited; the wait + throttle
   mean press-banking would still be slow — first press takes the wait,
   ~100–150 KB/s afterwards — so even cracked, 1fichier would be a
   last-resort tier, never a first-choice host).

## Research sources

- mpiton/vortex v0.3.0-beta.1 release notes (newreleases.io) —
  `vortex-mod-1fichier` free/premium modes, `get_token.cgi`, captcha flag.
- cloudslinker.com/support-clouds/1fichier/ — API is Premium-only, free
  tier 100–150 KB/s + wait timers, no API keys for free accounts.
- litzler-boissons.fr/en/what-to-do-when-1fichier-is-blocked — 1fichier
  blocks datacenter/VPS IPs; VPS downloads return a small HTML page.
- safhec.fr/en/how-to-enjoy-a-1fichier-premium-account — datacenter-IP
  downloads may be denied even with premium; Access vs Premium tiers.
- antpremium.com 1fichier premium review (18 Nov 2026) — premium removes
  waiting time and captchas (implies free has both).
- rclone `backend/fichier` (deepwiki) — API-key auth, Premium-only.
- Search variants used: "1fichier free download wait timer direct link
  extraction 2026", "yt-dlp 1fichier extractor", "1fichier API free account
  limits", "JDownloader 1fichier plugin free download wait countdown",
  "1fichier download form POST dl button direct link github", "rclone
  1fichier backend API endpoint", "1fichier free download captcha
  recaptcha hCaptcha", "Leinad4Mind 1fichier-dl python downloader",
  "1fichier error #350 IP temporarily locked", "1fichier blocks datacenter
  IP VPS Oracle cloud".

## Files changed

- `Fast_Fetch/checkpoints/20261120-phase-c-1fichier.md` (this file) — only
  change in this branch.
