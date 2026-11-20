#!/usr/bin/env python3
"""Simkl metadata extraction (pure — no network, no key).

Simkl is the Phase-2 metadata fallback: site metadata first, TMDb next,
Simkl fills what is still missing. Probe (2026-11-20, 100 catalog works
with an IMDb id but no rating): Simkl found 86/100 — ratings on 95% of
found, poster 98%, overview 99%, genres 100%, year 98%, runtime 92%.
TMDb found 26/68 completed calls on the same 100, and TMDb calls from
the Oracle VM fail at the network level about half the time.

The summary endpoints (``/movies/:id``, ``/tv/:id``, ``/anime/:id``, by
IMDb id or Simkl id) return one rich object — but some lookups return a
LIST of matches instead of one object; the first dict element is the
match (the probe crashed on this once, before the fix). Cast is NOT in
the summary shape, so Simkl never fills cast; the movie payload does
carry a ``director`` string, which is NOT wired here (the backfill's
Simkl field list is title/year/overview/genres/rating/poster/fanart/
runtime — see the checkpoint for the director follow-up).

Image URLs (verified live 2026-11-20: poster ``_m.jpg`` 200 with real
JPEG bytes, fanart ``_medium.jpg`` 200 with real JPEG bytes; ``_l.jpg``
and ``_s.jpg`` 404, fanart ``_m.jpg`` 301s to an alert placeholder):

    poster: https://simkl.in/posters/{poster}_m.jpg        (340px wide)
    fanart: https://simkl.in/fanart/{fanart}_medium.jpg   (1920x1080)

Ratings: ``ratings.imdb.rating`` is the real IMDb score and wins; when
it is absent the Simkl score (``ratings.simkl.rating``) is used instead
and is recorded as source ``simkl`` — a Simkl score is NOT an IMDb one,
the same honesty rule as TMDb's vote_average. Both are normalised to
one-decimal strings by the caller (``metadata_backfill.normalize_rating``).

Never invents a field: a missing value stays absent.
"""
from __future__ import annotations

import re
import urllib.parse

SIMKL_API_ROOT = "https://api.simkl.com"
SIMKL_POSTER_ROOT = "https://simkl.in/posters"
SIMKL_FANART_ROOT = "https://simkl.in/fanart"

#: The app registered on Simkl ("Radd Stream", AUTH V2, 2026-11-20).
APP_NAME = "radd-stream"
APP_VERSION = "1.0"

#: Summary-endpoint segment per work kind.
ENDPOINT_BY_KIND = {"movie": "movies", "tv": "tv", "anime": "anime"}

_IMDB_RE = re.compile(r"^tt\d{6,10}$")


def endpoint_for(*, series: bool = False, anime: bool = False) -> str:
    """The Simkl summary-endpoint segment for this work."""
    if anime:
        return "anime"
    return "tv" if series else "movies"


def lookup_url(key: str, endpoint: str, imdb_id: str) -> str:
    """A catalog lookup URL. Carries the key as a query parameter (the
    probe proved this form) plus the ``simkl-api-key`` header the caller
    sets — never log this URL, it holds the key."""
    query = urllib.parse.urlencode({
        "client_id": key,
        "app-name": APP_NAME,
        "app-version": APP_VERSION,
    })
    return "%s/%s/%s?%s" % (
        SIMKL_API_ROOT, endpoint, urllib.parse.quote(imdb_id), query)


def normalize_payload(payload):
    """One Simkl record as a dict, or None.

    Most lookups return one object; some return a list of matches, in
    which case the first dict element is the match. Anything else (an
    empty list, a scalar, a list with no dict) is not a record."""
    if isinstance(payload, dict):
        return payload
    if isinstance(payload, list):
        for item in payload:
            if isinstance(item, dict):
                return item
    return None


def is_list_response(payload) -> bool:
    """True when the API answered with a list of matches (the shape the
    probe crashed on before it learned to take the first element)."""
    return isinstance(payload, list)


def _image_url(root: str, path, suffix: str) -> str | None:
    text = str(path or "").strip().strip("/")
    if not text:
        return None
    if text.startswith(("http://", "https://")):
        return text
    if any(ch.isspace() for ch in text):
        return None
    return "%s/%s%s" % (root, text, suffix)


def poster_url(path) -> str | None:
    """The medium poster URL for a Simkl ``poster`` path, or None."""
    return _image_url(SIMKL_POSTER_ROOT, path, "_m.jpg")


def fanart_url(path) -> str | None:
    """The 1920x1080 fanart URL for a Simkl ``fanart`` path, or None."""
    return _image_url(SIMKL_FANART_ROOT, path, "_medium.jpg")


def best_rating(ratings) -> tuple:
    """``(value, kind)`` — the IMDb rating when Simkl has one, else the
    Simkl score; ``(None, None)`` when neither is a real score. A 0 or
    out-of-range value is not a score, it is a missing one."""
    if not isinstance(ratings, dict):
        return None, None
    for kind in ("imdb", "simkl"):
        entry = ratings.get(kind)
        if not isinstance(entry, dict):
            continue
        value = entry.get("rating")
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            continue
        if 0.0 < float(value) <= 10.0:
            return value, kind
    return None, None


def _plausible_year(value) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        year = int(value)
    except (TypeError, ValueError):
        return None
    return year if 1900 <= year <= 2100 else None


def _runtime_minutes(value) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        minutes = int(value)
    except (TypeError, ValueError):
        return None
    return minutes if minutes > 0 else None


def extract_metadata(payload) -> dict:
    """The fillable fields out of one Simkl summary payload.

    Returns only what the payload actually carries — a missing field is
    absent, never invented. ``runtime`` is minutes (a movie's length, a
    show's per-episode length); the backfill has no runtime column, so
    callers that cannot store it report it and move on. ``title`` is
    returned for logging/comparison, never stored by the backfill."""
    data = normalize_payload(payload)
    if data is None:
        return {}
    out: dict = {}
    title = str(data.get("title") or "").strip()
    if title:
        out["title"] = title
    year = _plausible_year(data.get("year"))
    if year is not None:
        out["year"] = year
    overview = str(data.get("overview") or "").strip()
    if overview:
        out["overview"] = overview
    genres = [str(item).strip() for item in data.get("genres") or []
              if str(item).strip()]
    if genres:
        out["genres"] = genres
    rating, kind = best_rating(data.get("ratings"))
    if rating is not None:
        out["rating"] = rating
        out["rating_kind"] = kind
    poster = poster_url(data.get("poster"))
    if poster:
        out["poster"] = poster
    fanart = fanart_url(data.get("fanart"))
    if fanart:
        out["fanart"] = fanart
    runtime = _runtime_minutes(data.get("runtime"))
    if runtime is not None:
        out["runtime"] = runtime
    ids = data.get("ids") or {}
    if isinstance(ids, dict):
        tmdb_id = str(ids.get("tmdb") or "").strip()
        if tmdb_id:
            out["tmdb_id"] = tmdb_id
        imdb_id = str(ids.get("imdb") or "").strip()
        if _IMDB_RE.fullmatch(imdb_id):
            out["imdb_id"] = imdb_id
    return out
