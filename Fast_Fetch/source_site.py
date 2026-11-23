#!/usr/bin/env python3
"""Derive a post's ``source_site`` from its page URL.

Why this module exists: ``posts.source_site`` defaults to ``'vegamovies'``
in the schema, and most importers never set it, so ~105,000 live rows
(2026-11-23, measured read-only against the live catalog) sit under the
``vegamovies`` label while their page lives on another site. The page URL
is the one honest signal — a post IS the page at its URL — so the
misattribution backfill (``backfill_source_site.py``) and the
``save_scraped_data`` guard in ``fast_fetch.py`` both derive the site from
the URL host through this one mapping.

Rules, in match order:

* exact hosts first — aliases that do not contain the site slug
  (``bulwik.com`` is castle, ``hdutv.com`` is hdtvb, ``filmiy.in`` is
  filmiyin, ``hum.tv`` is humtv);
* then a substring match on the host — the host carries the site slug
  (``new1.vegamovies.lease`` is vegamovies, ``www.mp4moviez.deal`` is
  mp4moviez, ``beta.levidia.ch`` is levidia). Matching the HOST, not the
  whole URL, avoids the false positives a raw ``LIKE '%castle%'`` gives
  (castlevania titles on other sites).

Labels are the importer ``SITE`` values where one exists (levidia,
archive.org, humtv, mp4moviez, moviesflix, kuttymovies, megacartoons,
5movierulz, moviecok, filmyhit, filmywap, kisskh), else the registry site
name. An unknown host returns ``None`` — the caller leaves the row alone
and reports it, never guesses.

Stdlib only.
"""
from __future__ import annotations

from urllib.parse import urlparse

#: Exact hosts whose label is not a substring of the host.
_HOST_EXACT = {
    "bulwik.com": "castle",
    "hdutv.com": "hdtvb",
    "filmiy.in": "filmiyin",
    "hum.tv": "humtv",
}

#: (host substring, site label). Longest slug first, so a more specific
#: host always wins over a shorter slug that happens to contain it.
_HOST_SUBSTRINGS = (
    ("hindilinks4u", "hindilinks4u"),
    ("toonworld4all", "toonworld4all"),
    ("hindidubbedmovies", "hindidubbedmovies"),
    ("hindianimeszone", "hindianimeszone"),
    ("themoviebox", "themoviebox"),
    ("movienestbd", "movienestbd"),
    ("multimovies", "multimovies"),
    ("moviesdrive", "moviesdrive"),
    ("moviezguru", "moviezguru"),
    ("megacartoons", "megacartoons"),
    ("kuttymovies", "kuttymovies"),
    ("katmoviehd", "katmoviehd"),
    ("filmyzilla", "filmyzilla"),
    ("world4ufree", "world4ufree"),
    ("vegamovies", "vegamovies"),
    ("moviesflix", "moviesflix"),
    ("moviesmela", "moviesmela"),
    ("mp4moviez", "mp4moviez"),
    ("pagalmovies", "pagalmovies"),
    ("5movierulz", "5movierulz"),
    ("movierulz", "5movierulz"),
    ("desicinemas", "desicinemas"),
    ("desiserials", "desiserials"),
    ("filmyhit", "filmyhit"),
    ("filmywap", "filmywap"),
    ("ssrmovies", "ssrmovies"),
    ("rogmovies", "rogmovies"),
    ("rareanimes", "rareanimes"),
    ("moviecok", "moviecok"),
    ("moviehax", "moviehax"),
    ("dramadog", "dramadog"),
    ("dramacool", "dramacool"),
    ("animeworld", "animeworld"),
    ("xprimehub", "xprimehub"),
    ("bollyflix", "bollyflix"),
    ("archive.org", "archive.org"),
    ("levidia", "levidia"),
    ("kisskh", "kisskh"),
    ("aryplus", "aryplus"),
    ("animahd", "animahd"),
    ("javmost", "javmost"),
    ("missav", "missav"),
    ("hdhub4u", "hdhub4u"),
    ("bolly4u", "bolly4u"),
    ("10xflix", "10xflix"),
    ("9xmovie", "9xmovies"),  # the live host is singular: 9xmovie.bayern
    ("7starhd", "7starhd"),
    ("hdtvb", "hdtvb"),
    ("harpalgeo", "harpalgeo"),
)


def host_of(url: str | None) -> str:
    """Lowercased hostname of a URL, ``www.`` stripped; ``''`` when unparseable."""
    if not url:
        return ""
    try:
        host = (urlparse(str(url)).hostname or "").lower()
    except (ValueError, AttributeError, TypeError):
        return ""
    if host.startswith("www."):
        host = host[4:]
    return host


def site_for_host(host: str | None) -> str | None:
    """Site label for a hostname, or ``None`` when the host is unknown."""
    if not host:
        return None
    host = str(host).lower()
    if host.startswith("www."):
        host = host[4:]
    if host in _HOST_EXACT:
        return _HOST_EXACT[host]
    for slug, site in _HOST_SUBSTRINGS:
        if slug in host:
            return site
    return None


def site_for_url(url: str | None) -> str | None:
    """Site label for a post URL, or ``None`` when its host is unknown."""
    return site_for_host(host_of(url))
