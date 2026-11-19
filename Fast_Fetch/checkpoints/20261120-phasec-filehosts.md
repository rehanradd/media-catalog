# 2026-11-20 — Phase C Lane G: classic file hosts (megaup / multiup / clicknupload) `[PHASEC-FILEHOSTS]`

Branch `muse/phasec-filehosts-20261120` from `44631e10` (H4 R9 tip; rebased from
`78c66197` after H4 R8 + H4 R9 merged). Coordinator merges nothing — this lane
pushes its branch and reports back. No live deploy, no live DB write, no
service restart, no `/opt/radd-stream` touch.

## Verdicts (census order)

- **megaup — UNCRAKABLE from the server** (82,163 rows; sample
  `megaup.net/7736122f11a02cb55fff532f708f4f0d/...720p...mkv`, status active).
  Chain proven from the hatch egress: GET page (200, 768 KB) → 2 s countdown
  → the page's inline script reveals
  `https://download.megaup.net/?url=<per-fetch token>` (no captcha on the
  page). Two independent server-side blocks: (a) `megaup.net` TLS-resets the
  Oracle VM egress immediately after ClientHello (curl AND Python urllib,
  root AND file page, twice; control `example.com` 200 from the same box;
  hatch egress 200) — a stored cf_clearance cannot fix a reset that happens
  before any HTTP request is sent; (b) `download.megaup.net/?url=<fresh
  token>` answers a Cloudflare "Just a moment..." 403 challenge from BOTH
  hatch and the VM, and a second attempt (cookies + Referer) timed out at
  60 s with 0 bytes. No resolver built — the final step is unproven even
  off-server.
- **multiup — CHAIN CRACKED, NOT RUNNABLE from the server** (67,454 rows;
  sample `multiup.io/download/3572141cc0682e3b05f9a602cc4ffbd4/...720p...mkv`,
  status active). Chain proven live from hatch: GET download page (200,
  487 KB) → POST its mirror form (`/fr/mirror/<id>`) with the page's
  `_csrf_token` (session cookies kept) → "Liste d'hébergeurs" page (200)
  with 4 mirrors: `gofile.io/d/ldocgtHD`, `mixdrop.ag/f/pjjpz9w9bnzxpw`,
  `dailyuploads.net/y6jrm8dwbb8f`, `1fichier.com/?ybg5v1vw37huj7ei43zk&af=62851`.
  Blocker is environmental only: `multiup.io` TLS-resets the Oracle VM egress
  exactly like megaup.net (curl AND urllib, root AND page; `example.com` 200).
  Resolver built anyway (see below) — correct, fixture-tested, and live-proven
  from a non-blocked egress; it fails server-side at the first GET with
  status 0 "Connection reset by peer" until the egress block lifts.
- **clicknupload — UNCRAKABLE (interactive hCaptcha)** (52,864 rows).
  14 of 15 sampled URLs are dead despite DB status "active" (all return the
  13,837-byte "File Not Found" page); 1 live:
  `clicknupload.cam/mc8pdwk068nt` (1.7 GB, "SSRmovies.COM - Sa Re Ga Ma Pa
  Episode 05 3rd October 2026 1080p WEB-DL x264.mkv"). Flow proven from the
  VM (XFileSharing): GET → POST `op=download1` → 15 s countdown + hCaptcha
  (`hcaptcha.com/1/api.js`, sitekey `d6e335c1-0dab-4bf7-80c0-1ce402e273e6`) →
  POST `op=download2` with the `rand` token. Without a solved captcha the
  server answers `<div class="err">Wrong captcha</div>` and re-renders the
  form (proven: waited the full 16 s, posted with an empty
  `h-captcha-response`). No server-side bypass. No resolver built.

## Research (first, per standing order)

- megaup.net: free tier = captcha + wait + adblock check; GreasyFork has
  anti-adblock/autodownloader userscripts (`megaupSkipADBCheck`,
  "MegaUp.net Anti-AdBlock Nuke", "Bypass Instant IGG-GAMES URLBlue +
  MegaUp") and a debrid-generator upsell; no JDownloader plugin documents a
  server-side token flow. Sources: greasyfork.org/en/scripts/by-site/megaup.net,
  debrid-downloader.com/premium-generator/megaup.
- multiup.io: mirror-aggregator download pages (`/download/<32-hex>/<file>`);
  the mirror list is NOT in the initial HTML — it needs the POST. Facebook
  screenshot confirms the current UI ("powered by Gofile Team"). Sources:
  multiup.io/download/e1f5b5285ab03832f6f8f46354a34c29/03ED.xdelta,
  thepcgames.net/ash-of-gods-redemption-download.
- clicknupload.co/.cam/.click: SibSoft XFileSharing (captcha protection is a
  named feature of the script); JDownloader's `XFileSharingProBasic` base
  class covers the family. Sources: sibsoft.net/xfilesharing_free.html,
  deepwiki.com/mycodedoesnotcompile2/jdownloader_mirror.

## Built (multiup only)

- `Fast_Fetch/importers/host_resolvers.py`: `SERVICE_MULTIUP = "multiup"`,
  `is_multiup_url` / `multiup_download_id` (`/download/<32-hex>` with
  optional locale prefix), `unwrap_multiup` (GET page → POST mirror form
  with `_csrf_token` + Referer → pick best mirror: gofile, then mixdrop,
  else page order; share/reseller/Usenet links excluded; `&amp;`
  unescaped). Returns the mirror link as `final_url`, status ALWAYS
  "unverified" — the mirror is never resolved (per the brief: the mirror's
  own resolver owns the next hop). Wired into `RESOLVER_BY_SERVICE` and
  `SERVICE_RESOLVERS` (before the mxdrop catch-all).
- Two real gotchas fixed during the build: (1) the mirror form + token sit
  at ~464 KB of a ~488 KB page and the mirror links at ~464–476 KB — both
  past the module's 200 KB `PROBE_READ_BYTES`, so `_cookie_jar_fetch` gained
  a `read_bytes` parameter (default unchanged) and the unwrapper reads with
  `MULTIUP_READ_BYTES = 600_000`; (2) the mirror form action is locale'd
  (`/fr/mirror/<id>`), so it is parsed from the HTML, never constructed.
- `Fast_Fetch/importers/test_multiup.py` (23 tests): id extraction, wiring
  (incl. routing precedes the mxdrop catch-all), best-mirror preference
  (gofile → mixdrop → page order), POST token/Referer, verify ignored + the
  mirror never fetched, dead/blocked/transport-failure mapping (status 0
  "Connection reset by peer" → unknown, never dead), no-form / no-mirror /
  not-a-URL cases, attribute reorder, locale action, `&amp;` unescape, no
  stored-URL reuse. Fixtures are reduced from the real pages fetched
  2026-11-20; every request goes through an injected fake fetch, socket
  raiser on.
- Gate step (126 suites, was 125) + `MANDATORY_RUN` entry
  (`test_multiup.py`).
- Live proof (hatch egress, real `unwrap_multiup` call, no fixtures):
  `final_url https://gofile.io/d/ldocgtHD`, "mirror list live (4
  mirror(s))", status unverified. Server-side live proof is impossible
  while the egress RST stands (see above).

## Local test results

- New module `test_multiup.py`: 23/23 OK.
- `test_host_resolvers.py`: 303/303 OK (shared-helper regression check —
  `_cookie_jar_fetch` signature change).
- `test_docs_invariants.py`: 22/22 OK. `test_ci_manifest.py`: 10/10 after
  commit (1 failure before commit is the by-design "present on disk but NOT
  tracked in HEAD" guard for the new module).
- Backend CI: pending (run id to be recorded after push).

## Still owed (for the coordinator)

1. **Egress block (megaup + multiup):** both hosts TLS-reset the Oracle VM
   egress at ClientHello. Until that lifts (or a sanctioned alternate
   egress exists), no server-side resolver for either host can run. A
   stored clearance cannot help — the reset precedes HTTP.
2. **Dispatch handoff (multiup):** `fast_fetch.resolve_live_source` has no
   `multiup` branch, and `embed_from_verdict` refuses "unverified", so the
   press path cannot serve a multiup verdict as-is. The correct integration
   is a re-dispatch of `final_url` through the mirror's own resolver
   (gofile/mixdrop) — a design call for the coordinator, not this lane.
3. **clicknupload:** nothing to build while hCaptcha is enforced; re-probe
   only if the host drops the captcha. Note the DB statuses are stale:
   14/15 sampled clicknupload rows say "active" and are dead.
4. **megaup:** re-probe only if the download endpoint stops challenging
   (the page chain itself needs no captcha — only the endpoint + egress
   block it).
5. CONTINUITY.md rotated on first edit (11,992 B → under 12,000 B; archive
   count 123 → 124: 121 dated + 3 undated).

## Files

- `Fast_Fetch/importers/host_resolvers.py` (resolver + wiring + jar-fetch cap)
- `Fast_Fetch/importers/test_multiup.py` (new, 23 tests)
- `.github/workflows/backend-ci.yml` (gate step 126)
- `Fast_Fetch/test_ci_manifest.py` (`MANDATORY_RUN`)
- `Fast_Fetch/CONTINUITY.md` (rotated index entry)
- `Fast_Fetch/checkpoints/20261120-phasec-filehosts.md` (this file)

NOT merged — parent merges; no live deploy, no live DB write.
