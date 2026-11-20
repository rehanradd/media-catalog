#!/usr/bin/env python3
"""Fill missing post metadata (rating, synopsis, poster, cast, director, year,
ids, genres) from site siblings first, then IMDb datasets, then metadata APIs.

Why this exists
--------------
The catalog is missing most of its metadata: 134,201 of 197,084 works
(68%) have no rating, 153,941 of 225,585 posts (68%) have no cast, 201,841
(89%) have no director, 60,834 (27%) have no synopsis, 15,155 (7%) have no
poster. The per-site importers capture what the source pages carry, and most
pages carry little (filmyzilla: 0% rating / 0% synopsis / 0% cast;
moviesmela: 0% / 0% / 7%; 9xmovies: 0% / 33% / 56%). The projection takes
the first non-empty value per field from a work's posts, so filling POSTS
is what fills WORKS — there is no work-level write here.

Order of sources (site metadata is primary, per the standing order)
--------------------------------------------------------------------
1. SITE PASS (no network): copy the best non-empty value across a work's
   sibling posts (same canonical_key). Free, and worth ~2,900 post ratings,
   ~3,200 cast rows, ~1,500 directors, ~1,400 synopses, ~450 posters.
2. IMDb DATASETS (no key, one download): ``title.ratings.tsv.gz`` (8.6 MB)
   joined on ``imdb_id`` rates every post that carries an IMDb id — 32,015
   posts / 27,858 works in one pass, no API calls. ``title.basics`` adds
   year/runtime/genres; ``title.crew`` adds directors. Non-commercial only:
   the datasets may not be republished or resold as a movie database.
3. API PASS (keyless first, keyed when configured): one representative post
   per work is looked up, then the site pass propagates the win to its
   siblings. Per-field priority:
   * rating: OMDb (real IMDb rating) > Cinemeta (imdbRating) > MDBList
     (aggregated) > TMDb vote_average (a real TMDb score, NOT an IMDb one —
     recorded as source ``tmdb`` in the report and in a SQL comment) >
     Simkl (IMDb rating when it has one, else its own score — recorded
     as source ``simkl``) > Trakt > AniList / Kitsu (anime only,
     averageScore / 100)
   * synopsis: TMDb overview > Simkl overview > Cinemeta > TVMaze summary >
     AniList / Kitsu
   * poster: TMDb poster_path > Simkl poster > Cinemeta > Fanart.tv >
     AniList / Kitsu cover
   * cast: TMDb credits (top 8) > TVMaze cast > AniList characters >
     Cinemeta cast
   * director: TMDb credits (job=Director) > Simkl > OMDb > Cinemeta > IMDb crew
   * year / ids: TMDb (release_date / first_air_date, external_ids) >
     Simkl year / ids.tmdb > Cinemeta year > TVMaze premiered > AniList
     startDate

Safety (the law for this module)
--------------------------------
* NEVER writes to the input database. It is opened read-only (``mode=ro``)
  and the output is a staging SQL file + a JSON report. ``--apply-to`` writes
  the staging SQL into a COPY made with the sqlite3 backup API — and refuses
  when the copy path equals the input path or the live catalog path.
* Fills ONLY empty fields (NULL / '' / '[]' / 'N/A' / 'Unknown' / 0 rating),
  unless ``--force`` re-resolves filled rows too. Never overwrites a stored
  value with a worse one, never invents one: a field with no source stays
  empty, and every filled value carries its source in the report and in a
  ``-- source:`` comment on its UPDATE line.
* Skips ``is_18_plus=1`` rows entirely, same as ``backfill_tmdb.py``.
* Title searches require a guard: the normalized title must match and the
  year must agree within ±1 (when both are known). An IMDb-id lookup needs no
  title guard — the id IS the identity.
* Same title, same year, two films (Indian Marco 2022 vs Hollywood Marco
  2022) are disambiguated, never guessed: a title search that matches two
  or more candidates prefers the one whose language/country matches the
  post's stored language/country, then one sharing a cast member or
  director; still tied, nothing is filled and the post goes on an
  ambiguous-review list in the report. An IMDb/TMDb-id lookup bypasses
  this — the id IS the identity. A single-payload source (OMDb, TVMaze)
  is also vetoed when the post affirmatively claims a specific
  non-English language (or non-USA country) and the payload's language
  is known and different — the post's bare 'English' never vetoes, because
  rows predating the language migration carry it as a default.
* Streaming output (v2): fills are written to the staging SQL in batches
  of ``--batch-size`` posts (default 100) as they are found, with a JSON
  progress file updated after every batch — a killed run loses at most one
  batch. ``--resume`` reads the staging SQL (and the progress file when
  present) and skips the posts already written. A staging file without a
  closing ``COMMIT;`` is an interrupted run: resume it, never apply it.
* Live fill (detail open): ``fill_one`` fills ONE work on demand, for the
  ``POST /api/v2/enrich`` route — same four passes at group scope, same
  disambiguation, same guards. It writes through the caller's connection
  (the api_v2 request session commits); it never opens a connection and
  never commits. The batch's per-run metered cap becomes a persistent
  per-day ``DailyBudget`` sidecar, because a live server has no "run".
* Ratings are stored as one-decimal strings (``"7.5"``), exactly the shape
  ``backfill_tmdb.normalize_rating`` writes and the projection's
  ``valid_rating`` accepts. A 0–100 score (AniList / Kitsu) is divided by 10.
* Stdlib only. No ``import requests`` — ``urllib`` throughout.

Sources
-------
================  ============  ====================  ========================
source             key needed    free-tier limit       commercial use
================  ============  ====================  ========================
cinemeta           none          unpublished, gentle   addon data, permissive
tvmaze             none          ~20 calls / 10 s      CC BY-SA 4.0 (credit)
anilist            none          ~90 req / min         read terms first
kitsu              none          fair use, 429-gated   fair use
jikan              none          3 req/s, 60 req/min   unofficial wrapper
imdb-datasets      none          daily TSV download    NON-COMMERCIAL ONLY
tmdb               TMDB_API_KEY  ~40 req/s (soft)      free key NON-COMMERCIAL
                   or BEARER     cache max 6 months    (agreement needed once
                                                        the app earns revenue)
simkl              SIMKL_CLIENT  gentle, ~3 req/s      free key, app
                   _ID                               registration
omdb               OMDB_API_KEY  1,000 req/day free    content CC BY-NC 4.0
                                                        (Patreon tiers lift it)
mdblist            MDBLIST_KEY   1,000 req/day free    aggregates other APIs
trakt              TRAKT_ID      ~1,000 calls / 5 min  approval for commercial
fanart             FANART_KEY    ~2 req/s, unlimited   artwork only
================  ============  ====================  ========================

Keys come from the environment only (never a file, never a log):
``TMDB_API_KEY`` / ``TMDB_BEARER_TOKEN``, ``OMDB_API_KEY``,
``MDBLIST_API_KEY``, ``TRAKT_CLIENT_ID``, ``FANART_API_KEY``,
``SIMKL_CLIENT_ID``.
A source with no key is skipped and named in the report — the run still
fills everything the keyless sources can reach.

Usage
-----
    # Plan only: staging SQL + JSON report, no database is touched.
    python3 -S Fast_Fetch/metadata_backfill.py --db /path/to/catalog.db

    # Add the bulk IMDb ratings pass (download the TSVs first, once):
    python3 -S Fast_Fetch/metadata_backfill.py --db catalog.db \\
        --imdb-datasets ~/imdb-data --fetch-imdb-datasets

    # Materialise a copy with the staging SQL applied (gate-time shape):
    python3 -S Fast_Fetch/metadata_backfill.py --db catalog.db \\
        --apply-to /tmp/catalog-filled.db

    # Dry run: upstream lookups happen, nothing is written at all.
    python3 -S Fast_Fetch/metadata_backfill.py --db catalog.db --dry-run

    # Streamed (v2): write every 100 filled posts as they are found, with
    # a JSON progress file updated after every batch — a killed run loses
    # at most one batch, and the count is visible while it runs.
    python3 -S Fast_Fetch/metadata_backfill.py --db catalog.db --batch-size 100

    # Resume a killed run where it left off (reads the staging SQL and the
    # progress file, skips the posts already written).
    python3 -S Fast_Fetch/metadata_backfill.py --db catalog.db --resume

    # Scope a run: only ratings, only series, only the first 500 works.
    python3 -S Fast_Fetch/metadata_backfill.py --db catalog.db \\
        --only rating --limit 500
"""

from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import json
import os
import re
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import config  # noqa: E402  (repo convention: DB_PATH / staging_db_path)
import simkl_enricher  # noqa: E402  (Simkl Phase-2 fallback extraction)

# ── constants ──────────────────────────────────────────────────────────────

FIELDS = (
    "rating", "synopsis", "poster", "backdrop", "cast", "director",
    "year", "tmdb_id", "imdb_id", "genres",
)

# posts column for each fillable field.
FIELD_COLUMN = {
    "rating": "imdb_rating",
    "synopsis": "synopsis",
    "poster": "poster_url",
    "backdrop": "backdrop_urls",
    "cast": "cast_names",
    "director": "director",
    "year": "year",
    "tmdb_id": "tmdb_id",
    "imdb_id": "imdb_id",
    "genres": "genres",
}

# Values that mean "empty" in a text column. "[]" is real: cast_names stores
# the literal string "[]" for an empty cast list.
PLACEHOLDERS = {"", "[]", "{}", "n/a", "na", "none", "null", "unknown", "unk"}

# The live catalog path: --apply-to refuses to write here, ever.
LIVE_CATALOG = "/home/ubuntu/radd-stream-data/merged_catalog_20261025.db"

TMDB_IMAGE_ROOT = "https://image.tmdb.org/t/p/w500"
CINEMETA_ROOT = "https://v3-cinemeta.strem.io"
IMDB_DATASETS_ROOT = "https://datasets.imdbws.com"

DEFAULT_TIMEOUT = 15.0
DEFAULT_WORKERS = 4
DEFAULT_CACHE_TTL = 30 * 24 * 3600  # 30 days; TMDb allows caching up to 6 months.
DEFAULT_BATCH_SIZE = 100  # streaming: posts written per staging-SQL batch.

# Per-field source order for the API pass (keyless sources first where they
# can answer; a source that returns a field stops the chain for that field).
FIELD_SOURCES = {
    "rating": ("omdb", "cinemeta", "mdblist", "tmdb", "simkl", "trakt", "anilist", "kitsu"),
    "synopsis": ("tmdb", "simkl", "cinemeta", "tvmaze", "anilist", "kitsu", "jikan"),
    "poster": ("tmdb", "simkl", "cinemeta", "fanart", "anilist", "kitsu", "jikan"),
    "backdrop": ("tmdb", "simkl", "cinemeta", "fanart"),
    "cast": ("tmdb", "tvmaze", "anilist", "cinemeta"),
    "director": ("tmdb", "simkl", "omdb", "cinemeta"),
    "year": ("tmdb", "simkl", "cinemeta", "tvmaze", "anilist", "kitsu"),
    "tmdb_id": ("tmdb", "simkl"),
    "imdb_id": ("tmdb", "omdb", "mdblist", "trakt"),
    "genres": ("tmdb", "simkl", "cinemeta", "anilist", "kitsu"),
}

# Anime-only sources: consulted only when the work looks animated.
ANIME_SOURCES = {"anilist", "kitsu", "jikan"}

# Free-tier daily call budget per run for the metered keyed sources. The
# operator restarts the run the next day; the report counts what was spent.
DAILY_CALL_BUDGET = {"omdb": 950, "mdblist": 950}


# ── pure helpers ───────────────────────────────────────────────────────────

def normalize_rating(value) -> str | None:
    """A one-decimal rating string (``"7.5"``), or None for anything that is
    not a score. Same shape as ``backfill_tmdb.normalize_rating``: the
    projection and the app read this column as a decimal string, so every
    source is reduced to it here. A 0–100 score is divided by 10 first
    (AniList / Kitsu ``averageScore``); ``"7.8/10"`` and ``"75/100"`` suffixes
    are tolerated; 0 means unrated, never a real score."""
    if value is None or isinstance(value, bool):
        return None
    text = str(value).strip()
    if "/" in text:
        head, _, tail = text.partition("/")
        try:
            denom = float(tail.strip())
        except ValueError:
            denom = 10.0
        try:
            numerator = float(head.strip())
        except ValueError:
            return None
        if denom == 100.0:
            rating = numerator / 10.0
        elif denom == 10.0:
            rating = numerator
        else:
            return None
    else:
        try:
            rating = float(text)
        except (TypeError, ValueError):
            return None
        if rating > 10.0:  # a bare 0–100 score
            rating = rating / 10.0
    if not 0.0 < rating <= 10.0:
        return None
    return f"{rating:.1f}"


def parse_year(value) -> int | None:
    """A plausible release year from ``2018``, ``"2018-03-02"``, ``"2018"``."""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        year = value
    else:
        match = re.search(r"\b(19\d{2}|20\d{2})\b", str(value))
        if not match:
            return None
        year = int(match.group(1))
    if 1900 <= year <= 2100:
        return year
    return None


def clean_text(value, *, max_len: int = 2000) -> str | None:
    """Stripped, whitespace-collapsed text, or None for a placeholder."""
    if value is None:
        return None
    text = re.sub(r"\s+", " ", str(value)).strip()
    if not text or text.lower() in PLACEHOLDERS:
        return None
    return text[:max_len]


def is_empty_value(value) -> bool:
    """True when a stored column value means 'no data here'."""
    if value is None:
        return True
    if isinstance(value, (int, float)):
        return value == 0
    text = str(value).strip()
    return (not text) or text.lower() in PLACEHOLDERS


def split_names(value) -> list[str]:
    """Cast / director names out of a stored string or a JSON list."""
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        raw = [str(item) for item in value]
    else:
        text = str(value).strip()
        if not text or text.lower() in PLACEHOLDERS:
            return []
        if text.startswith("["):
            try:
                parsed = json.loads(text)
            except ValueError:
                parsed = None
            if isinstance(parsed, list):
                raw = [str(item) for item in parsed]
            else:
                raw = re.split(r"[,/|;]", text)
        else:
            raw = re.split(r"[,/|;]", text)
    names: list[str] = []
    for item in raw:
        name = re.sub(r"\s+", " ", item).strip(" \t\r\n\"'")
        if name and name.lower() not in PLACEHOLDERS and name not in names:
            names.append(name)
    return names


def join_names(names, *, limit: int = 10) -> str | None:
    """The stored shape for cast_names / director: comma-separated names."""
    cleaned = [name for name in (names or []) if name][:limit]
    return ", ".join(cleaned) if cleaned else None


def parse_genres(value) -> list[str]:
    """Genre tags out of a stored JSON list string."""
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        return [str(item) for item in value]
    text = str(value).strip()
    if not text or text.lower() in PLACEHOLDERS:
        return []
    try:
        parsed = json.loads(text)
    except ValueError:
        return [text] if text else []
    if isinstance(parsed, list):
        return [str(item) for item in parsed]
    return []


def format_genres(names) -> str | None:
    """The stored shape for posts.genres: a JSON list string."""
    cleaned = [name for name in (names or []) if name]
    return json.dumps(cleaned, ensure_ascii=False) if cleaned else None


_NOISE_PHRASES = (
    "hindi dubbed", "hindi-dubbed", "dual audio", "dual-audio",
    "english dubbed", "web-dl", "web dl",
)

_NOISE_WORDS = (
    "uncut", "unrated", "hdrip", "webrip", "bluray", "hdts", "hdcam",
    "hevc", "x264", "x265", "480p", "720p", "1080p", "2160p", "4k",
)


def normalize_title(title: str) -> str:
    """A comparable title: lowercased, punctuation stripped, release-noise
    words dropped, year suffix dropped. Two titles that differ only by a
    site's ``Hindi Dubbed`` suffix compare equal after this."""
    text = str(title or "").lower()
    text = re.sub(r"\(\s*(19\d{2}|20\d{2})\s*\)", " ", text)
    for phrase in _NOISE_PHRASES:
        text = text.replace(phrase, " ")
    text = re.sub(r"[^a-z0-9 ]+", " ", text)
    words = [word for word in text.split() if word not in _NOISE_WORDS]
    return " ".join(words).strip()


def titles_match(query_title: str, candidate_title: str) -> bool:
    """True when two titles are the same work after normalisation. Strict on
    purpose: a loose match here writes another film's rating onto a row.
    One direction only: the QUERY may carry a season/episode suffix the
    candidate lacks (``One Piece Season 1`` is ``One Piece`` + a suffix), but
    a candidate LONGER than the query is a different work (``Dune`` is not
    ``Dune Part Two``) and never matches. The year guard is the backstop for
    the query-longer case."""
    left = normalize_title(query_title)
    right = normalize_title(candidate_title)
    if not left or not right:
        return False
    if left == right:
        return True
    return left.startswith(right + " ")


def years_compatible(query_year, candidate_year) -> bool:
    """Year guard: agree within ±1 when both are known; unknown passes."""
    left = parse_year(query_year)
    right = parse_year(candidate_year)
    if left is None or right is None:
        return True
    return abs(left - right) <= 1


def match_candidate(query: dict, candidate_title, candidate_year) -> bool:
    """The title+year guard every title search must pass before its payload
    is trusted. An IMDb-id lookup never reaches this — the id is the match."""
    return (titles_match(query.get("title") or query.get("clean_title") or "",
                         candidate_title or "")
            and years_compatible(query.get("year"), candidate_year))


# ── collision disambiguation (the Marco fix) ─────────────────────────────────

# Language names and ISO 639-1 codes, both accepted on either side: a post
# stores "Malayalam" (the classifier's word) while TMDb answers "ml".
_LANGUAGE_ALIASES = {
    "malayalam": "ml", "ml": "ml", "hindi": "hi", "hi": "hi",
    "tamil": "ta", "ta": "ta", "telugu": "te", "te": "te",
    "kannada": "kn", "kn": "kn", "bengali": "bn", "bn": "bn",
    "marathi": "mr", "mr": "mr", "gujarati": "gu", "gu": "gu",
    "punjabi": "pa", "pa": "pa", "urdu": "ur", "ur": "ur",
    "english": "en", "en": "en", "japanese": "ja", "ja": "ja",
    "korean": "ko", "ko": "ko", "chinese": "zh", "zh": "zh",
    "mandarin": "zh", "spanish": "es", "es": "es", "french": "fr",
    "fr": "fr", "german": "de", "de": "de", "arabic": "ar", "ar": "ar",
    "russian": "ru", "ru": "ru", "portuguese": "pt", "pt": "pt",
    "italian": "it", "it": "it", "thai": "th", "th": "th",
    "turkish": "tr", "tr": "tr",
}

# Country names and ISO 3166-1 codes, both accepted on either side.
_COUNTRY_ALIASES = {
    "india": "IN", "in": "IN", "usa": "US", "us": "US",
    "united states": "US", "united kingdom": "GB", "uk": "GB", "gb": "GB",
    "japan": "JP", "jp": "JP", "korea": "KR", "kr": "KR",
    "south korea": "KR", "china": "CN", "cn": "CN", "france": "FR",
    "fr": "FR", "germany": "DE", "de": "DE", "spain": "ES", "es": "ES",
    "italy": "IT", "it": "IT", "canada": "CA", "ca": "CA",
    "australia": "AU", "au": "AU", "pakistan": "PK", "pk": "PK",
    "bangladesh": "BD", "bd": "BD", "sri lanka": "LK", "lk": "LK",
    "thailand": "TH", "th": "TH", "turkey": "TR", "tr": "TR",
    "russia": "RU", "ru": "RU", "brazil": "BR", "br": "BR",
}


def language_codes(value) -> set[str]:
    """ISO language codes out of a stored or upstream value: a single name
    or code (``"Malayalam"``, ``"ml"``), or a comma-separated list
    (OMDb's ``"Malayalam, English"``). Placeholders mean 'unknown', never
    a language."""
    if value is None:
        return set()
    if isinstance(value, (list, tuple)):
        parts = [str(item) for item in value]
    else:
        parts = str(value).split(",")
    codes = set()
    for part in parts:
        code = _LANGUAGE_ALIASES.get(part.strip().lower())
        if code:
            codes.add(code)
    return codes


def country_codes(value) -> set[str]:
    """ISO country codes out of a stored or upstream value, same shape as
    :func:`language_codes`."""
    if value is None:
        return set()
    if isinstance(value, (list, tuple)):
        parts = [str(item) for item in value]
    else:
        parts = str(value).split(",")
    codes = set()
    for part in parts:
        code = _COUNTRY_ALIASES.get(part.strip().lower())
        if code:
            codes.add(code)
    return codes


def _norm_name(name: str) -> str:
    return re.sub(r"\s+", " ", str(name or "")).strip().lower()


def _query_names(query: dict) -> set[str]:
    """The post's known cast + director, normalised for overlap checks."""
    names = set()
    for field in ("cast", "director"):
        for name in query.get(field) or []:
            if _norm_name(name):
                names.add(_norm_name(name))
    return names


def _language_country_score(query: dict, candidate: dict) -> int:
    """How well a candidate matches the post's stored language/country:
    language agreement is worth 2, country agreement 1. Either side
    missing means no evidence, never a mismatch."""
    score = 0
    if language_codes(query.get("language")) & language_codes(candidate.get("language")):
        score += 2
    if country_codes(query.get("country")) & country_codes(candidate.get("country")):
        score += 1
    return score


def _name_overlap(known: set[str], candidate: dict) -> int:
    """Shared cast/director names between the post and a candidate."""
    theirs = {_norm_name(name) for name in (candidate.get("cast") or [])
              if _norm_name(name)}
    theirs |= {_norm_name(name) for name in (candidate.get("director") or [])
               if _norm_name(name)}
    return len(known & theirs)


def choose_candidate(query: dict, candidates: list[dict], *,
                     enrich=None):
    """Pick one candidate out of a title search's matches — or refuse.

    ``candidates`` are dicts with ``title``, ``year``, and (when the
    payload carries them) ``language``, ``country``, ``cast``,
    ``director``. Returns ``(chosen, ambiguous, tied)``: ``chosen`` is
    the winning candidate dict (or None), ``ambiguous`` is True when two
    or more candidates survived every tiebreak, and ``tied`` is the list
    still tied in that case (for the review list). ``enrich`` is an
    optional callable, called only for candidates still tied after the
    language step, returning a dict with ``cast``/``director`` (and
    anything else the caller needs, e.g. a fetched detail) merged into
    the candidate before the cast step.

    Steps, in order: (1) the title+year guard; (2) language/country —
    the unique best scorer wins, a shared best narrows the field; (3)
    cast/director overlap — the unique best wins; (4) still tied: fill
    NOTHING. An id lookup never reaches this function — the id is the
    identity, so there is no tie to break."""
    matches = [c for c in candidates
               if match_candidate(query, c.get("title"), c.get("year"))]
    if not matches:
        return None, False, []
    if len(matches) == 1:
        return matches[0], False, []
    best = max(_language_country_score(query, c) for c in matches)
    if best > 0:
        winners = [c for c in matches
                   if _language_country_score(query, c) == best]
        if len(winners) == 1:
            return winners[0], False, []
        matches = winners
    known = _query_names(query)
    if known:
        if enrich is not None:
            enriched = []
            for candidate in matches:
                extra = enrich(candidate) or {}
                merged = dict(candidate)
                for key in ("cast", "director", "meta"):
                    if extra.get(key):
                        merged[key] = extra[key]
                enriched.append(merged)
            matches = enriched
        best = max(_name_overlap(known, c) for c in matches)
        if best > 0:
            winners = [c for c in matches if _name_overlap(known, c) == best]
            if len(winners) == 1:
                return winners[0], False, []
            matches = winners
    return None, True, matches


def language_veto(query: dict, payload_language, payload_country=None) -> bool:
    """True when a single-payload answer must be refused: the post
    affirmatively claims a specific non-English language (or a specific
    non-USA country) and the payload's language (or country) is known
    and different. The post's bare 'English' / 'USA' never vetoes —
    rows predating the language migration carry those as defaults, so
    they are not evidence. A dubbed film (post 'English', payload
    'Malayalam') is likewise never vetoed on this test."""
    qlangs = language_codes(query.get("language"))
    plangs = language_codes(payload_language)
    if qlangs and qlangs != {"en"} and plangs and not (qlangs & plangs):
        return True
    qcountries = country_codes(query.get("country"))
    pcountries = country_codes(payload_country)
    if (qcountries and qcountries != {"US"} and pcountries
            and not (qcountries & pcountries)):
        return True
    return False


def is_series_like(content_type, seasons) -> bool:
    """Movies vs series, the same split ``backfill_tmdb.cinemeta_kind`` uses."""
    if str(content_type or "").lower() in ("series", "ongoing_season", "complete_season"):
        return True
    try:
        return int(seasons or 0) > 0
    except (TypeError, ValueError):
        return False


def sql_literal(value) -> str:
    """A SQL literal for a staging UPDATE: ints bare, strings quoted with
    single-quote doubling. Values come from upstream JSON, never from a
    human, so this is the only quoting the staging file gets."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    return "'" + str(value).replace("'", "''") + "'"


# ── Metadata ─────────────────────────────────────────────────────────────────

class Metadata:
    """One upstream answer, normalised. ``source`` names where every field
    came from — it is written into the report and the SQL comments, never
    into the database (there is no column for it)."""

    __slots__ = ("fields", "sources")

    def __init__(self, **kwargs):
        self.fields: dict = {}
        self.sources: dict = {}
        for key, value in kwargs.items():
            if key in FIELDS and value is not None:
                self.fields[key] = value

    def set(self, field: str, value, source: str) -> None:
        if field in FIELDS and value is not None:
            self.fields[field] = value
            self.sources[field] = source

    def get(self, field: str, default=None):
        return self.fields.get(field, default)

    def merge(self, other: "Metadata", *, only_missing: bool = True) -> None:
        """Fold another answer in. With only_missing (the default) the first
        source to answer a field keeps it — which is how the per-field source
        order is honoured."""
        for field, value in other.fields.items():
            if value is None:
                continue
            if only_missing and field in self.fields:
                continue
            self.fields[field] = value
            self.sources[field] = other.sources.get(field, "?")

    def missing(self, wanted) -> list[str]:
        return [field for field in wanted if field not in self.fields]

    def is_empty(self) -> bool:
        return not self.fields

    def to_post_updates(self, columns: set[str]) -> dict:
        """DB-ready column → value pairs for the posts table. Ratings are
        one-decimal strings; genres are a JSON list string; cast/director
        are comma-joined names; backdrops are a JSON list of URLs."""
        updates: dict = {}
        for field, value in self.fields.items():
            column = FIELD_COLUMN[field]
            if column not in columns:
                continue
            if field == "rating":
                text = normalize_rating(value)
                if text is not None:
                    updates[column] = text
            elif field == "year":
                year = parse_year(value)
                if year is not None:
                    updates[column] = year
            elif field == "genres":
                text = format_genres(value if isinstance(value, list) else parse_genres(value))
                if text is not None:
                    updates[column] = text
            elif field == "cast":
                text = join_names(split_names(value), limit=10)
                if text is not None:
                    updates[column] = text
            elif field == "director":
                text = join_names(split_names(value), limit=3)
                if text is not None:
                    updates[column] = text
            elif field == "backdrop":
                urls = [url for url in (value if isinstance(value, list) else [value])
                        if isinstance(url, str) and url.startswith("http")]
                if urls:
                    updates[column] = json.dumps(urls[:8], ensure_ascii=False)
            elif field in ("tmdb_id", "imdb_id"):
                text = str(value).strip()
                if text:
                    updates[column] = text
            else:  # synopsis, poster
                text = clean_text(value)
                if text is None:
                    continue
                if field == "poster" and not text.startswith("http"):
                    continue
                updates[column] = text
        return updates


# ── pacing, cache, HTTP ──────────────────────────────────────────────────────

class Pace:
    """One shared min-interval per source, thread-safe. All workers wait
    their turn, so the source sees at most one request per interval no
    matter how many threads run."""

    def __init__(self, min_interval: float = 0.25):
        self.min_interval = max(0.0, float(min_interval))
        self._lock = threading.Lock()
        self._next = 0.0

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            delay = self._next - now
            if delay > 0:
                time.sleep(delay)
                now = time.monotonic()
            self._next = max(now, self._next) + self.min_interval

    def backoff(self, seconds: float) -> None:
        """Push the next start out (used after a 429 Retry-After)."""
        with self._lock:
            self._next = max(self._next, time.monotonic() + max(0.0, seconds))


class UpstreamError(Exception):
    """A non-429 upstream failure: status + a short human-readable reason."""


class UpstreamRateLimited(Exception):
    """A 429 with the Retry-After the source asked for (seconds)."""

    def __init__(self, retry_after: float):
        super().__init__(f"rate limited, retry after {retry_after:g}s")
        self.retry_after = retry_after


class ResponseCache:
    """A JSON file of upstream payloads, keyed by source + lookup key, with
    a TTL. Metadata barely changes, so a hit means a whole API call saved;
    the cache is what makes a re-run cheap. Written atomically (temp file +
    rename) and flushed on demand — never on every lookup."""

    def __init__(self, path: Path | None, *, ttl: int = DEFAULT_CACHE_TTL):
        self.path = Path(path) if path else None
        self.ttl = ttl
        self.entries: dict = {}
        self.dirty = False
        if self.path and self.path.exists():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(data, dict):
                    self.entries = data.get("entries", {}) or {}
            except (ValueError, OSError):
                self.entries = {}

    def _fresh(self, entry: dict) -> bool:
        try:
            return time.time() - float(entry.get("fetched_at", 0)) < self.ttl
        except (TypeError, ValueError):
            return False

    def get(self, key: str):
        entry = self.entries.get(key)
        if isinstance(entry, dict) and self._fresh(entry):
            return entry.get("payload")
        return None

    def put(self, key: str, payload) -> None:
        self.entries[key] = {"fetched_at": time.time(), "payload": payload}
        self.dirty = True

    def flush(self) -> None:
        if not self.path or not self.dirty:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(json.dumps({"entries": self.entries}, ensure_ascii=False),
                       encoding="utf-8")
        tmp.replace(self.path)
        self.dirty = False


class DailyBudget:
    """Persistent per-day call counts for the metered sources (OMDb /
    MDBList, 950 calls each). The batch's cap is per-run; a live server
    has no run, so the live-fill path seeds each metered source's
    ``calls`` from this file before resolving and writes the count back
    after. A cache hit never consumes budget (``Source.cached`` only
    counts real fetches). The file is a tiny JSON dict next to the
    response cache; a new date resets the counts. A lost or corrupt file
    means "no budget spent" — the cap is a guard, not a quota, and
    under-counting after a crash is safer than refusing fills."""

    def __init__(self, path: Path | None):
        self.path = Path(path) if path else None
        self._lock = threading.Lock()
        self._date = time.strftime("%Y-%m-%d", time.localtime())
        self._counts: dict[str, int] = {}
        if self.path and self.path.exists():
            try:
                data = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(data, dict) and data.get("date") == self._date:
                    counts = data.get("calls") or {}
                    if isinstance(counts, dict):
                        self._counts = {k: int(v) for k, v in counts.items()}
            except (ValueError, OSError, TypeError):
                self._counts = {}

    def count(self, name: str) -> int:
        with self._lock:
            return int(self._counts.get(name, 0))

    def record(self, name: str, calls: int) -> None:
        """Persist the source's call count for today (monotonic: never lowers)."""
        if not self.path:
            return
        with self._lock:
            self._counts[name] = max(int(self._counts.get(name, 0)),
                                     int(calls))
            self._save()

    def _save(self) -> None:
        if not self.path:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp.write_text(json.dumps({"date": self._date, "calls": self._counts},
                                  ensure_ascii=False), encoding="utf-8")
        tmp.replace(self.path)


class HttpClient:
    """One urllib client per source, with its own Pace. Retries a 429 up to
    three times (honouring Retry-After), retries a 5xx once, and raises
    UpstreamError for anything else. No ``requests`` — stdlib only."""

    USER_AGENT = "radd-stream-metadata-backfill/1.0 (catalog enrichment)"

    def __init__(self, pace: Pace, *, timeout: float = DEFAULT_TIMEOUT):
        self.pace = pace
        self.timeout = timeout

    def _request(self, request: urllib.request.Request):
        last_error: Exception | None = None
        for attempt in range(4):
            self.pace.wait()
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    return response.status, response.read()
            except urllib.error.HTTPError as error:
                retry_after = error.headers.get("Retry-After") if error.headers else None
                if error.code == 429:
                    wait = 60.0
                    if retry_after:
                        try:
                            wait = min(300.0, max(1.0, float(retry_after)))
                        except ValueError:
                            pass
                    self.pace.backoff(wait)
                    last_error = UpstreamRateLimited(wait)
                    if attempt < 3:
                        continue
                    raise last_error
                if error.code >= 500 and attempt == 0:
                    last_error = UpstreamError(f"HTTP {error.code}")
                    time.sleep(2.0)
                    continue
                raise UpstreamError(f"HTTP {error.code}") from error
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                last_error = UpstreamError(str(error))
                if attempt == 0:
                    time.sleep(2.0)
                    continue
                raise last_error
        raise last_error or UpstreamError("request failed")

    def get_json(self, url: str, *, headers: dict | None = None):
        request = urllib.request.Request(url, headers={
            "User-Agent": self.USER_AGENT,
            "Accept": "application/json",
            **(headers or {}),
        })
        status, body = self._request(request)
        if status != 200:
            raise UpstreamError(f"HTTP {status}")
        try:
            return json.loads(body.decode("utf-8", "replace"))
        except ValueError as error:
            raise UpstreamError(f"bad JSON: {error}") from error

    def post_json(self, url: str, payload: dict, *, headers: dict | None = None):
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=body, headers={
            "User-Agent": self.USER_AGENT,
            "Accept": "application/json",
            "Content-Type": "application/json",
            **(headers or {}),
        }, method="POST")
        status, raw = self._request(request)
        if status != 200:
            raise UpstreamError(f"HTTP {status}")
        try:
            return json.loads(raw.decode("utf-8", "replace"))
        except ValueError as error:
            raise UpstreamError(f"bad JSON: {error}") from error


# ── IMDb datasets (bulk, no key, non-commercial only) ────────────────────────

class ImdbDatasets:
    """The IMDb non-commercial datasets: one download covers every title that
    carries an IMDb id. ``title.ratings.tsv.gz`` alone is 8.6 MB and rates
    all 27,858 works that have an ``imdb_id`` — the single biggest ratings
    win in this module, with zero API calls.

    Licence: the datasets are free for NON-COMMERCIAL use only. They may not
    be republished, resold, or repurposed into a movie-information database
    beyond personal use. The ratings we copy into our own catalog are
    facts about titles we already list; the TSVs themselves are never
    shipped, served, or published."""

    RATINGS_FILE = "title.ratings.tsv.gz"
    BASICS_FILE = "title.basics.tsv.gz"
    CREW_FILE = "title.crew.tsv.gz"

    def __init__(self, directory: Path):
        self.directory = Path(directory)
        self.ratings: dict[str, tuple[str, int]] = {}   # tconst -> (rating, votes)
        self.basics: dict[str, dict] = {}               # tconst -> {year, runtime, genres}
        self.crew: dict[str, list[str]] = {}            # tconst -> [directors]

    @staticmethod
    def _read_tsv_gz(path: Path):
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as handle:
            header = handle.readline().rstrip("\n").split("\t")
            for line in handle:
                values = line.rstrip("\n").split("\t")
                if len(values) != len(header):
                    continue
                yield dict(zip(header, values))

    def load_ratings(self) -> int:
        path = self.directory / self.RATINGS_FILE
        count = 0
        for row in self._read_tsv_gz(path):
            rating = normalize_rating(row.get("averageRating"))
            if rating is None:
                continue
            try:
                votes = int(row.get("numVotes") or 0)
            except ValueError:
                votes = 0
            self.ratings[row["tconst"]] = (rating, votes)
            count += 1
        return count

    def load_basics(self) -> int:
        path = self.directory / self.BASICS_FILE
        count = 0
        for row in self._read_tsv_gz(path):
            if row.get("titleType") not in ("movie", "tvSeries", "tvMiniSeries"):
                continue
            year = parse_year(row.get("startYear"))
            genres = [g for g in (row.get("genres") or "").split(",") if g and g != "\\N"]
            try:
                runtime = int(row.get("runtimeMinutes") or 0) or None
            except ValueError:
                runtime = None
            self.basics[row["tconst"]] = {
                "year": year, "runtime": runtime, "genres": genres,
            }
            count += 1
        return count

    def load_crew(self) -> int:
        path = self.directory / self.CREW_FILE
        count = 0
        for row in self._read_tsv_gz(path):
            directors = [d for d in (row.get("directors") or "").split(",")
                         if d and d != "\\N"]
            if directors:
                self.crew[row["tconst"]] = directors
                count += 1
        return count

    def download(self, files=("title.ratings.tsv.gz", "title.basics.tsv.gz",
                              "title.crew.tsv.gz")) -> list[str]:
        """Fetch the TSVs from datasets.imdbws.com into the directory."""
        self.directory.mkdir(parents=True, exist_ok=True)
        fetched: list[str] = []
        for name in files:
            target = self.directory / name
            if target.exists() and target.stat().st_size > 0:
                fetched.append(name)
                continue
            request = urllib.request.Request(
                f"{IMDB_DATASETS_ROOT}/{name}",
                headers={"User-Agent": HttpClient.USER_AGENT})
            with urllib.request.urlopen(request, timeout=120) as response, \
                    open(target, "wb") as handle:
                handle.write(response.read())
            fetched.append(name)
        return fetched

    def metadata_for(self, imdb_id: str) -> Metadata:
        """A Metadata from the loaded TSVs for one IMDb id (ratings + year +
        genres + directors). The ratings table is the workhorse; basics and
        crew only contribute when they were loaded."""
        meta = Metadata()
        rating_row = self.ratings.get(imdb_id)
        if rating_row:
            meta.set("rating", rating_row[0], "imdb-datasets")
        basics = self.basics.get(imdb_id)
        if basics:
            if basics.get("year"):
                meta.set("year", basics["year"], "imdb-datasets")
            if basics.get("genres"):
                meta.set("genres", basics["genres"], "imdb-datasets")
        directors = self.crew.get(imdb_id)
        if directors:
            meta.set("director", directors, "imdb-datasets")
        return meta


# ── upstream sources ─────────────────────────────────────────────────────────

def strip_html(text: str) -> str | None:
    """Plain text out of an HTML summary (TVMaze / AniList descriptions)."""
    if not text:
        return None
    import html as _html
    cleaned = re.sub(r"<[^>]+>", " ", str(text))
    cleaned = _html.unescape(cleaned)
    return clean_text(cleaned, max_len=2000)


class Source:
    """One upstream. Every source owns its HttpClient (and Pace), its cache
    namespace, and its call count — the report reads all three. A source
    with a missing key reports disabled and is never called."""

    NAME = "?"
    ENV_VAR: str | None = None
    MIN_INTERVAL = 0.5
    DAILY_BUDGET: int | None = None
    ANIME_ONLY = False

    def __init__(self, cache: ResponseCache, *, timeout: float = DEFAULT_TIMEOUT):
        self.cache = cache
        self.http = HttpClient(Pace(self.MIN_INTERVAL), timeout=timeout)
        self.calls = 0
        self.cache_hits = 0
        self.exhausted = False
        self.ambiguities: list[dict] = []

    def _record_ambiguous(self, query: dict, candidates, reason: str) -> None:
        """A title this source could not resolve to one film: the post is
        left unfilled by this source and the tie goes on the review list
        (post id, title, year, the tied candidates, why). Never guessed."""
        self.ambiguities.append({
            "post_id": query.get("post_id"),
            "title": query.get("title") or query.get("clean_title"),
            "year": query.get("year"),
            "source": self.NAME,
            "reason": reason,
            "candidates": [
                {"title": c.get("title"), "year": c.get("year"),
                 "language": c.get("language"), "country": c.get("country")}
                for c in candidates
            ],
        })

    @property
    def key(self) -> str:
        return os.environ.get(self.ENV_VAR, "").strip() if self.ENV_VAR else ""

    @property
    def enabled(self) -> bool:
        return self.ENV_VAR is None or bool(self.key)

    def cached(self, lookup_key: str, fetch):
        """A cached payload, or the fetch result stored for next time. A
        miss that hits the daily budget returns None and flags exhausted."""
        key = f"{self.NAME}:{lookup_key}"
        payload = self.cache.get(key)
        if payload is not None:
            self.cache_hits += 1
            return payload
        if self.DAILY_BUDGET is not None and self.calls >= self.DAILY_BUDGET:
            self.exhausted = True
            return None
        self.calls += 1
        payload = fetch()
        if payload is not None:
            self.cache.put(key, payload)
        return payload

    def lookup(self, query: dict) -> Metadata | None:
        raise NotImplementedError


class CinemetaSource(Source):
    """Stremio's Cinemeta addon — keyless. Looked up by IMDb id only, so it
    answers the 32,015 posts that already carry one. Gives the real IMDb
    rating, description, poster, cast, director, year, genres."""

    NAME = "cinemeta"

    def lookup(self, query: dict) -> Metadata | None:
        imdb_id = query.get("imdb_id")
        if not imdb_id:
            return None
        kind = "series" if is_series_like(query.get("content_type"), query.get("seasons")) else "movie"
        payload = self.cached(f"{kind}:{imdb_id}", lambda: self.http.get_json(
            f"{CINEMETA_ROOT}/meta/{kind}/{imdb_id}.json"))
        if not isinstance(payload, dict):
            return None
        meta = payload.get("meta") or {}
        out = Metadata()
        out.set("rating", meta.get("imdbRating"), self.NAME)
        out.set("synopsis", meta.get("description"), self.NAME)
        out.set("poster", meta.get("poster"), self.NAME)
        if meta.get("background"):
            out.set("backdrop", [meta["background"]], self.NAME)
        out.set("cast", meta.get("cast"), self.NAME)
        out.set("director", meta.get("director"), self.NAME)
        out.set("year", meta.get("year"), self.NAME)
        out.set("genres", meta.get("genres"), self.NAME)
        return out if not out.is_empty() else None


class TmdbSource(Source):
    """TMDb — the broadest catalogue here. Needs TMDB_API_KEY or
    TMDB_BEARER_TOKEN. Search by title+year (guarded), /find by IMDb id,
    detail with credits + external_ids + images. vote_average is a real
    TMDb score, NOT an IMDb one: it is stored in imdb_rating only when no
    IMDb rating exists anywhere, and the report marks it source=tmdb."""

    NAME = "tmdb"
    ENV_VAR = "TMDB_API_KEY"
    MIN_INTERVAL = 0.25

    def _headers(self) -> dict:
        bearer = os.environ.get("TMDB_BEARER_TOKEN", "").strip()
        if bearer:
            return {"Authorization": f"Bearer {bearer}"}
        return {}

    def _url(self, path: str, **params) -> str:
        params = {k: v for k, v in params.items() if v is not None}
        if not os.environ.get("TMDB_BEARER_TOKEN", "").strip():
            params["api_key"] = self.key
        query = urllib.parse.urlencode(params)
        return f"https://api.themoviedb.org/3{path}?{query}" if query else \
            f"https://api.themoviedb.org/3{path}"

    @property
    def enabled(self) -> bool:
        return bool(self.key or os.environ.get("TMDB_BEARER_TOKEN", "").strip())

    def _detail(self, kind: str, tmdb_id: int) -> Metadata | None:
        payload = self.cached(f"detail:{kind}:{tmdb_id}", lambda: self.http.get_json(
            self._url(f"/{kind}/{tmdb_id}",
                      append_to_response="credits,external_ids,images"),
            headers=self._headers()))
        if not isinstance(payload, dict):
            return None
        out = Metadata()
        out.set("rating", payload.get("vote_average"), self.NAME)
        out.set("synopsis", payload.get("overview"), self.NAME)
        if payload.get("poster_path"):
            out.set("poster", TMDB_IMAGE_ROOT + payload["poster_path"], self.NAME)
        backdrops = [TMDB_IMAGE_ROOT + b["file_path"]
                     for b in (payload.get("images") or {}).get("backdrops") or []
                     if b.get("file_path")][:3]
        if payload.get("backdrop_path"):
            backdrops.insert(0, TMDB_IMAGE_ROOT + payload["backdrop_path"])
        if backdrops:
            out.set("backdrop", backdrops, self.NAME)
        credits = payload.get("credits") or {}
        cast = [c.get("name") for c in credits.get("cast") or [] if c.get("name")][:8]
        if cast:
            out.set("cast", cast, self.NAME)
        directors = [c.get("name") for c in credits.get("crew") or []
                     if c.get("job") == "Director" and c.get("name")][:2]
        if directors:
            out.set("director", directors, self.NAME)
        year = payload.get("release_date") or payload.get("first_air_date")
        out.set("year", year, self.NAME)
        genres = [g.get("name") for g in payload.get("genres") or [] if g.get("name")]
        if genres:
            out.set("genres", genres, self.NAME)
        out.set("tmdb_id", payload.get("id"), self.NAME)
        external = payload.get("external_ids") or {}
        if external.get("imdb_id"):
            out.set("imdb_id", external["imdb_id"], self.NAME)
        return out if not out.is_empty() else None

    def _enrich_candidate(self, kind: str, candidate: dict) -> dict:
        """Cast/director for a tied search result, from its detail. The
        detail is cached, so when the enriched candidate wins its detail
        is not fetched twice."""
        meta = self._detail(kind, candidate["ref"]["id"])
        if meta is None:
            return {}
        return {"cast": split_names(meta.get("cast")),
                "director": split_names(meta.get("director")),
                "meta": meta}

    def lookup(self, query: dict) -> Metadata | None:
        series = is_series_like(query.get("content_type"), query.get("seasons"))
        kind = "tv" if series else "movie"
        if query.get("imdb_id"):
            payload = self.cached(f"find:{query['imdb_id']}", lambda: self.http.get_json(
                self._url(f"/find/{query['imdb_id']}", external_source="imdb_id"),
                headers=self._headers()))
            if isinstance(payload, dict):
                results = (payload.get("tv_results") if series else payload.get("movie_results")) or []
                if not results:
                    results = (payload.get("movie_results") or []) + (payload.get("tv_results") or [])
                if results:
                    first = results[0]
                    return self._detail("tv" if "first_air_date" in first else "movie",
                                        first["id"])
            return None
        if query.get("tmdb_id"):
            try:
                return self._detail(kind, int(query["tmdb_id"]))
            except (TypeError, ValueError):
                return None
        title = query.get("title") or query.get("clean_title") or ""
        if not title:
            return None
        params = {"query": title}
        if query.get("year"):
            params["primary_release_year" if kind == "movie" else "first_air_date_year"] = query["year"]
        payload = self.cached(f"search:{kind}:{normalize_title(title)}:{query.get('year')}",
                              lambda: self.http.get_json(
                                  self._url(f"/search/{kind}", **params),
                                  headers=self._headers()))
        if not isinstance(payload, dict):
            return None
        matches = []
        for result in payload.get("results") or []:
            matches.append({
                "title": result.get("title") or result.get("name"),
                "year": result.get("release_date") or result.get("first_air_date"),
                "language": result.get("original_language"),
                "country": (result.get("origin_country") or [None])[0],
                "cast": [], "director": [],
                "ref": result,
            })
        chosen, ambiguous, tied = choose_candidate(
            query, matches, enrich=lambda c: self._enrich_candidate(kind, c))
        if chosen is not None:
            if chosen.get("meta") is not None:
                return chosen["meta"]
            return self._detail(kind, chosen["ref"]["id"])
        if ambiguous:
            self._record_ambiguous(query, tied, "tie-after-language-and-cast")
        return None


class SimklSource(Source):
    """Simkl — the Phase-2 fallback after TMDb. Needs SIMKL_CLIENT_ID
    (the "Radd Stream" app, AUTH V2, registered 2026-11-20).

    Looked up by IMDb id only — exact identity, so no title guard is
    needed: ``/movies|/tv|/anime/:imdb``. Gives the real IMDb rating when
    Simkl has one, else its own Simkl score (recorded as source simkl,
    never as an IMDb score — the same honesty rule as TMDb's
    vote_average); overview, poster, fanart, year, genres, runtime, the
    TMDb id for the cross-link, and the movie payload's ``director``
    string. No cast in the summary shape — cast stays TMDb-only. Probe
    2026-11-20 on 100 no-rating works: Simkl
    86/100 found, TMDb 26/68 completed calls on the same 100.

    Some lookups answer with a list of matches instead of one object;
    ``simkl_enricher`` takes the first dict element (the probe crashed
    on this shape once). A 404 for an unknown IMDb id raises
    UpstreamError, same as every other source's miss path."""

    NAME = "simkl"
    ENV_VAR = "SIMKL_CLIENT_ID"
    MIN_INTERVAL = 0.35  # the probe's pacing; Simkl publishes no hard limit

    def _headers(self) -> dict:
        return {"simkl-api-key": self.key, "Content-Type": "application/json"}

    def lookup(self, query: dict) -> Metadata | None:
        imdb_id = query.get("imdb_id")
        if not imdb_id:
            return None
        endpoint = simkl_enricher.endpoint_for(
            series=is_series_like(query.get("content_type"),
                                   query.get("seasons")),
            anime=bool(query.get("animated")))
        url = simkl_enricher.lookup_url(self.key, endpoint, imdb_id)
        payload = self.cached(f"{endpoint}:{imdb_id}",
                              lambda: self.http.get_json(url, headers=self._headers()))
        data = simkl_enricher.extract_metadata(payload)
        if not data:
            return None
        out = Metadata()
        out.set("rating", data.get("rating"), self.NAME)
        out.set("synopsis", data.get("overview"), self.NAME)
        out.set("poster", data.get("poster"), self.NAME)
        if data.get("fanart"):
            out.set("backdrop", [data["fanart"]], self.NAME)
        out.set("year", data.get("year"), self.NAME)
        out.set("genres", data.get("genres"), self.NAME)
        out.set("director", data.get("director"), self.NAME)
        out.set("tmdb_id", data.get("tmdb_id"), self.NAME)
        return out if not out.is_empty() else None


class OmdbSource(Source):
    """OMDb — the IMDb rating itself, plus RT / Metacritic in the same
    payload. Needs OMDB_API_KEY. Free tier: 1,000 requests/day, so the run
    stops this source at 950 calls and the report says how many were spent."""

    NAME = "omdb"
    ENV_VAR = "OMDB_API_KEY"
    MIN_INTERVAL = 1.0
    DAILY_BUDGET = DAILY_CALL_BUDGET["omdb"]

    def lookup(self, query: dict) -> Metadata | None:
        if query.get("imdb_id"):
            lookup_key = f"i:{query['imdb_id']}"
            params = {"i": query["imdb_id"]}
        else:
            title = query.get("title") or query.get("clean_title") or ""
            if not title:
                return None
            lookup_key = f"t:{normalize_title(title)}:{query.get('year')}"
            params = {"t": title}
            if query.get("year"):
                params["y"] = str(query["year"])
        params["plot"] = "short"
        params["apikey"] = self.key
        url = "https://www.omdbapi.com/?" + urllib.parse.urlencode(params)
        payload = self.cached(lookup_key, lambda: self.http.get_json(url))
        if not isinstance(payload, dict) or payload.get("Response") != "True":
            return None
        if not query.get("imdb_id") and not match_candidate(
                query, payload.get("Title"), payload.get("Year")):
            return None
        if not query.get("imdb_id") and language_veto(
                query, payload.get("Language"), payload.get("Country")):
            # One payload, but it is the wrong film for this post: the
            # post affirmatively claims a language/country the payload
            # contradicts. Refuse it and let the review list have it.
            self._record_ambiguous(query, [{
                "title": payload.get("Title"), "year": payload.get("Year"),
                "language": payload.get("Language"),
                "country": payload.get("Country"),
            }], "language-contradiction")
            return None
        out = Metadata()
        out.set("rating", payload.get("imdbRating"), self.NAME)
        out.set("synopsis", payload.get("Plot"), self.NAME)
        poster = payload.get("Poster")
        if poster and poster != "N/A":
            out.set("poster", poster, self.NAME)
        out.set("cast", payload.get("Actors"), self.NAME)
        out.set("director", payload.get("Director"), self.NAME)
        out.set("year", payload.get("Year"), self.NAME)
        if payload.get("Genre"):
            out.set("genres", [g.strip() for g in str(payload["Genre"]).split(",")], self.NAME)
        if payload.get("imdbID"):
            out.set("imdb_id", payload["imdbID"], self.NAME)
        return out if not out.is_empty() else None


class TvmazeSource(Source):
    """TVMaze — keyless, TV only, no ratings. Best for series synopses,
    cast, and premiere years. ~20 calls / 10 s per IP."""

    NAME = "tvmaze"
    MIN_INTERVAL = 0.5

    def lookup(self, query: dict) -> Metadata | None:
        if not is_series_like(query.get("content_type"), query.get("seasons")):
            # TVMaze is TV-only; a movie lookup would only ever mismatch.
            if query.get("content_type") == "movie":
                return None
        if query.get("imdb_id"):
            lookup_key = f"imdb:{query['imdb_id']}"
            url = f"https://api.tvmaze.com/lookup/shows?imdb={query['imdb_id']}"
        else:
            title = query.get("title") or query.get("clean_title") or ""
            if not title:
                return None
            lookup_key = f"q:{normalize_title(title)}"
            url = ("https://api.tvmaze.com/singlesearch/shows?q="
                   + urllib.parse.quote_plus(title))
        try:
            show = self.cached(lookup_key, lambda: self.http.get_json(url))
        except UpstreamError as error:
            if "HTTP 404" in str(error):
                return None
            raise
        if not isinstance(show, dict):
            return None
        if not query.get("imdb_id") and not match_candidate(
                query, show.get("name"), show.get("premiered")):
            return None
        if not query.get("imdb_id") and language_veto(query, show.get("language")):
            self._record_ambiguous(query, [{
                "title": show.get("name"), "year": show.get("premiered"),
                "language": show.get("language"), "country": None,
            }], "language-contradiction")
            return None
        out = Metadata()
        out.set("synopsis", strip_html(show.get("summary")), self.NAME)
        image = show.get("image") or {}
        out.set("poster", image.get("original") or image.get("medium"), self.NAME)
        out.set("year", show.get("premiered"), self.NAME)
        if show.get("genres"):
            out.set("genres", show["genres"], self.NAME)
        externals = show.get("externals") or {}
        if externals.get("imdb"):
            out.set("imdb_id", externals["imdb"], self.NAME)
        show_id = show.get("id")
        if show_id:
            try:
                cast_payload = self.cached(f"cast:{show_id}", lambda: self.http.get_json(
                    f"https://api.tvmaze.com/shows/{show_id}/cast"))
            except UpstreamError:
                cast_payload = None
            if isinstance(cast_payload, list):
                names = [entry.get("person", {}).get("name")
                         for entry in cast_payload[:8]]
                out.set("cast", [n for n in names if n], self.NAME)
        return out if not out.is_empty() else None


class AnilistSource(Source):
    """AniList — keyless GraphQL, anime only. averageScore is 0–100 and is
    divided by 10 here. ~90 requests/min; 429s carry Retry-After."""

    NAME = "anilist"
    MIN_INTERVAL = 0.7
    ANIME_ONLY = True

    QUERY = """
        query ($search: String) {
          Media(search: $search, type: ANIME) {
            title { romaji english native }
            description averageScore coverImage { large }
            genres startDate { year }
            characters(perPage: 8, sort: [ROLE, FAVOURITES_DESC]) {
              edges { node { name { full } } }
            }
          }
        }"""

    def lookup(self, query: dict) -> Metadata | None:
        title = query.get("title") or query.get("clean_title") or ""
        if not title:
            return None
        payload = self.cached(f"q:{normalize_title(title)}", lambda: self.http.post_json(
            "https://graphql.anilist.co",
            {"query": self.QUERY, "variables": {"search": title}}))
        if not isinstance(payload, dict):
            return None
        media = (payload.get("data") or {}).get("Media")
        if not isinstance(media, dict):
            return None
        titles = media.get("title") or {}
        start = media.get("startDate") or {}
        candidates = [titles.get("romaji"), titles.get("english"), titles.get("native")]
        if not any(titles_match(query.get("title") or "", c or "")
                   for c in candidates if c):
            return None
        if not years_compatible(query.get("year"), start.get("year")):
            return None
        out = Metadata()
        out.set("rating", media.get("averageScore"), self.NAME)
        out.set("synopsis", strip_html(media.get("description")), self.NAME)
        cover = media.get("coverImage") or {}
        out.set("poster", cover.get("large"), self.NAME)
        out.set("year", start.get("year"), self.NAME)
        if media.get("genres"):
            out.set("genres", media["genres"], self.NAME)
        edges = (media.get("characters") or {}).get("edges") or []
        names = [edge.get("node", {}).get("name", {}).get("full")
                 for edge in edges if edge.get("node")]
        out.set("cast", [n for n in names if n], self.NAME)
        return out if not out.is_empty() else None


class KitsuSource(Source):
    """Kitsu — keyless JSON:API, anime only. averageRating is a 0–100
    string; divided by 10 here. Page size max 20, fair use."""

    NAME = "kitsu"
    MIN_INTERVAL = 0.5
    ANIME_ONLY = True

    def lookup(self, query: dict) -> Metadata | None:
        title = query.get("title") or query.get("clean_title") or ""
        if not title:
            return None
        url = ("https://kitsu.app/api/edge/anime?filter[text]="
               + urllib.parse.quote_plus(title) + "&page[limit]=5")
        payload = self.cached(f"q:{normalize_title(title)}",
                              lambda: self.http.get_json(url))
        if not isinstance(payload, dict):
            return None
        matches = []
        for item in payload.get("data") or []:
            attrs = item.get("attributes") or {}
            titles = attrs.get("titles") or {}
            candidates = [attrs.get("canonicalTitle"), titles.get("en"),
                          titles.get("en_jp"), titles.get("ja_jp")]
            title = next((c for c in candidates
                          if c and titles_match(query.get("title") or "", c)),
                         None)
            if title is None:
                continue
            if not years_compatible(query.get("year"), attrs.get("startDate")):
                continue
            matches.append({
                "title": title, "year": attrs.get("startDate"),
                "language": None, "country": None,
                "cast": [], "director": [], "ref": attrs,
            })
        chosen, ambiguous, tied = choose_candidate(query, matches)
        if chosen is None:
            if ambiguous:
                self._record_ambiguous(query, tied, "tie-after-language-and-cast")
            return None
        attrs = chosen["ref"]
        out = Metadata()
        out.set("rating", attrs.get("averageRating"), self.NAME)
        out.set("synopsis", attrs.get("synopsis"), self.NAME)
        poster = attrs.get("posterImage") or {}
        out.set("poster", poster.get("large") or poster.get("original"), self.NAME)
        out.set("year", attrs.get("startDate"), self.NAME)
        return out if not out.is_empty() else None


class JikanSource(Source):
    """Jikan — the MyAnimeList wrapper, keyless, anime only, fallback only:
    api.jikan.moe was unreachable from two networks in Oct 2026 and the
    service carries no uptime guarantee. 3 req/s, 60 req/min."""

    NAME = "jikan"
    MIN_INTERVAL = 0.35
    ANIME_ONLY = True

    def lookup(self, query: dict) -> Metadata | None:
        title = query.get("title") or query.get("clean_title") or ""
        if not title:
            return None
        url = ("https://api.jikan.moe/v4/anime?q=" + urllib.parse.quote_plus(title)
               + "&limit=5&order_by=members&sort=desc")
        payload = self.cached(f"q:{normalize_title(title)}",
                              lambda: self.http.get_json(url))
        if not isinstance(payload, dict):
            return None
        matches = []
        for item in payload.get("data") or []:
            candidates = [item.get("title"), item.get("title_english"),
                          item.get("title_japanese")]
            title = next((c for c in candidates
                          if c and titles_match(query.get("title") or "", c)),
                         None)
            if title is None:
                continue
            if not years_compatible(query.get("year"), item.get("year")):
                continue
            matches.append({
                "title": title, "year": item.get("year"),
                "language": None, "country": None,
                "cast": [], "director": [], "ref": item,
            })
        chosen, ambiguous, tied = choose_candidate(query, matches)
        if chosen is None:
            if ambiguous:
                self._record_ambiguous(query, tied, "tie-after-language-and-cast")
            return None
        item = chosen["ref"]
        out = Metadata()
        out.set("rating", item.get("score"), self.NAME)
        out.set("synopsis", item.get("synopsis"), self.NAME)
        images = (item.get("images") or {}).get("jpg") or {}
        out.set("poster", images.get("large_image_url"), self.NAME)
        out.set("year", item.get("year"), self.NAME)
        genres = [g.get("name") for g in item.get("genres") or [] if g.get("name")]
        if genres:
            out.set("genres", genres, self.NAME)
        return out if not out.is_empty() else None


class MdblistSource(Source):
    """MDBList — one call aggregates IMDb, RT, Metacritic, Letterboxd, TMDb,
    Trakt and MAL ratings. Needs MDBLIST_API_KEY. Free tier: 1,000
    requests/day; the run stops this source at 950 calls."""

    NAME = "mdblist"
    ENV_VAR = "MDBLIST_API_KEY"
    MIN_INTERVAL = 1.0
    DAILY_BUDGET = DAILY_CALL_BUDGET["mdblist"]

    def lookup(self, query: dict) -> Metadata | None:
        if query.get("imdb_id"):
            lookup_key = f"i:{query['imdb_id']}"
            params = {"i": query["imdb_id"]}
        else:
            title = query.get("title") or query.get("clean_title") or ""
            if not title:
                return None
            lookup_key = f"t:{normalize_title(title)}:{query.get('year')}"
            params = {"t": title}
            if query.get("year"):
                params["y"] = str(query["year"])
        params["apikey"] = self.key
        url = "https://mdblist.com/api/?" + urllib.parse.urlencode(params)
        payload = self.cached(lookup_key, lambda: self.http.get_json(url))
        if not isinstance(payload, dict) or not payload.get("title"):
            return None
        if not query.get("imdb_id") and not match_candidate(
                query, payload.get("title"), payload.get("year")):
            return None
        if not query.get("imdb_id") and language_veto(
                query, payload.get("language"), payload.get("country")):
            self._record_ambiguous(query, [{
                "title": payload.get("title"), "year": payload.get("year"),
                "language": payload.get("language"),
                "country": payload.get("country"),
            }], "language-contradiction")
            return None
        out = Metadata()
        ratings = payload.get("ratings") or []
        by_source = {str(r.get("source", "")).lower(): r.get("value")
                     for r in ratings if isinstance(r, dict)}
        for preferred in ("imdb", "tmdb", "trakt", "metacritic"):
            if by_source.get(preferred) is not None:
                out.set("rating", by_source[preferred], self.NAME)
                break
        out.set("synopsis", payload.get("description"), self.NAME)
        out.set("poster", payload.get("poster"), self.NAME)
        out.set("year", payload.get("year"), self.NAME)
        if payload.get("imdbid"):
            out.set("imdb_id", payload["imdbid"], self.NAME)
        if payload.get("tmdbid"):
            out.set("tmdb_id", payload["tmdbid"], self.NAME)
        return out if not out.is_empty() else None


class TraktSource(Source):
    """Trakt — ratings plus TMDb / IMDb ids in one call. Needs a free
    TRAKT_CLIENT_ID (no OAuth for read-only discovery). ~1,000 calls /
    5 min; commercial use needs approval."""

    NAME = "trakt"
    ENV_VAR = "TRAKT_CLIENT_ID"
    MIN_INTERVAL = 0.2

    def _headers(self) -> dict:
        return {"trakt-api-version": "2", "trakt-api-key": self.key}

    def lookup(self, query: dict) -> Metadata | None:
        series = is_series_like(query.get("content_type"), query.get("seasons"))
        kind = "show" if series else "movie"
        plural = "shows" if series else "movies"
        title = query.get("title") or query.get("clean_title") or ""
        if not title:
            return None
        search = self.cached(
            f"search:{kind}:{normalize_title(title)}",
            lambda: self.http.get_json(
                "https://api.trakt.tv/search/" + kind + "?query="
                + urllib.parse.quote_plus(title), headers=self._headers()))
        if not isinstance(search, list):
            return None
        chosen = None
        for entry in search:
            item = entry.get(kind) or {}
            if match_candidate(query, item.get("title"), item.get("year")):
                chosen = item
                break
        if not chosen:
            return None
        ids = chosen.get("ids") or {}
        trakt_id = ids.get("trakt")
        if not trakt_id:
            return None
        detail = self.cached(f"detail:{kind}:{trakt_id}", lambda: self.http.get_json(
            f"https://api.trakt.tv/{plural}/{trakt_id}?extended=full",
            headers=self._headers()))
        if not isinstance(detail, dict):
            return None
        out = Metadata()
        out.set("rating", detail.get("rating"), self.NAME)
        out.set("synopsis", detail.get("overview"), self.NAME)
        out.set("year", detail.get("year"), self.NAME)
        genres = detail.get("genres")
        if genres:
            out.set("genres", genres, self.NAME)
        detail_ids = detail.get("ids") or {}
        if detail_ids.get("imdb"):
            out.set("imdb_id", detail_ids["imdb"], self.NAME)
        if detail_ids.get("tmdb"):
            out.set("tmdb_id", detail_ids["tmdb"], self.NAME)
        return out if not out.is_empty() else None


class FanartSource(Source):
    """Fanart.tv — posters and backdrops by TMDb id (movies) only. Needs
    FANART_API_KEY. Project tier is free with a 7-day delay on new art;
    Personal tier is free with a 2-day delay. ~2 req/s."""

    NAME = "fanart"
    ENV_VAR = "FANART_API_KEY"
    MIN_INTERVAL = 0.5

    def lookup(self, query: dict) -> Metadata | None:
        if not query.get("tmdb_id"):
            return None
        if is_series_like(query.get("content_type"), query.get("seasons")):
            return None  # TV needs a TVDB id, which the catalog does not carry.
        try:
            tmdb_id = int(query["tmdb_id"])
        except (TypeError, ValueError):
            return None
        payload = self.cached(f"movie:{tmdb_id}", lambda: self.http.get_json(
            f"https://webservice.fanart.tv/v3/movies/{tmdb_id}?api_key={self.key}"))
        if not isinstance(payload, dict):
            return None
        out = Metadata()
        posters = payload.get("movieposter") or []
        if posters and posters[0].get("url"):
            out.set("poster", posters[0]["url"], self.NAME)
        backgrounds = payload.get("moviebackground") or []
        if backgrounds and backgrounds[0].get("url"):
            out.set("backdrop", [backgrounds[0]["url"]], self.NAME)
        return out if not out.is_empty() else None


SOURCE_CLASSES = (
    CinemetaSource, TmdbSource, SimklSource, OmdbSource, TvmazeSource, AnilistSource,
    KitsuSource, JikanSource, MdblistSource, TraktSource, FanartSource,
)


# ── planning ─────────────────────────────────────────────────────────────────

# Column → field, the reverse of FIELD_COLUMN (used to filter an API answer
# down to the fields its representative actually misses).
COLUMN_FIELD = {column: field for field, column in FIELD_COLUMN.items()}

# Row columns the passes read. Everything else is looked up through these.
ROW_COLUMNS = (
    "id", "canonical_key", "title", "clean_title", "year", "content_type",
    "seasons", "imdb_id", "tmdb_id", "is_18_plus", "is_animated",
    # Disambiguation context: the post's own language/country/industry
    # (site metadata is primary). _stream_groups filters by post_columns,
    # so databases without these columns keep working.
    "language", "country", "industry",
)


class Plan:
    """The staging plan: post_id → {field: (db_value, source)}. First writer
    wins, so the pass order IS the priority order (site > datasets > API),
    unless a later pass runs with force=True (the API pass only)."""

    def __init__(self):
        self.updates: dict[int, dict[str, tuple]] = {}
        self.post_key: dict[int, str] = {}
        # Already written by an earlier (resumed) run: post_id →
        # {field: source}. Seeded fields are final — no pass re-fills
        # them — and are excluded from count()/by_source() so a resumed
        # run's report counts only what THIS run filled.
        self.seeded: dict[int, dict[str, str]] = {}
        # Posts with new fills since the last batch flush (O(1) check).
        self.dirty: set[int] = set()

    def seed(self, post_id: int, fields: dict[str, str]) -> None:
        """Mark fields as already written by a previous run."""
        if fields:
            self.seeded.setdefault(post_id, {}).update(fields)

    def add(self, post_id: int, field: str, value, source: str,
            *, force: bool = False) -> bool:
        if value is None:
            return False
        if field in self.seeded.get(post_id, {}):
            return False
        per_post = self.updates.setdefault(post_id, {})
        if field in per_post and not force:
            return False
        per_post[field] = (value, source)
        self.dirty.add(post_id)
        return True

    def add_metadata(self, post_id: int, meta: Metadata, columns: set[str],
                     *, force: bool = False) -> int:
        added = 0
        for column, value in meta.to_post_updates(columns).items():
            field = COLUMN_FIELD[column]
            if self.add(post_id, field, value, meta.sources.get(field, "?"),
                        force=force):
                added += 1
        return added

    def has(self, post_id: int, field: str) -> bool:
        return (field in self.updates.get(post_id, {})
                or field in self.seeded.get(post_id, {}))

    def count(self) -> int:
        return sum(len(fields) for fields in self.updates.values())

    def by_source(self) -> dict[str, dict[str, int]]:
        """field → {source: count} — the provenance the report prints."""
        out: dict[str, dict[str, int]] = {}
        for fields in self.updates.values():
            for field, (_, source) in fields.items():
                out.setdefault(field, {}).setdefault(source, 0)
                out[field][source] += 1
        return out

    def seeded_posts(self) -> int:
        return len(self.seeded)

    def seeded_fields(self) -> int:
        return sum(len(fields) for fields in self.seeded.values())


def post_columns(conn: sqlite3.Connection) -> set[str]:
    """The columns the posts table actually has (the live table carries all
    of them; a staging copy may not)."""
    return {row[1] for row in conn.execute("PRAGMA table_info(posts)")}


def connect_readonly(db_path: Path) -> sqlite3.Connection:
    """A read-only connection. The input database is never written — not by
    this module, not by SQLite's journal, not by accident."""
    if not db_path.exists():
        raise ValueError(f"no database at {db_path}")
    uri = f"file:{urllib.parse.quote(str(db_path))}?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def _stream_groups(conn: sqlite3.Connection, columns: set[str]):
    """Yield (canonical_key, [rows]) for every non-adult post, ordered by
    key then id. Streaming keeps memory flat no matter how big the catalog
    is; a group is a work's posts (usually 1–5 rows). Keyless posts arrive
    under their own ``post:<id>`` key so they are still processed."""
    wanted = [c for c in ROW_COLUMNS if c in columns]
    fillable = [FIELD_COLUMN[f] for f in FIELDS if FIELD_COLUMN[f] in columns]
    select = ", ".join(wanted + fillable)
    adult = "is_18_plus = 0" if "is_18_plus" in columns else "1 = 1"
    order = "canonical_key, id" if "canonical_key" in columns else "id"
    # The passes read rows by name; a caller may hand us a plain connection.
    conn.row_factory = sqlite3.Row
    cursor = conn.execute(
        f"SELECT {select} FROM posts WHERE {adult} ORDER BY {order}")
    current_key = None
    group: list = []
    for row in cursor:
        key = row["canonical_key"] if "canonical_key" in row.keys() else None
        key = key if key and str(key).strip().lower() != "unknown" else f"post:{row['id']}"
        if key != current_key:
            if group:
                yield current_key, group
            current_key, group = key, []
        group.append(row)
    if group:
        yield current_key, group


def _field_missing(row, field: str, plan: Plan) -> bool:
    """A field still needs filling for this row: not in the plan, and the
    stored value is empty (or a placeholder)."""
    if plan.has(row["id"], field):
        return False
    return is_empty_value(row[FIELD_COLUMN[field]])


def _donor_values(rows, wanted: list[str], plan: Plan, *,
                  include_plan_donors: bool = False) -> dict[str, tuple]:
    """Best stored value per field across a work's rows: the donor for a
    field is the sibling with the most filled fields (ties: lowest id) —
    a post that carries a rating usually carries the rest too. With
    include_plan_donors, values already planned for a sibling (from the
    datasets or API pass) count as donor values, which is how an API win
    on one post propagates to the whole work. Shared by the batch site
    pass and the live fill — one rule, one place."""
    donors: dict[str, tuple] = {}
    for field in wanted:
        best_row = None
        best_score = -1
        for row in rows:
            value = row[FIELD_COLUMN[field]]
            if is_empty_value(value):
                continue
            score = sum(1 for f in wanted
                        if not is_empty_value(row[FIELD_COLUMN[f]]))
            if score > best_score:
                best_score, best_row = score, row
        if best_row is not None:
            donors[field] = (best_row[FIELD_COLUMN[field]], "site")
        elif include_plan_donors:
            for row in rows:
                planned = plan.updates.get(row["id"], {}).get(field)
                if planned is not None:
                    donors[field] = planned
                    break
    return donors


def plan_site_pass(conn: sqlite3.Connection, columns: set[str],
                   wanted: list[str], plan: Plan, *,
                   include_plan_donors: bool = False) -> dict:
    """Copy the best non-empty value across a work's sibling posts. The donor
    for a field is the sibling with the most filled fields (ties: lowest
    id) — a post that carries a rating usually carries the rest too. With
    include_plan_donors, values already planned for a sibling (from the
    datasets or API pass) count as donor values, which is how an API win on
    one post propagates to the whole work. Returns pass stats."""
    stats = {"groups": 0, "posts": 0, "filled": 0, "by_field": {}}
    key_had: dict[str, set[str]] = {}
    for key, rows in _stream_groups(conn, columns):
        stats["groups"] += 1
        stats["posts"] += len(rows)
        for row in rows:
            plan.post_key[row["id"]] = key
        had = {field for field in wanted
               if any(not is_empty_value(r[FIELD_COLUMN[field]]) for r in rows)}
        key_had[key] = had
        # Donor values: existing first, then (on the propagation run) planned.
        donors = _donor_values(rows, wanted, plan,
                               include_plan_donors=include_plan_donors)
        for row in rows:
            for field, (value, source) in donors.items():
                if _field_missing(row, field, plan):
                    if plan.add(row["id"], field, value, source):
                        stats["filled"] += 1
                        stats["by_field"][field] = stats["by_field"].get(field, 0) + 1
    stats["key_had"] = key_had
    return stats


def plan_datasets_pass(conn: sqlite3.Connection, columns: set[str],
                       wanted: list[str], datasets: ImdbDatasets,
                       plan: Plan) -> dict:
    """Join the loaded IMDb TSVs on imdb_id. Ratings only where the post has
    an id and no rating; year/genres/director ride along when loaded."""
    stats = {"posts": 0, "filled": 0, "by_field": {}}
    if not datasets.ratings and not datasets.basics and not datasets.crew:
        return stats
    wanted_set = set(wanted)
    for _, rows in _stream_groups(conn, columns):
        for row in rows:
            imdb_id = row["imdb_id"]
            if not imdb_id:
                continue
            meta = datasets.metadata_for(imdb_id)
            if meta.is_empty():
                continue
            # Only the fields this row actually misses may be filled: the
            # datasets must never overwrite a stored rating (or any value).
            limited = Metadata()
            for field in meta.fields:
                if field not in wanted_set:
                    continue
                if _field_missing(row, field, plan):
                    limited.set(field, meta.fields[field],
                                meta.sources.get(field, "?"))
            if limited.is_empty():
                continue
            stats["posts"] += 1
            added = plan.add_metadata(row["id"], limited, columns)
            stats["filled"] += added
            for field in limited.fields:
                if plan.has(row["id"], field):
                    stats["by_field"][field] = stats["by_field"].get(field, 0) + 1
    return stats


def _representatives(conn: sqlite3.Connection, columns: set[str],
                     wanted: list[str], plan: Plan, *,
                     limit: int = 0, after_id: int = 0,
                     force: bool = False) -> list[dict]:
    """One representative per work: the posts of a key ranked by how much
    they still miss (most missing first, ties by lowest id), up to three
    candidates. The emptiest post is the one that needs the API most — and
    if its title turns out unsearchable, the next candidate gets a try.
    The representative's title/year is what the APIs search on."""
    reps: list[dict] = []
    for key, rows in _stream_groups(conn, columns):
        if limit and len(reps) >= limit:
            break
        candidates = []
        for row in rows:
            if row["id"] <= after_id:
                continue
            missing = [f for f in wanted
                       if force or _field_missing(row, f, plan)]
            if missing:
                candidates.append(_candidate_from_row(row, key, missing))
        if not candidates:
            continue
        candidates.sort(key=lambda c: (-len(c["missing"]), c["id"]))
        reps.append({"id": candidates[0]["id"], "key": key,
                     "candidates": candidates[:3]})
    return reps


def _resolve_one(query: dict, missing: list[str], sources: dict) -> tuple[Metadata, dict]:
    """Run one candidate through the per-field source chains. Each source is
    called at most once per candidate and its whole answer is merged (so a
    TMDb call for the synopsis also fills cast/director/year for free); the
    field order is the priority order, so the rating chain runs before any
    source that could answer it second-best."""
    answers: dict[str, Metadata | None] = {}
    meta = Metadata()
    tried: list[str] = []
    hit: list[str] = []
    failed: list[str] = []
    for field in missing:
        if field in meta.fields:
            continue
        for source_name in FIELD_SOURCES[field]:
            source = sources.get(source_name)
            if source is None or not source.enabled:
                continue
            if source.ANIME_ONLY and not query.get("animated"):
                continue
            if source_name not in answers:
                try:
                    answers[source_name] = source.lookup(query)
                    tried.append(source_name)
                except UpstreamError:
                    answers[source_name] = None
                    failed.append(source_name)
                    continue
            answer = answers[source_name]
            if answer is None:
                continue
            if field in answer.fields:
                meta.merge(answer)
                hit.append(source_name)
                break
    # Only the fields this candidate actually misses may be filled.
    limited = Metadata()
    for field in missing:
        if field in meta.fields:
            limited.set(field, meta.fields[field],
                        meta.sources.get(field, "?"))
    return limited, {"tried": tried, "hit": hit, "failed": failed}


def _resolve_representative(rep: dict, sources: dict, plan: Plan,
                            columns: set[str], *, force: bool = False) -> tuple:
    """Try a work's candidates in order until one returns a non-empty answer.
    Returns (post_id, metadata, call_stats) — post_id is None when no
    candidate matched anything."""
    tried: list[str] = []
    hit: list[str] = []
    failed: list[str] = []
    for candidate in rep["candidates"]:
        query = {
            "post_id": candidate["id"],
            "title": candidate["title"], "clean_title": candidate["clean_title"],
            "year": candidate["year"], "content_type": candidate["content_type"],
            "seasons": candidate["seasons"], "imdb_id": candidate["imdb_id"],
            "tmdb_id": candidate["tmdb_id"],
            "animated": candidate["animated"],
            "language": candidate.get("language"),
            "country": candidate.get("country"),
            "industry": candidate.get("industry"),
            "cast": candidate.get("cast") or [],
            "director": candidate.get("director") or [],
        }
        missing = [f for f in candidate["missing"]
                   if force or not plan.has(candidate["id"], f)]
        if not missing:
            continue
        meta, call_stats = _resolve_one(query, missing, sources)
        tried.extend(call_stats["tried"])
        hit.extend(call_stats["hit"])
        failed.extend(call_stats["failed"])
        if not meta.is_empty():
            return candidate["id"], meta, {"tried": tried, "hit": hit,
                                           "failed": failed}
    return None, Metadata(), {"tried": tried, "hit": hit, "failed": failed}


def plan_api_pass(conn: sqlite3.Connection, columns: set[str],
                  wanted: list[str], sources: dict, plan: Plan, *,
                  limit: int = 0, after_id: int = 0, workers: int = DEFAULT_WORKERS,
                  force: bool = False, writer=None) -> dict:
    """The API pass over the representatives, in a thread pool. Sources are
    thread-safe (each owns its Pace + HttpClient; the cache is read/written
    under no lock, which is safe here because a key is written by at most
    one worker — the representative's lookup key is unique to its title)."""
    stats = {"representatives": 0, "filled": 0, "by_field": {},
             "no_match": 0, "failed": 0}
    reps = _representatives(conn, columns, wanted, plan, limit=limit,
                            after_id=after_id, force=force)
    stats["representatives"] = len(reps)
    if not reps:
        return stats
    lock = threading.Lock()

    def _work(rep: dict):
        post_id, meta, call_stats = _resolve_representative(
            rep, sources, plan, columns, force=force)
        return post_id, meta, call_stats

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        for post_id, meta, calls in pool.map(_work, reps):
            if calls["failed"]:
                stats["failed"] += 1
            if post_id is None or meta.is_empty():
                stats["no_match"] += 1
                continue
            with lock:
                added = plan.add_metadata(post_id, meta, columns, force=force)
            stats["filled"] += added
            for field in meta.fields:
                if plan.has(post_id, field):
                    stats["by_field"][field] = stats["by_field"].get(field, 0) + 1
            # Streaming: a full batch on disk now, while the pool keeps
            # working — this loop is the main thread, so the flush is safe
            # here and nowhere else in the pass.
            if writer is not None and len(plan.dirty) >= writer.batch_size:
                writer.flush(plan)
    return stats


def estimate_work_gains(key_had: dict, plan: Plan, wanted: list[str]) -> dict:
    """Works that gain a field when the projection next runs: the work had
    no post carrying the field before, and the plan fills it on at least one
    of its posts. (The projection takes the first non-empty value per field,
    so a filled post flows to the work.) Keyless posts are not works and are
    excluded."""
    gains = {field: 0 for field in wanted}
    key_fields: dict[str, set[str]] = {}
    for post_id, fields in plan.updates.items():
        key = plan.post_key.get(post_id, "")
        if not key or key.startswith("post:"):
            continue
        key_fields.setdefault(key, set()).update(fields)
    for key, filled in key_fields.items():
        had = key_had.get(key, set())
        for field in filled:
            if field in wanted and field not in had:
                gains[field] += 1
    return gains


# ── staging output ───────────────────────────────────────────────────────────

def _update_line(post_id: int, fields: dict) -> str:
    """One UPDATE line: values as literals, a ``-- field: source`` comment
    carrying the provenance. The one-shot writer and the streaming writer
    share this function so the file format is identical either way (the
    existing test pins the exact strings)."""
    assignments = ", ".join(
        f"{FIELD_COLUMN[field]} = {sql_literal(value)}"
        for field, (value, _) in sorted(fields.items()))
    provenance = "; ".join(
        f"{field}: {source}" for field, (_, source) in sorted(fields.items()))
    return (f"UPDATE posts SET {assignments} WHERE id = {post_id}; "
            f"-- {provenance}")


def write_staging_sql(path: Path, plan: Plan, meta: dict) -> int:
    """The staging SQL file: one UPDATE per post, values as literals, every
    line carrying a ``-- field: source`` comment so the provenance is visible
    at the gate. Deterministic order (by post id) so re-runs diff cleanly.
    Apply with ``sqlite3 <db> < <file>`` — on a COPY, at the gate, never on
    the live database directly from this module."""
    lines = [
        f"-- metadata_backfill staging SQL — generated {meta.get('generated_at', '')}",
        f"-- source database: {meta.get('db', '')}",
        f"-- posts updated: {len(plan.updates)} · fields filled: {plan.count()}",
        "-- apply with: sqlite3 <copy-of-the-catalog.db> < this-file",
        "-- then verify counts and re-run the v2 projection before serving.",
        "",
        "BEGIN;",
    ]
    for post_id in sorted(plan.updates):
        lines.append(_update_line(post_id, plan.updates[post_id]))
    lines.append("COMMIT;")
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")
    return len(plan.updates)


def _parse_resume_sql(path: Path) -> tuple[dict[int, dict[str, str]], bool]:
    """Read a staging SQL file back: post_id → {field: source} for every
    UPDATE line, plus whether the file ends with a COMMIT; (a finished run)
    or not (an interrupted one — resume it, never apply it). The parse
    splits on the last ``; -- `` so a semicolon inside a quoted value
    cannot fool it."""
    flushed: dict[int, dict[str, str]] = {}
    complete = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() == "COMMIT;":
            complete = True
            continue
        if not line.startswith("UPDATE posts SET "):
            continue
        head, sep, provenance = line.rpartition("; -- ")
        if not sep:
            continue
        match = re.search(r"WHERE id = (\d+)$", head)
        if match is None:
            continue
        fields: dict[str, str] = {}
        for part in provenance.split(";"):
            part = part.strip()
            if ": " in part:
                field, source = part.split(": ", 1)
                fields[field.strip()] = source.strip()
        if fields:
            flushed.setdefault(int(match.group(1)), {}).update(fields)
    return flushed, complete


class BatchWriter:
    """Streams the staging SQL to disk in batches as posts are filled, with
    a JSON progress file updated after every batch. A crash loses at most
    one batch; a file without a closing COMMIT; is an interrupted run —
    resume it, never apply it."""

    def __init__(self, sql_path: Path, progress_path: Path, meta: dict, *,
                 batch_size: int = DEFAULT_BATCH_SIZE):
        self.sql_path = sql_path
        self.progress_path = progress_path
        self.meta = meta
        self.batch_size = max(1, batch_size)
        self.started_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        self.resumed_at: str | None = None
        self.flushed: dict[int, dict[str, str]] = {}
        self.posts_updated = 0
        self.fields_filled = 0
        self.by_field: dict[str, int] = {}
        self.by_source: dict[str, dict[str, int]] = {}
        self.ambiguous: list[dict] = []
        self.resumed_posts = 0
        self.resumed_fields = 0
        self._file = None
        self._started_monotonic = time.monotonic()

    def open(self, *, resume: bool = False) -> None:
        """Create the staging file (fresh) or reopen it for appending. On
        resume the already-written posts are loaded first, so the run never
        re-fills them; the original started_at and the earlier review list
        carry over."""
        if resume and self.sql_path.exists():
            self.flushed, _complete = _parse_resume_sql(self.sql_path)
            self.resumed_posts = len(self.flushed)
            self.resumed_fields = sum(len(f) for f in self.flushed.values())
            self.posts_updated = self.resumed_posts
            self.fields_filled = self.resumed_fields
            for fields in self.flushed.values():
                for field, source in fields.items():
                    self.by_field[field] = self.by_field.get(field, 0) + 1
                    self.by_source.setdefault(field, {}).setdefault(source, 0)
                    self.by_source[field][source] += 1
            if self.progress_path.exists():
                try:
                    old = json.loads(
                        self.progress_path.read_text(encoding="utf-8"))
                except (ValueError, OSError):
                    old = {}
                self.started_at = old.get("started_at") or self.started_at
                self.ambiguous = old.get("ambiguous") or []
            self.resumed_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            self._file = self.sql_path.open("a", encoding="utf-8")
            with self.sql_path.open("rb") as probe:
                probe.seek(0, 2)
                size = probe.tell()
                probe.seek(max(0, size - 64))
                tail = probe.read().decode("utf-8", "replace")
            if tail and not tail.endswith("\n"):
                self._file.write("\n")
            if tail.rstrip().endswith("COMMIT;"):
                # A finished run's file: start a new transaction block so
                # the resumed run's rows are still one atomic batch.
                self._file.write("BEGIN;\n")
            self._write_progress()
            return
        self._file = self.sql_path.open("w", encoding="utf-8")
        self._file.write("\n".join([
            f"-- metadata_backfill staging SQL — generated {self.meta.get('generated_at', '')}",
            f"-- source database: {self.meta.get('db', '')}",
            "-- apply with: sqlite3 <copy-of-the-catalog.db> < this-file",
            "-- then verify counts and re-run the v2 projection before serving.",
            "",
            "BEGIN;",
            "",
        ]))
        self._file.flush()
        self._write_progress()

    def flush(self, plan: Plan, *, force: bool = False) -> int:
        """Write every complete batch of batch_size dirty posts (or all of
        them when force=True). Returns the number of posts written."""
        if self._file is None:
            return 0
        dirty = sorted(plan.dirty)
        if not force and len(dirty) < self.batch_size:
            return 0
        if force:
            take = dirty
        else:
            take = dirty[:len(dirty) // self.batch_size * self.batch_size]
        for post_id in take:
            fields = plan.updates.get(post_id)
            if not fields:
                plan.dirty.discard(post_id)
                continue
            self._file.write(_update_line(post_id, fields) + "\n")
            self.flushed[post_id] = {f: s for f, (_, s) in fields.items()}
            self.posts_updated += 1
            self.fields_filled += len(fields)
            for field, (_, source) in fields.items():
                self.by_field[field] = self.by_field.get(field, 0) + 1
                self.by_source.setdefault(field, {}).setdefault(source, 0)
                self.by_source[field][source] += 1
            plan.dirty.discard(post_id)
        self._file.flush()
        os.fsync(self._file.fileno())
        self._write_progress()
        if take:
            elapsed = time.monotonic() - self._started_monotonic
            print(f"[·] batch: {self.posts_updated:,} posts "
                  f"({self.fields_filled:,} fields) in {elapsed:.0f}s",
                  flush=True)
        return len(take)

    def finish(self, plan: Plan) -> int:
        """Flush the remainder, close the transaction, write the final
        progress. Returns the total posts written (resumed + this run)."""
        self.flush(plan, force=True)
        if self._file is not None:
            self._file.write(
                f"-- posts updated: {self.posts_updated} · "
                f"fields filled: {self.fields_filled}\n")
            self._file.write("COMMIT;\n")
            self._file.flush()
            os.fsync(self._file.fileno())
            self._file.close()
            self._file = None
        self._write_progress(complete=True)
        return self.posts_updated

    def _write_progress(self, *, complete: bool = False) -> None:
        progress = {
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "db": self.meta.get("db", ""),
            "started_at": self.started_at,
            "resumed_at": self.resumed_at,
            "last_batch_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "finished_at": (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                            if complete else None),
            "complete": complete,
            "batch_size": self.batch_size,
            "posts_updated": self.posts_updated,
            "fields_filled": self.fields_filled,
            "by_field": self.by_field,
            "by_source": self.by_source,
            "resumed_posts": self.resumed_posts,
            "resumed_fields": self.resumed_fields,
            "ambiguous_count": len(self.ambiguous),
            "ambiguous": self.ambiguous,
            "notes": [
                "A staging file without a closing COMMIT; is an interrupted "
                "run — resume it with --resume, never apply it.",
            ],
        }
        tmp = self.progress_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(progress, indent=2, ensure_ascii=False),
                       encoding="utf-8")
        os.replace(tmp, self.progress_path)


def apply_to_copy(src_db: Path, sql_path: Path, out_db: Path) -> dict:
    """Materialise a copy of the source database with the staging SQL applied.
    The copy is made with the sqlite3 backup API (a consistent snapshot,
    never a plain file copy of a live WAL database). Refuses when the output
    is the input, the live catalog, or an existing file."""
    src_resolved = src_db.resolve()
    out_resolved = out_db.resolve()
    if out_resolved == src_resolved:
        raise ValueError("--apply-to must name a different file than --db")
    if out_resolved == Path(LIVE_CATALOG).resolve():
        raise ValueError("--apply-to refuses the live catalog path")
    if out_resolved.exists():
        raise ValueError(f"--apply-to target already exists: {out_db} (remove it first)")
    sql_text = sql_path.read_text(encoding="utf-8")
    statements = sum(1 for line in sql_text.splitlines()
                     if line.startswith("UPDATE posts SET"))
    src = sqlite3.connect(f"file:{urllib.parse.quote(str(src_db))}?mode=ro", uri=True)
    try:
        dst = sqlite3.connect(out_db)
        try:
            src.backup(dst)
        finally:
            src.close()
        with dst:
            dst.executescript(sql_text)
        integrity = dst.execute("PRAGMA integrity_check").fetchone()[0]
        changed = dst.execute("SELECT COUNT(*) FROM posts").fetchone()[0]
    finally:
        dst.close()
    return {"statements": statements, "integrity": integrity, "posts": changed}


# ── orchestrator ─────────────────────────────────────────────────────────────

def _target_path(args) -> Path:
    if args.db:
        return Path(args.db).expanduser()
    if args.site:
        return config.staging_db_path(args.site)
    return Path(config.DB_PATH)


def _build_sources(cache: ResponseCache, wanted_sources: list[str],
                   timeout: float, min_interval: float) -> dict:
    """One instance per requested source class, keyed by NAME. A source with
    a missing key is still built (so the report can name it disabled) but is
    never called."""
    sources: dict[str, Source] = {}
    for cls in SOURCE_CLASSES:
        if wanted_sources and cls.NAME not in wanted_sources:
            continue
        source = cls(cache, timeout=timeout)
        if min_interval > 0:
            source.http.pace.min_interval = max(
                source.http.pace.min_interval, min_interval)
        sources[cls.NAME] = source
    return sources


def build_sources(cache: ResponseCache, wanted_sources: list[str] | None = None,
                  *, timeout: float = 10.0, min_interval: float = 0.0) -> dict:
    """Public entry for the live-fill route (``POST /api/v2/enrich``): one
    instance per source class, keyed by NAME. A source with a missing key
    is still built (so the report can name it disabled) but is never
    called. The batch calls ``_build_sources`` directly with its own
    pacing; the live route wants the 10 s upstream timeout and no
    inter-request pacing (the app fires it in the background)."""
    return _build_sources(cache, wanted_sources or [], timeout, min_interval)


def run(conn: sqlite3.Connection, *, wanted: list[str], sources: dict,
        datasets: ImdbDatasets | None, cache: ResponseCache,
        limit: int = 0, after_id: int = 0, workers: int = DEFAULT_WORKERS,
        force: bool = False, writer=None) -> tuple[Plan, dict]:
    """The four passes, in priority order. Returns (plan, report-stats).
    With a writer, each pass streams its filled posts to disk in batches
    as it goes — a killed run loses at most one batch."""
    columns = post_columns(conn)
    if "posts" not in {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}:
        raise ValueError("database has no posts table")
    available = [f for f in wanted if FIELD_COLUMN[f] in columns]
    if not available:
        raise ValueError("posts has none of the fillable columns")
    plan = Plan()
    stats: dict = {"wanted": available, "passes": {}}
    if writer is not None:
        for post_id, fields in writer.flushed.items():
            plan.seed(post_id, fields)
        stats["resumed_posts"] = writer.resumed_posts
        stats["resumed_fields"] = writer.resumed_fields

    site_first = plan_site_pass(conn, columns, available, plan)
    stats["passes"]["site"] = {k: v for k, v in site_first.items()
                               if k != "key_had"}
    key_had = site_first.get("key_had", {})
    if writer is not None:
        writer.flush(plan)

    if datasets is not None:
        stats["passes"]["imdb_datasets"] = plan_datasets_pass(
            conn, columns, available, datasets, plan)
        if writer is not None:
            writer.flush(plan)

    if sources:
        stats["passes"]["api"] = plan_api_pass(
            conn, columns, available, sources, plan,
            limit=limit, after_id=after_id, workers=workers, force=force,
            writer=writer)

    # Propagation: an API/datasets win on one post now reaches its siblings.
    site_second = plan_site_pass(conn, columns, available, plan,
                                 include_plan_donors=True)
    stats["passes"]["site_propagate"] = {k: v for k, v in site_second.items()
                                        if k != "key_had"}
    if writer is not None:
        writer.flush(plan, force=True)

    stats["posts_updated"] = len(plan.updates)
    stats["fields_filled"] = plan.count()
    stats["by_field"] = plan.by_source()
    stats["works_improved"] = estimate_work_gains(key_had, plan, available)
    stats["sources"] = {
        name: {"enabled": source.enabled, "calls": source.calls,
               "cache_hits": source.cache_hits, "exhausted": source.exhausted}
        for name, source in sources.items()
    }
    ambiguous: list[dict] = []
    for source in sources.values():
        ambiguous.extend(source.ambiguities)
    stats["ambiguous"] = ambiguous
    stats["ambiguous_count"] = len(ambiguous)
    if writer is not None:
        writer.ambiguous = ambiguous
    cache.flush()
    return plan, stats


# ── live fill (one work, on detail open) ─────────────────────────────────────

# SQL literals that mean "empty" — the same set is_empty_value() checks in
# Python, plus "0": an int 0 is empty in Python, and a stored "0" is never
# a real value for any fillable field (a 0 rating is unrated, year 0 is
# invalid, id "0" is no id). A row the plan thinks is empty is still empty
# when the UPDATE lands, so two detail opens racing each other cannot
# overwrite.
_EMPTY_SQL = ("", "[]", "{}", "n/a", "na", "none", "null", "unknown", "unk",
              "0")


def _candidate_from_row(row, key, missing) -> dict:
    """The API-pass candidate shape for one post row — the same dict
    _representatives builds, shared so the batch and the live fill ask the
    APIs about the same post in the same words."""
    return {
        "id": row["id"], "key": key,
        "title": row["title"], "clean_title": row["clean_title"],
        "year": row["year"], "content_type": row["content_type"],
        "seasons": row["seasons"], "imdb_id": row["imdb_id"],
        "tmdb_id": row["tmdb_id"],
        "animated": bool(row["is_animated"]) if "is_animated" in row.keys() else False,
        # Disambiguation context (site metadata is primary): the post's own
        # language/country/industry, plus its known cast/director for the
        # overlap tiebreak.
        "language": row["language"] if "language" in row.keys() else None,
        "country": row["country"] if "country" in row.keys() else None,
        "industry": row["industry"] if "industry" in row.keys() else None,
        "cast": split_names(row[FIELD_COLUMN["cast"]])
                if FIELD_COLUMN["cast"] in row.keys() else [],
        "director": split_names(row[FIELD_COLUMN["director"]])
                if FIELD_COLUMN["director"] in row.keys() else [],
        "missing": missing,
    }


def _row_key(row) -> str:
    """A post's group key: its canonical_key, or a per-post key when it has
    none — a keyless post is its own group."""
    key = row["canonical_key"] if "canonical_key" in row.keys() else None
    return (key if key and str(key).strip().lower() != "unknown"
            else f"post:{row['id']}")


def _group_rows(conn: sqlite3.Connection, columns: set[str], *,
                post_id=None, work_key=None, post_ids=None):
    """Rows for one live-fill group: the post and its siblings under the
    same canonical_key, or every post sharing a work key, or — for a
    work_id the route resolved through cat_work_sites — the union of the
    canonical_key groups of the given posts (one work can span several
    keys). A keyless post is its own group. Rows carry the same columns
    _stream_groups yields, so the passes read them the same way."""
    wanted = [c for c in ROW_COLUMNS if c in columns]
    fillable = [FIELD_COLUMN[f] for f in FIELDS if FIELD_COLUMN[f] in columns]
    select = ", ".join(wanted + fillable)
    conn.row_factory = sqlite3.Row
    key = work_key
    if post_id is not None:
        one = conn.execute(
            f"SELECT {select} FROM posts WHERE id = ?", (post_id,)).fetchone()
        if one is None:
            return []
        key = one["canonical_key"] if "canonical_key" in one.keys() else None
        if not key or str(key).strip().lower() == "unknown":
            return [one]
    if post_ids is not None:
        rows = []
        keys = set()
        for pid in post_ids:
            one = conn.execute(
                f"SELECT {select} FROM posts WHERE id = ?", (pid,)).fetchone()
            if one is None:
                continue
            key = one["canonical_key"] if "canonical_key" in one.keys() else None
            if not key or str(key).strip().lower() == "unknown":
                rows.append(one)
            else:
                keys.add(key)
        for key in sorted(keys):
            rows.extend(conn.execute(
                f"SELECT {select} FROM posts WHERE canonical_key = ? ORDER BY id",
                (key,)).fetchall())
        seen: set[int] = set()
        out = []
        for row in sorted(rows, key=lambda r: r["id"]):
            if row["id"] not in seen:
                seen.add(row["id"])
                out.append(row)
        return out
    return conn.execute(
        f"SELECT {select} FROM posts WHERE canonical_key = ? ORDER BY id",
        (key,)).fetchall()


def _display_value(field: str, value) -> str | None:
    """The value as the detail screen shows it: the stored shape, except
    genres, which the posts table keeps as a JSON list string while the
    screen shows a comma-joined list."""
    if value is None:
        return None
    if field == "genres":
        try:
            names = json.loads(value) if isinstance(value, str) else value
        except ValueError:
            names = None
        if isinstance(names, list):
            joined = ", ".join(str(n) for n in names if str(n).strip())
            return joined or None
        return str(value)
    if field == "year":
        year = parse_year(value)
        return str(year) if year is not None else None
    return str(value)


def _apply_plan(conn: sqlite3.Connection, plan: Plan) -> list[int]:
    """Write a plan into the caller's connection, one UPDATE per post, each
    with a fill-only-empty guard on every column. Returns the post ids
    actually changed. The caller owns the transaction (the api_v2 request
    session commits); this function never commits and never opens a
    connection."""
    updated: list[int] = []
    guard = ", ".join("?" for _ in _EMPTY_SQL)
    for post_id, fields in plan.updates.items():
        sets: list[str] = []
        values: list = []
        guards: list[str] = []
        guard_params: list = []
        for field, (value, _source) in fields.items():
            column = FIELD_COLUMN[field]
            sets.append(f"{column} = ?")
            values.append(value)
            guards.append(f"({column} IS NULL OR "
                          f"LOWER(TRIM(CAST({column} AS TEXT))) IN ({guard}))")
            guard_params.extend(_EMPTY_SQL)
        sql = (f"UPDATE posts SET {', '.join(sets)} WHERE id = ? "
               f"AND {' AND '.join(guards)}")
        cur = conn.execute(sql, values + [post_id] + guard_params)
        if cur.rowcount > 0:
            updated.append(post_id)
    return updated


def _fill_result(status: str) -> dict:
    return {"status": status, "filled": {}, "sources": {}, "ambiguous": [],
            "posts_updated": [], "fields_filled": 0, "calls": {},
            "filled_post": {}}


def fill_one(conn, *, post_id=None, work_key=None, post_ids=None, wanted=None,
             sources, datasets=None, budget=None, cache=None) -> dict:
    """Fill the missing metadata of ONE work (or one keyless post), live.

    The detail-open path: the same four passes as run(), at group scope —
    site siblings, IMDb datasets (the live route passes none: loading the
    TSVs per request is too heavy; OMDb/TMDb cover ratings anyway), API via
    _resolve_representative (which runs the v2 choose_candidate — language /
    industry first, cast/director overlap, still tied = fill NOTHING), then
    propagation to siblings. The group is named by exactly one of post_id
    (the post plus its canonical_key siblings), work_key (every post under
    that key), or post_ids (the union of the canonical_key groups of the
    given posts — how the route answers a work_id, since one work can span
    several keys). Writes go through _apply_plan into the caller's
    connection; this function never commits and never opens a connection.
    The response carries the work-level value for every fillable field
    that has one after the attempt — newly filled OR already stored in a
    sibling post — so the caller can show them at once instead of waiting
    for the next projection run (which is what carries the values into
    cat_works). That echo is also what heals a stale projection: the app
    asks because its cat_works row is blank, and the answer carries the
    value the posts already hold. The caller merges blanks-only, so
    values it already shows are harmless. Statuses: filled (the attempt
    wrote new values) | already_complete (nothing to write) | no_match |
    ambiguous | not_found | skipped_adult."""
    given = [x for x in (post_id, work_key, post_ids) if x is not None]
    if len(given) != 1:
        raise ValueError(
            "fill_one needs exactly one of post_id, work_key, post_ids")
    columns = post_columns(conn)
    available = [f for f in (wanted or list(FIELDS)) if FIELD_COLUMN[f] in columns]
    if not available:
        raise ValueError("posts has none of the fillable columns")
    rows = _group_rows(conn, columns, post_id=post_id, work_key=work_key,
                       post_ids=post_ids)
    if not rows:
        return _fill_result("not_found")
    if any("is_18_plus" in row.keys() and int(row["is_18_plus"] or 0)
           for row in rows):
        return _fill_result("skipped_adult")
    # The representative's key: the work key when given, else the first
    # row's key. plan.post_key is per row — one work can span several
    # canonical keys, and the report must see each.
    key = work_key
    if key is None:
        key = _row_key(rows[0])
    plan = Plan()
    for row in rows:
        plan.post_key[row["id"]] = _row_key(row)
    # Work-level missing BEFORE the attempt: the fields the detail shows blank.
    missing_before = [f for f in available
                      if all(is_empty_value(r[FIELD_COLUMN[f]]) for r in rows)]
    # Pass 1 — site siblings.
    donors = _donor_values(rows, available, plan)
    for row in rows:
        for field, (value, source) in donors.items():
            if _field_missing(row, field, plan):
                plan.add(row["id"], field, value, source)
    # Pass 2 — IMDb datasets (live: none, see the docstring).
    if datasets is not None and (datasets.ratings or datasets.basics
                                 or datasets.crew):
        for row in rows:
            imdb_id = row["imdb_id"]
            if not imdb_id:
                continue
            meta = datasets.metadata_for(imdb_id)
            if meta.is_empty():
                continue
            limited = Metadata()
            for field in meta.fields:
                if field not in available:
                    continue
                if _field_missing(row, field, plan):
                    limited.set(field, meta.fields[field],
                                meta.sources.get(field, "?"))
            if not limited.is_empty():
                plan.add_metadata(row["id"], limited, columns)
    # Pass 3 — API, on the representative (emptiest post first).
    if sources:
        if budget is not None:
            for name, source in sources.items():
                if source.DAILY_BUDGET is not None:
                    source.calls = budget.count(name)
        candidates = []
        for row in rows:
            missing = [f for f in available if _field_missing(row, f, plan)]
            if missing:
                candidates.append(_candidate_from_row(row, key, missing))
        if candidates:
            candidates.sort(key=lambda c: (-len(c["missing"]), c["id"]))
            rep = {"id": candidates[0]["id"], "key": key,
                   "candidates": candidates[:3]}
            winner, meta, _calls = _resolve_representative(
                rep, sources, plan, columns)
            if winner is not None and not meta.is_empty():
                plan.add_metadata(winner, meta, columns)
        if budget is not None:
            for name, source in sources.items():
                if source.DAILY_BUDGET is not None:
                    budget.record(name, source.calls)
    # Pass 4 — propagation to siblings.
    donors = _donor_values(rows, available, plan, include_plan_donors=True)
    for row in rows:
        for field, (value, source) in donors.items():
            if _field_missing(row, field, plan):
                plan.add(row["id"], field, value, source)
    updated = _apply_plan(conn, plan)
    # Work-level values after the attempt, for every fillable field that
    # has one: the first non-empty value across the rows (id order), stored
    # or newly planned. filled_post names the post each value was read
    # from, so the route can mint the artwork handles against the post
    # that actually holds the art.
    filled: dict[str, str] = {}
    filled_sources: dict[str, str] = {}
    filled_post: dict[str, int] = {}
    for field in available:
        for row in rows:
            stored = row[FIELD_COLUMN[field]]
            if not is_empty_value(stored):
                text = _display_value(field, stored)
                if text is not None:
                    filled[field] = text
                    filled_sources[field] = "site"
                    filled_post[field] = row["id"]
                break
            planned = plan.updates.get(row["id"], {}).get(field)
            if planned is not None:
                text = _display_value(field, planned[0])
                if text is not None:
                    filled[field] = text
                    filled_sources[field] = planned[1]
                    filled_post[field] = row["id"]
                break
    ambiguous = []
    group_ids = {row["id"] for row in rows}
    for source in sources.values():
        for entry in source.ambiguities:
            if entry.get("post_id") in group_ids:
                ambiguous.append(entry)
    if cache is not None:
        cache.flush()
    if plan.count() > 0:
        status = "filled"
    elif not missing_before:
        status = "already_complete"
    elif ambiguous:
        status = "ambiguous"
    else:
        status = "no_match"
    return {
        "status": status,
        "filled": filled,
        "sources": filled_sources,
        "ambiguous": ambiguous,
        "posts_updated": updated,
        "fields_filled": plan.count(),
        "filled_post": filled_post,
        "calls": {name: {"calls": source.calls,
                         "cache_hits": source.cache_hits,
                         "exhausted": source.exhausted}
                  for name, source in sources.items()},
    }


def build_report(db_path: Path, plan: Plan, stats: dict,
                 sources: dict) -> dict:
    """The JSON report: what was filled, from where, and what is still
    missing a key. The rating source is recorded here (and in the SQL
    comments) because there is no column for it in the database."""
    disabled = [name for name, source in sources.items() if not source.enabled]
    report = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "db": str(db_path),
        "wanted_fields": stats["wanted"],
        "posts_updated": stats["posts_updated"],
        "fields_filled": stats["fields_filled"],
        "by_field": stats["by_field"],
        "works_improved_estimate": stats["works_improved"],
        "passes": stats["passes"],
        "sources": stats["sources"],
        "keys_missing": disabled,
        "notes": [
            "Ratings are stored as one-decimal strings (\"7.5\"); a rating "
            "with source=tmdb is a TMDb vote_average, not an IMDb rating.",
            "works_improved_estimate counts works whose first non-empty "
            "post value appears only after this plan is applied and the v2 "
            "projection re-runs.",
            "Disabled sources were skipped for a missing key; set the key "
            "in the environment and re-run to fill what they alone cover.",
            "ambiguous_review lists the posts no source would fill because "
            "two or more same-title-same-year candidates tied — a human "
            "checks them; blank beats wrong.",
        ],
    }
    if stats.get("resumed_posts"):
        report["resumed_posts"] = stats["resumed_posts"]
        report["resumed_fields"] = stats["resumed_fields"]
    if stats.get("ambiguous"):
        report["ambiguous_review"] = stats["ambiguous"]
    return report


# ── CLI ──────────────────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__.splitlines()[0],
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    target = parser.add_mutually_exclusive_group()
    target.add_argument("--db", type=Path,
                        help="SQLite database (default: config.DB_PATH)")
    target.add_argument("--site",
                        help="Use that site's staging database")
    parser.add_argument("--out", type=Path, default=Path("metadata-backfill.sql"),
                        help="Staging SQL output (default metadata-backfill.sql)")
    parser.add_argument("--report", type=Path, default=None,
                        help="JSON report output (default: <out> with .json)")
    parser.add_argument("--cache-dir", type=Path, default=None,
                        help="Upstream-response cache dir (default "
                             "~/.cache/radd-stream-metadata)")
    parser.add_argument("--imdb-datasets", type=Path, default=None,
                        help="Directory holding title.ratings.tsv.gz (and "
                             "optionally title.basics.tsv.gz, title.crew.tsv.gz)")
    parser.add_argument("--fetch-imdb-datasets", action="store_true",
                        help="Download the IMDb TSVs into --imdb-datasets first")
    parser.add_argument("--sources", default="",
                        help="Comma-separated sources to use (default: all "
                             "that are enabled). Names: "
                             + ", ".join(cls.NAME for cls in SOURCE_CLASSES))
    parser.add_argument("--only", default="",
                        help="Comma-separated fields to fill (default: all): "
                             + ", ".join(FIELDS))
    parser.add_argument("--limit", type=int, default=0,
                        help="API pass: at most this many works (0 = all)")
    parser.add_argument("--after-id", type=int, default=0,
                        help="API pass: resume after this representative post id")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS,
                        help=f"Network workers (default {DEFAULT_WORKERS})")
    parser.add_argument("--min-interval", type=float, default=0.0,
                        help="Floor on every source's seconds between requests")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                        help=f"Per-request timeout in seconds (default {DEFAULT_TIMEOUT:g})")
    parser.add_argument("--force", action="store_true",
                        help="API pass: re-resolve rows that already have a "
                             "value (site/datasets values still win ties)")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE,
                        help=f"Streaming: posts written per staging-SQL batch "
                             f"(default {DEFAULT_BATCH_SIZE}); a crash loses "
                             "at most one batch")
    parser.add_argument("--resume", action="store_true",
                        help="Resume a killed run: read the existing staging "
                             "SQL + progress file and skip already-filled posts")
    parser.add_argument("--progress", type=Path, default=None,
                        help="JSON progress output (default: <out> with "
                             ".progress.json)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Query upstreams and report, but write no files")
    parser.add_argument("--apply-to", type=Path, default=None,
                        help="Also materialise a COPY of --db with the staging "
                             "SQL applied (refuses the live path and existing files)")
    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if (args.limit < 0 or args.after_id < 0 or args.workers < 1
            or args.batch_size < 1):
        parser.error("--limit/--after-id must be >= 0, --workers/--batch-size >= 1")
    if args.fetch_imdb_datasets and not args.imdb_datasets:
        parser.error("--fetch-imdb-datasets needs --imdb-datasets")
    wanted = [f.strip() for f in args.only.split(",") if f.strip()] or list(FIELDS)
    unknown = [f for f in wanted if f not in FIELDS]
    if unknown:
        parser.error(f"unknown field(s): {', '.join(unknown)} (fields: {', '.join(FIELDS)})")
    wanted_sources = [s.strip() for s in args.sources.split(",") if s.strip()]
    known_sources = {cls.NAME for cls in SOURCE_CLASSES}
    unknown_sources = [s for s in wanted_sources if s not in known_sources]
    if unknown_sources:
        parser.error(f"unknown source(s): {', '.join(unknown_sources)}")

    try:
        db_path = _target_path(args)
        conn = connect_readonly(db_path)
    except ValueError as error:
        print(f"[!] {error}", file=sys.stderr)
        return 2

    cache_dir = args.cache_dir or Path.home() / ".cache" / "radd-stream-metadata"
    cache = ResponseCache(cache_dir / "responses.json")
    datasets = None
    if args.imdb_datasets:
        datasets = ImdbDatasets(args.imdb_datasets)
        try:
            if args.fetch_imdb_datasets:
                fetched = datasets.download()
                print(f"[·] IMDb datasets: fetched {', '.join(fetched)}", flush=True)
            ratings = datasets.load_ratings()
            print(f"[·] IMDb datasets: {ratings:,} rated titles loaded", flush=True)
            basics_path = args.imdb_datasets / ImdbDatasets.BASICS_FILE
            if basics_path.exists():
                print(f"[·] IMDb datasets: {datasets.load_basics():,} basics loaded",
                      flush=True)
            crew_path = args.imdb_datasets / ImdbDatasets.CREW_FILE
            if crew_path.exists():
                print(f"[·] IMDb datasets: {datasets.load_crew():,} crew rows loaded",
                      flush=True)
        except (OSError, ValueError, EOFError) as error:
            print(f"[!] IMDb datasets unreadable: {error}", file=sys.stderr)
            return 2

    sources = _build_sources(cache, wanted_sources, args.timeout, args.min_interval)
    enabled = [name for name, s in sources.items() if s.enabled]
    disabled = [name for name, s in sources.items() if not s.enabled]
    print(f"[·] {db_path.name}: sources on: {', '.join(enabled) or 'none'}"
          + (f" · off (no key): {', '.join(disabled)}" if disabled else ""),
          flush=True)
    generated_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    meta = {"generated_at": generated_at, "db": str(db_path)}
    writer = None
    if not args.dry_run:
        progress_path = args.progress or args.out.with_suffix(".progress.json")
        writer = BatchWriter(args.out, progress_path, meta,
                             batch_size=args.batch_size)
        writer.open(resume=args.resume)
    try:
        plan, stats = run(
            conn, wanted=wanted, sources=sources, datasets=datasets,
            cache=cache, limit=args.limit, after_id=args.after_id,
            workers=args.workers, force=args.force, writer=writer)
    except ValueError as error:
        print(f"[!] {error}", file=sys.stderr)
        return 2
    finally:
        conn.close()

    report = build_report(db_path, plan, stats, sources)
    report["generated_at"] = generated_at
    if args.dry_run:
        print(json.dumps(report, indent=2, ensure_ascii=False))
        print(f"[·] dry run — {stats['posts_updated']} posts would be updated, "
              f"{stats['fields_filled']} fields filled; nothing written")
        return 0

    wrote = writer.finish(plan)
    report_path = args.report or args.out.with_suffix(".json")
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False),
                           encoding="utf-8")
    print(f"[✓] {wrote} posts in {args.out} ({writer.fields_filled} fields) · "
          f"report in {report_path}")
    for field, count in sorted(stats["works_improved"].items()):
        if count:
            print(f"    works gaining {field}: ~{count:,}")
    if args.apply_to:
        try:
            result = apply_to_copy(db_path, args.out, args.apply_to)
        except ValueError as error:
            print(f"[!] {error}", file=sys.stderr)
            return 2
        print(f"[✓] copy at {args.apply_to}: {result['statements']} statements, "
              f"integrity {result['integrity']}, {result['posts']:,} posts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
