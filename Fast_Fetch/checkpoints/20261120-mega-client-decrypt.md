# 2026-11-20 — Mega client-side decryption (muse/mega-client-decrypt-20261120)

Owner decision 04:42 PKT 20 Nov: client-side decrypt, NO paid Pro. Design first, then build;
do NOT merge — main chat merges on CI green. Design: `DESIGN.md` in the branch worktree.

## The wire is scheme-neutral (the build's one real finding)

The first build used `decrypt: "mega"` / `mega_node_id` / `mega_key`. It broke the project's
own provenance rule: the bare word "mega" is a needle (`test_api_v2.assert_clean` does
substring matching over the reply, and the ledger's `mega.nz` host puts "mega" in the needle
set), so an existing download-only test failed on the marker. The public wire now names the
scheme, never the hoster: `decrypt: "aes-ctr"`, `decrypt_node_id`, `decrypt_key`. The request
flag stays `mega_decrypt` — a client declaration, never in a reply. The sweep's word-boundary
check would have passed the old wire; the stricter substring check is what caught it.

## What was built

Server (stdlib only): `host_resolvers.verify_mega_live` (stdlib POST to the g API, never
returns the g URL); `fast_fetch` mega branch (parses the stored URL, verifies liveness,
returns the decrypt fields, no `url`); `viewer` stream/download descriptors answer the JSON
descriptor for both redirect values, never a 302; `catalog_api._link_view` marker +
`play_bundle` flag threading; `api_v2.route_play` reads `mega_decrypt` (only explicit JSON
`true`); `resolved_cache` refuses to bank a decrypting resolve; `tiers.playable` honours
the marker only when the flag is set. Negative-cache trap fixed: a mega success clears the
resolve failure and the Stage-2 cached-probe branch skips mega rows, else one play poisons
the next press for 12 h.

App: `MegaCrypto` / `MegaApi` (app-side mint) / `MegaDecryptProxy` (loopback, Range, 2 MB
window) / `MegaProxyHolder` (one proxy per play session, key check + container-magic check
before any byte). The detail screen stages only the SCHEME (`PlayerSessionStore.stageDecrypt`);
the player resolves the descriptor and starts the holder for a marked handle, stops it on
release; quota/dead/bad-key/network map to plain-words messages. Download enqueue downloads
clear bytes through the proxy. `playV2` sends `mega_decrypt: true`.

## Tests (local)

Server: tiers 57, resolved_cache 95, host_resolvers 309, catalog_api 168, api_v2 93,
download_endpoint 31, redact 118 — all OK. Full backend gate, first run: 124/128. The
four failures were all diagnosed, none of them the decrypt logic: test_redact (2 HTTP
timeouts under load — the known flake, 118 OK on re-run); test_serve_verify (my
`_release_has_unfailed_candidate` selected `l.service`, which the fixture's cat_links
lacks — fixed by recognising mega rows by their mega.nz URL, the same test
`fast_fetch._is_mega_row` uses; 68 OK on re-run); test_docs_invariants (my CONTINUITY.md
line pushed the file to 12,328 bytes over the 12,000 budget and the archive count was
stale at 133 — line shortened, count corrected to 134, file now 11,975 bytes; 22 OK on
re-run); test_ci_manifest (the branch base predates main's autocrawl fix, so the workflow
lacks `test_auto_crawl_cron.py` — fixed by rebasing onto current main before the push).
App: MegaCrypto / MegaApi / MegaDecryptProxy / MegaProxyHolder tests + JSON deserialization
tests for the new wire fields (StreamResponse, WireDetailLink, WirePlayCandidate,
WireResolveResponse, WirePlayRequest). Android CI on the branch is the gate (no local
Gradle). Known v1 limits: one proxy per process (a second mega start stops the first and
interrupts an in-progress mega download — resumable, never garbage); a decrypting BACKUP
handle reached by mid-play failover has no staged marker, so it is skipped. Quota is per
user IP (~5 GB / 6 h); a ~900 MB film is ~1/5 of a 6 h budget.

## State

Branch `muse/mega-client-decrypt-20261120`, worktree `~/workspace/mega-client-decrypt-20261120`,
base main `6199929e` (main has since advanced: autocrawl fix ce5dd227, slices 3389877c —
rebase owed at merge time). 38 files, +2,336/−46. Pre-push checks pass. Commit + push + CI
verdicts owed; parent merges.

## Gates #2/#3 (folded in at rebase time, 2026-11-20)

- Gate #2 (129 suites): 118 pass. 2 real — `test_docs_invariants` count/bytes (the rebase
  brought the slices lane's checkpoint file, so the count is 135 and CONTINUITY.md was
  12,044 B; fixed to 11,984 B). 9 temp-DB suites failed on the EPERM write flake recurring
  (Python `mkstemp`+write → `PermissionError` while shell writes worked, 01:05–01:49 UTC).
  A background driver waited for a write window, amended the commit, ran gate #3.
- Gate #3: **129/129 pass, 0 fail** (log `/tmp/mega-gate3.log`).
- CI on `d8bb58ed`: Backend 44658516021 SUCCESS, Android 44658516013 SUCCESS (2,784 tests,
  0 failed). Rebased onto main `deddff6f` (app small fixes) at merge time; CONTINUITY.md
  conflict resolved (kept both the mega and scale-fix bullets, count corrected to 136),
  `test_redact.py` auto-merged (scale lane + this lane both added tests — both kept).

## Android CI failure + proxy fix (02:04 UTC 20 Nov)

First CI on tip `34d5bd0e`: Backend CI SUCCESS (run 44657324135), Android CI FAILURE
(run 44657324118) — 2,783 tests, exactly 1 failed:
`MegaDecryptProxyTest.aFailedCiphertextFetchDropsTheConnection`, AssertionError at
`MegaDecryptProxyTest.kt:168`. Real bug, not a flake: `respondStreaming` wrote the
`200 OK` headers BEFORE the first `readPlain`, so when the ciphertext fetch threw,
the client had already been promised `Content-Length: <fileSize>` and got 0 bytes —
a lying 200, not the dropped connection the design and the `readPlain` doc comment
promise. Fix (proxy only, no test change): `handle()` fetches the first chunk
before writing headers; on failure it returns without writing anything, so the
socket closes and the client gets an IOException. The window cache makes the
streaming loop reuse that first chunk — no second fetch. Re-verdict on the fix
commit `d8bb58ed`: Backend 44658516021 SUCCESS, Android 44658516013 SUCCESS.
