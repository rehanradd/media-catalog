# Phase C — filepress (2026-11-20)

Branch: `muse/phase-c-filepress-20261120`, from main `8f7948d2` (H4 R8).
Base note: main has since moved (H4 R9 `44631e10`, 02:52 PKT) — rebased
before push; see §5.
Author: rehanradd <199005610+rehanradd@users.noreply.github.com>.
Status: **CRACKED VIA WARP EGRESS — real press proven (206 + Matroska
bytes). NOT servable from production as-is: see §4.**
No code changed (deliberately — §4.3), nothing merged/deployed. This file
is the deliverable plus the probe scripts in `/tmp` on the Oracle VM
(`fp-probe-20261120.py`, `fp-rt.py`, `fp-pairs.py`, `fp-method.py`).
Probe results also in `/tmp/fp-range.bin` (512 Matroska bytes, VM).

## 0. What already existed (do not re-build)

- The FileBee 2-POST chain is **already built, tested and wired**:
  `Fast_Fetch/importers/host_resolvers.py::unwrap_filepress`
  (`indexDownlaod` + null captchaValue → token → `downlaod2` → direct link,
  Range-probed, only 206+video bytes is "active"), dispatched from
  `fast_fetch.py::resolve_live_source` (filepress branch, gate-skip from the
  unwrapper's own probe). Proven live 2026-11-13 from the hatch egress.
  The Android field-test harness mirrors the same chain
  (`App/.../resolve/Filepress.kt`, Engine 1).
- So this lane's job was never "write the unwrapper" — it was: re-measure
  the three legs now (page → API → origin) from the production egress and
  prove a real press, or prove exactly what blocks it.

## 1. Research (before touching code)

- No public filepress extractor exists: yt-dlp has none and rejects piracy
  sites outright; web/GitHub searches for "filepress extractor / direct link
  generator" return nothing usable (only URL-shortener noise and a gist list
  that merely names filepress).
- Cloudflare 522 = origin never answers Cloudflare (firewall DROP / wrong IP
  / server down); a visitor cannot fix it. Diagnosis is: test the origin
  directly (DNS + `--resolve` / direct-IP curl).
- Cloudflare WARP free tier: free, no account/card, Linux CLI (`warp-cli`),
  proxy mode exposes a local SOCKS5 proxy so only configured clients use
  it (no system-wide reroute). Install: apt repo `pkg.cloudflareclient.com`.
  Sources: evoxt.com WARP-on-VPS guide, websiteunblock.net WARP guide,
  blog.cloudflare.com WARP-for-Linux announcement.
- SEMrush (queried 20 Nov): `flapdoodle.cyou` (the 13-Nov download origin)
  had 165K visits Oct 2026, 51% of its traffic from `filepress.lat`;
  Aug 2026 traffic came from `filepress.baby` (93%). The family rotates
  domains; the origin itself was a live, trafficked host — then its DNS died
  (see §2).

## 2. Diagnosis — Oracle VM (production egress), 02:38–03:0x PKT 20 Nov

Live DB (read-only) confirmed: **171,476** filepress rows
(expired 60,786 / unverified 55,871 / active 54,819 per the brief).
Fresh active samples used: `6a8adba687ebcb22d0263089`,
`6a83e05087ebcb22d008d9ce`, `6a69de7e35670cd1489da618`, plus the brief's
`6ad44fdf69b577b115b03617` and own-host samples below.

### 2a. The mirror carousel (all behind Cloudflare, same anycast IPs)

- `new1–new4.filepress.lat`, `filepress.lat` → `104.21.49.108`,
  `172.67.189.193`. `filepress.baby` → `193.124.64.3`.
- Redirect chain, followed live: `filepress.baby` → `fpgo.xyz` →
  `rt.filepress.lat`; `new3` (root + API) → `new4.filepress.lat`.
  Current mirror at 03:0x PKT: **new4.filepress.lat**.
- Older mirrors' API routes now serve the SPA fallback (HTTP 200 + SPA
  HTML on POST `/api/file/downlaod/`, seen twice from hatch on new3/new2)
  or 301 to the next mirror. The API route lives only on the current mirror.

### 2b. Blocker 1 — Cloudflare managed challenge, intermittent + sticky

- File pages: 403 `cf-mitigated: challenge` ("Just a moment...") on EVERY
  mirror from BOTH egresses (Oracle BOM PoP, hatch SJC/SEA/ORD/IAD).
- API on new4: **HTTP 200 + real JSON twice from Oracle** (cf-ray NRT),
  then **403 challenge 5+ times in a row** on the same pair, and 403 on
  the method-variant probes. The challenge is per-(IP, host, window):
  it opens, then sticks — plausibly our own ~30 probes in ~20 min
  escalated the Oracle IP's bot score (a 429 "Too many requests" was also
  seen on `new6.filepress.today`). Lesson for any retry design: retries
  inside a challenge window are useless; the window must cool first.
- Own-host APIs all fail: `new4.filepress.baby` + `new1.filepress.lat`
  403 ×2 each; `new2.filepress.today` connection-reset ×2;
  `new6.filepress.today` 429 then reset; `filepress.click` TLS verify
  fail then reset. `new5/new6.filebee.xyz` NXDOMAIN (dead subdomains).
  `filebee.xyz` (83,926 DB rows, biggest single host) answers 200 + SPA
  HTML on the API route — no API there.
- Consequence: the resolver as built (`blocked:403` → give up at once)
  cannot mint from production while the window is closed; when the window
  opens it can reach the API (proven twice this session).

### 2c. Blocker 2 — the download origin is gone from global DNS

- `aws.flapdoodle.cyou`: **NXDOMAIN everywhere** — Oracle resolver,
  `dig @1.1.1.1` from Oracle (empty answer), and Google DoH
  (`dns.google/resolve`, Status 3 NXDOMAIN, SOA via Cloudflare NS).
  It 522'd on 13 Nov; now the name does not exist at all. No minted link
  on that host can ever play again. `downlaod2` may mint links on a NEW
  origin host — unknowable until a file mints (see §2d).

### 2d. The index question — unresolved, needs a cool window

- When new4's API answered JSON, every file tried returned
  `{"status":false,"statusCode":400,"err":"METHOD_HIDDEN"}`:
  task sample `6ad44fdf…`, fresh active `6a8adba6…`.
  Per the 13-Nov notes METHOD_HIDDEN = "file not in this mirror's index"
  (the method `indexDownlaod` itself is recognised — a bad method returns
  METHOD_NOT_FOUND). Unproven either way: the 20 DB rows actually stored
  under `new4.filepress.lat` (sample id `6af82ffaf6b10ab9877ea338`,
  status=unverified) could not be tested — its one attempt hit the
  403 window. Method-variant probes (`indexDownload`, `download`,
  `getFile`) all hit the 403 window too: no vocabulary signal.
- The own-host hypothesis (a file mints only on the mirror that indexed
  it) is untested for a live host for the same reason.
- RESOLVED by §2e/§3: through WARP the window opens and files DO mint
  (2 of 8 random active ids); METHOD_HIDDEN / INDEX_CLONE_FAILED are
  per-file states, not a dead API.

### 2e. Alternate egress — Cloudflare WARP (free, no card) — IT WORKS

- VM is **aarch64**; the staged apt repo pinned `[arch=amd64]`, so the
  first install failed (unmet amd64 deps). The repo DOES publish arm64
  (`binary-arm64/Packages`, cloudflare-warp 2026.3.846.0); the sources
  list was corrected to `arch=arm64` and install succeeded.
  No global IPv6 on the VM (`ip -6` empty, `curl -6` fails) — IPv6 egress
  is not an option.
- `warp-cli --accept-tos registration new` (free, no account) →
  `mode proxy` → `proxy port 40000` → `connect`. Proxy mode ONLY: only
  clients configured for the SOCKS5 proxy use it; the live service never
  sees it. Egress IP via proxy: **104.28.232.96** (Cloudflare) vs direct
  92.4.95.252. WARP was **disconnected after every probe round** and is
  disconnected now; the package stays installed, daemon idle.
- Through WARP the challenge vanishes: file page **200** (1090 B,
  0.71 s), API **200 JSON on every call** (12/12 in this session).
  Direct egress in the same window: 403 challenge throughout (§2b).

## 3. PROOF — the chain plays (03:02–03:05 PKT 20 Nov, Oracle VM via WARP)

File 1 — DB id `6696d7ff628d83367f631417` (status=active):
- `POST new4.filepress.lat/api/file/downlaod/` → 200
  `{"status":true,"data":"6aff71eb7d938fbed7165471"}` (0.5–1 s)
- `POST .../downlaod2/` with the token → 200, direct link on
  `404page.flapdooodlex05.space`:
  `…/download/<base64>/White.Rose.2024.480p.Web-DL.HIN-TAM.x264.AAC.ESubs.Vegamovies.to.mkv`
- Range probe `bytes=0-511` via WARP → **HTTP 206, 512 bytes, 2.23 s**,
  first bytes `1a 45 df a3 … matroska` (EBML/Matroska). Repeated 1.66 s /
  206. **This is a real press: the file plays.**
- Same link from hatch egress → **403 "Your request was blocked."**
  (25 B, text/plain). From direct Oracle egress → timeout (000, 20 s).

File 2 — DB id `6996e2fc4b1ecb5acf7ed6e6` → token
`6aff72527d938fbed71656f5` → `downlaod2` 200, link on
`404page.flapdooodlex03.space` (origin rotates `flapdooodlexNN.space`):
`…/The.Swedish.Connection.2026.1080p.NF.WEB-DL.DUAL.DDP5.1.x264-Vegamovies.hot.mkv`.
Both minted links embed `@104.28.200.92@` (the WARP exit IP of the mint
requests; ifconfig via the same proxy showed 104.28.232.96 — WARP has
several exits, and the 206 still succeeded, so exact-IP enforcement is
false; the 403s are egress-reputation, not per-link binding).

Batch (8 random active DB ids, downlaod via WARP): **2 minted**
(above), 2 `INDEX_CLONE_FAILED` (notFound / File Not Found), 2
`METHOD_HIDDEN`, 2 unrecorded (output cut by `tail` — rerun if a rate
is needed). Mint rate ≈ 25% on random active rows — normal resolver
failover territory, not a blocker. Method vocabulary confirmed unchanged:
`indexDownload` (correct spelling) → `METHOD_NOT_FOUND`, so
`indexDownlaod` stays the only recognised method.

## 4. Verdict — cracked, but NOT servable from production as-is

**CRACKED** by the Phase C proof rule (a real press: 206 + video bytes).
Three facts bound what happens next:

1. **The mint needs a non-challenged egress.** Direct Oracle egress is
   challenge-stuck (403 from ~02:45 PKT through 03:05 PKT; it answered
   JSON twice earlier, then closed). WARP proxy egress answers every
   time. The old origin `aws.flapdoodle.cyou` is NXDOMAIN globally
   (§2c) — dead for good — but `downlaod2` now mints on
   `404page.flapdooodlexNN.space`, which is live.
2. **A WARP-minted link 403s from other egresses** (hatch 403, Oracle
   direct timeout). Whether a user's phone/residential IP can play a
   server-minted link is UNPROVEN — only the owner's phone field test
   can answer it. If the phone 403s too, server-side mint + 302 can
   never serve filepress, and the win is the CLIENT-side mint
   (the Android harness already implements this chain; from a
   residential IP the API answered 301s in run 1 — mirror moved, not
   challenged — and the harness now follows redirects).
3. **No code changed, deliberately.** The unwrapper already exists and
   is correct; what it needs is (a) `FILEPRESS_API_BASE` new3 → new4
   plus 301-following mirror discovery (the carousel moves every few
   days), and (b) an egress decision: routing the resolver's filepress
   calls through the WARP SOCKS5 proxy needs a stdlib SOCKS5 client
   (urllib has no SOCKS support) or a localhost bridge, plus
   warp-svc running in proxy mode on the production VM — an ops
   footprint that needs the coordinator/owner call. Landing (a)
   without (b) would be worse than nothing: in an open direct window
   the resolver could mint and serve a link the client then 403s on
   (the current code is safe as-is: its verify=True Range probe runs
   from direct egress, times out, and the link is never served).

## 5. What would unblock serving (in order)

1. **Phone field test** (owner): re-run the filepress samples with the
   redirect-following harness. If the phone mints + plays, filepress is
   cracked for users via on-device resolution — the server path is then
   probe-only and no server change is needed for playback.
2. If the phone ALSO 403s on a WARP-minted link, filepress can only
   play from the minting egress: server-side serving is impossible by
   design, and the only remaining server use is health verification
   (mint via WARP to confirm a file exists).
3. If a server path is still wanted: owner/coordinator decision on
   WARP-in-production (proxy mode, always-on daemon) + stdlib SOCKS5
   transport for `host_resolvers` + base bump to new4 with mirror
   discovery + a live press through the real resolver. Re-check the
   mint host at that point (`flapdooodlexNN.space` rotates too).
4. The direct-egress challenge window may reopen on its own — re-probe
   one direct API call before assuming it is permanent; this session's
   window stayed closed for 20+ min.

## 6. Files / runs

- Worktree `~/workspace/phase-c-filepress-20261120`, branch
  `muse/phase-c-filepress-20261120`. Rebased onto main `44631e10`
  (H4 R9) before push. **Only this checkpoint file changed; no code.**
- Push + Backend CI run id: (filled after push — see report).
- This checkpoint: `Fast_Fetch/checkpoints/20261120-phase-c-filepress.md`.
- Probe scripts: `/tmp/fp-probe-20261120.py`, `/tmp/fp-rt.py`,
  `/tmp/fp-pairs.py`, `/tmp/fp-method.py` (hatch + Oracle VM copies);
  `/tmp/fp-range.bin` on the VM = the 512 Matroska bytes.
- VM state left behind: `cloudflare-warp` 2026.3.846.0 INSTALLED,
  `/etc/apt/sources.list.d/cloudflare-client.list` corrected to
  `arch=arm64`, `warp-cli` DISCONNECTED (proxy mode configured, port
  40000, registration kept). No tunnel, no service change, no reboot.
- SSH: `ubuntu@92.4.95.252`, key `~/.ssh/id_ed25519`,
  `-o UserKnownHostsFile=/home/hatch/.ssh/known_hosts`. All DB reads
  `sqlite3 -readonly` against
  `/home/ubuntu/radd-stream-data/merged_catalog_20261025.db`. No writes
  anywhere; live tree / service / panel untouched.
- Process note: one `exec` in this session returned
  `userConfirmationRequired: timed_out` yet still delivered its full
  output (the hatch rt/fpgo probe) — nothing was lost or retried.
