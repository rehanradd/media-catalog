# Phase C — multiup (2026-11-20)

Builder lane: `muse/phase-c-multiup-20261120`, base main `8f7948d2` (H4 R8).
Do NOT merge — coordinator merges. No live-tree / service / live-DB writes.

## Facts (live DB, read-only, 02:36 PKT 20 Nov)
- `multiup`: 67,454 `download_sources` rows — active 32,484 / unverified 28,831 / dead 6,138.
- All 67,454 rows created 2026-10-24 → 2026-11-16 (i.e. all within the last 30 days — fresh imports, not ancient rows).
- Stored URL forms: `https://multiup.io/download/<32-hex>[/<filename>]` and `https://multiup.org/download/<32-hex>[/]`.
- `catalog_v2.py`: `multiup` is in `_DOWNLOAD_ONLY_SERVICES` (download-only, not an embed service).
- `import_9xmovies.py` sets `service = "multiup"`. No working resolver existed.

## 1. RESEARCH (before any code)
- Web search, several phrasings + typo variants (`multiup.io download mirror links`,
  `multiup downloader open source github yt-dlp`, `multiup.org scraper github`,
  `"multiup" jdownloader plugin source`, `multiup mirror page bypass captcha`):
  - NO yt-dlp extractor for multiup; NO dedicated open-source multiup resolver found.
  - JDownloader handles multiup.org links (forum reports: sometimes "all files offline").
  - One Reddit user wrote a program scraping the actual hoster download links from
    multiup.org links; flow described: open site, click Download → hoster-choice page → ads → actual link.
  - multiup.io mirror page (crawled 2026-11-19): `/en/mirror/<id>` lists hosts
    (USENET, Gofile.io, Megaup.net, Vikingfile.com, Mixdrop.ag, Chomikuj.pl,
    Dailyuploads.net, Fireload.com, 1fichier.com) — but the crawler text strips hrefs.
  - Conclusion: no fitting open-source piece — custom unwrap required (lightest chain wins).

## 2. PROBES (Oracle VM egress, light, 5 sample links max — all 5 used)
Probe method: curl + stdlib-urllib probe scripts in `/tmp/mu-probe/` on the VM
(`mu_probe.py` = GET+POST+mirror extract+gofile/mixdrop unwrap; `mu_mirror_probe.py` = one mirror unwrap).
Full raw pages saved at `/tmp/mu1.html` (download page, 57,563 B) and `/tmp/mu_mirror.html` (mirror list, 63,704 B).

### The chain (PROVEN to run end-to-end from Oracle egress, 3x)
1. `GET https://multiup.io/download/<32-hex>` → 200, HTML carries
   `<input type="hidden" name="_csrf_token" value="...">` (120–132 chars) + session cookie.
   `multiup.org/download/<id>` 301s to the `.io` URL.
2. `POST https://multiup.io/en/mirror/<id>` with `_csrf_token`, session cookie, Referer
   → 200, mirror list HTML with PLAIN anchors:
   `<a href="<mirror>" target="_blank" nameHost="<host>" link="<mirror>" validity="<valid|invalid|unknown>">Download</a>`
3. Follow the best mirror with an existing resolver: gofile (`hr.unwrap_gofile`,
   verified live 2026-11-08) or mixdrop (`hr.unwrap_mxdrop`, `/f/<id>` → `/e/<id>` rewrite).

### Transport quirk (measured)
- `multiup.io` resolves to `57.129.5.167` (OVH, direct — NOT Cloudflare);
  `multiup.org` is Cloudflare-fronted.
- The `.io` origin intermittently RSTs the TLS handshake mid-`SSL_connect`
  (`curl: (35) Recv failure: Connection reset by peer`, urllib `Errno 104`),
  roughly half of all attempts from the Oracle egress; successes interleave with
  failures on the same URL minutes apart. NOT a deterministic block.
  Mitigation in the resolver: retry each request (3 attempts, short sleep),
  fail fast on HTTP 404 (dead file). A resolver that retries gets the page ~70–80%
  of the time per call; the sweep re-tries failed rows later anyway.

### Sample results (5/5 budget used)
| # | id (trunc) | title | result |
|---|-----------|-------|--------|
| 1 | 237586a5… | Overlord The Sacred Kingdom 2024 480p | page OK; mirrors: usenet(valid), vikingfile, **gofile DEAD** ("content not found (folder deleted)"), dailyuploads, rapidgator |
| 2 | deb05b4f… | Dragon (2025) Hindi dub | multiup **404** — file deleted from multiup |
| 3 | 624dfb8c… | The Grandmother (2021) 720p | page OK; mirrors: gofile **DEAD**, vikingfile, **mixdrop DEAD** ("video cannot be found"), rapidgator, dailyuploads, 1fichier |
| 4 | 019bf212… | (active, multiup.org) | multiup **404** — file deleted from multiup |
| 5 | 766d7c94… | Ghostbusters (1984) 720p | page OK; mirrors: usenet(valid), 1fichier only — no gofile/mixdrop at all |

- multiup's own `validity` flag agreed with live checks in every case
  (`invalid` == dead; usenet `valid` on all samples but unresolvable — it is a
  premium `/download-fast/` Usenet upsell, not a free direct file).
- Other mirrors seen (vikingfile, dailyuploads, rapidgator, 1fichier, megaup, chomikuj,
  fireload) have NO resolver in the repo. 1fichier is the biggest uncracked host
  overall (149,916 rows) — a separate Phase C item, out of scope here.

## 3. VERDICT SO FAR — NOT CRACKED (no live press proof)
- The extraction chain is proven to RUN from the Oracle egress, but in 5 random
  samples NOT ONE resolvable mirror (gofile/mixdrop) was alive: 2/5 multiup files
  are deleted outright, and every gofile/mixdrop mirror probed was deleted at the
  host too. So no real press (302 to a working file) exists yet.
- What is being built anyway (task step 3: a working chain exists — the failure is
  dead mirrors, not the chain): `unwrap_multiup` in `fast_fetch.py` + dispatch
  branch in `resolve_live_source` + offline tests with recorded fixtures + CI
  registration. Once live, the link sweep will exercise it against all 67,454 rows
  and any row with a live gofile/mixdrop mirror will resolve; the press proof is
  then owed from a live row.
- UNBLOCKER for a "cracked" verdict: a multiup row whose mirror list contains a
  LIVE gofile or mixdrop mirror (0/5 random samples had one). Next step for the
  coordinator: authorize a targeted probe batch (fresh rows only, or rows whose
  mirror list the sweep has already fetched), or crack 1fichier/vikingfile to
  widen the mirror set. The 5-sample light-probe budget for this lane is spent.

## 4. BUILD (in progress)
- `Fast_Fetch/fast_fetch.py`: `multiup_extract_id`, `multiup_parse_mirrors`,
  `_multiup_session` (ONE cookie-jar opener for the GET+POST pair, retry-on-reset,
  fail-fast 404),
  `unwrap_multiup(url, timeout=12, _http=None, _resolvers=None)` →
  `(direct_url, resource_id, is_temp, verdict)`; tries gofile then mixdrop
  (validity-ranked within a host), `verify=True`; only an `active` verdict wins.
  Stdlib only; no `requests`.
- Dispatch in `resolve_live_source` (after the gofile branch): `service == "multiup"`
  or `"multiup" in source_u/cached_u`; sets `fresh_url`/`fresh_temp` and
  `_gate_from_verdict = True` + `embed_verdict` (gofile/mxdrop direct links fail a
  bare re-probe — 302 without auth / 403 — so the unwrapper's Range probe IS the
  gate evidence, same as the gofile/mixdrop branches).
- Tests: `Fast_Fetch/test_multiup_unwrap.py` (offline, canned pages via the `_http`
  seam + stub resolvers; no network). Registered in `backend-ci.yml` gate step and
  `test_ci_manifest.py` MANDATORY_RUN.
- Results (local, 20 Nov 2026): `test_multiup_unwrap.py` 18/18 OK;
  `test_ci_manifest.py` 10/10 OK (after commit — it requires the file tracked);
  `importers/test_host_resolvers.py` 303/303 OK; `pre-push-checks.py` passed.
- Commit: `36d8e6d8` on `muse/phase-c-multiup-20261120` (base `8f7948d2`).
- CI round 1: Backend CI run 44623242699 → FAILURE, single failing step
  `test_ci_manifest.py::test_manifest_is_never_a_subset_of_trunks`
  (`['Fast_Fetch/test_resolver_probes.py'] != []`) — NOT my code: main had
  moved (H4 R9, `44631e10`) and my branch's manifest was a subset of trunk's.
  Fix: rebased onto `44631e10` (manifest + workflow steps auto-merged);
  CONTINUITY.md conflict resolved by taking trunk's version verbatim (file is
  8 bytes under the 12,000 B ceiling; H4 R9's note orders rotation before any
  further addition — this checkpoint file is the session log).
- Commit after rebase: `6fe4e9cc` on `muse/phase-c-multiup-20261120`
  (base `44631e10`). Backend CI run id (round 2): PENDING push (see report).
- CI round 2: Backend CI run 44624503866 → FAILURE, single failing step
  `test_docs_invariants.py::test_the_index_counts_are_the_directory_listings`
  (`123 != 124`: CONTINUITY.md claimed 123 archive files, checkpoints holds
  124 — my new checkpoint file). Manifest passed; the multiup step was skipped
  (fail-fast). Fix: count line 123→124 (121 dated + 3 undated, verified by
  listing) + README index entry for this checkpoint; fixup commit on top.
  Current tip: see report.
