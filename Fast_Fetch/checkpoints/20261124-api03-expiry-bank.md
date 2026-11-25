# API-03 — bank expiry at resolve time for every signed host (2026-11-24)

Branch `muse/phase4-api03-expiry-bank-20261124` from `ef15f2fc`.
Phase 4 checkup item API-03 [P0]: `expires_at` NULL on 99.97% of
`download_sources` (db-read §2: 4,420,259 / 4,421,713); only the
HubCloud path banked one (FSL true window 18,000 s / 5 h, parse fixed
on main). Final-origin map: signed known-expiry 718,555 rows (16.3%),
median 9 h.

## What was wrong

The serve side already knew how to use an expiry —
`resolve_live_source` re-resolves a row whose `expires_in <= 0`,
and `resolved_cache` re-verifies from `expires_at` — but with NULL
everywhere those guards never fired. A token that dies in 20 minutes
was served as fresh for the 6-hour temporary-link heuristic. The
resolve path banked an expiry only when (a) the unwrapper's verdict
carried `expires_in`, or (b) the fresh URL's response headers declared
max-age/Expires (signed CDNs almost never do). Every branch that
minted a URL without a verdict — gdflix, fastdl, hubcdn, greenmotors,
vcloud, mega — and every HubCloud non-R2 winner (10Gbps Google URL)
fell through to `record_link_expiry(clear=True)`.

## Fix

- New `Fast_Fetch/expiry_bank.py` (stdlib only, pure, no DB/network):
  `parse_signed_expiry` reads R2/S3 presigned (`X-Amz-Date` +
  `X-Amz-Expires`, `calendar.timegm` — never `mktime`, same rule as
  `fast_fetch._r2_presigned_expiry`), `s` issue epoch + `e` lifetime,
  absolute `e` / `exp` / `expires` epoch; `conservative_window_seconds`
  banks 900 s for Google usercontent video URLs only
  (`video-downloads.googleusercontent.com`, `lh3…/pw/`) — fresh URLs
  die in under ~20 min (gdflix B8, confirmed twice), so 900 s is 75%
  of the measured death bound. `derive_expiry` returns the parse
  first, the window second, None otherwise. A stillborn parsed expiry
  is returned as-is: banking the past instant is what makes the serve
  guard re-resolve the row.
- `fast_fetch.py`: `_derive_resolve_expiry` helper + three call sites
  in `resolve_live_source` / `_bank_hubcloud_siblings` — the
  non-HubCloud banking arm (after headers + verdict miss, before the
  `no_directive` clear), the HubCloud arm (non-R2 winner), and the
  sibling bank (hubcloud_10g sibling). An unreachable probe still
  leaves the old measurement alone; permanent hosts derive nothing
  and keep the clear. No change to `resolved_cache.py` or `viewer.py`:
  the JIT answer already carries `**_expiry_fields(src)`, so the cache
  inherits the banked expiry with no extra wiring.
- Hosts now banking expiry: R2/S3-signed (FSL, google_cdn — parsed),
  s+e hosts (vidmoly 12 h, vidhide/streamhg 36 h, vidrift titan ~2.8 h),
  absolute-epoch hosts (mixdrop 6 h, greenmotors 4 h — its `exp` was
  parsed by the unwrapper but dropped by the branch; now read off the
  URL — streamtape 19.4 h), Google usercontent (gdflix, fastdl,
  hubcloud_10g, HubCDN Google twin — 900 s window).
- Deliberately NOT banked: MEGA (the server banks no MEGA URL at all,
  by design — `verify_mega_live` keeps none of the g-URL, the app
  mints it per press; the <75 min death is why it stays per-press);
  bare-`e` lifetimes (API-10's `duration_param_expiry`, voe/stream —
  already flows via the verdict); gofile / uploadflix / mediafire /
  vidlink / moviebox (29 h-old URL still plays) / vidout / filepress
  (no measured death, no parsable expiry — no number invented).

## Orthogonality

- API-10 (`muse/resolver-expiry-voe-stream-20261123`, tip `deb91ed8`):
  adds `duration_param_expiry` + fallback in `unwrap_packer_hls` /
  `unwrap_voe` only. This branch touches neither function nor either
  call site, and never reads a bare `e` lifetime.
- API-01 (`muse/phase4-api01-ipbound-20261124`, tip `364988aa`):
  touches `host_classifier.py`, `resolved_cache.py`, `viewer.py` +
  tests. This branch touches none of those files.
- `resolver-bank-defects` (tip `2961378b`): touches `fast_fetch.py`
  + `serve_verify.py` + `test_bank_defects.py`. Shared file
  `fast_fetch.py` only, different hunks (bank-defects: negative-cache
  / stillborn-floor paths; this branch: the resolve-time expiry arms).
  No stillborn floor here — a stillborn parse banks past, the serve
  guard re-resolves.
- API-05 (static-URL probe, `api_v2.py`): untouched.

## Tests

New `Fast_Fetch/test_expiry_bank.py`, 36 tests, offline (temp SQLite,
stubbed unwrappers + gate probe): 29 pure parse/window/derive tests
(R2 18000/28800 exact, UTC-not-local, s+e 43200/129600, absolute e /
exp / expires, bare-`e` refused, stillborn parses, absurd epoch
rejected, drive.usercontent / ZipDisk / mediafire / uploadflix /
mega / gofile excluded) + 7 resolve tests (gdflix Google → now+900
`measured_window`; greenmotors exp → exact 14400 `signed_params`;
HubCDN Google → now+900 with `is_temporary` still 0; pixeldrain →
NULL; past-expiry row re-resolves with a fresh window; future-expiry
row serves cached with no unwrap; HubCloud sibling bank: FSL keeps
parsed 18000, 10Gbps banks 900, PixelServer stays NULL). CI step
added (gate 162 → 163 suites). Negative control: on unfixed
`fast_fetch.py` exactly the 5 banking tests fail/error (3 FAIL + 2
TypeError on the NULL expiry); the 2 controls + 29 pure tests pass.

Local: test_expiry_bank 36/36, test_database 242/242 (1 skipped),
test_hubcloud_oneround 18/18, test_resolved_cache 95/95,
test_shared_cache 33/33, test_host_classifier 77/77,
test_host_resolvers 314/314, test_docs_invariants OK. Full gate is
CI's (162 → 163 suites).

## Follow-up owed

- Gate-time rebase note: this branch rotates the same two oldest
  CONTINUITY bullets (One Piece, Registry §2) as API-01's branch —
  the gate runner must reconcile CONTINUITY.md (checkpoint + rotation
  files have distinct names and do not collide).
- The pool/warm path (`backfill_resolved_cache.py`) still banks no
  expiry — that is API-16's resume-pool-pass territory, not this
  branch. A backfill of `expires_at` from stored signed URLs (R2 /
  s+e / absolute-epoch shapes are all re-derivable offline from the
  URL alone) is a separate, safe batch — no re-resolve needed.
- Egress-portability probe for the Google 900 s window: the <20 min
  death was measured from Oracle + Tor; if a client egress keeps the
  token alive longer, the window is still safe (conservative), but a
  shorter death on some egress would need re-measuring.

NOT merged — parent merges after the gate; Backend + Android CI on
the pushed tip owed at handoff.
