# Harness fix after field-test run 1 (0/50 minted) — `muse/harness-fix-run1-20261116`

Run 1: owner's TECNO KF6p, Android 11, home Wi-Fi, 22:00–22:13 PKT 16 Nov.
Log: `results/field-test-run1-20261116.json` (50 attempts, 0 successes, 4,947,023
bytes, 785 s, battery 0 pp). Verdict: the CONTROL set (filemoon + rpmplay,
hosts that should mint) went 0/10 — the harness is broken, not the hosts.
G-1 FAILS, no banking, no G-10. This branch fixes the harness; it does not
claim to crack filepress/1fichier (already known uncracked from the VM side).

## Fault 1 — filepress 20/20 `downlaod: http:301` (http_chain)

The `OkHttpResolveTransport` never follows redirects (`followRedirects(false)`),
and `HttpChainEngine.apiCall` treated any non-200 as terminal — so the first
POST died on the redirect itself. The FileBee mirrors rotate (server note:
new2 301s to new3), so the 301 is the mirror moving, not a failure.
Reproduced from the Oracle VM only as far as the egress allows: the VM gets a
403 Cloudflare challenge on the API (datacenter IP), the phone (residential
IP) got the 301 — the redirect's `Location` could not be observed from here.
Fix (Filepress.kt): `apiCall` now follows 301/302/303/307/308 by re-POSTing
the same JSON body to the `Location` (resolved against the request URL, so
relative Locations work), up to 5 hops, re-basing the `Referer` to the new
origin on each hop (a mismatched Referer turns the answer into
INVALID_REQUEST). A GET would be meaningless to this API, so 301/302/303 are
followed with the same POST, like 307/308. No Location, or past the cap, fails
as before (`http:301` / `redirect_loop`).
Also fixed the `downlaod` typo in the harness's step labels (`download:` /
`download2:`). The API's own spelling (`indexDownlaod`, `/api/file/downlaod/`)
goes on the wire unchanged; the labels deliberately diverge from the server's
`host_resolvers.py` vocabulary, which still writes `downlaod:`.

## Fault 2 — WebView minted NOTHING in 30 attempts (detector blind)

1fichier 20/20 `wall_cap` (~30 s each), filemoon 3/3 `wall_cap` despite ~1.5 MB
downloaded per attempt, rpmplay 2/2 `wall_cap`. The detector only recognised
URLs ending in a media extension (`.mp4`/`.mkv`/`.webm`/`.m3u8`/`.mpd`) — via
`shouldInterceptRequest` and `<video>`/anchor `src`. These players serve
extensionless playlist/stream URLs while the bytes arrive as `.ts` segments,
so a page that was already streaming never produced a candidate, and the loop
waited out the whole 30 s cap blind.
Fix (WebViewEngine.kt): the engine now records EVERY subresource URL the page
requests, reads the player's `currentSrc` (not just the `src` attribute —
hls.js/MediaSource players leave `src` empty and put a blob: URL in
`currentSrc`) and the `performance` resource list (the network truth:
every XHR/fetch/media request, including extensionless playlist/segment
URLs), ranks the candidates — DownloadListener URL first, then media
extensions, then extensionless URLs carrying a media marker (`/hls/`,
`/stream`, `/video`, `m3u8`, `mp4`, `download`, …), then other non-static
URLs — and Range-probes them INSIDE the 30 s wall cap (up to 4 per poll
round, 24 per attempt); the first ACTIVE probe mints at once. `.ts`/`.m4s`
segments are never candidates (probing one would "mint" a 10-second slice);
the bridge URL, blocklisted hosts, static assets and non-http(s) schemes are
never candidates. The ranking/extraction/navigation decisions are pure
functions (`rankProbeCandidates`, `parseExtracted`, `navigationAllowed`) so
they are unit-testable without Robolectric.
Regression test (`WebViewDetectorTest`): ranking order, segment/static/
blocklist exclusion, navigation rules, extraction parsing, and a local
fixture page — a JDK `HttpServer` on 127.0.0.1 serving `/page.html` (video
tag + `fetch` of the playlist), `/stream/video.mp4` (ftyp magic),
`/hls/list.m3u8` (`#EXTM3U`), `/hls/seg0.ts`, `/app.js` — ranked candidates
probe ACTIVE through the real `OkHttpResolveTransport` while the segment
never mints.

## Fault 3 — rpmplay 5/5 `other_host`

The old navigation guard allowed the assigned bridge URL and same-host
redirects only, and aborted the attempt when the tab navigated cross-host.
rpmplay bridges navigate the SAME tab across hosts as a normal part of the
flow. Fix: same-tab main-frame navigation is now followed across hosts (the
current host is tracked, not fenced); only non-http(s) schemes are refused,
so the WebView never tries to open another app. The `other_host` abort is
gone.

## Fault 4 (secondary) — 1fichier click never fired

The download-click JS matched only the English word "download" on buttons and
inputs. 1fichier is French: its button reads "Télécharger". The matcher now
also matches "télécharger"/"telecharger" and anchor elements. (1fichier is
still known uncracked; this only lets the flow reach its wait timer.)

## Emulator verification (owner order 22:19 PKT 16 Nov)

Test on an emulator ourselves BEFORE the owner runs anything on his phone.
Two enablers in this branch:
- `Run control only` button + `ResolveTestViewModel.runControlOnly()`: runs
  just the 10 control samples (filemoon + rpmplay), ~1–5 min instead of the
  full 50's ~13 min — short enough for a Firebase Robo run.
- Logcat lines: `RESOLVE_ATTEMPT linkId=… service=… success=… error=…` per
  attempt and `RESOLVE_SUMMARY total=… filepress=… 1fichier=… filemoon=…
  rpmplay=…` at the end of a run (tag `ResolveTest`). The export leaves the
  phone only through the share sheet, so logcat is how an emulator run
  reports its mint counts back.
- Robo script `App/docs/robo-resolve-test.json`: Settings → "Open resolve
  test" → "Run control only" → waits for "Mint again" (appears only when the
  run finishes) up to 6 min → `TERMINATE_CRAWL`. Submission is a browser
  task to the Firebase Test Lab console (project `radd-stream-test`, free,
  no card; `gcloud` is not on this VM) — the parent runs it; mint counts
  come from the matrix logcat. Control must mint > 0 there before the owner
  re-runs. If Robo cannot complete the run, Appetize is the fallback.

## Files

- `App/app/src/main/kotlin/com/raddstream/app/resolve/Filepress.kt` —
  redirect-following `apiCall` (+5-hop cap, Referer re-base), `download:` labels.
- `App/app/src/main/kotlin/com/raddstream/app/resolve/WebViewEngine.kt` —
  recorded-URL ranking + in-cap probing, cross-host navigation, `currentSrc`
  + performance extraction, French download click.
- `App/app/src/main/kotlin/com/raddstream/app/resolve/ResolveTestViewModel.kt` —
  logcat lines, `runControlOnly()`.
- `App/app/src/main/kotlin/com/raddstream/app/resolve/ResolveTestScreen.kt` +
  `App/app/src/main/res/values/strings.xml` — "Run control only" button.
- `App/app/src/test/kotlin/com/raddstream/app/resolve/HttpChainEngineTest.kt` —
  label updates + 4 redirect tests.
- `App/app/src/test/kotlin/com/raddstream/app/resolve/WebViewDetectorTest.kt` —
  new: ranking, navigation, extraction, local fixture page.
- `App/docs/robo-resolve-test.json` — Robo script for the emulator run.
- `results/field-test-run1-20261116.json` — the run-1 log (evidence).

## Verification

- CI, first push e250a7c5: Backend 43919653458 SUCCESS; Android 43919653116
  FAILED at `compileDebugUnitTestKotlin` (main compile + lint passed) —
  `WebViewDetectorTest` used `com.sun.net.httpserver.HttpServer`, which the
  Android unit-test classpath does not resolve. Fixed in the follow-up commit:
  the fixture now serves over a raw `ServerSocket` (`FixtureHttpServer`, the
  same shape as `NewPhoneProgressPullTest`'s `FakeSyncServer`, which documents
  the same red on run 42323050286). No production code changed in the fixup.
  Second-push run ids in the parent report.
- Emulator: Firebase Robo control run owed before the owner re-runs; see the
  Robo script and the parent report for the matrix + mint counts.
