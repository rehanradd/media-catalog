# 2026-11-20 — Phase C lane A: filepress — wire the unwrapper into the dispatcher

Branch: `muse/phasec-filepress-20261120` (base 8f7948d2, rebased onto 44631e10
before push). Host: filepress — 171,476 stored `download_sources` rows
(active 54,819 / expired 60,786 / unverified 55,871, live DB, read-only).
No merge, no push to main — lane report only.

## 1. Research first (online, before building)

- Z-Mirror `filepress()` (Dawn-India/Z-Mirror, PR #35, Dec 2022, repo archived
  Feb 2025): Playwright only — click "Direct Download", sleep 10 s, click
  "Download Now", take the Google Drive link the navigation lands on. Heavier
  than the in-repo chain (browser + 10 s wait) and from 2022, so it is not
  the lighter chain; the in-repo 2-POST API chain stays canonical.
- Domains (HypeStat/BigDomainData/Scamadviser, Nov 2026): the family rotates
  TLDs constantly — filepress.lat (created 2026-06-06), .baby (redirects to
  new2.filepress.lat), .cloud (→ new2/new4.filepress.cloud), .today, .live,
  .wiki, .top, .cfd, .click, .fun, .lol, .online, .rest, .sbs, .icu, .ink,
  .shop; subdomains new1–new8; filebee.xyz/filebee.top. Stored rows span all
  of them (biggest single bucket: new4.filepress.baby, 54,398 rows). No
  public source documents a lighter chain than the 2-POST API.
- Sources saved here; nothing else fit, so the build is custom (the existing
  chain), not a new invention.

## 2. Audit — the two `unwrap_filepress` implementations

- `Fast_Fetch/importers/host_resolvers.py::unwrap_filepress` (~line 2900):
  the REAL unwrapper — verdict dict, 2-POST FileBee API chain proven live
  2026-11-13 (POST `/api/file/downlaod/` with `{"id", "method":
  "indexDownlaod", "captchaValue": null}` → 24-hex token; POST
  `/api/file/downlaod2/` → `[direct link]`; Range-probe gate, only a 206
  with real video bytes is "active"). Was absent from RESOLVER_BY_SERVICE
  and SERVICE_RESOLVERS — that was the gap.
- `Fast_Fetch/fast_fetch.py::unwrap_filepress` (line 3213): a STUB, not a
  resolver — returns the page URL unchanged with the id extracted. Used at
  import time by vegamovies/xprimehub/rogmovies/7starhd importers to store
  `final_url` = page URL. It cannot be merged into the live resolver (tuple
  vs verdict dict, scrape-time vs play-time). Reconciled instead: it now
  delegates id extraction to `hr._filepress_extract_id` (the strict 24-hex
  rule, single source of truth) via `_embed_resolvers()`, keeping its
  `(url, res_id, 0)` contract and the loose last-segment fallback for odd
  stored URLs (`abcdef` still returns `abcdef`). Verified: real id, bare id,
  and loose fallback all return the old shapes.
- The PLAY path already called `hr.unwrap_filepress` (fast_fetch
  `resolve_live_source`, the `SERVICE_FILEPRESS` elif branch) — so the
  missing wiring was only the central dispatcher (`resolve_url` /
  `resolve_service` / CLI), which had no production callers yet but is the
  authority the serve/heal paths route through.

## 3. Live probes from the Oracle VM (read-only DB queries only)

Sample ids are 24-hex (e.g. `6ad44fdf69b577b115b03617`, status expired;
active-status ids `6a8adba687ebcb22d0263089`, `6a83e05087ebcb22d008d9ce`,
`6a69de7e35670cd1489da618`, `6a4c9297da46d78cca676554`).

Direct from Oracle egress (92.4.95.252) — ALL blocked:
- GET page + POST downlaod on new1/new2/new3/new4.filepress.lat: 403
  Cloudflare "Just a moment..." (urllib AND curl — IP reputation, not a
  TLS fingerprint).
- POST downlaod on new3/new4.filepress.baby: 301 → 301 → 403; the .baby
  origin (193.124.64.3) and filepress.today return empty/reset from this
  egress. new2.filepress.lat POST now answers 403 directly (no 301).
- Hatch-VM egress: IncompleteRead via the egress proxy — inconclusive,
  not a signal.

VPN-tunnel test (user-authorized, free only, no signup, no card):
VPN Gate public API (`vpngate.net/api/iphone/`, 97 usable OpenVPN configs),
`openvpn --route-nopull` + one /32 host route per target IP so SSH and the
live service never touch the tunnel; tunnel torn down after each probe.
- public-vpn-194 (Japan): tunnel up; GET new3 → **301 to
  `new4.filepress.lat`** (page AND API — the live mirror rotated new3 →
  new4 in Nov 2026); POST downlaod via tunnel → **HTTP 200
  application/json** (`x-powered-by: Express`, `cf-cache-status:
  DYNAMIC`). IP block bypassed — the chain reaches real JSON.
- public-vpn-97 (Japan): same 301 → 200 JSON; 5 ids tried on new4.lat
  (1 expired + 4 active-status) → ALL `{"status":false,"statusCode":400,
  "data":"Method Not Found","err":"METHOD_HIDDEN"}`. Per the 13 Nov
  vocabulary the method IS accepted (a bad method is METHOD_NOT_FOUND);
  METHOD_HIDDEN means the file is not in THAT mirror's index. The API
  index is per mirror — .baby files do not mint from .lat.
- public-vpn-51 (Japan): tunnel up, but the .baby origin (193.124.64.3)
  returns empty/000 through it — that egress cannot reach the .baby
  origin at all. No .baby mint tested.
- Reconnect flake: vpn-97 AUTH_FAILED on two reconnects after working
  twice; vpn-261/199 never brought tun0 up in 30 s. VPN Gate servers are
  session-limited and flaky under reconnect — expected, recorded, not
  chased further.
- Download origin `aws.flapdoodle.cyou`: HTTP 522 on every /download/*
  path for ~50 min on 2026-11-13, and **no longer resolves at all** from
  the VM's DNS on 2026-11-20. No minted link has yet played from any
  egress.

Conclusion of the probes: the chain is proven to the JSON API through an
alternate egress, and the per-mirror index explains every METHOD_HIDDEN.
A live token mint on the current mirrors is STILL UNPROVEN, and no
play/download proof exists yet (see §6 owed).

## 4. Build (3 files)

`Fast_Fetch/importers/host_resolvers.py`:
- `is_filepress_page(url)`: host has a `filepress`/`filebee` label (any TLD,
  any newN subdomain) AND a 24-hex id. Id-less index URLs
  (filepress.click/file, filepress.rest/file/) and wrong-host URLs fall
  through to the mxdrop catch-all.
- Wired: `SERVICE_FILEPRESS: unwrap_filepress` in RESOLVER_BY_SERVICE and
  `(SERVICE_FILEPRESS, is_filepress_page, unwrap_filepress)` in
  SERVICE_RESOLVERS before the catch-all.
- `FILEPRESS_API_BASE`: new3 → **new4** (fallback only, for a bare id).
- `_filepress_api_call` now takes `base` and follows 301/302/307/308 by
  re-POSTing the same body to the Location, updating the Referer to the
  redirect target's origin (max 3 hops, then `redirect_loop`; a redirect
  with no usable Location is `http:<status>`). `unwrap_filepress` posts to
  the PAGE URL's own origin — the index is per mirror — so a .baby file
  asks its own mirror and follows that mirror's rotation.
- `unwrap_filepress` docstring: minted link is fetched fresh on every
  resolve and never stored; a stored presigned URL is never served.
- Measured-limits comment rewritten with the 20 Nov findings (§3).

`Fast_Fetch/fast_fetch.py`: `unwrap_filepress` delegates id extraction to
`hr._filepress_extract_id` (see §2); play-branch comment updated to the
20 Nov measurements (new4, 301, Oracle 403, origin unresolvable).

No workflow/manifest change was needed: `test_host_resolvers.py` is
already a backend-ci step (line 715) and in MANDATORY_RUN. No new module.

## 5. Tests (local, this VM)

- `Fast_Fetch/importers/test_host_resolvers.py`: **317 tests, OK**
  (303 before + 14 new: TestFilepressRecognition ×3, TestUnwrapFilepress
  ×10, dispatcher coverage + routing). New: mint→probe active with
  encoded filename, new3→new4 redirect with Referer update on BOTH posts,
  relative-Location redirect, redirect loop → unknown, METHOD_HIDDEN →
  unknown, blocked 403 / dead 404, empty token / empty link list, no-id
  never calls the API, bare-id fallback base, fresh-mint-every-resolve
  (two `resolve_url` calls mint two different links; the second serves
  the second — no stored presigned URL reuse).
- `Fast_Fetch/test_database.py`: **239 tests, OK** (skipped=1) — includes
  the existing filepress chain tests; the Referer assertion still passes
  because the origin-derived base keeps new3 for a new3 page URL.
- `Fast_Fetch/test_7starhd_import.py`: 6 tests, 1 failure —
  `test_mobilejsr_unwrapping_mock` (1 source, needs ≥3). PRE-EXISTING:
  fails identically on the clean base (verified with `git stash`).
  Unrelated to this lane (mobilejsr, not filepress); reported, not fixed.
- `Fast_Fetch/test_ci_manifest.py`: 1 failure on this stale base —
  `test_manifest_is_never_a_subset_of_trunks` (missing
  `test_resolver_probes.py`, the H4 R9 suite on origin/main). Expected
  to clear after the rebase onto 44631e10; re-checked there.
- Two test bugs of mine found by the run and fixed, not code bugs:
  "downlaod/" (trailing slash) is NOT a substring of "downlaod2/", which
  broke the redirect fixture (missing new3 downlaod2 → 301 entry) and the
  fresh-mint count assertion.

## 6. What is still owed (live verify at gate time)

- **Live mint + play proof (the big one):** no filepress link has played
  yet. Next attempt needs an egress that (a) passes the Cloudflare
  challenge on the API and (b) reaches the file's OWN mirror origin:
  retry the mint with active-status ids against their own mirror's API
  (new3/new4.filepress.baby) through a working tunnel, then Range-probe
  the minted link (206 + video mime). If the minted host is still
  aws.flapdoodle.cyou, it must resolve first — it does not today.
- Production egress is still 403-challenged on every mirror. Serving
  from production needs one of: the Oracle IP's reputation recovering,
  an H4 R8 clearance earned (Chromium IS on the VM) and replayed, or a
  VPN/proxy egress adopted as infrastructure — an owner decision, not
  this lane's. The tunnel used here was a probe route only.
- The gate-time live verify for the wiring itself: press a filepress
  title whose row resolves; a METHOD_HIDDEN answer must read as
  "unknown" (failover to siblings), never "dead" and never served.
