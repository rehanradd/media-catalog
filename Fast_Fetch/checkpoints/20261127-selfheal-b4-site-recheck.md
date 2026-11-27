# 2026-11-27 — Self-healing Branch 4: site recheck wiring

Branch `selfheal-b4-site-recheck` from main `0ff41ebc` (Branch 3 landed).
Design: `~/workspace/backend-self-healing-design-20261127.md` §1a (Branch 4 line).
Parent task: wire the parked/dead sweep signal to the domain-resolver's
discovery chain, so a site whose rows go dead is re-checked and its
registry moves to the live domain — with the old domain closed as
parked|dead, never guessed.

## Grounding found during this branch (all measured 27 Nov)

- `download_sources` has NO `source_site` column (schema: id, release_id,
  source_name, service, resource_id, source_url, final_url, …). The
  per-site signal is the row's final-URL host matching a site's
  `active_base_url` host, per the design doc's parenthetical. Live data
  confirms the signal exists: 55,494 rows have final_url containing
  vegamovies/filmyzilla/hindilinks4u.
- `radd-domain-probe.timer` is LIVE and enabled (fired 10:32 PKT 27 Nov,
  next Sat 10:38) and runs `domain_resolver.py` no-arg →
  `probe_all_sites()` (full, all sites). So `probe_all_sites` already HAS
  a caller; the remaining gap was the `resolve_site` chain for down
  sites + the 7-day staleness window — which this branch wires.
- **DuckDuckGo is reachable from Oracle egress** (html 202 in 0.25s,
  lite 202 in 0.41s, measured 27 Nov) — `resolve_site` step 8 works,
  no WARP needed for the recheck chain.
- **The live sweep wrapper is STALE** — it is the old trap-less version
  (no TERM/INT trap, foreground child, no stop_requested); the repo copy
  has the INF-05 trap fix since 24 Nov and was never installed. This
  branch's gate installs the repo copy (with the new recheck hook) at
  the next natural relaunch, which is its own gate.
- **The live `radd-domain-probe.service` differs from the repo copy**:
  live has the `RADD_ADMIN_V2_DB` env line, the repo copy lacked it.
  This branch synced the repo copy (env line added) and changed
  ExecStart. **At gate time the updated service must be installed**
  (`sudo cp` to `/etc/systemd/system/`, `daemon-reload`, verify with
  `systemctl cat`), and the updated sweep wrapper installed before
  relaunch — verify each with a diff first so only the intended
  changes land.

## What changed (production, 6 files)

- New `Fast_Fetch/recheck_queue.py` (stdlib): sidecar SQLite table
  `domain_recheck_queue` next to the catalog DB (`queue_path_for_db` →
  `domain_recheck_queue.db`); `ensure_queue`, `enqueue` (upsert: dead
  rows accumulate, reason replaced, status back to `pending`, original
  `queued_at` kept), `pending(path, limit, cooldown_s=6h)` (due rows
  only: never attempted or last attempt older than cooldown, oldest
  first), `get_entry`, `mark_attempt(path, site, result, done=)` (done
  closes the row; failure leaves it pending for retry after cooldown),
  `count_pending`.
- `Fast_Fetch/domain_resolver.py`: `SITE_ROT_THRESHOLD = 10`,
  `RECHECK_COOLDOWN_SECONDS = 6*3600`, `STALE_RECHECK_DAYS = 7`,
  `STALE_RECHECK_SECONDS = 7*86400`; `site_hosts_map()` (active-base
  host → site name, active base only); `maybe_queue_site_recheck(site,
  dead_rows, reason, queue_path, registry_path)` (False below
  threshold, unknown site, or no queue path; True + enqueue
  otherwise); `_old_domain_status(reason)` helper (parked if
  `parked-marker:`, None if blocked/CF-challenge, else dead);
  `_record_verified` enriched with kwargs `old_status/old_reason/
  discovered_by` (old rotation entry gets status + reason when known;
  new entry gets `discovered_by` + verify reason); `resolve_site`
  saves the cached-base probe result and routes all 8 chain success
  returns through a local `_verified(hit)` helper that passes the
  cached failure's status/reason + `hit["method"]` as discovered_by;
  `probe_all_sites` gains `stale_after_seconds=None` (None = existing
  TTL behaviour; set = that window, skip reason "verified within stale
  window").
- `Fast_Fetch/fast_probe.py`: imports `domain_resolver` + `recheck_queue`
  with the same try/except pattern as `host_registry`; `_probe_host`
  gains `site_hosts=None` param and counts dead/parked verdicts per
  site into `stats["site_signals"]` **in ROWS** (`len(group["row_ids"])`,
  because the threshold is a dead-row count), only for verdicts
  `dead`/`parked` (never `expired` — a 5xx/WAF answer is a sick
  intermediary, not a moved site — never `active`); `_merge_stats`
  merges `site_signals`; new `queue_site_rechecks(site_signals,
  queue_path, registry_path)` (best-effort, never raises, returns
  queued sites sorted by count desc); `run_pilot` gains
  `registry_path=None` param, builds the site-host map before the
  pool, passes `site_hosts` to workers, and after merging calls
  `queue_site_rechecks` (best-effort, failure printed to stderr, never
  fails the batch) and records `merged["site_rechecks_queued"]`;
  module docstring gains a Branch 4 paragraph.
- New `Fast_Fetch/domain_recheck.py` (stdlib CLI): `--db` (derives queue
  sidecar) / `--queue` / `--registry` / `--limit` / `--probe-stale` /
  `--stale-days` (default 7) / `--cooldown-hours` (default 6) /
  `--dry-run`; `drain_queue()` (per-site `resolve_site`, success →
  mark done `resolved:<base>`, empty → mark pending
  `unresolved:<status from registry>`, exception → mark pending
  `error:...`, one JSON log line per site, injectable `resolver`/
  `fetcher` for tests); `probe_stale_sites()` (probe_all_sites
  only_stale + 7d window, full chain only for status `down`, never
  blocked/unverifiable/paused/skipped); exit 0 unless args/queue
  unusable.
- `Fast_Fetch/fast-probe-sweep-loop.sh`: post-batch hook after `FAIL=0`,
  before the probed-0 check: `python3 -S Fast_Fetch/domain_recheck.py
  --db "$DB" --limit 5 >> "$LOG" 2>&1 || echo "... domain recheck hook
  failed (continuing)" >> "$LOG"`, skipped when a STOP file is present;
  header comment gains item 4 documenting the hook.
- `Fast_Fetch/systemd/radd-domain-probe.service`: ExecStart →
  `/usr/bin/python3 -S /opt/radd-stream/Fast_Fetch/domain_recheck.py
  --db /home/ubuntu/radd-stream-data/merged_catalog_20261025.db
  --probe-stale`; added the `RADD_ADMIN_V2_DB` env line (syncing live);
  Description + comments rewritten for the new behaviour (drain queue +
  7-day stale probe + resolve down sites; resolve-all never on a
  timer). Timer file unchanged (daily 10:30).

## Tests (all offline, stubbed — no network)

- `test_domain_resolver.py`: 81/81 (72 existing + 9 new
  `RecheckQueueTests`): threshold 10 queues / 9 does not; unknown site
  never queued; `site_hosts_map` uses active base only; parked old
  domain closed `parked` with reason on promotion (cached base returns
  parking page via `HOMEPAGE_PARKED`, mirror verifies); dead old domain
  closed `dead` on hard failure (cached 404); blocked old domain gets
  NO status (cached CF-challenge 403 + "Just a moment", mirror
  verifies); parked old domain never promoted (cached + mirror both
  parked → `""`, base unchanged, status `down`, no rotation_history);
  failed candidate never written (TLD candidate returns wp-json with
  no quality marker — body built inline with links on the candidate
  host so it fails on the marker, not the link check — registry
  active_base_url/mirrors/rotation unchanged; status/last_checked
  moving is the run's own record, not the candidate);
  `probe_all_sites` stale window (8-day-old last_verified probed
  under 7d, 1-hour-fresh skipped; stale date computed from
  `time.time() - 8*86400`, not hardcoded).
- `test_fast_probe.py`: 77/77 (69 existing + 8 new
  `SiteRecheckSignalTests`): dead rows on a site base count in rows
  (HEAD 404, 3 row_ids → `{"ssrmovies": 3}`); parked rows count (HEAD
  405 → GET fallback 200 parked body, 2 rows → 2); active rows do not
  count (HEAD 200); expired rows do not count (HEAD 403 → GET 403 →
  `expired`); dead rows on a file host do not count; `site_hosts=None`
  disables counting; `_merge_stats` adds site_signals. Fixture
  corrections learned while writing them: a HEAD 403/429/5xx falls
  through to the GET fallback (`_needs_get_fallback` — an intermediary
  refusal is not a verdict about the link), and vcloud.beer is a
  `KNOWN_HEAD_LIARS` member, so its HEAD 404 also falls through to
  GET — the file-host test uses a non-liar host instead.
- New `test_recheck_queue.py`: 8/8 (storage: enqueue/pending/cooldown/
  mark_attempt/done-reactivation/queue_path_for_db/count_pending).
- New `test_domain_recheck.py`: 8/8 (drain with fake resolver:
  resolved→done, unresolved→pending, error→pending, limit/cooldown;
  probe-stale with stub fetcher: down site gets full chain via the TLD
  sibling step, blocked site left alone; end-to-end integration: temp
  registry with a site whose active_base_url is a parked domain + temp
  ledger DB with 12 rows on it + `run_pilot` with a stub session
  factory (HEAD 403 → GET parked body) → queue entry → drain with stub
  fetcher (cached parked, mirror verifies) → registry moves to the live
  domain, old domain marked parked with timestamp + reason. A minimal
  local fake connection is used; no helpers imported from
  `test_fast_probe.py`).
- `backend-ci.yml`: 229 → 231 `🧪` steps (the 2 new suites added right
  after `test_domain_resolver.py` with a comment — the gate runner
  reads the module list from this file, so the local gate and CI get
  them together).
- `Fast_Fetch/CONTINUITY.md` is at 89 lines / 11,980 bytes (cap 12,000
  bytes / 120 lines) and CANNOT take a session entry — same as Branch 3:
  full note here + one index line in `checkpoints/README.md`, plus the
  CONTINUITY count claim updated byte-neutrally 173→174 / 170→171
  dated. (Branch 3's note file existed but its README index line was
  missing — added here, above Branch 2's line, to keep the index
  honest.)

## Out of scope (not touched)

- Branch 2 residual `_bank_hubcloud_siblings` (`fast_fetch.py:10098`)
  writing the ledger directly. No expiry work (Branch 5). No
  durability log (Branch 6). `probe_site`'s redirect-move path (writes
  via `update_site_registry` without rotation history) is a
  pre-existing gap, left as is — a redirect proves a move, not
  parked/dead, so it cannot fill the parked|dead status honestly.

## Gate (parent-owned; to be filled by the gate run)

- Local full gate: TBD.
- Backend CI + Android CI on the exact tip: TBD.
- Landing gate: TBD (stop sweep, `.backup` backup, counts exact,
  integrity to `/tmp/b4-integrity.out`, gh api PATCH, live-tree ff,
  service restart, live tests, sweep relaunch via the wrapper, panel-push
  clone sync, re-pin `.deployed_commit`, delete branch). At gate time:
  install the updated `radd-domain-probe.service` (sudo cp +
  daemon-reload + `systemctl cat` verify) and the updated sweep wrapper
  (diff first) before relaunching the sweep; delete the oldest
  superseded gate backup (inf08, 07:47) after the new backup to restore
  disk free to ~21G for the Sat 03:42 HF run.
