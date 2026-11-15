# 2026-11-15 — transport failures earn no verdict [LINK-TRANSPORT-NO-VERDICT]

## What was wrong (measured on the live catalog, read-only)

`link_state.classify()` mapped transport errors (`timeout`, `dns`, `tls`,
`conn_reset`, `conn_error`) to `status='dead'` on one strike. Measured
2026-11-15 07:50 PKT against the live catalog:

- `download_sources`: 4,451,674 rows; marked dead 10,871.
- `probe_error` on the dead rows: `http_404` 3,311 (real), `timeout` 2,794,
  `http_400` 2,551, `conn_reset` 1,969, `dns` 223.
- Transport failures (timeout + reset + DNS) = **4,986 of 10,871 dead rows
  (46%), all with `http_status=0`** — no answer at all. Our side failed to
  reach the endpoint, and the ledger recorded it as a verdict about the
  link. That contradicts the `resolved_cache` rule (`TRANSPORT_CAUSES`
  must never strike, pinned by tests) and the serve path
  (`serve_verify.classify_failure`: transport is "OUR side failing ...
  never evidence about the link").

The sweep itself (`link_state.propagate`, maintenance tick scope `serve`)
is alive: cursor at id 17,605 of 4.45M rows, 17,600 rows checked since
2026-11-09 in runs of 400 rows (2 batches/tick, ~6h). At that rate full
coverage takes years — the rate is a separate, still-open fix (see below).
The 30-day stale re-probe is the only healing for the 4,986, and at the
current rate it is years away.

## The fix (this change)

`Fast_Fetch/link_state.py`:

- New `_TRANSPORT_ERRORS = frozenset(("timeout", "dns", "tls",
  "conn_reset", "conn_error"))`, with a comment tying it to
  `resolved_cache.TRANSPORT_CAUSES` (a test pins the equality).
- `classify()` returns `STATUS_UNVERIFIED` for a transport error, before
  the `_ERROR_CODES -> DEAD` branch. The check fires only when no HTTP
  status was received (any real answer was already handled by the status
  branches above: 401/403/429 -> expired, 404/410/451 and other 4xx/5xx
  -> dead). `verdict_for()` returns None for an unverified verdict, so
  the sweep writes nothing and the row stays eligible for a later run.
- The now-unreachable `error.startswith("conn_error") -> DEAD` branch
  below it was removed.
- The module docstring's published status table was updated to match.

Tests:

- `test_link_state.py`: the five pinned rows (timeout/dns/tls/conn_reset/
  `conn_error:ValueError`) now expect `"unverified"`; new test
  `test_transport_vocabulary_matches_resolved_cache` pins
  `_TRANSPORT_ERRORS == resolved_cache.TRANSPORT_CAUSES`.
- `test_resolved_cache.py`: `test_transport_vocabulary_is_link_states_own_words`
  updated — the shared vocabulary now means a transport failure earns no
  verdict in the ledger AND no strike in the circuit (it used to pin
  `classify(...) == STATUS_DEAD` for every transport word; that was the
  bug being fixed).

Local results (in-memory/temp fixtures, this VM): `test_link_state.py`
34/34 OK, `test_resolved_cache.py` 85/85 OK, `test_transient_dead_heal.py`
5/5 OK, `test_probe_coverage.py` 13/13 OK. The transient-dead-heal tests
do not call `classify()` (they exercise `fast_fetch`'s write-back path
directly) and are unaffected.

## Deliberately NOT changed

- `redirect_loop`, `redirect_limit`, `empty_body`, `head_unsupported`
  still map to dead: those mean we REACHED an endpoint that misbehaves.
  (`head_unsupported` is a prober concern — fall back to GET/Range —
  not a classify concern.)
- DNS ambiguity: link_health's `"dns"` does not distinguish NXDOMAIN
  (host gone) from a resolver failure on our side, so it earns no
  verdict either. If the prober ever splits them, NXDOMAIN can go hard
  (serve_verify already treats dns as hard).
- `fast_fetch.probe_single_source` / `run_health_check` (viewer route +
  CLI) has the SAME mislabel — its catch-all writes `status='dead'` with
  `http_status=0` for a reset (pinned by test_transient_dead_heal). That
  surface is per-post and operator/route-triggered, not the sweep; it is
  a follow-up, not part of this change.
- The already-dead 4,986 transport rows are NOT un-deaded by this change:
  it stops NEW transport-kills. Un-deading them needs a re-probe, which
  needs the rate fix (below) — or a one-time targeted re-probe of rows
  with `status='dead' AND http_status=0`.

## Still open (rate)

`PROPAGATE_BATCHES_PER_TICK = 2` (400 rows/tick, ~6h) = ~1,600 rows/day
against 4.45M rows. The module docstring and the FIRST_TICK_WARM_LIMIT
comment say small slices are deliberate (a 200-link warm starved a Play
resolve for 35s in the Robo 501 re-test, 2026-11-14) — so the rate fix
must be measured, not guessed: time one 200-row batch on the live egress
(dry-run propagate, writes nothing), then size the slice (in-process tick
raise vs a separate out-of-process timer like prefetch) from that number.
NOT live — gate deploy is the supervisor's call.
