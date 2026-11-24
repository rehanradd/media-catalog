# INF-05 — sweep stuck-child root cause + fix [P1]

Branch: `muse/phase4-inf05-sweep-stuck-child-20261124` from `ef15f2fc`.
Tests: `test_fast_probe.py` 61/61 (48 pre-existing + 13 new), twice in a row;
`test_host_clearance` 44/44, `test_host_registry` 35/35,
`test_network_intercept` 22/22, `test_link_sweep` 21/21. Negative control:
3 new stop tests fail on unfixed code (`AttributeError: no _STOP_EVENT`).
NOT merged — parent merges; no gate, no restart, no live-tree/DB touch.

## Root cause (measured, not guessed)

The sweep loop only reads the STOP file BETWEEN batches, and a batch runs
30 min to 14 h (`/tmp/fast-probe-sweep.log`: 19 Nov batches 5–14 h; 23 Nov
batches 30–60 min). So a gate stop could only ever land as a hand-sent
SIGTERM to the exact child PID — every single "batch exit 143" in the log
is one, and every gate checkpoint records it ("SIGTERM to exact PID":
library-bump child 2157338 stalled on gdflix, panel-tests-ci 2239828 CPU
stuck at 1:07, registry-corrections 2184727 stalled on slow host). That
SIGTERM killed the child mid-write (queued verdicts lost, writer never
flushed) and counted as a wrapper failure — three stops in a row exited
the loop for good ("3 consecutive failures, stopping loop", 22 Nov 02:27,
21 Nov 21:40), so repeated gate stops killed the sweep until a human
relaunched it.

Worse, the wrapper runs the child in the foreground with NO trap, so
killing the WRAPPER orphans the child: it never reads the STOP file (only
the wrapper does) and keeps writing to the live DB through the backup
window. Proven locally (SIGTERM to a trap-less wrapper leaves the
foreground child alive, reparented to PID 1) and fingerprinted live —
greenmotors, 23 Nov: wrapper exited 18:54:17, yet `pgrep -f
'[f]ast_probe.py'` matched until 19:09:22, after the duplicate loop was
killed 18:25. That 15-minute orphaned child IS the stuck child.

Contributing math: a batch is 5,000 rows, per-host cap 208, so one host
thread grinds 208 URLs serially at ≥1 s spacing plus up to 16 s of
timeouts per URL — ~55 min per host thread before any other delay, hours
with slow hosts. The stop could never wait that out (gate waits 30 min).

NOT the cause (checked and ruled out): `select_rows` does cap per-host
rows (a batch spans ≥24 hosts, it cannot collapse onto one); `host_gate`
holds one lock at a time (no cross-host deadlock); the writer's lock
retries are bounded (~3.5 min/flush); every socket path has the 8 s
timeout. Residual unproven hole, left alone: `resp.read()` in `head()`
has a per-recv 8 s timeout only, so a drip-feed server could hold one
host thread — the stop event + second SIGTERM still let the operator
force-kill it, and the other 47 threads still finish.

## Fix (stdlib only, verdict path untouched)

`Fast_Fetch/fast_probe.py`:
- SIGTERM/SIGINT install a handler that only SETS the stop event.
  Workers finish their in-flight URL, then stop; the writer flushes what
  was queued; the run reports `"stopped": true` and exits 0, so the
  wrapper counts it as a clean batch end, not a failure. A SECOND signal
  restores the default disposition and re-delivers, so a wedged shutdown
  can still be forced.
- The batch itself watches the STOP file (`--stop-file`, default
  `/tmp/fast-probe-sweep.stop`, env `RADD_SWEEP_STOP_FILE`): a child
  orphaned by a dead wrapper still sees the gate's stop file and exits on
  its own, within one in-flight URL per host thread. Checked before every
  URL and before the pool starts.
- `run_pilot` / `run_compare` take `stop_event` / `stop_file`;
  `stop_file=""` disables the file watch (the engine tests pass it so the
  offline suite never depends on a host-global file).

`Fast_Fetch/fast-probe-sweep-loop.sh` (NEW, repo copy of the live script):
trap forwards TERM/INT to the child (`wait` + `child=$!`), a TERM to the
wrapper also sets `stop_requested` so the loop exits instead of launching
another batch, and a present STOP file breaks the loop at once after a
batch instead of counting a failure + sleeping 30 s. Body verified by
diff against the live `/home/ubuntu/fast-probe-sweep-loop.sh`: identical
except the three documented changes. INSTALL (gate owner, at the next
natural relaunch — the running loop already read the old copy):
`cp Fast_Fetch/fast-probe-sweep-loop.sh /home/ubuntu/fast-probe-sweep-loop.sh`
then relaunch ONLY via the wrapper, never `fast_probe.py` directly. Do NOT
install mid-gate; do NOT restart a running loop to pick this up.

## INF-09 (gate checklist)

No gate checklist lives in the repo (no `SWEEP-STOPPED` / gate-procedure
doc under `docs/` or `Fast_Fetch/`; the gate scripts live only on the VM),
so there was no checklist file to edit in this branch. The stuck-child
check the gate needs until the wrapper is installed: after touching STOP,
`pgrep -f '[f]ast_probe.py'` must clear AND the wrapper must be gone —
a child with no wrapper is the stuck child; SIGTERM the exact child PID
once (it now exits 0 with `"stopped": true` instead of dying mid-write).
After the wrapper install, the pgrep check alone suffices. Gate owner to
carry this into the VM gate scripts at landing time.

## Follow-up owed

- Install the wrapper copy on the VM at the next relaunch (gate owner).
- Gate scripts on the VM still never signal the child — they rely on the
  STOP file + 30-min pgrep wait; with this fix the child exits within
  seconds of the STOP file, so the wait should clear fast. If a gate ever
  still sees a stuck child, SIGTERM the exact child PID (graceful now).
- `/tmp` on the hatch VM is 100% full (512M tmpfs, other lanes' scratch);
  local tests were run with `TMPDIR=~/workspace/phase4/inf05-tmp`.
  CI is the backstop.
