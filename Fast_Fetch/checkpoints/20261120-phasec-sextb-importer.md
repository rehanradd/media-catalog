# 2026-11-20 — Phase C SexTB importer: NO IMPORTER — site is geo/IP-walled, no fetchable surface

Branch: `muse/phasec-sextb-importer-20261120` from `a52a4101`. No code, no
registry entry, no `ADULT_SOURCE_SITES` change — this lane's deliverable is the
negative finding, recorded so no later lane re-probes blindly or builds a
hollow importer from invented markup. NOT merged — parent/coordinator decides
what (if anything) follows. No live deploy, no DB touched.

## Verdict

SexTB (`sextb.net`) is ALIVE but UNCRAWLABLE from every server egress tried.
Cloudflare answers 403 "Access Restricted" on the homepage, the sitemap, and
a known video URL, with the stated reason "currently unavailable in your
region or has been temporarily restricted" — a geo/IP restriction on the
Oracle VM's IP (`92.4.95.252`, cf-ray `...-BOM`), not a JS challenge and not
a login wall. The hatch egress is blocked too (403, 25 B "Your request was
blocked."). The MissAV precedent does NOT transfer: MissAV's HTML was walled
but its `/sitemap.xml` answered 200, so a sitemap-first importer could stage
real URLs; SexTB's sitemap 403s as well, so there is no enumeration surface
at all and no page of real markup was ever observed. Any `import_sextb.py`
written now would have parsers guessed from nothing — the task's own rule
forbids building that hollow importer.

## Probe table (Oracle VM, 03:37–03:42 PKT 2026-11-20; curl, browser UA)

| URL | Result |
|---|---|
| `https://sextb.net/` | 403, 13,156 B, `server: cloudflare`, "Access Restricted" |
| `https://www.sextb.net/` | 403, 13,160 B, same page |
| `https://sextb.net/robots.txt` | 200, 3,259 B — Cloudflare-managed content-signal text, `Allow: /`, NO `Sitemap:` line |
| `https://sextb.net/sitemap.xml` | 403, same block page |
| `https://sextb.net/sitemap_index.xml` | 403, same block page |
| `https://sextb.net/jufd-634-rm` (known video URL, uBlock issue #35351) | 403, same block page |
| `http://sextb.net/` | 403 (redirect to https, then blocked) |
| `https://sextb.com/` | 000 — connection failure |
| `https://sextb.cc/` + `/robots.txt` + `/sitemap.xml` + `/sitemap_index.xml` | 403 on ALL four, "Just a moment..." JS-challenge page (~5.4 KB each) |
| Full browser headers (Accept-Language, Sec-Fetch-*, Chrome 126 Win UA) on `https://sextb.net/` | 403, 3,817 B (gzip) — headers do not help |
| No-UA homepage | 403 |
| Hatch-egress homepage | 403, 25 B "Your request was blocked." |

Block-page text (Oracle VM, tags/CSS stripped): "Access Restricted — Sorry,
this website is currently unavailable in your region or has been temporarily
restricted. Why is this happening? This restriction may be due to
geographical limitations, security policies, maintenance, or compliance with
local regulations. Performance & security by Cloudflare — Ray ID:
a5d2b536ec049be3 • Your IP: 92.4.95.252".

## Site facts (from search, not from a fetch — nothing was fetchable)

- Domain `sextb.net`, registered 2021-03-16 (Dynadot, expires 2027-03-16),
  Tranco rank #8,511 (>10M monthly visitors), Cloudflare-fronted, valid TLS.
- Japanese adult video (JAV) streaming, censored + uncensored, free, no
  login mentioned anywhere; also runs live cams ("sextb live").
- One real video URL observed second-hand: `https://sextb.net/jufd-634-rm`
  (uBlockOrigin/uAssets issue #35351, 2026-11-14 — a real user watched 3
  videos there, then hit an adblock-detection warning). That proves the site
  serves real browsers in some region; it does NOT establish a URL pattern —
  do not generalise `/<CODE>-rm` from a single sample.
- `sextb.cc` (Netcraft: first seen March 2026, rank ~1.3M, Cloudflare) is
  NOT treated as an operator mirror: unverified provenance, and it is
  challenge-walled on every path including robots.txt anyway.

## What was NOT done, and why

- No `Fast_Fetch/importers/import_sextb.py`: no sitemap, no listing page, no
  video page, no observed markup — nothing to parse, and no `--limit 20`
  staging run is possible (every fetch 403s; 0 rows would be staged).
- No `domain_registry.json` entry: `test_shipped_registry_is_v2_with_all_27_sites`
  pins exactly 27 sites, and the registry is the rotation list for live
  sites — a blocked site with no importer does not belong there yet.
- No `ADULT_SOURCE_SITES` addition in `fast_fetch.py`: the set is site
  authority for rows that exist; SexTB has zero rows.
- No new test suite / gate step / `MANDATORY_RUN` entry: there is no module
  to test. The local checks run were `test_docs_invariants.py` (22/22) and
  `test_ci_manifest.py` (10/10) — the docs touched here must keep passing.
- No real-browser check: this lane cannot drive a browser; the uBlock report
  above is the only real-browser evidence, second-hand.

## What would unblock a real importer (in order)

1. **Real-browser HAR from an allowed region** (owner phone / a browser in a
   region the site serves): homepage + one video page + sitemap attempt.
   That capture gives the URL shape, the listing markup, and the metadata
   fields — the importer is then shaped from observed HTML exactly like
   JAVMost was, with every row `is_18_plus=1` and a bridge-only
   `unverified` source (no m3u8 is expected in the page; extraction is a
   Phase C crack task).
2. **WARP egress test** (filepress precedent, 2026-11-20: WARP proxy egress
   answered 200 where direct Oracle egress 403'd): if WARP egress gets a
   200 homepage/sitemap from the Oracle VM, the importer becomes buildable
   server-side — but serving through WARP is an owner/ops decision, not
   this lane's.
3. **H4 R8 clearance reuse** (`host_clearance.py`): only helps if a
   clearance can be EARNED first — a geo block is not a challenge, so there
   is likely no clearance to earn from a blocked IP; do not assume it.
4. Re-probe after any move: the site may rotate domains (check the registry
   pattern) or lift the restriction; a fresh probe is one curl, not a build.

## Standing rules this lane kept

- Adult content stored flagged, never displayed: had an importer been
  possible, every row would have carried `is_18_plus=1` by site authority
  (JAVMost pattern), staging-vault only (`--db`, default
  `/home/ubuntu/radd-stream-data/sextb.db`), never the live DB.
- Stdlib only, no `requests`; never touched `/opt/radd-stream`, never
  restarted a service, never read the agy2 chat log on the VM.
