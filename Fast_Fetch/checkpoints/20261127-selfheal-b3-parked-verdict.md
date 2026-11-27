# 2026-11-27 — Self-healing Branch 3: parked verdict + host observations

Branch `selfheal-b3-parked-verdict` from main `12d1ebb6` (Branch 2 landed).
Design: `~/workspace/backend-self-healing-design-20261127.md` §1b (Branch 3 line).
Parent task: a parked verdict for the sweep's link classification, wired to
host observations so a parked domain is retried on its active domain.

## What changed (production, 4 files)

- `Fast_Fetch/link_state.py`
  - New ledger status `parked` (`STATUS_PARKED`, `LEDGER_STATUSES` += parked).
    `classify()`: `error == "parked"` → `parked`, checked right after the
    `ok` branch — a parking page answers 200, so it would otherwise fall
    through to the `not_media` → active branch, which is exactly the lie
    this status removes. Generic HTML is NOT parked (design Q6, honest
    scope): only a true parking marker earns the verdict.
  - `PARKED_BODY_MARKERS` (14 true parking markers, a strict subset of
    `domain_resolver.PARKED_MARKERS` excluding `"just a moment"`,
    `"attention required"`, `"verify you are human"`) + `is_parked_body`
    (bytes or str, first 4096 bytes) returning `"parked-marker:<marker>"`
    or None, matching `domain_resolver._parked_reason`'s format.
  - `state_for("parked")` → `"unchecked"` (projection fold — the
    projection vocabulary is closed, pinned by `test_catalog_api.py`
    `_LINK_STATES` and `validate_catalog_full.py` `PROJECTION_STATES`).
    `projected_state` now delegates to `state_for` so both projection
    paths agree (sync via `state_for`, rebuild via
    `catalog_v2._link_state_of`, which folds out-of-vocabulary the same
    way). The ledger keeps the truth (`parked`); the projection only
    ever claims what it can score. Serving safety: `tiers.py` CASE falls
    to ELSE 5 for `parked` (below active, above dead); `api_v2.py`
    direct-play requires state in (active, unknown, unchecked) and the
    Branch 2 stage-2 bytes checker refuses HTML at serve time.
  - `_HOST_OK_STATUSES` unchanged (`active`, `expired` only) — a parked
    verdict counts as a host_health failure.
- `Fast_Fetch/host_registry.py`
  - `_load_observations` now also carries an `"html_observations"` key
    (same sidecar file as `record_redirect`, distinct counter).
  - `record_html_observation(host, sample_url, *, registry_path,
    observations_path, redirect_target, fetcher, search_fetcher)`:
    per-host counting, threshold 20 (same `REDIRECT_MOVE_THRESHOLD`,
    one constant for both signals). At threshold, and once per host
    (`evaluated_at` gating), discovery runs in order — redirect target,
    own `rotation_history` + `known_mirrors`, sibling entries sharing
    the service label, DDG search via `domain_resolver.ddg_search`
    (slug = base label, lazy import to avoid cycles) — and the first
    candidate `validate_host_move` passes is applied with evidence
    `parked-sweep`; when nothing validates, the host is marked a
    parked-candidate (`parked_candidate` + `parked_at` +
    `parked_reason`), never dead, never a guess. Never raises.
  - `_mark_host_parked` + `_discover_move_candidates` helpers; added to
    `__all__`.
- `Fast_Fetch/fast_probe.py`
  - `HostSession` now stores the body the last GET read in
    `last_get_body` (at most `GET_READ_CAP`, reset at the start of every
    `get` call) — the ranged-GET fallback reads a body the caller may
    need to inspect without paying for a second request. No return
    contract changed (the 4-tuple / 3-tuple stays pinned by tests).
  - `_probe_host` GET-fallback branch: on a real 200 answer, an HTML
    body is one HTML-instead-of-media observation (`_record_html`, via
    `record_html_observation`, with the GET chain's redirect target);
    a body carrying a parking marker earns verdict `parked`
    (`probe_error="parked"`, http_status 200). A parked verdict records
    NO redirect observation (a chain that landed on a parking page must
    not poison the redirect channel). Blocked (403/429) and transport
    answers never record an observation.
  - `html_observations` added to probe stats + `_merge_stats`.
  - `_is_html_body` helper (HTML openers in the first 512 bytes, or a
    `<title>`). Module docstring documents the parked channel and the
    known limitation: a parked domain that answers HEAD 200 cleanly is
    still missed (the HEAD chain cannot see a body).
- `Fast_Fetch/validate_catalog_full.py`
  - `LEDGER_STATUSES` += `"parked"`; the "no ledger-only status leaked
    into the projection" check now covers `'unverified'` AND `'parked'`
    (`cat_links.state` must never be `parked`).

## What did NOT change

- Resolver behaviour for real media; Branch 4 (site recheck wiring) and
  Branch 5 (expiry) untouched; no scraper-lane or panel-lane work.
- `_bank_hubcloud_siblings` still writes the LEDGER directly (not via
  `record_resolved_cache`) — residual from Branch 2, noted, not fixed.
- No `RESOLVER_VERSION` bump: the change only ADDS a verdict for
  parking pages, it does not change what passes as media.

## Tests (new, existing modules only — no new module, no CI change)

- `test_link_state.py::ParkedTests` (5 tests): parked verdict beats the
  200 active branch; `is_parked_body` fires on every marker and nothing
  else (video bytes, Cloudflare challenge, generic HTML, marker past
  4096 bytes); every parked marker is also in
  `domain_resolver.PARKED_MARKERS` (one vocabulary for parking; the
  three challenge phrases are pinned OUT); `state_for` folds parked to
  unchecked while the ledger keeps the truth; a parked row propagates
  through the ledger and projects `unchecked` with zero projected rows
  carrying `state = 'parked'`.
- `test_host_registry.py::TestRecordHtmlObservation` (9 tests): counting
  below threshold, 19 vs 20, threshold without candidate marks
  parked-candidate once (21st observation never re-evaluates),
  redirect-target validates and applies (evidence `parked-sweep`, old
  host marked parked), history candidate validates and applies,
  candidate failing validation is not applied, **vCloud fixture** (real
  `vcloud.beer` seed entry → 20 observations for `vcloud.lol` →
  `.my` validated and applied, old host parked, sibling untouched),
  DDG candidate validates and applies, IP host does not count, HTML
  and redirect counts are separate (19 + 19 reaches neither threshold).
- `test_fast_probe.py::ParkedFallbackTests` (8 tests): fake connection
  extended with a 4-tuple body form; `last_get_body` holds the GET body
  and the 4-tuple return is unchanged; parked page via GET fallback is
  parked not active (vcloud liar shape + plain-host 405 shape);
  generic HTML stays active and records an observation; 403 GET
  records no observation; video body records no observation; HEAD 200
  never sees the body (the known limitation, pinned so a future
  "GET every HEAD 200" has to reckon with the cost);
  `_merge_stats` sums `html_observations`.
- `test_link_sweep_budget.py`: `LEDGER_STATUSES` expected list +=
  `"parked"`.

## Local verification (this clone, before push)

- `test_link_state`: 40/40 OK. `test_host_registry`: 44/44 OK.
  `test_fast_probe`: 69/69 OK. `test_link_sweep_budget`: 15/15 OK.
- Full 229-suite gate: **230/230 PASS, 0 FAIL** (local, this clone;
  `~/workspace/selfheal-b3-gate.sh`, log `~/workspace/selfheal-b3-gate.log`).
  The run was interrupted once by a hatch service restart at 44/230 (all
  PASS); the runner is resumable and continued from module 45 under
  `setsid`. The two Branch 2 flakes (test_warp_proxy, test_api_v2) did
  not fire this time.
- `CONTINUITY.md` NOT edited: it is at 11,980 / 12,000 bytes, so any
  addition breaks `test_docs_invariants.py`. This file is the log.

## Status

NOT merged, NOT landed. Next: commit on `selfheal-b3-parked-verdict`,
push via the bundle + VM-clone route, Backend + Android CI green on
the exact tip, then the full gate (sweep stop → merge check + stable
counts → `.backup` → counts exact → integrity → `gh api` PATCH main →
live-tree ff + HEAD assert → service restart → live tests from the
Oracle VM on 8080 → sweep relaunch via wrapper → panel-push clone
sync → branch deleted).
