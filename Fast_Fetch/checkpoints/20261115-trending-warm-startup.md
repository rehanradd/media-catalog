# Warm the trending orders at service startup — 2026-11-15

## Why
The loop's owed check (06:52 PKT, 2026-11-15): the first trending hit after
a restart 503s exactly once. The grouping behind the trending order is a
full-catalog GROUP BY (~10 s cold on the live catalog) and the request path
runs it under the 10 s progress deadline, so that first hit trips the nginx
read timeout. The stale-while-revalidate fix (2026-11-14) covers every
later miss — a TTL expiry or a merge serves the stale order and rebuilds
in a daemon thread — but a true cold start has no stale order to serve,
and the module comment said so explicitly ("Only a true cold start ...
still builds synchronously — that hit is once per process start").

## Changes
- `Fast_Fetch/viewer.py`: new `_trending_warm()` — builds the three
  trending orders the app actually requests (unfiltered "Most copies"
  rail, movie, series) by reusing `_trending_rebuild` (its own
  connection, no deadline) with the same where-clause construction the
  request path uses (adult clause + type facet + operator hides).
  Best-effort per shape; a failure only logs. `start_server()` calls it
  after the servers are constructed and before `serve_forever()`, in
  the same try/except style as the maintenance thread — a warm failure
  never stops serving, and the request path still cold-builds on demand
  exactly as before.
- Tests: `TrendingTests.test_startup_warm_populates_the_cache` in
  `test_home_rails.py` — the warm returns `["", "movie", "series"]`,
  the cache holds all three orders, and the warmed order answers the
  same page (and the same adult exclusion) the cold path did.

## Cost
Three groupings at startup, ~10 s each cold on the live catalog (less
once the page cache is warm) — paid once per restart, before the first
request can arrive, instead of once per process start on a request
thread under the deadline. The gate deploy's live test waits for the
process either way; the service already takes ~83 s to stop.

## Local verification
test_home_rails (TrendingTests 11 OK) + test_redact + test_serve_verify +
test_api_v2 + test_docs_invariants = **322 tests OK**.
