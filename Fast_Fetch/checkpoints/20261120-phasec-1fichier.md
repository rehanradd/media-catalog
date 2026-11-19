# Phase C — 1fichier resolver (2026-11-20)

Branch `muse/phasec-1fichier-20261120`, base main `44631e10` (H4 R9 merged;
re-based twice during the lane: `78c66197` -> `8f7948d2` (H4 R8) ->
`44631e10` (H4 R9), no commits carried over either rebase).

## Research (before building, wide phrasings + typo variants)

1. 1fichier free tier (premiumdownloader.io, bestdebrid.com, world-debrid.com,
   viposearch.com): visitors get ads, captcha, a wait timer between files,
   throttled speed (~100-150 KB/s), one download at a time, no resume, and a
   daily data cap. Premium (~EUR 3/mo, 15/yr) removes all of it. Debrid
   services sell the same bypass per file (~$0.10) through shared Premium
   accounts. Tokens are short (~5 min) — regenerate, never recycle.
2. API is Premium-only (rclone.org/fichier, cloudslinker.com): "a Premium
   subscription is required to use the API"; "free accounts cannot generate
   API keys at all". Key from https://1fichier.com/console/params.pl.
   rclone source (current master, `backend/fichier/`): base
   `https://api.1fichier.com/v1`, `Authorization: Bearer <api_key>`
   (`fichier.go:220`), `POST /download/get_token.cgi` with
   `{"url", "single": 1, "pass"?, "cdn"?}` -> `{"url", "Status", "Message"}`
   (`api.go:115-139`, `structs.go:19-24,140-145`); `validToken` is
   `Status == "OK"`. meaSERIES Kodi client (github.com/001101/meaSERIES,
   `kodi/index.php`): same endpoint, body `{"url", "pretty": 1}`, answer
   `$obj['url']`. Ruby gem `un_fichier_api` documents `File.download`
   params including `restrict_ip`, `single`, `cdn`, `pass`.
3. Free-flow workarounds (1fichier-dl fork, thecapt1917/1fichier-bypass,
   self-debrid/JDownloader): all route around the wait with proxies, a
   third-party API, or JDownloader queueing the 5-10 min cooldown. None is a
   server-side chain that fits this codebase (no proxies, no third-party
   key, no desktop app), and JDownloader itself just waits the timer out.
   1fichier "actively restricts traffic from commercial data center IP
   blocks" (viposearch.com).
4. Cold storage: a file untouched 60 days moves to cold storage; guest files
   deleted after 15 days, registered 30 days after upload / 90 days after
   last access. A 404 often means 1fichier already removed the file.

No library fits: stdlib-only code, and the chain is one POST + one Range
probe. The resolver reuses `http_request` / `_finish_media` / `probe_range`.

## Audit — live probes (read-only DB queries, VM egress)

Live catalog (mode=ro, `merged_catalog_20261025.db`, 2026-11-20 02:4x PKT):
**153,576** `download_sources` rows match `1fichier.com` (task brief said
152,317 — re-measured higher). Sample actives:
`?maobk0tho3kglmwqi6ar`, `?s7lmp7q669nl2wjjalfd`, `?253ae2jy44ccqredoeka`,
`?wdmlm3q3rmu3rhhnn9ry`, `?3wyzqcmhaiqicd342xpx`.

P1. Oracle VM (92.4.95.252) GET `https://1fichier.com/?maobk...` ->
**403, 6,033 B**: "For security reasons, following many request errors,
your IP address (92.4.95.252) is temporarily locked. ... error #350".
Second URL in the same minute -> connection reset by peer.
P2. Same URL from the hatch egress -> **200, 6,460 B**, but the body is the
"Accès restreint – professional infrastructure detected" page ("This IP
address has been identified as belonging to a server, proxy, VPN, relay
network... Premium plans are reserved for private, non-shared residential
Internet connections... For access via servers, proxies... please use CDN
Credits"): **no download form, no file, nothing to POST**.
P3. `GET https://api.1fichier.com/v1/file/info.cgi` from the Oracle VM ->
**400 `{"status":"KO","message":"Content-Type not JSON #40"}`** — the API
route answers from the VM egress. Same GET from hatch -> 400
`{"message":"No JSON #47","status":"KO"}`.
P4. After ~6 requests the Oracle IP's connections to `api.1fichier.com`
were reset on every verb (GET and POST, both endpoints), still resetting
after a 90 s wait. The block escalates with probe count — probe rarely,
once. (One hatch POST to `get_token.cgi` hung past the 25 s timeout;
not retried.)
P5. `1fichier.com` is blocked by policy for `browser.open` (not retried);
the official API doc page could not be fetched that way. The auth scheme
is instead pinned by rclone source (Bearer, above), not by the doc page.

Conclusion: the free page flow cannot even be observed from server egress,
so no free-flow code is built — any POST shape would be guesswork. The
only chain is the Premium API, and the key does not exist.

## Build

`Fast_Fetch/importers/host_resolvers.py`:
- `SERVICE_1FICHIER = "1fichier"`, `is_1fichier_url` / `_fichier_extract_id`
  (id `/?id` or `/id`, min 8 chars so `tarifs.html` / `console/params.pl`
  never match), `unwrap_1fichier(url, fetch=None, verify=True, api_key=None)`.
- One POST to `FICHIER_GET_TOKEN` (`https://api.1fichier.com/v1/download/get_token.cgi`),
  `Authorization: Bearer <key>`, JSON `{"url": canonical, "single": 1,
  "restrict_ip": 0}`. `restrict_ip: 0` is the press-path requirement (the
  server mints, the phone plays — a server-IP-bound token would 403 on the
  handset); it is a real API param (un_fichier_api `File.download`) but
  **TO CONFIRM at live proof**, since rclone does not send it.
- Key source: explicit arg, else `RADD_1FICHIER_API_KEY` env var — never a
  literal, never logged. No key -> `unknown` + `reason: no_api_key`, zero
  requests made, never `dead`.
- KO mapping (vocabulary inferred from community reports — no KO body was
  ever observed, unauthenticated POSTs reset): file-gone wording -> `dead`,
  key/auth/limit wording -> `blocked`, anything else -> `unknown`. HTTP
  401/403/429 -> `blocked`; transport failure / raise -> `unknown`.
- Minted link: `is_temporary=True`, `expires_in=300` (tokens ~5 min),
  Range-probed via `_finish_media` when `verify=True`; only 206 video/*
  is `active`. One API POST + one probe per resolve; HTTP answers are
  never retried.
- Wired into `RESOLVER_BY_SERVICE` and `SERVICE_RESOLVERS` (before the
  mxdrop catch-all). Stored rows carry `service: "1fichier"`, which now
  resolves.

`Fast_Fetch/importers/test_1fichier.py` (new, 36 tests) + gate step (126
suites) + `test_ci_manifest.py` `MANDATORY_RUN`. Covers: id extraction on
the 5 real stored URLs, site-page rejection, no-key unknown + zero
requests, env-vs-explicit key precedence, the exact POST shape (Bearer,
`single: 1`, `restrict_ip: 0`, canonical URL), verified mint -> active
206 video/mp4, unverified mint stops before the byte endpoint, refused
probe never active, KO/HTTP/transport error mapping, the no-stored-URL
rule (two resolves follow their own mints; final URL never the page URL),
and both wiring maps. Local: new module 36/36, `test_host_resolvers`
303/303, `test_ci_manifest` 10/10 (after commit — it fails pre-commit by
design: the new file must be tracked in HEAD), `test_docs_invariants`
pending at commit time. NOT merged — parent merges; no live deploy.

## Proof — NONE (blocked, not skipped)

No real play/download proof exists: the resolver cannot mint without a
Premium API key, and the free page is blocked from both egresses (P1/P2
above). The verdict a press gets today is `unknown / no_api_key` — the
host stays unproven, no row is marked dead, nothing is served.

## Owed (owner-only: paid Premium + user login to create the key)

1. Owner creates a 1fichier Premium account and an API key at
   `1fichier.com/console/params.pl`, and places it server-side as
   `RADD_1FICHIER_API_KEY` (env / key file — never in code, chat, or
   memory). Then: live mint + Range probe (206 + video mime) from the VM,
   and a handset play of the minted link to confirm `restrict_ip: 0`
   works (if the API rejects the param or the phone 403s, drop it and
   re-prove; if the minted link is server-IP-bound by default, the host
   cannot serve phones and the resolver must say so).
2. Re-check the KO vocabulary against a real KO body once a key exists;
   the dead/blocked regexes are inferred, not observed.
3. Rotation note: this lane rotated `CONTINUITY.md` (11,992 B, 8 B under
   the ceiling) — three "Rotated session history" pointer lines left the
   index (2026-11-16 playback telemetry beacon, 2026-11-19 B2
   episode_guide, 2026-11-18 R1+R2+R4 host ranking). Nothing was deleted:
   each file stays in `Fast_Fetch/checkpoints/` and keeps a full entry in
   `Fast_Fetch/checkpoints/README.md` (telemetry already had a rotation
   record there). NOT rotated out: the 2026-11-18 B4 and H2 pointer lines
   stay in `CONTINUITY.md` because they have no other home — the B4 file
   exists but is unindexed in the README, and the H2 backfills line is the
   only record of that session (no checkpoint file exists for it).
   Archive count updated 123 -> 124 (121 dated + 3 undated).
