#!/usr/bin/env python3
"""
unwrap_mp4moviez.py — resolve a stored mp4moviez dl.php link to direct media.

The converter (import_mp4moviez.py) stores the site's own redirect endpoint
and never follows it, so all 53,096 mp4moviez rows in the live catalog carry
``final_url = NULL`` and no sibling row under any other service — 10,560 posts
have no other source at all. This module is the missing follow step.

LIVE FLOW — proven 2026-11-23 from Oracle VM egress, plain urllib, no
browser, no cookies, no challenge (probe: ~/workspace/sweep-probes/
probe_mp4moviez_dlphp.py, probe_mp4moviez_dlphp2.py, probe_mp4moviez_dlphp3.py;
two distinct stored ids, q=480 and q=720):

    GET www.mp4moviez.computer/dl.php?id=<n>&q=<q>&title=<slug>
        --301--> www.mp4moviez11.com/dl.php?id=<n>&q=<q>&title=<slug>
        --302--> dl2.fastxmp4.com/download/<n>/<slug>--<q>p-[Orgmovies].mp4?e=..&st=..
        --200--> video/mp4, Range bytes=0-0 -> 206

Measured timings (Oracle egress, ~1 req/s pacing):
    hop 1 (301)            57-65 ms
    hop 2 (302)           206-258 ms
    hop 3 (200 video/mp4) 521-523 ms
    full 3-hop resolve    788-833 ms
    Range 206 check       479-481 ms (file ~313-328 MB: 326,751,422 /
                          328,540,975 bytes for the two probed ids)
    follow-all GET incl. 200 KB body read: 1,371 ms

Three facts shape this module, each measured, not assumed:

1. The final filename is built from the ``title`` query value, not looked up
   by id: the same id with a wrong title still 302s, but to a filename that
   404s on fastxmp4. So the unwrapper MUST follow the site's redirects and
   must never construct the fastxmp4 URL itself.
2. The fastxmp4 URL carries signed query params (``e``, ``st``) minted by the
   site's 302. Their expiry was not measured, so the resolved link is banked
   as temporary (``is_temporary = 1``) — resolve at play time, do not serve a
   stored one past its unknown lifetime.
3. The stored host (``.computer``) is one 301 behind the live host
   (``mp4moviez11.com``). Domain rotation is handled by following the 301,
   not by rewriting the stored URL.

Stdlib only. No database access, no writes, no download: the final response
is read for exactly one byte, which is enough to prove the 200 + video/*
content type. A failure (dead id, wrong title, non-media final, network
error) returns an empty list — never a bridge URL dressed up as media.
"""

from __future__ import annotations

import re
import time
import urllib.error
import urllib.parse
import urllib.request

#: Hosts a stored dl.php URL may live on. The live chain 301s from
#: ``.computer`` to ``mp4moviez11.com``; both are accepted so a stored row
#: and a re-resolved row validate the same way. Any other host is refused —
#: a dl.php on an unrelated domain is not this site's endpoint.
DL_HOSTS = frozenset({
    "mp4moviez.computer",
    "www.mp4moviez.computer",
    "mp4moviez11.com",
    "www.mp4moviez11.com",
})

#: The byte host the 302 lands on (measured live, both probed ids).
FINAL_HOST_SUFFIX = ".fastxmp4.com"

#: Media suffixes / content types that count as a playable final answer.
_MEDIA_SUFFIXES = (".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".m3u8")
_USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
               "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

DL_ID_RE = re.compile(r"[?&]id=(?P<id>\d+)")
DL_QUALITY_RE = re.compile(r"[?&]q=(?P<q>\d+)")


def parse_dl_url(url: str | None) -> dict | None:
    """Split a stored dl.php URL into its parts, or None when it is not one.

    Returns ``{"id": ..., "quality": ..., "title": ..., "host": ...}``.
    ``title`` may be empty (some rows store a bare id); ``id`` and
    ``quality`` are required — a dl.php link without them resolves nowhere.
    """
    if not url or not isinstance(url, str):
        return None
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        return None
    if parts.scheme not in ("http", "https"):
        return None
    if parts.netloc.lower() not in DL_HOSTS:
        return None
    if parts.path != "/dl.php":
        return None
    query = urllib.parse.parse_qs(parts.query)
    ids = query.get("id")
    quals = query.get("q")
    if not ids or not ids[0].isdigit() or not quals or not quals[0].isdigit():
        return None
    titles = query.get("title") or [""]
    return {"id": ids[0], "quality": quals[0], "title": titles[0],
            "host": parts.netloc.lower()}


def is_mp4moviez_dl_url(url: str | None) -> bool:
    """True when ``url`` is a stored mp4moviez dl.php endpoint."""
    return parse_dl_url(url) is not None


def _is_media(final_url: str, content_type: str | None) -> bool:
    if content_type and content_type.split(";")[0].strip().lower().startswith(
            ("video/", "audio/")):
        return True
    path = urllib.parse.urlsplit(final_url).path.lower()
    return path.endswith(_MEDIA_SUFFIXES)


def resolve_mp4moviez_dl(dl_url: str, timeout: float = 15.0) -> dict | None:
    """Follow one stored dl.php URL to its direct media URL.

    Returns ``{"final_url", "content_type", "content_length", "ms"}`` when
    the chain lands on media, else None. Reads exactly one byte of the final
    response — enough to prove the 200 + media content type, never a
    download. The redirect chain (301 to the live mp4moviez host, 302 to the
    signed fastxmp4 file) is followed by urllib's default handler.
    """
    parsed = parse_dl_url(dl_url)
    if parsed is None:
        return None
    headers = {"User-Agent": _USER_AGENT,
               "Referer": f"https://{parsed['host']}/",
               "Accept": "video/*,*/*;q=0.8"}
    request = urllib.request.Request(dl_url, headers=headers)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read(1)
            final_url = response.geturl()
            content_type = response.headers.get("Content-Type")
            content_length = response.headers.get("Content-Length")
    except (urllib.error.HTTPError, urllib.error.URLError, OSError,
            ValueError):
        return None
    if not _is_media(final_url, content_type):
        return None
    return {"final_url": final_url, "content_type": content_type,
            "content_length": content_length,
            "ms": round((time.monotonic() - started) * 1000, 1)}


def unwrap_mp4moviez(dl_url: str, timeout: float = 15.0) -> list[dict]:
    """Resolve a stored mp4moviez dl.php link to its direct file row.

    Returns a one-element list in the ``unwrap_mobilejsr`` shape
    (``source_name`` / ``service`` / ``resource_id`` / ``source_url`` /
    ``final_url`` / ``is_temporary``), or ``[]`` when the link is dead,
    mistitled, non-media, or unreachable. ``service`` is the byte host
    (``fastxmp4``); ``is_temporary`` is 1 because the fastxmp4 URL carries
    server-minted signed params (``e``, ``st``) of unmeasured expiry — the
    dispatch layer should resolve at play time, not bank and serve stale.
    """
    parsed = parse_dl_url(dl_url)
    if parsed is None:
        return []
    resolved = resolve_mp4moviez_dl(dl_url, timeout=timeout)
    if resolved is None:
        return []
    quality = f"{parsed['quality']}p"
    return [{
        "source_name": f"mp4moviez {quality} download",
        "service": "fastxmp4",
        "resource_id": parsed["id"],
        "source_url": dl_url,
        "final_url": resolved["final_url"],
        "is_temporary": 1,
    }]
