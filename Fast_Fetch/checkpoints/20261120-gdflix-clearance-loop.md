# 2026-11-20 — GDFlix clearance re-earning loop (Phase C)

Branch `muse/gdflix-clearance-loop-20261120`, base main `7940fd6b`. Built
under the 04:42 PKT 20 Nov all-decisions delegation (gdflix clearance
re-earning loop: BUILD). Build only — no deploy, no restart, no live-DB
write; the parent merges and the gate deploys.

## The gap

Phase C built the real gdflix button-flow resolver (main `6011f989`, 21
tests) but did NOT crack the host: `new10.gdflix.io` answers the Oracle
egress with a 403 Cloudflare managed challenge. H4 R8 (main `8f7948d2`)
built the per-host clearance store (`Fast_Fetch/host_clearance.py`,
15-min reuse ceiling) and the resolver threads it — but nothing ever
EARNS a clearance, so every resolve hits the challenge cold. This lane
builds the loop that earns and re-earns it.

## What was built

- `Fast_Fetch/clearance_loop.py` (new, stdlib only, 795 lines):
  `ClearanceLoop` over the same DB as the clearance store, with a
  `clearance_loop_attempts` table (per-host last outcome, failure count,
  next_attempt_at). `run_once(hosts, earner_cmd, opener, now)` — one
  pass, never raises, one summary dict per host. `run_loop` — daemon
  mode (`--interval-s` / `--max-seconds`, SIGTERM/SIGINT stop).
  `request_reearn(db, host)` — the resolve-path trigger: marks a host
  due, never moves a backoff window earlier, never raises, only for
  configured hosts. CLI: `--once` (timer mode, exit 0 even when every
  earn fails), `--status`, `--place` (operator hand-placement of a
  browser-harvested cookie), `--db`, `--host` (repeatable), `--earner-cmd`.
- Earners, in order: an external solver (`--earner-cmd`, prints
  `name=value` lines; only clearance names are placed — a solver that
  prints a session id must never store it) if configured, else the
  stdlib earner: one browser-UA GET, same headers/UA as
  `fast_fetch.http_get`, timeout 10 s, no retries. A clean 200/206 with
  a clearance cookie is captured via `note_response`; a managed
  challenge records `challenge` and backs off. A bare 403/503 with no
  marker is `blocked` (a block of another kind, not a challenge).
- Backoff: `BACKOFF_S = (60, 300, 900, 3600)` — error starts at 60 s,
  challenge starts at 300 s, capped at 1 hour. Re-earn lead
  `REEARN_LEAD_S = 180` (a clearance is due when under 3 min of life
  remains). `EARN_USER_AGENT` is pinned equal to `fast_fetch.USER_AGENT`
  (a test asserts it — the edge binds the cookie to the UA that earned
  it). Log lines are JSON, one per host, and never carry a cookie value.
- `Fast_Fetch/fast_fetch.py` hook (patch, applied in the branch worktree):
  guarded `import clearance_loop as _cloop` beside the H4 R8 import,
  a `_request_clearance_reearn(host)` helper, and calls on the two
  `unwrap_gdflix` failure returns — unconditionally on a fetch
  exception / non-200 / empty page, and on the final no-button-yield
  return only when the served page is a challenge response
  (`_hclear.is_challenge_response(200, body=page)`). A clean page with
  no buttons does NOT mark the host due.
- `Fast_Fetch/test_clearance_loop.py` (new, 24 tests): skip/due, earn,
  challenge backoff from 300 s, backoff growth + 1 h cap, hostname
  isolation, `request_reearn`, external earner, UA pin, no cookie value
  in logs, CLI exit 0, and 3 resolve-hook tests.
- `Fast_Fetch/systemd/radd-clearance-loop.service` + `.timer` (new):
  oneshot `--once`, every 5 min, `Nice=19`, idle IO, `RADD_DB_PATH`
  pointing at the live catalog (same value `radd-stream.service`
  carries). The timer is the loop; the runner is `--once` only.
- `.github/workflows/backend-ci.yml`: new `test_clearance_loop.py`
  step after `test_gdflix_unwrap.py` (patch).
  `Fast_Fetch/test_ci_manifest.py`: `MANDATORY_RUN` gains
  `"test_clearance_loop.py"`.

## Honest capability boundary

The loop is scheduling + capture + trigger machinery, NOT a challenge
solver. Stdlib Python cannot solve a Cloudflare managed challenge
(JS proof-of-work + browser-environment probe); only a real browser or
a FlareSolverr-style solver passes. The stdlib earner succeeds only
when the edge serves a clean 200/206 with a clearance cookie — i.e.
when the challenge is already satisfied or not presented. The working
pattern stays "solve once in a browser, replay the cookie over HTTP
from the same exit IP + UA": `--place` stores a browser-harvested
cookie, and `--earner-cmd` is the plug for a real solver. Until a
clearance is earned by one of those two routes, gdflix resolves keep
hitting the challenge — the loop makes the earning automatic and
bounded, it does not make it possible by itself.

## Tests

`python3 -S Fast_Fetch/test_clearance_loop.py`: 24/24 pass locally,
against the PATCHED fast_fetch (hook applied in the branch worktree).
At landing it was 22/24 — two real failures, not the sandbox:
(1) `RequestReearnTests.test_never_raises_on_a_broken_store` —
`ClearanceLoop.__init__` did `str(db_path)`, so a Mock became a junk
filename and the mark "succeeded"; fixed to accept only
str/bytes/os.PathLike as a path, else no store. (2)
`ResolveHookTests.test_unwrap_clean_no_button_page_does_not_mark_due`
— `due()` is True for a never-seen host by design, so the test
asserted the wrong observable; fixed to assert no row was written to
`clearance_loop_attempts`. The lane's "21/24, 3 tempfile failures"
note was the EPERM-era sandbox, not this measurement. The gate is
still the Backend CI verdict on the branch.

## Landing (2026-11-20, branch worktree `gdflix-land-worktree`)

The EPERM git-write fault that blocked the build lane is NOT active
at landing: `git worktree add -b muse/gdflix-clearance-loop-20261120
7940fd6b`, `git apply` of the patch, and file writes all succeeded
from the shell. The branch was created locally from main `7940fd6b`
and the patch applied in the worktree (no API route needed). Two
landing fixes (see Tests): `__init__` path validation, and the
wrong `due()` assertion. CONTINUITY.md was reconciled against
main's copy (count 136 → 137, bullet trimmed to fit the 12 KB
ceiling: 11,991 bytes, 86 lines). Deliberately NOT landed:
`checkpoints/20261120-continuity-rotation-2.md` — it rotates two
bullets out of the lane's stale-base CONTINUITY, but on current main
both are already accounted for (B2 lives in
`20261120-continuity-rotation.md`, MX #4 is still in CONTINUITY.md)
and main is under budget, so the rotation is a false record and
would put the count at 138.

## Handoff to the parent

Landed on branch `muse/gdflix-clearance-loop-20261120` from main
`7940fd6b`: `Fast_Fetch/clearance_loop.py`,
`Fast_Fetch/test_clearance_loop.py`, `Fast_Fetch/test_ci_manifest.py`,
`Fast_Fetch/CONTINUITY.md` (reconciled, count 137),
`Fast_Fetch/checkpoints/20261120-gdflix-clearance-loop.md`,
`Fast_Fetch/systemd/radd-clearance-loop.service`,
`Fast_Fetch/systemd/radd-clearance-loop.timer`, plus the patch
(`fast_fetch.py` hook + `backend-ci.yml` step, applied in the
worktree). `20261120-continuity-rotation-2.md` deliberately NOT
landed (see Landing). Remaining: commit (author rehanradd
<199005610+rehanradd@users.noreply.github.com>), run
`~/workspace/pre-push-checks.py`, push, watch Backend CI
(workflow name "🐍 Backend CI — Stdlib Python Tests"). Do NOT start
the loop on the VM and do NOT touch the live DB — the gate owns that.
