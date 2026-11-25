"""Resolve-time expiry banking for signed hosts (API-03).

`expires_at` is NULL on 99.97% of `download_sources` because only the
HubCloud path ever banked one. The resolve path already knows how to
*serve* an expiry — `resolve_live_source` re-resolves a row whose
`expires_in <= 0` and `resolved_cache` re-verifies from `expires_at` —
but with NULL everywhere those guards never fire, so a token that dies
in 20 minutes is served as fresh for the 6-hour temporary-link
heuristic. This module is the missing reader: given a freshly minted
final URL it derives the expiry from the URL itself, or from the
measured death window of its origin when the URL carries no parsable
expiry.

Two sources of truth, both measured, neither invented:

* Parsed from the signed URL — R2/S3 presigned (`X-Amz-Date` +
  `X-Amz-Expires`), `s` issue epoch + `e` lifetime, or an absolute
  `e` / `exp` / `expires` epoch. Evidence: `~/workspace/
  final-origin-map-20261123.md` (signed known-expiry 718,555 rows,
  16.3%, median 9 h) and the per-host rows cited below.
* Measured window — for token-bound origins whose URLs carry no
  parsable expiry at all. Exactly one origin qualifies today:
  Google usercontent (`video-downloads.googleusercontent.com`, and
  `lh3.googleusercontent.com/pw/`), whose fresh URLs die in under
  ~20 minutes (gdflix B8, confirmed twice; hubcloud_10g and fastdl
  aged URLs 400). The banked window is 900 s — 75% of the measured
  death bound — so a row is re-resolved well before the token dies.

Deliberately NOT covered here, with the reason:

* MEGA — the server banks no MEGA URL at all, by design (Phase C
  decision 2026-11-20): `verify_mega_live` keeps none of the g-URL
  and the app mints it per press from node + key, so the <75 min
  g-URL death is why it stays per-press, not something to bank.
* Bare-`e` lifetimes (`e=14400` with no `s`) — that shape belongs
  to the API-10 branch (`host_resolvers.duration_param_expiry`,
  voe/stream unwrappers), which already feeds `expires_in` through
  the unwrapper verdict. This module never reads a bare `e`.
* Hosts with no measured death and no parsable expiry (gofile,
  uploadflix, mediafire, vidlink, moviebox — a 29 h-old URL still
  plays — vidout, filepress) return None: no number is banked
  rather than a made-up one, and the existing serve-side probe
  still guards those rows.

Stdlib only. Pure functions: no DB, no network, no imports from
`fast_fetch` or `host_resolvers`, so either side can use it without
an import cycle. Never raises on a malformed URL — an unreadable
expiry is None, not an exception in the press path.
"""

from __future__ import annotations

import calendar
import re
import time
import urllib.parse

#: ttl_source written when the expiry was parsed off the signed URL.
TTL_SOURCE_SIGNED = "signed_params"
#: ttl_source written when the expiry is the measured window for the origin.
TTL_SOURCE_WINDOW = "measured_window"

#: Conservative banked window for a Google usercontent video URL:
#: fresh URLs die in under ~20 min (gdflix B8, confirmed twice), so
#: 900 s (75% of the death bound) re-resolves well before the token dies.
GOOGLE_VIDEO_WINDOW_SECONDS = 900

#: A parsed expiry further than this from now is garbage, not a
#: lifetime — no real window in the final-origin map exceeds 36 h,
#: and the longest-lived transfer (WeTransfer, 7 d) is far inside it.
#: Stillborn tokens a few days past parse fine inside this band, so
#: the serve guard (expires_in <= 0 -> re-resolve) sees them.
_SANITY_DAYS = 366

_EPOCH_RE = re.compile(r"\d{9,11}")
_LIFETIME_RE = re.compile(r"\d{1,7}")


def _query(url: str) -> dict:
    try:
        return urllib.parse.parse_qs(urllib.parse.urlsplit(url or "").query)
    except Exception:
        return {}


def _first(query: dict, *names: str) -> str:
    for name in names:
        values = query.get(name) or []
        if values:
            return values[0]
    return ""


def parse_signed_expiry(url: str, *, now: float | None = None
                        ) -> tuple[int | None, str | None]:
    """(expires_at epoch, basis) parsed off a signed final URL.

    Tries, in order: R2/S3 presigned (`X-Amz-Date` + `X-Amz-Expires`),
    `s` issue epoch + `e` lifetime, absolute `e` / `exp` / `expires`
    epoch. Returns (None, None) when the URL carries no parsable
    signed lifetime — including permanent URLs and token-bound URLs
    whose token is opaque. A stillborn token (expiry already past)
    still parses: the truth is banked and the serve guard re-resolves
    it, instead of NULL pretending the row is fresh.
    """
    if not url or not str(url).startswith(("http://", "https://")):
        return None, None
    now_ts = time.time() if now is None else float(now)
    query = _query(str(url))

    # R2/S3 presigned: X-Amz-Date is UTC (YYYYMMDDTHHMMSSZ) and
    # X-Amz-Expires is a LIFETIME in seconds — the expiry is date +
    # lifetime. Parsed with calendar.timegm, NEVER time.mktime:
    # mktime reads the struct as LOCAL time and shifts the answer by
    # the UTC offset (same rule as fast_fetch._r2_presigned_expiry).
    date_raw = _first(query, "X-Amz-Date")
    lifetime_raw = _first(query, "X-Amz-Expires")
    if date_raw and lifetime_raw.isdigit():
        try:
            struct = time.strptime(date_raw, "%Y%m%dT%H%M%SZ")
            expires_at = int(calendar.timegm(struct)) + int(lifetime_raw)
        except (ValueError, OverflowError):
            expires_at = None
        if expires_at is not None:
            return _sanitise(expires_at, now_ts, "r2_presigned")

    # s + e: s is the issue epoch, e the lifetime in seconds
    # (vidmoly 43200, vidhide 129600, vidrift titan ~10000).
    start = _first(query, "s")
    lifetime = _first(query, "e")
    if _EPOCH_RE.fullmatch(start or "") and _LIFETIME_RE.fullmatch(lifetime or ""):
        return _sanitise(int(start) + int(lifetime), now_ts, "s_plus_e")

    # Absolute epoch in e / exp / expires (mixdrop e, greenmotors exp
    # = 14,400 s verified, streamtape e). A bare lifetime in `e` is
    # NOT read here — that shape is API-10's duration_param_expiry.
    raw = _first(query, "e", "exp", "expires")
    if _EPOCH_RE.fullmatch(raw or ""):
        return _sanitise(int(raw), now_ts, "absolute_epoch")

    return None, None


def _sanitise(expires_at: int, now_ts: float, basis: str
              ) -> tuple[int | None, str | None]:
    """Reject garbage epochs; keep real windows and real stillborns."""
    span = _SANITY_DAYS * 86400
    if not (now_ts - span <= expires_at <= now_ts + span):
        return None, None
    return expires_at, basis


def conservative_window_seconds(url: str) -> tuple[int | None, str | None]:
    """(seconds, basis) for a token-bound origin with no parsable expiry.

    Only Google usercontent video URLs qualify: `video-downloads`
    (gdflix / fastdl / hubcloud_10g / HubCDN Google) and `lh3` `/pw/`
    (HubCloud gpdl click final). `drive.usercontent.google.com` is
    deliberately NOT here — its file id never expires, only the
    confirm step is per-session — and neither is ZipDisk's
    `storage.googleapis.com` host. Anything else returns (None, None).
    """
    if not url or not str(url).startswith(("http://", "https://")):
        return None, None
    try:
        parts = urllib.parse.urlsplit(str(url))
    except Exception:
        return None, None
    host = (parts.hostname or "").lower()
    if host == "video-downloads.googleusercontent.com":
        return GOOGLE_VIDEO_WINDOW_SECONDS, "measured_window:googleusercontent"
    if host == "lh3.googleusercontent.com" and (parts.path or "").startswith("/pw/"):
        return GOOGLE_VIDEO_WINDOW_SECONDS, "measured_window:googleusercontent"
    return None, None


def derive_expiry(url: str, *, now: float | None = None) -> dict | None:
    """The expiry to bank for a freshly minted final URL, or None.

    Returns {"expires_at": int, "ttl_seconds": float, "basis": str,
    "observed_via": str} — `observed_via` is the `ttl_source` value
    the ledger records. Parsed lifetimes win over the measured
    window; a URL with neither banks nothing (None), never a
    guessed number. A stillborn parsed expiry is returned as-is:
    banking the past instant is what makes the serve guard
    re-resolve the row on its next press.
    """
    now_ts = time.time() if now is None else float(now)
    expires_at, basis = parse_signed_expiry(url, now=now_ts)
    if expires_at is not None:
        return {
            "expires_at": expires_at,
            "ttl_seconds": expires_at - now_ts,
            "basis": basis,
            "observed_via": TTL_SOURCE_SIGNED,
        }
    seconds, basis = conservative_window_seconds(url)
    if seconds is not None:
        return {
            "expires_at": int(now_ts) + int(seconds),
            "ttl_seconds": float(seconds),
            "basis": basis,
            "observed_via": TTL_SOURCE_WINDOW,
        }
    return None
