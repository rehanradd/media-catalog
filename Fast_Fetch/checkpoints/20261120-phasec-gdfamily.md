# Lane C — Google-Drive shortener family (gdflix / gdtot / gd-mirror / appdrive / kmhd)

Branch `muse/phasec-gdfamily-20261120` from `8f7948d2` (H4 R8), rebased onto
`44631e10` (H4 R9) before push. Parent merges; no live deploy. All work
read-only against the live DB (SELECTs only, no writes, no restarts).

## Open items — index

1. **gd-mirror — CRACKED (wiring only; chain already existed).** Every sampled
   row is an iqsmart embed; the live Stage-1 path (`current_hosts`
   `classify_backend` → `"streamhg"` → `_unwrap_iqsmart`) and `resolve_url`
   already unwrap it. What was missing: `resolve_service("gd-mirror")`
   answered `no_unwrapper`, and `catalog_v2._SERVER_RESOLVABLE_SERVICES`
   did not list the labels, so iframe rows showed as `webview` instead of
   `resolve_required`. Both wired here. LIVE PROOF on the VM (lane code):
   `…/embed/tv/133064/1/2` and `…/1/3` → `active`, 206, HLS
   (`application/vnd.apple.mpegurl`, 1,081 bytes) on `*.cdn-centaurus.com`
   via both `resolve_service("gd-mirror")` and `resolve_url`; `…/1/1` →
   `dead` ("iqsmart api returned no media data for this title" — the
   title's data is gone, the chain ran end to end).
2. **gdflix — chain built + fixture-tested, NOT live-proven: Cloudflare-blocked.**
   `gd.kmhd.me/file/<id>` 302s to `new10.gdflix.io`; both answer 403
   "Just a moment..." even with full browser headers. New code returns
   `blocked` + "cloudflare challenge page" live. Serves the moment a placed
   `cf_clearance` (H4 R8 store) or a reachable egress lets the page through.
3. **gdtot — chain built + fixture-tested, NOT live-proven: TLS-reset from the VM.**
   `/file/<id>` 302s to `ww16.gdtot.eu/file/<id>?sub1=<uuid>`, then the hop
   resets (curl 35); `/ddl/` and `gdbot.xyz` reset the same way,
   intermittently. The site is alive (hatch egress: 200 in ~6 s). Live
   verdict from the new code: `unknown`, "ddl page carries no drive link"
   + "gdbot page HTTP 0" + "routes exhausted". No `gdtot_crypt` cookie is
   used or needed by the documented chain.
4. **appdrive — chain built + fixture-tested, NOT live-proven: host unreachable.**
   `appdrive.in` TCP-connect times out on 443 and 80 from the VM (empty
   reply from hatch egress); `appdrive.info` answers 200 but serves a
   ParkLogic parking page, not the file page. Live verdict from the new
   code: `unknown`, "sharer page HTTP 0 (transport: urlopen error timed out)".
   No login email/password is used: PyBypass's credential route is NOT
   implemented (no account, no stored credential — the documented anonymous
   key+POST chain is what is built).
5. **kmhd — NO unwrapper (aggregator pages, not file pages).** `kmhd.eu`
   redirects to `kmhd.me`; `/archives/<n>` returns 200 WordPress pages whose
   buttons point at OTHER sharers ("G-Drive [Tot]" → `gdtot.re/<slug>`,
   "G-Drive [GD]" → `gdfiles.org/file/<id>`, "G-Drive [PD]" →
   `new.piratedrive.com/file/<id>`, "G-Drive [Klop]" →
   `drive.klop.me/file/<id>`). A kmhd parser alone plays nothing until a
   downstream chain is proven. Owed: revisit when any downstream chain goes live.
6. **What is still owed:** live mint proof for the sharer chain (needs a
   placed `cf_clearance` for gdflix, or a reachable egress for gdtot /
   appdrive); kmhd parser (blocked on the above); `gdtot_crypt` fallback
   only if the anonymous chain stays dead AND the owner supplies a cookie
   (not requested — no credential exists).

## Research (sources saved here, not just in the report)

- bipinkrish/Link-Bypasser-Bot `ddl.py` — `sharer_scraper` (GET page →
  `"key", "…"` + `#drc` button → POST multipart `action=direct`,
  `key`, `action_token=""`, header `x-token: <hostname>` → JSON `url` →
  drive link, else `.btn` anchor) and `gdtot` (`gdbot.xyz/file/<id>` token
  link → `("…")` path → sharer; fallback `{host}/ddl/<id>` →
  `myDl('…')`).
- sanjit-sinha/PyBypass (unmaintained, 2022) — the "gdrivesharer" family:
  appdrive + lookalikes driveapp/drivehub/gdflix.pro/drivesharer/drivebit/
  drivelinks/driveace/drivepro/gdflix.top share one app; gdtot needs a
  `crypt` cookie (older route — the anonymous ddl.py chain supersedes it);
  appdrive needs login email/password (NOT implemented — no credential).
- vega-app `gdflixExtractor.ts` (DeepWiki) — GDFlix pages: `onload`
  `location.replace` wrappers, per-button servers, ResumeBot token regex.
- phisher98/cloudstream `StreamPlayUtils.extractGdflix` — same family.
- SilentDemonSD/FZBypassBot README (Sep 2026) — `appdrive.*` ✅,
  `*.gdflix.*` ❌, `new*.gdtot.zip` ❌, `GDTOT_CRYPT` optional ("works
  with & without").
- SitePoint forum — Drive direct links come from the web-client
  download/streaming flow (`uc?export=download` → virus-scan confirm →
  `drive.usercontent.google.com` → `video-downloads.googleusercontent.com`);
  no public API returns them.
- sarperavci/CloudflareBypassForScraping issue #29 — gdtot behind
  Cloudflare Turnstile (2024 report, browser-only bypass).

## Audit (live DB counts, read-only; probes from the Oracle VM)

| service | rows | stored shape | VM egress verdict |
|---|---|---|---|
| gdflix | 122,070 | `gd.kmhd.me/file/<id>` | 403 CF challenge (both hosts) |
| gdtot | 78,577 | `new.gdtot.eu/file/<digits>` | 302 → ww16, then TLS reset (curl 35) |
| gd-mirror | 59,394 | `streams.iqsmartgames.com/embed/…` | iqsmart chain, live-proven (item 1) |
| appdrive | 35,863 | `appdrive.in/file/<hex>` | TCP connect timeout (443 + 80) |
| kmhd | 30,253 | `kmhd.eu/archives/<n>` | 200 WordPress aggregator (item 5) |

`gd.kmhd.me` and kmhd are the same brand, NOT the same backend: the first
is gdflix's file host, the second is the KatMovieHD WordPress catalog.
`host_clearance` table does not exist in the live DB yet — no clearance is
stored for any family host (H4 R8 landed the store, nothing placed).

## Build

- `Fast_Fetch/importers/host_resolvers.py` — new section "gdrive sharers"
  (after `unwrap_filepress`, filepress precedent: proven chain, gated so a
  dead/blocked origin is never served): `unwrap_gdrive_sharer`
  (cookie-jar default fetch — the POST is session-checked; the chain is
  GET page → key + `#drc` → multipart POST → JSON `url` → `unwrap_gdrive`
  for the Range/container-magic verify, or the `.btn` intermediate hop),
  `unwrap_gdtot` (`/ddl/` → `myDl`, else gdbot token → sharer; default
  fetch is `http_request` for its transport retries, sharer POST switches
  to a jar when the caller did not inject a fetch), pure parsers
  (`sharer_key_from_page`, `sharer_btn_link`, `gdtot_mydl_link`,
  `gdbot_token_url/path`, `_sharer_multipart`), predicates
  (`is_gdrive_sharer_url`, `is_gdtot_url`), `SERVICE_APPDRIVE/GDFLIX/GDTOT`.
  Wired into `RESOLVER_BY_SERVICE` (appdrive, gdflix, gdtot, gd-mirror,
  gdmirror → `unwrap_iqsmart`) and `SERVICE_RESOLVERS` (sharer + gdtot
  tuples; `is_iqsmart_embed` already routed gd-mirror by shape). kmhd has
  no entry anywhere, deliberately.
- `Fast_Fetch/catalog_v2.py` — `gd-mirror` + `gdmirror` added to
  `_SERVER_RESOLVABLE_SERVICES` (iframe rows now `resolve_required`, and
  `viewer.link_view` flips them from `external` to `resolve`). The sharer
  family stays OUT: its rows are url-typed cloud pages, already
  `resolve_required` without the set.
- `Fast_Fetch/importers/test_gdrive_sharer.py` — 34 offline tests
  (recognition, parsers, multipart shape, full sharer chain active,
  POST shape incl. `x-token`, CF/transport/page/POST failure shapes,
  intermediate-page route, verify=False mints-but-never-probes, Drive
  quota → unverified, no stored presigned-URL reuse, gdtot both routes +
  fall-through, wiring incl. `classify_link` gd-mirror →
  `("embed", "resolve_required")` vs unlisted embed → `webview`).
  Socket guard: a live socket raises.
- `.github/workflows/backend-ci.yml` — gate step `test_gdrive_sharer.py`
  (126 suites); `Fast_Fetch/test_ci_manifest.py` — `MANDATORY_RUN`.
- `Fast_Fetch/CONTINUITY.md` — ROTATED on first edit (H4 R9 note: the
  file was 8 B under its 12,000 B ceiling): the "🕒 Queued quiet-window
  actions" table moved verbatim to `20261120-continuity-queued-actions.md`,
  item 7 repointed, count 122 → 124 (this file + the rotation archive),
  one-line session entry appended.

## Tests (local, this worktree)

- New module `test_gdrive_sharer.py`: 34/34 OK.
- `test_host_resolvers.py`: 303/303 OK. `test_catalog_v2.py`: 109/109 OK.
  `test_tiers.py`: 51/51 OK.
- `test_ci_manifest.py`: green after commit + rebase (pre-rebase it fails
  on the H4 R9 `test_resolver_probes.py` step, which lands with the rebase,
  and on the new module being untracked, which the commit fixes).
- Backend CI is the gate: run id recorded in the coordinator report.

## Files changed

- `Fast_Fetch/importers/host_resolvers.py` (+~420 lines)
- `Fast_Fetch/importers/test_gdrive_sharer.py` (new, 34 tests)
- `Fast_Fetch/catalog_v2.py` (+8 lines)
- `.github/workflows/backend-ci.yml` (+12 lines, gate step)
- `Fast_Fetch/test_ci_manifest.py` (+1 line, MANDATORY_RUN)
- `Fast_Fetch/CONTINUITY.md` (rotation + entry, size kept ≤ 12,000 B)
- `Fast_Fetch/checkpoints/20261120-phasec-gdfamily.md` (this file)
- `Fast_Fetch/checkpoints/20261120-continuity-queued-actions.md` (rotation archive)
- `Fast_Fetch/checkpoints/README.md` (newest-first entry)
