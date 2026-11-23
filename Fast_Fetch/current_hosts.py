#!/usr/bin/env python3
"""Stage-1 proven-host resolver for Radd Stream (stdlib only).

Covers ONLY the hosts the research actually proved, from the host-knowledge
notes (patterns only — this file stores no keys, file codes, tokens, or
signed URLs, and it never stores a resolved signed URL: every resolve runs
fresh at play time):

- watch: iqsmart chain (StreamHG), modiplay backends (StreamHG + Vidrift
  + Vidara), vidrift direct, vidbolt, filmu movies, vibuxer signed HLS route.
- download: PixelDrain (resumable) and fastdl.beer (real file, NOT
  resumable — it ranks below every resumable host); hubdrive (ajax
  direct-download — needs the operator's RADD_HUBDRIVE_CRYPT login cookie,
  without it the ajax answers 408 and the copy fails
  "hubdrive_needs_cookie", never dead) and megaup (button link after a
  6 s wait, no-redirect Location; unreachable from Oracle egress
  2026-11-21, so unproven live); bbupload (ajax getdownload — no
  cookie, no wait, no captcha; a removed file still answers ok with
  null FileName/filesize, so that is the dead signal; the file host
  answers image/png on genuine Matroska bytes and honors ranges only
  via a ?range= query param, so not resumable); hubcdn (hdboss.in gateway:
  /cloud/<token> page -> /l.php 302s; the public R2 mirror is permanent and
  preferred, Google direct second, Gofile/Telegram banked as mirrors only —
  see _unwrap_hubcdn).

Rules this module enforces, wherever it is used:

1. Classify by the link's REAL host, never by the database service label.
   Mirror labels (gd-mirror, gdmirror, filesforever-*) are iqsmart-family
   shapes only when their link host says so; a label alone proves nothing.
2. Rank by BACKEND, never by front-end. modiplay resolving to StreamHG is
   the same backend as iqsmart, and modiplay resolving to Vidrift is the
   same backend as vidrift direct — neither pair counts as two independent
   backups. vidbolt and filmu/vidly share a media-host family, so they sit
   in one independence group ("rousav") and a truly different backend is
   always preferred as the second backup.
3. A copy is playable only after a capped probe of the media itself:
   a playlist that answers 200 with renditions, or a file that answers
   206 (resumable) / 200 with a video mime (playable, resumable only with
   Range support). An HTML page, shell, landing page, or bridge page is
   NEVER a video file, however cheerful its 200 looks.
4. Failure classes are honest and distinct: a 404 on the media is a dead
   file for that copy; a 403 is a block, not death; a 429 is rate-limited,
   not dead; a timeout/empty reply is a transport failure, not death; a
   signed master that 403s with a missing-key shape is gated, not dead.
   Only a failed probe keeps a copy out of a band — a stamped status in
   the ledger never ranks on its own, and a stamped-dead copy that passes
   a fresh probe is healed, not kept dead.
5. filmu series is NEVER served from static evidence: its signed master
   403s on a plain server fetch, so the server-side answer for a filmu
   series copy is "server-side unverified", never the signed link.
6. No probe ever full-reads a file: file probes are Range bytes=0-511
   and every body read is capped. HEAD is a hint only, never a verdict.
7. Quality bands come from the release, never from a playlist rendition:
   wide (2.40:1) renditions would mis-band on height alone, so this module
   reports rendition sizes and lets the caller band by release fields.
   HDR is a flag, never a band (that fix is a later slice, not this one).
"""

from __future__ import annotations

import base64
import json
import os
import re
import sys
import threading
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
import urllib.error
import urllib.parse
import urllib.request

from truelink_1fichier import _unwrap_1fichier

try:  # package-style import when run as Fast_Fetch/<module>.py
    from Fast_Fetch import warp_proxy
except ImportError:  # running from inside Fast_Fetch/ (the documented way)
    import warp_proxy  # type: ignore

try:  # same split as warp_proxy above: host_classifier is the trust
    # authority for what a hubcdn gateway Location may name (R2/Google).
    from Fast_Fetch import host_classifier as _hc
except ImportError:
    import host_classifier as _hc  # type: ignore

USER_AGENT = "Mozilla/5.0"
DEFAULT_TIMEOUT = 15.0
MAX_BODY_BYTES = 524288
FILE_PROBE_BYTES = 512

#: Backends this module can resolve. "modiplay" is a front-end, not a
#: backend: resolve_copy returns the backend it actually resolved to.
STAGE1_BACKENDS = frozenset({
    "streamhg", "vidrift", "vidbolt", "vidly", "vibuxer",
    "pixeldrain", "fastdl-beer", "vidara", "hubdrive", "megaup",
    "bbupload", "1fichier", "hubcdn",
})

#: Independence groups: two copies in one group are one backup, not two.
INDEPENDENCE_GROUP = {
    "streamhg": "streamhg",
    "vidrift": "vidrift",
    "vidbolt": "rousav",
    "vidly": "rousav",
    "vibuxer": "vibuxer",
    "pixeldrain": "pixeldrain",
    "fastdl-beer": "fastdl-beer",
    "vidara": "vidara",
    "hubdrive": "hubdrive",
    "megaup": "megaup",
    "bbupload": "bbupload",
    "1fichier": "1fichier",
    "hubcdn": "hubcdn",
}

#: Watch tie-break order between backends (only after probe evidence).
WATCH_PREFERENCE = (
    "streamhg", "vidrift", "vidbolt", "vidly", "vibuxer",
    "vidara", "pixeldrain", "hubcdn", "fastdl-beer", "hubdrive", "megaup",
    "bbupload", "1fichier",
)

#: Download order: resumable first (handled by the sort), then this order.
#: fastdl.beer is last on purpose — its files ignore Range. bbupload is
#: last with it: its file host honors ranges only via a ?range= query
#: param, never via the Range header, so the probe reports resumable=False.
#: hubcdn sits second: its R2 mirror is resumable AND permanent (unsigned
#: public bucket, no expiry), so it outranks every non-resumable host.
DOWNLOAD_PREFERENCE = ("pixeldrain", "hubcdn", "fastdl-beer", "hubdrive",
                       "megaup", "bbupload", "1fichier")

_MEDIA_SUFFIXES = (
    ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".wmv", ".flv",
    ".ts", ".m3u8", ".mpd", ".mp3", ".m4a", ".aac",
)

_DOWNLOAD_EXTENSIONS = {
    "video/mp4": ".mp4",
    "video/x-matroska": ".mkv",
    "video/matroska": ".mkv",
    "video/webm": ".webm",
    "video/quicktime": ".mov",
    "video/x-msvideo": ".avi",
    "video/mp2t": ".ts",
    "video/mpeg": ".mpg",
    "application/vnd.apple.mpegurl": ".m3u8",
    "application/x-mpegurl": ".m3u8",
    "audio/mpeg": ".mp3",
    "audio/mp4": ".m4a",
}


def download_extension_for(mime_type, url: str = "") -> str:
    """Extension for a download name: mime first, URL suffix as fallback.

    A known video mime never yields ".bin". ".bin" survives only when
    neither the mime nor the media URL names a known container.
    """
    mime = str(mime_type or "").split(";", 1)[0].strip().lower()
    if mime in _DOWNLOAD_EXTENSIONS:
        return _DOWNLOAD_EXTENSIONS[mime]
    path = urllib.parse.urlsplit(str(url or "")).path.lower()
    for suffix in _MEDIA_SUFFIXES:
        if path.endswith(suffix):
            return suffix
    return ".bin"


class FetchResult:
    """One HTTP exchange, body already capped. status 0 == transport failure.

    ``headers`` keys are lower-cased; ``set_cookies`` is the raw
    Set-Cookie list (hubdrive's page sets session cookies the ajax call
    re-presents)."""

    def __init__(self, status=0, content_type="", body=b"", headers=None,
                 final_url="", error="", set_cookies=None):
        self.status = status
        self.content_type = content_type or ""
        self.body = body or b""
        self.headers = headers or {}
        self.final_url = final_url or ""
        self.error = error or ""
        self.set_cookies = list(set_cookies or [])

    def text(self) -> str:
        return self.body.decode("utf-8", errors="ignore")


def _set_cookies(headers) -> list:
    """Raw Set-Cookie values from a response's headers (all of them)."""
    try:
        values = headers.get_all("Set-Cookie") or []
    except Exception:
        values = []
    return [value for value in values if value]


def _cookies_from_set_cookie(values) -> dict:
    """name=value pairs from Set-Cookie headers (attributes dropped)."""
    out = {}
    for raw in values or []:
        pair = (raw or "").split(";", 1)[0]
        if "=" in pair:
            name, value = pair.split("=", 1)
            name = name.strip()
            if name:
                out[name] = value.strip()
    return out


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Redirect handler that never follows: a 3xx then surfaces as an
    HTTPError carrying the Location header (megaup's button link)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def default_fetch(url: str, referer: str | None = None, method: str = "GET",
                  range_bytes: tuple[int, int] | None = None,
                  data: dict | None = None,
                  json_data: dict | None = None,
                  timeout: float = DEFAULT_TIMEOUT,
                  cookies: dict | None = None,
                  headers: dict | None = None,
                  follow_redirects: bool = True) -> FetchResult:
    """Real network fetch: capped reads, redirects followed, no JS, no login.

    ``data`` sends a form-encoded POST body; ``json_data`` sends a JSON
    POST body (vidara's /api/stream requires JSON — a form-encoded body
    answers 400 "Missing or invalid filecode", measured 2026-11-21).
    ``cookies`` sends a Cookie header (hubdrive's crypt cookie, from the
    operator's RADD_HUBDRIVE_CRYPT — never a hardcoded value). ``headers``
    adds extra request headers. ``follow_redirects=False`` leaves a 3xx
    un-followed so the caller can read its Location header.
    """
    req_headers = {"User-Agent": USER_AGENT}
    if referer:
        req_headers["Referer"] = referer
    if cookies:
        req_headers["Cookie"] = "; ".join(
            f"{name}={value}" for name, value in cookies.items())
    if headers:
        req_headers.update(headers)
    if range_bytes is not None:
        req_headers["Range"] = f"bytes={range_bytes[0]}-{range_bytes[1]}"
    body_data = None
    if json_data is not None:
        body_data = json.dumps(json_data).encode()
        req_headers["Content-Type"] = "application/json"
        if method == "GET":
            method = "POST"
    elif data is not None:
        body_data = urllib.parse.urlencode(data).encode()
        req_headers["Content-Type"] = "application/x-www-form-urlencoded"
        if method == "GET":
            method = "POST"
    req = urllib.request.Request(url, headers=req_headers, method=method,
                                 data=body_data)
    # WARP proxy (RADD_WARP_PROXY): when on, the resolver's fetches go
    # through 127.0.0.1:40000; when off this is exactly the opener
    # built before (no proxy handler, direct egress).
    _proxy = warp_proxy.urllib_proxy_handler()
    _handlers = [_proxy] if _proxy is not None else []
    opener = (urllib.request.build_opener(*_handlers) if follow_redirects
              else urllib.request.build_opener(*_handlers, _NoRedirect()))
    try:
        with opener.open(req, timeout=timeout) as resp:
            body = resp.read(MAX_BODY_BYTES + 1)[:MAX_BODY_BYTES + 1]
            return FetchResult(
                status=resp.status,
                content_type=resp.headers.get("Content-Type", ""),
                body=body,
                headers={k.lower(): v for k, v in resp.headers.items()},
                final_url=resp.geturl(),
                set_cookies=_set_cookies(resp.headers),
            )
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read(MAX_BODY_BYTES + 1)[:MAX_BODY_BYTES + 1]
        except Exception:
            body = b""
        hdrs = {}
        if exc.headers is not None:
            hdrs = {k.lower(): v for k, v in exc.headers.items()}
        return FetchResult(status=exc.code, content_type="",
                           body=body, headers=hdrs,
                           error=f"http_{exc.code}",
                           set_cookies=_set_cookies(exc.headers))
    except Exception as exc:  # timeout, DNS, reset, empty reply: transport
        return FetchResult(status=0, error=f"transport:{type(exc).__name__}")


def _is_video_mime(content_type: str) -> bool:
    ct = (content_type or "").split(";", 1)[0].strip().lower()
    return ct.startswith("video/") or ct.startswith("audio/")


def _video_mime_from_magic(body: bytes) -> str | None:
    """Container mime sniffed from the first bytes, or None.

    R2 (and other object stores) serve an upload with the content type
    the uploader set — the hubcdn R2 assets are often plain
    ``application/octet-stream`` on an extension-less URL, so neither the
    mime nor the suffix proves a video. The bytes do: EBML (Matroska/WebM),
    an ftyp box (MP4), or a RIFF/AVI header. Measured 2026-11-20 on live
    hubcdn R2 assets (ids 6169709, 6169755, 290696): 206, octet-stream,
    EBML magic ``1a45dfa3`` — the gate called them ``unverified`` and the
    press failed with ``no_playable_source`` until the magic was read.
    """
    data = body or b""
    if data[:4] == b"\x1a\x45\xdf\xa3":
        # EBML shell: Matroska, and WebM shares the shell — the catalog's
        # hubcdn assets are .mkv, so the Matroska mime is reported.
        return "video/x-matroska"
    if len(data) >= 8 and data[4:8] == b"ftyp":
        return "video/mp4"
    if data[:4] == b"RIFF" and len(data) >= 12 and data[8:12] == b"AVI ":
        return "video/x-msvideo"
    return None


def classify_probe(result: FetchResult, url: str = "") -> dict:
    """Turn one probe exchange into an honest verdict (never a guess).

    Returns ok/kind/mime/size/resumable/renditions/failure. ``failure``
    is None on a pass; otherwise one of: dead_file, blocked, challenge,
    signed_gated, rate_limited, transport, html_shell, empty_playlist,
    unverified. Only dead_file means this copy's media is gone.
    """
    status = result.status
    ct = (result.content_type or "").split(";", 1)[0].strip().lower()
    body = result.body or b""
    text = body.decode("utf-8", errors="ignore")
    headers = result.headers or {}
    size = None
    try:
        if headers.get("content-length"):
            size = int(headers["content-length"])
    except (TypeError, ValueError):
        size = None

    if status == 0 or result.error.startswith("transport"):
        return {"ok": False, "kind": None, "mime": None, "size": size,
                "resumable": False, "renditions": [], "failure": "transport"}
    if status in (404, 410):
        return {"ok": False, "kind": None, "mime": None, "size": size,
                "resumable": False, "renditions": [], "failure": "dead_file"}
    if status == 429:
        return {"ok": False, "kind": None, "mime": None, "size": size,
                "resumable": False, "renditions": [],
                "failure": "rate_limited"}
    if status == 403:
        lowered = text.lower()
        if "missingkey" in lowered or "missing key" in lowered:
            failure = "signed_gated"
        elif any(m in lowered for m in ("just a moment", "attention required",
                                        "turnstile", "cf-challenge",
                                        "verify you are human")):
            failure = "challenge"
        else:
            failure = "blocked"
        return {"ok": False, "kind": None, "mime": None, "size": size,
                "resumable": False, "renditions": [], "failure": failure}

    stripped = text.lstrip()
    if "mpegurl" in ct or stripped.startswith("#EXTM3U"):
        renditions = [(int(w), int(h)) for w, h in
                      re.findall(r"RESOLUTION=(\d+)x(\d+)", text)]
        if renditions:
            return {"ok": True, "kind": "playlist",
                    "mime": "application/vnd.apple.mpegurl", "size": size,
                    "resumable": False, "renditions": renditions,
                    "failure": None}
        if "#EXTINF" in text:
            return {"ok": True, "kind": "playlist",
                    "mime": "application/vnd.apple.mpegurl", "size": size,
                    "resumable": False, "renditions": [],
                    "failure": None}
        return {"ok": False, "kind": None, "mime": None, "size": size,
                "resumable": False, "renditions": [],
                "failure": "empty_playlist"}

    magic_mime = _video_mime_from_magic(body)
    if status in (200, 206) and (_is_video_mime(ct) or
                                 (ct == "application/octet-stream" and
                                  (urllib.parse.urlsplit(url).path.lower().endswith(_MEDIA_SUFFIXES)
                                   or magic_mime is not None)) or
                                 # A file host that lies about the mime:
                                 # bbupload's superduperload answers
                                 # image/png on genuine Matroska bytes
                                 # (measured 2026-11-21, Oracle egress:
                                 # EBML magic 1a45dfa3 at offset 0, DocType
                                 # matroska). The bytes prove the container
                                 # whatever the header claims — except an
                                 # HTML page, which the shell check below
                                 # must still catch.
                                 (magic_mime is not None and
                                  "text/html" not in ct)):
        ranges = (headers.get("accept-ranges") or "").lower()
        resumable = status == 206 or "bytes" in ranges
        return {"ok": True, "kind": "file",
                # The bytes proved the container where the content type did
                # not: report the container mime so the tier ranking reads
                # the copy as video (H4 R6), not as an unknown octet-stream.
                "mime": (ct if _is_video_mime(ct) else (magic_mime or ct)) or None,
                "size": size, "resumable": resumable,
                "renditions": [], "failure": None}

    if status == 200 and ("text/html" in ct or "<html" in text.lower()[:2048]):
        return {"ok": False, "kind": None, "mime": None, "size": size,
                "resumable": False, "renditions": [],
                "failure": "html_shell"}
    return {"ok": False, "kind": None, "mime": None, "size": size,
            "resumable": False, "renditions": [], "failure": "unverified"}


def probe_media(url: str, referer: str | None = None,
                kind_hint: str = "playlist",
                fetch=default_fetch) -> dict:
    """Probe one media address with a capped read. Files: Range only."""
    if kind_hint == "file":
        result = fetch(url, referer, range_bytes=(0, FILE_PROBE_BYTES))
    else:
        result = fetch(url, referer)
    verdict = classify_probe(result, url)
    verdict["probed_url_host"] = urllib.parse.urlsplit(url).netloc
    return verdict


def clean_url(url: str) -> str:
    """Strip/encode stray whitespace inside a saved URL (vibuxer defect:
    most saved rows carry embedded whitespace and must be trimmed/encoded
    before any fetch, never fetched verbatim)."""
    cleaned = "".join(str(url or "").split())
    return cleaned


def _host_of(url: str) -> str:
    try:
        return urllib.parse.urlsplit(str(url or "")).netloc.lower().split(":")[0]
    except Exception:
        return ""


def classify_backend(url_a: str = "", url_b: str = "") -> str:
    """Backend for a saved link, by real host — never by service label.

    url_a is the saved (source) address; url_b is a previously resolved
    address, used only when url_a names no known host. Returns "unknown"
    when neither names a stage-1 host.
    """
    for url in (url_a, url_b):
        host = _host_of(url)
        path = urllib.parse.urlsplit(str(url or "")).path or ""
        if not host:
            continue
        if host == "pixeldrain.com" or "/api/file/" in path:
            return "pixeldrain"
        if host.endswith("fastdl.beer"):
            return "fastdl-beer"
        # hubdrive rotates TLDs (.pics, .blog, .bar, .in, ...) — match the
        # bare name, never a longer host that merely starts with it.
        if host == "hubdrive" or host.startswith("hubdrive.") or ".hubdrive." in host:
            return "hubdrive"
        if host in ("megaup.net", "megaup.mobi") or host.endswith(
                (".megaup.net", ".megaup.mobi")):
            return "megaup"
        # bbupload: the bridge page lives on download.bbupload.to (a
        # stable registered domain — no TLD rotation proven, so the
        # dotted match, not the bare-label hubdrive treatment); the
        # resolved file is served from superduperload.site.
        if host in ("bbupload.to", "download.bbupload.to") or host.endswith(
                ".bbupload.to"):
            return "bbupload"
        # 1fichier: direct file host, no TLD rotation proven — exact +
        # dotted-suffix match on 1fichier.com only, so lookalikes such as
        # 1fichier.com.evil.com and not1fichier.com stay unknown.
        if host == "1fichier.com" or host.endswith(".1fichier.com"):
            return "1fichier"
        if host == "superduperload.site" or host.endswith(
                ".superduperload.site"):
            return "bbupload"
        if host == "embed.vidrift.net":
            return "vidrift"
        if host == "vidbolt.xyz":
            return "vidbolt"
        # hubcdn: the hdboss.in gateway. Only /cloud/<token> pages unwrap;
        # an /l.php hop URL on its own is not a saved shape (the importer
        # banks the cloud link), so it stays unknown rather than guessed.
        if host == "hubcdn.io" or host.endswith(".hubcdn.io"):
            if "/cloud/" in path:
                return "hubcdn"
        if host in ("embed.filmu.in", "vidly.pro") or host.endswith(".vidly.pro"):
            return "vidly"
        if host == "streams.iqsmartgames.com":
            return "streamhg"
        if host == "pro.iqsmartgames.com" and path.startswith("/evid/"):
            return "streamhg"
        if "modiplay" in host:
            return "modiplay"
        if host == "vibuxer.com" or host.endswith(".vibuxer.com"):
            return "vibuxer"
        if host == "filesforever.link":
            return "filesforever"
    return "unknown"


def independence_group(backend: str) -> str:
    return INDEPENDENCE_GROUP.get(backend, backend)


# ── packer unpacking (Dean Edwards packer, as the players ship it) ──────────

def _b36(number: int, base: int) -> str:
    chars = "0123456789abcdefghijklmnopqrstuvwxyz"
    if number == 0:
        return "0"
    out = ""
    while number > 0:
        out = chars[number % base] + out
        number //= base
    return out


def unpack_packer(body: str) -> str:
    """Unpack one eval(p,a,c,k) block, or "" when the page has none.

    Pure regex + substitution: no JS engine, no page execution.
    """
    try:
        match = re.search(
            r"eval\(function\(p,a,c,k,e,d\).*?return p\}\('(.*?)',(\d+),(\d+),'([^']*)'\.split",
            body, re.S)
        if not match:
            return ""
        payload, base, count, words = (match.group(1), int(match.group(2)),
                                       int(match.group(3)),
                                       match.group(4).split("|"))
        out = payload
        for index in range(count - 1, -1, -1):
            if index < len(words) and words[index]:
                out = re.sub(r"\b" + _b36(index, base) + r"\b",
                             words[index], out)
        return out
    except Exception:
        return ""


def extract_hls_links(text: str) -> dict:
    """hls2/hls3/hls4 values from an unpacked (or plain) player page."""
    links = {}
    block = re.search(r"links\s*=\s*\{([^}]+)\}", text)
    source = block.group(1) if block else text
    for key, value in re.findall(r'"(hls\d+)"\s*:\s*"([^"]+)"', source):
        links[key] = value
    return links


def _letvar(page: str, name: str) -> str:
    match = re.search(r'let ' + re.escape(name) + r'\s*=\s*"([^"]+)"', page)
    return match.group(1) if match else ""


def _extract_json_array(body: str, key: str):
    """Bracket-matched JSON array for `key` (vidrift orionStreams).

    A plain regex fails on the nested arrays, so the matcher walks the
    string tracking quotes/escapes and bracket depth. Returns the parsed
    value, None when the value is null/absent, or "unparsed" on bad JSON.
    """
    start = body.find('"' + key + '":')
    if start < 0:
        return None
    if body[start:].lstrip().startswith('"'):
        pass
    rest = body[start:]
    if rest[len('"' + key + '":'):].lstrip().startswith("null"):
        return None
    i = body.find("[", start)
    if i < 0:
        return None
    depth = 0
    in_str = False
    esc = False
    for j in range(i, len(body)):
        ch = body[j]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch == "[":
                depth += 1
            elif ch == "]":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(body[i:j + 1])
                    except Exception:
                        return "unparsed"
    return None


# ── unwrappers: each returns (backend, candidates, failure) ────────────────
# candidates: [{"url": ..., "referer": ..., "kind_hint": "playlist"|"file"}]
# failure: None on success, otherwise a failure-class string (never a URL).

def _unwrap_iqsmart(saved_url: str, fetch) -> tuple:
    embed = saved_url
    page = fetch(embed)
    if page.status != 200:
        return "streamhg", [], ("blocked" if page.status == 403 else
                                "transport" if page.status == 0 else "unverified")
    html = page.text()
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(embed).query)
    keys = query.get("key") or [""]
    key = keys[0]
    final_id, id_type = _letvar(html, "FinalID"), _letvar(html, "idType")
    if not final_id or not id_type or not key:
        return "streamhg", [], "unverified"
    host = urllib.parse.urlsplit(embed).netloc
    if "myseriesapi" in html:
        season, epname = _letvar(html, "season"), _letvar(html, "epname")
        api = (f"https://{host}/myseriesapi?{id_type}={final_id}"
               f"&season={season}&epname={epname}&key={key}")
    else:
        api = f"https://{host}/mymovieapi?{id_type}={final_id}&key={key}"
    api_res = fetch(api, referer=embed)
    if api_res.status != 200:
        return "streamhg", [], ("blocked" if api_res.status == 403 else
                                "transport" if api_res.status == 0 else "unverified")
    try:
        files = (json.loads(api_res.text()) or {}).get("data") or []
    except Exception:
        return "streamhg", [], "unverified"
    if not files:
        return "streamhg", [], "no_files"
    candidates = []
    for entry in files[:3]:
        slug = (entry or {}).get("fileslug") or ""
        if not slug:
            continue
        evid = fetch(f"https://pro.iqsmartgames.com/evid/{slug}",
                     referer=embed)
        if evid.status != 200:
            continue
        sid_match = re.search(r'const sid\s*=\s*"([^"]+)"', evid.text())
        if not sid_match:
            continue
        helper = fetch(
            "https://pro.iqsmartgames.com/embedhelper2.php",
            referer=embed, method="POST", data={
                "sid": sid_match.group(1), "UserFavSite": "",
                "currentDomain": json.dumps([host, "pro.iqsmartgames.com"])})
        if helper.status != 200:
            continue
        try:
            payload = json.loads(helper.text()) or {}
            sources = payload.get("sources") or {}
            decoded = json.loads(base64.b64decode(payload.get("mresult") or ""))
        except Exception:
            continue
        for code, source in sources.items():
            site_url = (source or {}).get("siteUrl") or ""
            if "hanerix.com" not in site_url:
                continue
            master_code = decoded.get(code) or ""
            if not master_code:
                continue
            candidates.append({"_hanerix_page": site_url + master_code,
                               "embed": embed})
    if candidates:
        return "streamhg", candidates, None
    return "streamhg", [], "unverified"


def _unwrap_hanerix_page(hanerix_url: str, fetch) -> tuple:
    """hanerix player page -> unpacked hls master candidates."""
    page = fetch(hanerix_url)
    if page.status != 200:
        return [], ("blocked" if page.status == 403 else
                    "transport" if page.status == 0 else "unverified")
    unpacked = unpack_packer(page.text()) or page.text()
    links = extract_hls_links(unpacked)
    candidates = []
    for key in ("hls4", "hls3", "hls2"):
        value = links.get(key) or ""
        if not value:
            continue
        full = value if value.startswith("http") else (
            "https://hanerix.com" + value)
        candidates.append({"url": full, "referer": hanerix_url,
                           "kind_hint": "playlist"})
    if candidates:
        return candidates, None
    return [], "unverified"


_MODIPLAY_SERVER_ORDER = {"streamhg": 0, "vidrift": 1, "bingr": 1, "vidara": 2}


def _modiplay_streamhg(origin: str, proxy_url: str, saved_url: str,
                      fetch) -> tuple:
    """streamhg proxy page -> stream_proxy master (verified live 2026-11-21:
    the master answers 200 application/vnd.apple.mpegurl with renditions)."""
    player = fetch(proxy_url, referer=saved_url)
    if player.status != 200:
        return "streamhg", [], ("blocked" if player.status == 403 else
                                "transport" if player.status == 0 else "unverified")
    body = player.text().replace("\\/", "/")
    found = [c for c in re.findall(
        r"/stream_proxy\.php\?url=[^\"'<> ]+", body)
        if "master.m3u8" in c]
    if found:
        master = origin + found[0].replace("&amp;", "&")
        return "streamhg", [{"url": master, "referer": saved_url,
                             "kind_hint": "playlist"}], None
    direct = re.findall(
        r"https?://[A-Za-z0-9.\-]+/[^\s\"'<>]*master\.m3u8", body)
    if direct:
        return "streamhg", [{"url": direct[0].replace("&amp;", "&"),
                             "referer": proxy_url,
                             "kind_hint": "playlist"}], None
    return "streamhg", [], "unverified"


def _modiplay_vidrift(proxy_url: str, saved_url: str, fetch) -> tuple:
    """vidrift/bingr proxy page -> iframe to the vidrift embed -> _unwrap_vidrift.

    Measured 2026-11-21: the proxy page is a ~5 KB iframe wrapper
    (``<iframe src="https://embed.vidrift.net/embed/movie/11667">``), NOT a
    page carrying an orion master — the old direct-orion regex never
    matched it, so the bingr branch always ended "shell_unverified". The
    iframe chain is the same one unwrap_cineverse already uses. The
    direct-orion match stays as a fallback for an older wrapper shape.
    """
    player = fetch(proxy_url, referer=saved_url)
    if player.status != 200:
        return "vidrift", [], ("blocked" if player.status == 403 else
                               "transport" if player.status == 0 else "unverified")
    body = player.text().replace("\\/", "/")
    iframe = re.search(
        r'<iframe[^>]+src=["\'](https://embed\.vidrift\.net/embed/[^"\']+)',
        body)
    if iframe:
        return _unwrap_vidrift(iframe.group(1), fetch)
    master = re.search(
        r"https://embed\.vidrift\.net/api/orion/[A-Za-z0-9_\-]+/master\.m3u8",
        body)
    if master:
        return "vidrift", [{"url": master.group(0),
                            "referer": "https://embed.vidrift.net/",
                            "kind_hint": "playlist"}], None
    return "vidrift", [], "shell_unverified"


def _modiplay_vidara(code: str, saved_url: str, fetch) -> tuple:
    """vidara: POST the filecode as JSON to /api/stream -> streaming_url.

    Measured 2026-11-21: ``POST https://vidara.to/api/stream`` with a
    JSON body ``{"filecode": code}`` answers 200 with a signed HLS master
    in ``streaming_url``. A form-encoded body answers 400 ("Missing or
    invalid filecode"), so the JSON body is required. The embed page's
    ``c=`` code IS the vidara filecode (the proxy page names the same
    ``/e/<code>``), so no proxy fetch is needed before the POST.
    """
    api = fetch("https://vidara.to/api/stream", referer=saved_url,
                method="POST", json_data={"filecode": code})
    if api.status != 200:
        return "vidara", [], ("blocked" if api.status == 403 else
                              "transport" if api.status == 0 else "vidara_unverified")
    try:
        master = (json.loads(api.text()) or {}).get("streaming_url") or ""
    except Exception:
        master = ""
    if not master:
        return "vidara", [], "vidara_unverified"
    return "vidara", [{"url": master,
                       "referer": f"https://vidara.to/e/{code}",
                       "kind_hint": "playlist"}], None


def _unwrap_modiplay(saved_url: str, fetch) -> tuple:
    """modiplay embed -> the backend it actually resolves to.

    The embed page lists 2–3 servers (proxy.php?p=<server>&c=<code>).
    Every listed server is tried in preference order — streamhg, then
    vidrift/bingr, then vidara — not just the first match: a movie page
    lists streamhg + vidrift, a TV page lists vidara + vidrift, and one
    server can 403 while its sibling works. Measured 2026-11-21 on 20
    sampled embed pages: streamhg present on 19, vidrift on all 20,
    vidara on 1 (first on that one). A server name with no proven chain
    is skipped, never dead: the failure is the preferred server's.
    """
    page = fetch(saved_url)
    if page.status != 200:
        return "modiplay", [], ("blocked" if page.status == 403 else
                                "transport" if page.status == 0 else "unverified")
    html = page.text()
    matches = re.findall(r"/proxy\.php\?p=([a-z0-9]+)(?:&amp;|&)c=([^\"&]+)",
                         html)
    if not matches:
        return "modiplay", [], "unverified"
    base = urllib.parse.urlsplit(saved_url)
    origin = f"{base.scheme}://{base.netloc}"
    ordered = sorted(matches,
                     key=lambda m: _MODIPLAY_SERVER_ORDER.get(m[0], 9))
    servers = {}
    for server, code in ordered:
        servers.setdefault(server, code)
    first_failure = None
    for server, code in servers.items():
        if server == "streamhg":
            proxy = (f"{origin}/proxy.php?p=streamhg&c={code}"
                     f"&title=x&site_ref=&noredirect=1")
            backend, candidates, failure = _modiplay_streamhg(
                origin, proxy, saved_url, fetch)
        elif server in ("vidrift", "bingr"):
            proxy = (f"{origin}/proxy.php?p={server}&c={code}"
                     f"&title=x&noredirect=1")
            backend, candidates, failure = _modiplay_vidrift(
                proxy, saved_url, fetch)
        elif server == "vidara":
            backend, candidates, failure = _modiplay_vidara(
                code, saved_url, fetch)
        else:
            continue
        if not failure:
            return backend, candidates, None
        if first_failure is None:
            first_failure = (backend, failure)
    if first_failure is not None:
        return first_failure[0], [], first_failure[1]
    return "modiplay", [], "unsupported_server"


def _unwrap_vidrift(saved_url: str, fetch) -> tuple:
    referer = "https://embed.vidrift.net/"
    page = fetch(saved_url, referer=referer)
    if page.status != 200:
        return "vidrift", [], ("blocked" if page.status == 403 else
                               "transport" if page.status == 0 else "unverified")
    html = page.text().replace("\\/", "/")
    if '"orionStreams":null' in html:
        return "vidrift", [], "no_streams"
    streams = _extract_json_array(html, "orionStreams")
    if not streams or streams == "unparsed":
        return "vidrift", [], "unverified"
    candidates = []
    for stream in streams:
        url = (stream or {}).get("url") or ""
        if not url:
            continue
        if url.startswith("/"):
            url = "https://embed.vidrift.net" + url
        kind = "playlist" if url.split("?")[0].endswith(".m3u8") else "file"
        candidates.append({"url": url, "referer": referer,
                           "kind_hint": kind})
    if candidates:
        return "vidrift", candidates, None
    return "vidrift", [], "no_streams"


#: VidBolt /api/resolve crack (WARP egress, 2026-11-23): the movie/TV page
#: JS fetches '/api/resolve?'+q where q is `type=movie&id=<tmdb>` or
#: `type=tv&id=<tmdb>&season=<s>&episode=<e>` — read live off the page
#: itself via WARP, 2026-11-23, so the resolve URL is built straight from
#: the saved link with no page fetch. The call needs Referer: the
#: movie/TV page — without it Cloudflare 403s (measured: the no-Referer
#: attempt answers the "Attention Required!" page). The response is live
#: stream JSON: {"stream": {"server": "quasar", "sources": [{"url":
#: "https://.../index_287.m3u8?Expires=...&Signature=...", "kind":
#: "hls", "quality": "1080p", "language": "Hindi"}, ...]}}. Servers
#: seen: quantum/quasar (movies), orion (TV); selectable via
#: &prefer=<server> — not used, the API's default pick is proven. The
#: stream URLs are SIGNED (Expires + Signature): resolve fresh on every
#: call, never serve a stored signed URL (same rule as the gamerxyt
#: per-load token). A 200 with {"stream": null} means this title has no
#: stream right now — "no_streams", never dead. A 404 on the resolve
#: means vidbolt has no such title — "dead_file".
#: One round + bank-all (2026-11-23): the resolve is one GET over the
#: shared keep-alive pool (see _vidbolt_pooled_fetch) and every source the
#: response returns is a candidate with its own expires_at (see
#: _vidbolt_candidates). Live re-measured 2026-11-23: the API rotates the
#: server per call (luna seen live, alongside the quasar/quantum/orion of
#: the first crack) and the rendition set varies (1-2 HLS masters today on
#: stream.vidbolt.xyz or a workers.dev bridge; 4 in the first capture) —
#: one response still carries ONE server, never several, so &prefer=
#: would be a second round, not banked here.
_VIDBOLT_MOVIE_RE = re.compile(r"^/movie/(\d+)/?$")
_VIDBOLT_TV_RE = re.compile(r"^/tv/(\d+)/(\d+)/(\d+)/?$")


def _vidbolt_direct(saved_url: str):
    """(resolve_url, referer) built straight from a vidbolt page URL.

    Returns None when the URL is not a /movie/<id> or /tv/<id>/<s>/<e>
    page — those shapes fall back to the page-fetch + ``var q`` path.
    """
    parts = urllib.parse.urlsplit(str(saved_url or ""))
    if (parts.netloc or "").lower().split(":")[0] != "vidbolt.xyz":
        return None
    path = parts.path or ""
    match = _VIDBOLT_MOVIE_RE.match(path)
    if match:
        vid = match.group(1)
        return (f"https://vidbolt.xyz/api/resolve?type=movie&id={vid}&hevc=0",
                f"https://vidbolt.xyz/movie/{vid}")
    match = _VIDBOLT_TV_RE.match(path)
    if match:
        vid, season, episode = match.groups()
        return (f"https://vidbolt.xyz/api/resolve?type=tv&id={vid}"
                f"&season={season}&episode={episode}&hevc=0",
                f"https://vidbolt.xyz/tv/{vid}/{season}/{episode}")
    return None


def _vidbolt_source_expiry(url: str) -> float | None:
    """Epoch expiry of one VidBolt source URL, or None.

    The signed HLS masters carry `Expires=<epoch>` (same reader as
    `host_resolvers.vidbolt_signed_expiry`, re-implemented here so this
    module stays importable without the importers package). The
    workers.dev bridge and `stream.vidbolt.xyz/v/<token>` shapes carry no
    parsable lifetime and return None — the caller keeps its conservative
    default for those instead of inventing one.
    """
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(url or "").query)
    raw = (query.get("Expires") or query.get("expires") or [""])[0]
    if not re.fullmatch(r"\d{9,11}", raw or ""):
        return None
    return float(int(raw))


def _vidbolt_candidates(res, referer: str) -> tuple:
    """(candidates, failure) parsed from one /api/resolve response."""
    try:
        payload = json.loads(res.text()) or {}
    except Exception:
        return [], "unverified"
    stream = payload.get("stream") or {}
    if not isinstance(stream, dict):
        return [], "unverified"
    sources = stream.get("sources") or []
    if not sources and stream.get("url"):
        sources = [stream]
    if not sources:
        return [], "no_streams"
    server = stream.get("server") or ""
    candidates = []
    seen = set()
    for source in sources:
        url = (source or {}).get("url") or ""
        if not url or url in seen:
            continue
        seen.add(url)
        kind = (source or {}).get("kind") or ""
        stem = url.split("?", 1)[0]
        if kind == "hls" or stem.endswith(".m3u8"):
            kind_hint = "playlist"
        elif kind == "mp4" or stem.endswith(".mp4"):
            kind_hint = "file"
        else:
            kind_hint = "playlist"
        candidate = {"url": url, "referer": referer, "kind_hint": kind_hint,
                     # Per-source expiry: each signed URL carries its own
                     # lifetime (or none), so a caller banking all
                     # candidates banks each with its own clock.
                     "expires_at": _vidbolt_source_expiry(url)}
        if (source or {}).get("quality"):
            candidate["quality"] = source["quality"]
        if server:
            candidate["server"] = server
        if (source or {}).get("language"):
            candidate["language"] = source["language"]
        candidates.append(candidate)
    if candidates:
        return candidates, None
    return [], "no_streams"



def _vidbolt_pooled_fetch(fetch):
    """The caller's fetch, with vidbolt.xyz /api/resolve calls routed over
    the shared keep-alive pool — only when the caller passed this module's
    own default fetch (the production press path).

    The pool is `host_resolvers.keepalive_fetch` (the HubCloud one-round
    pool, reused — not reinvented): one TLS + Cloudflare handshake per
    process instead of one per press. A test StubFetch (or any non-default
    fetch) passes through untouched, so mocked tests never touch the
    network; a missing or broken pool module falls back to the plain fetch
    instead of failing the resolve. Only the /api/resolve GET is pooled —
    the page-fetch fallback and the media probes keep the caller's fetch.
    The import is lazy and mirrors fast_fetch's `_resolvers()` shape (the
    importers dir on sys.path, plain `import host_resolvers`) so this
    module still imports when the pool is absent.
    """
    if fetch is not default_fetch:
        return fetch
    try:
        _dir = os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "importers")
        if _dir not in sys.path:
            sys.path.insert(0, _dir)
        import host_resolvers as _hr
        _pool = getattr(_hr, "keepalive_fetch", None)
    except Exception:
        return fetch
    if _pool is None:
        return fetch

    def _fetch(url, referer=None, *args, **kwargs):
        parts = urllib.parse.urlsplit(url or "")
        if ((parts.netloc or "").lower().split(":")[0] == "vidbolt.xyz"
                and (parts.path or "").startswith("/api/resolve")
                and not args and not kwargs):
            headers = {"Referer": referer} if referer else {}
            try:
                status, body, resp_headers, final_url = _pool(
                    url, headers=headers)
            except Exception:
                return fetch(url, referer=referer)
            if isinstance(body, str):
                body = body.encode("utf-8", "replace")
            return FetchResult(
                status=status,
                content_type=(resp_headers or {}).get("content-type", ""),
                body=body or b"",
                headers=resp_headers or {},
                final_url=final_url or url)
        return fetch(url, referer, *args, **kwargs)

    return _fetch


def _unwrap_vidbolt(saved_url: str, fetch) -> tuple:
    fetch = _vidbolt_pooled_fetch(fetch)
    direct = _vidbolt_direct(saved_url)
    if direct is not None:
        resolve_url, referer = direct
        res = fetch(resolve_url, referer=referer)
        if res.status != 200:
            return "vidbolt", [], ("blocked" if res.status == 403 else
                                   "dead_file" if res.status == 404 else
                                   "transport" if res.status == 0 else "unverified")
        candidates, failure = _vidbolt_candidates(res, referer)
        return "vidbolt", candidates, failure
    # Any other vidbolt shape: the old page-fetch path — the page JS
    # carries var q="type=...&id=..." and the resolve answers the same
    # stream JSON. Kept so an unexpected saved shape still resolves
    # instead of failing outright.
    page = fetch(saved_url, referer=saved_url)
    if page.status != 200:
        return "vidbolt", [], ("blocked" if page.status == 403 else
                               "transport" if page.status == 0 else "unverified")
    match = re.search(r'var q="([^"]+)"', page.text())
    if not match:
        return "vidbolt", [], "unverified"
    origin = urllib.parse.urlsplit(saved_url)
    resolve_url = f"{origin.scheme}://{origin.netloc}/api/resolve?{match.group(1)}"
    res = fetch(resolve_url, referer=saved_url)
    if res.status != 200:
        return "vidbolt", [], ("blocked" if res.status == 403 else
                               "transport" if res.status == 0 else "unverified")
    candidates, failure = _vidbolt_candidates(res, saved_url)
    return "vidbolt", candidates, failure



#: hubcdn.io trips "Too Many Requests" after ~25 rapid requests from one
#: IP (measured 2026-11-23, while hunting for a hidden API — there is
#: none; the HTML pages + the /l.php 302 ARE the API). Every hubcdn.io
#: request this module makes (cloud page + one hop per unique button)
#: passes through _hubcdn_pace, which spaces them at least this far apart
#: across threads (resolve_release runs siblings in parallel).
_HUBCDN_MIN_INTERVAL = 0.20
_hubcdn_lock = threading.Lock()
_hubcdn_last_request = 0.0


def _hubcdn_pace(sleep) -> None:
    """Wait until _HUBCDN_MIN_INTERVAL has passed since the last hubcdn.io
    request, then stamp the new request time. ``sleep`` is injectable so
    fixture tests never actually wait."""
    global _hubcdn_last_request
    with _hubcdn_lock:
        now = time.monotonic()
        wait_s = _HUBCDN_MIN_INTERVAL - (now - _hubcdn_last_request)
        if wait_s > 0:
            sleep(wait_s)
            now = time.monotonic()
        _hubcdn_last_request = now


def _hubcdn_mirror_kind(location: str) -> str | None:
    """Mirror slot a hop Location lands in, or None when it must not be
    trusted. R2/Google go through host_classifier's trust gate (a public
    ``pub-*.r2.dev`` bucket, or the Google front — lookalike hosts buy
    nothing). Gofile/Telegram are exact-host pointers only: a landing page
    / bot link is banked as a mirror, never probed as a file."""
    role = _hc.hubcdn_asset_role(location)
    if role == "stream":
        return "r2"
    if role == "download":
        return "google"
    host = _host_of(location)
    parts = urllib.parse.urlsplit(location)
    if host == "gofile.io" and parts.path.startswith("/d/"):
        return "gofile"
    if host == "t.me" and parts.path.strip("/"):
        return "telegram"
    return None


def _unwrap_hubcdn(saved_url: str, fetch, sleep=None) -> tuple:
    """hubcdn.io/cloud/<token> gateway -> its mirrors, R2 first.

    Measured live from Oracle egress 2026-11-23 (The Raid 2026, 720p-HEVC,
    id 44259): the cloud page renders 8 ``/l.php`` buttons (4 routes x 2
    labels); GET ``/l.php?file=...&token=...&id=...`` 302s immediately to
    the real host with no wait and no dl_token — the click dance (random
    dl_token + dl_ready cookie poll) is browser UX only, the server 302s
    without it. One pointer fans out to FOUR mirrors: public R2
    (``pub-*.r2.dev/<32-hex>``, unsigned, Range 206, permanent), Google
    Drive direct (token rotates between probes, short-lived), Gofile,
    Telegram. Five of the eight buttons share the SAME Google href, so
    hops are deduped by href first (4 hops max per quality, not 8).

    Only R2/Google are probe-able files, so only they become candidates
    (R2 first — permanent beats minted). Gofile/Telegram ride on each
    candidate as ``mirrors`` so the caller can bank all four destinations
    from one resolve. A page with no buttons and a filesdl countdown is
    the "Preparing Download" shape: reported, never served.
    """
    if sleep is None:
        sleep = time.sleep
    if not _hc.is_hubcdn_cloud_page(saved_url):
        return "hubcdn", [], "unverified"
    _hubcdn_pace(sleep)
    page = fetch(saved_url, referer=saved_url)
    if page.status == 429:
        return "hubcdn", [], "rate_limited"
    if page.status != 200:
        return "hubcdn", [], ("blocked" if page.status == 403 else
                              "transport" if page.status == 0 else "unverified")
    html = page.text()
    buttons = _hc.hubcdn_page_buttons(html)
    if not buttons:
        if _hc.hubcdn_preparing_target(html):
            return "hubcdn", [], "preparing_download"
        return "hubcdn", [], "unverified"
    seen, hrefs = set(), []
    for href, _label in buttons:
        absolute = _hc.hubcdn_resolve_href(href)
        if absolute not in seen:
            seen.add(absolute)
            hrefs.append(absolute)
    mirrors: dict[str, str] = {}
    saw_rate_limit = False
    for href in hrefs:
        _hubcdn_pace(sleep)
        resp = fetch(href, referer=saved_url, follow_redirects=False)
        if resp.status == 429:
            saw_rate_limit = True
            continue
        location = (resp.headers or {}).get("location") or ""
        if not location:
            continue
        direct = urllib.parse.urljoin(href, location)
        kind = _hubcdn_mirror_kind(direct)
        if kind is not None and kind not in mirrors:
            mirrors[kind] = direct
    candidates = []
    for kind in ("r2", "google"):
        if mirrors.get(kind):
            candidates.append({"url": mirrors[kind], "referer": saved_url,
                               "kind_hint": "file",
                               "mirrors": dict(mirrors)})
    if candidates:
        return "hubcdn", candidates, None
    if saw_rate_limit:
        return "hubcdn", [], "rate_limited"
    return "hubcdn", [], "unverified"


def _unwrap_vidly(saved_url: str, fetch) -> tuple:
    path = urllib.parse.urlsplit(saved_url).path or ""
    if "/tv/" in path:
        # Series: the signed master 403s on a plain server fetch. Serving
        # it anyway would hand the player a dead token, so stage-1 refuses
        # filmu series from static evidence outright (browser path only).
        return "vidly", [], "series_server_unverified"
    page = fetch(saved_url, referer="https://embed.filmu.in/")
    if page.status != 200:
        return "vidly", [], ("blocked" if page.status == 403 else
                             "transport" if page.status == 0 else "unverified")
    # The embed page is a Next.js shell: the media URL no longer sits in
    # static HTML as `https://<host>/media/<id>/index.m3u8` (that shape
    # is gone — measured 2026-11-08 on the live filmu page, zero matches).
    # It now arrives as a CloudFront-signed master behind the same
    # dash-hls-bridge Workers proxy vidbolt already resolves through
    # (`https://dash-hls-bridge.johndoesdmc.workers.dev/?url=...master.m3u8`
    # plus Policy/Signature/Key-Pair-Id). The signed query is part of the
    # URL: without it the bridge 502s, so the match must run over the
    # body AFTER the flight-data escapes are decoded (`\\u0026` -> `&`,
    # `&amp;` -> `&`), and must run to the closing quote, not to the
    # first `&`. The old /media/ shape is kept as a fallback.
    body = page.text()
    body = (body.replace("\\/", "/").replace("\\u0026", "&")
            .replace("\\u003d", "=").replace("\\x26", "&")
            .replace("&amp;", "&"))
    bridge = sorted(set(re.findall(
        r"https://dash-hls-bridge\.johndoesdmc\.workers\.dev/"
        r"\?url=[^\"'<>\\\s]*?master\.m3u8[^\"'<>\\\s]*", body)))
    legacy = sorted(set(re.findall(
        r"https://[A-Za-z0-9.\-]+/media/[A-Za-z0-9]+/index\.m3u8", body)))
    playlists = bridge + [url for url in legacy if url not in bridge]
    candidates = [{"url": url, "referer": "https://vidly.pro/",
                   "kind_hint": "playlist"} for url in playlists[:2]]
    if candidates:
        return "vidly", candidates, None
    return "vidly", [], "unverified"


def _unwrap_vibuxer(saved_url: str, fetch) -> tuple:
    url = clean_url(saved_url)
    page = fetch(url)
    if page.status != 200:
        return "vibuxer", [], ("blocked" if page.status == 403 else
                               "transport" if page.status == 0 else "unverified")
    unpacked = unpack_packer(page.text()) or page.text()
    links = extract_hls_links(unpacked)
    candidates = []
    for key in ("hls4", "hls3", "hls2"):
        value = links.get(key) or ""
        if not value:
            continue
        full = value if value.startswith("http") else urllib.parse.urljoin(url, value)
        candidates.append({"url": full, "referer": url,
                           "kind_hint": "playlist"})
    if candidates:
        return "vibuxer", candidates, None
    return "vibuxer", [], "unverified"


def _unwrap_pixeldrain(saved_url: str, fetch) -> tuple:
    match = re.search(r"/(?:u|d|api/file)/([A-Za-z0-9_-]+)", saved_url)
    if not match:
        return "pixeldrain", [], "unverified"
    direct = f"https://pixeldrain.com/api/file/{match.group(1)}"
    return "pixeldrain", [{"url": direct, "referer": None,
                           "kind_hint": "file"}], None


def _unwrap_fastdl(saved_url: str, fetch) -> tuple:
    page = fetch(saved_url)
    if page.status != 200:
        return "fastdl-beer", [], ("blocked" if page.status == 403 else
                                   "transport" if page.status == 0 else "unverified")
    match = re.search(r'var\s+reurl\s*=\s*["\']([^"\']+)["\']', page.text())
    if not match:
        return "fastdl-beer", [], "unverified"
    raw = match.group(1).strip()
    if "link=" in raw:
        direct = urllib.parse.unquote(raw.split("link=", 1)[1])
    else:
        direct = raw
    if not direct:
        return "fastdl-beer", [], "unverified"
    # Probe ONLY the decoded file address: probing the dl.php page itself
    # returns HTML and proves nothing about the file.
    return "fastdl-beer", [{"url": direct, "referer": None,
                            "kind_hint": "file"}], None


#: HubDrive's direct-download ajax only answers for a logged-in session:
#: the HUBDRIVE_CRYPT cookie is minted by a browser login, never by this
#: module, and never stored here. The operator supplies it as
#: RADD_HUBDRIVE_CRYPT; without it the ajax answers 408 and the copy
#: fails "hubdrive_needs_cookie" — an unmet requirement, never a dead file.
def hubdrive_crypt_cookie() -> str | None:
    value = (os.environ.get("RADD_HUBDRIVE_CRYPT") or "").strip()
    return value or None


def _scrape_btn_primary_link(html: str) -> str:
    """href of the first .btn-primary anchor (either attribute order)."""
    body = html or ""
    for pattern in (
        r"""<a[^>]*class=["'][^"']*btn-primary[^"']*["'][^>]*href=["']([^"']+)["']""",
        r"""<a[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*btn-primary[^"']*["']""",
    ):
        match = re.search(pattern, body, re.I)
        if match:
            return match.group(1).strip()
    return ""


def _unwrap_hubdrive(saved_url: str, fetch, cookies: dict | None = None) -> tuple:
    """hubdrive file page -> ajax direct-download -> download-page button.

    Port of nurlresolver hubdrive.ts (MIT): GET the /file/<id> page, POST
    /ajax.php?ajax=direct-download with form id=<id> (X-Requested-With),
    follow the returned file page to its .btn-primary link. Measured
    2026-11-21 from Oracle egress: the page answers 200 with a real
    title and the ajax endpoint answers JSON — but without the login
    HUBDRIVE_CRYPT cookie the ajax answers {"code": 408, "title":
    "Direct Download Unavailable"}. A cookie-jar replay of every
    Set-Cookie the hops set still 408s (the hops set none), so the cookie
    is a login credential, not a session one: it must come from the
    operator (RADD_HUBDRIVE_CRYPT). Without any crypt cookie presented
    the failure is "hubdrive_needs_cookie"; with one and still no file
    it is "hubdrive_unverified".
    """
    supplied = dict(cookies or {})
    if not supplied:
        env_cookie = hubdrive_crypt_cookie()
        if env_cookie:
            supplied = {"HUBDRIVE_CRYPT": env_cookie}
    page = fetch(saved_url, cookies=supplied or None)
    if page.status != 200:
        return "hubdrive", [], ("blocked" if page.status == 403 else
                                "transport" if page.status == 0 else "unverified")
    final_url = page.final_url or saved_url
    file_id = urllib.parse.urlsplit(final_url).path.rstrip("/").rsplit("/", 1)[-1]
    if not file_id.isdigit():
        file_id = urllib.parse.urlsplit(saved_url).path.rstrip("/").rsplit("/", 1)[-1]
    if not file_id.isdigit():
        return "hubdrive", [], "unverified"
    origin = urllib.parse.urlsplit(final_url)
    ajax_url = f"{origin.scheme}://{origin.netloc}/ajax.php?ajax=direct-download"
    jar = _cookies_from_set_cookie(page.set_cookies)
    jar.update(supplied)
    presented_crypt = any("crypt" in name.lower() for name in jar)
    api = fetch(ajax_url, referer=final_url, method="POST",
                data={"id": file_id},
                headers={"accept": "application/json",
                         "X-Requested-With": "XMLHttpRequest"},
                cookies=jar or None)
    if api.status != 200:
        return "hubdrive", [], ("blocked" if api.status == 403 else
                                "transport" if api.status == 0 else "hubdrive_unverified")
    try:
        payload = json.loads(api.text()) or {}
    except Exception:
        return "hubdrive", [], "hubdrive_unverified"
    code = str(payload.get("code") or "")
    file_page = payload.get("file") or ""
    if code != "200" or not file_page:
        if not presented_crypt:
            return "hubdrive", [], "hubdrive_needs_cookie"
        return "hubdrive", [], "hubdrive_unverified"
    file_url = urllib.parse.urljoin(f"{origin.scheme}://{origin.netloc}/", file_page)
    resp = fetch(file_url, referer=final_url, cookies=jar or None)
    if resp.status != 200:
        return "hubdrive", [], ("blocked" if resp.status == 403 else
                                "transport" if resp.status == 0 else "hubdrive_unverified")
    link = _scrape_btn_primary_link(resp.text())
    if not link:
        return "hubdrive", [], "hubdrive_unverified"
    direct = urllib.parse.urljoin(resp.final_url or file_url, link)
    return "hubdrive", [{"url": direct, "referer": resp.final_url or file_url,
                         "kind_hint": "file"}], None


#: Megaup's button link only works after the site's countdown: the
#: resolver waits 6 s (nurlresolver's wait) before following it.
_MEGAUP_WAIT_SECONDS = 6.0


def _unwrap_megaup(saved_url: str, fetch, sleep=None) -> tuple:
    """megaup file page -> download button -> no-redirect Location.

    Port of nurlresolver megaup.ts (MIT): GET the file page, take the
    ``btn btn-default`` href, wait 6 s, follow it without following
    redirects and read the Location — that is the file. A page that
    lands on error.html is a removed file ("dead_file", the site's own
    words: "File has been removed due to inactivity"). Measured
    2026-11-21 from Oracle egress: megaup.net resets the connection
    and megaup.mobi fails TLS verification, so the chain is unproven
    live from this box — fixture tests only.
    """
    if sleep is None:
        sleep = time.sleep
    page = fetch(saved_url)
    if page.status != 200:
        return "megaup", [], ("blocked" if page.status == 403 else
                              "transport" if page.status == 0 else "unverified")
    if urllib.parse.urlsplit(page.final_url or saved_url).path.endswith("error.html"):
        return "megaup", [], "dead_file"
    match = re.search(
        r"""class=["']btn btn-default["']\s+href=["']([^"']+)["']""",
        page.text())
    if not match:
        return "megaup", [], "unverified"
    button_url = urllib.parse.urljoin(page.final_url or saved_url,
                                      match.group(1).strip())
    sleep(_MEGAUP_WAIT_SECONDS)
    resp = fetch(button_url, referer=page.final_url or saved_url,
                 follow_redirects=False)
    link = (resp.headers or {}).get("location") or ""
    if not link:
        return "megaup", [], "unverified"
    direct = urllib.parse.urljoin(button_url, link)
    return "megaup", [{"url": direct, "referer": page.final_url or saved_url,
                       "kind_hint": "file"}], None


_BBUPLOAD_AJAX = "https://download.bbupload.to/ajax.php"
_BBUPLOAD_SLUG_RE = re.compile(r"""var\s+slug\s*=\s*["']([^"']+)["']""")


def _unwrap_bbupload(saved_url: str, fetch) -> tuple:
    """bbupload file page -> getdownload ajax -> superduperload file.

    Measured 2026-11-21 from Oracle egress: GET
    ``/ajax.php?action=getdownload&data=<slug>`` (slug = the ``v``
    query param, also ``var slug`` in the page) answers 200 JSON
    ``{"status": "ok", "data": {"FileName": ..., "filesize": ...,
    "downloadUrl": "https://superduperload.site/get/<token>"}}`` with
    no cookie, no wait and no captcha — the page's reCAPTCHA call is
    commented out and the 20 s countdown gates only the browser UI.
    A removed file still answers ``status: "ok"`` but with
    ``FileName``/``filesize`` null: that is the site's own dead-file
    signal ("dead_file"). The downloadUrl serves genuine Matroska
    bytes under a lying ``image/png`` content type (see
    classify_probe), and honors ranges only via the ``?range=``
    query param, so the copy probes ``resumable=False``.
    A superduperload URL is already resolved (the importer banks
    these as final_url), so it passes straight through.
    """
    cleaned = clean_url(saved_url)
    if cleaned.startswith("//"):
        cleaned = "https:" + cleaned
    host = _host_of(cleaned)
    if host == "superduperload.site" or host.endswith(".superduperload.site"):
        return "bbupload", [{"url": cleaned, "referer": cleaned,
                             "kind_hint": "file"}], None
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(cleaned).query)
    slug = (query.get("v") or [None])[0]
    if not slug:
        # No v param: fetch the page once and read var slug from it.
        page = fetch(cleaned)
        if page.status != 200:
            return "bbupload", [], ("blocked" if page.status == 403 else
                                    "transport" if page.status == 0 else
                                    "unverified")
        match = _BBUPLOAD_SLUG_RE.search(page.text())
        if not match:
            return "bbupload", [], "unverified"
        slug = match.group(1)
    ajax_url = (_BBUPLOAD_AJAX + "?action=getdownload&data=" +
                urllib.parse.quote(slug, safe=""))
    resp = fetch(ajax_url, referer=cleaned,
                 headers={"X-Requested-With": "XMLHttpRequest",
                          "Accept": "application/json"})
    if resp.status != 200:
        return "bbupload", [], ("blocked" if resp.status == 403 else
                                "transport" if resp.status == 0 else
                                "bbupload_unverified")
    try:
        payload = json.loads(resp.text())
    except (ValueError, TypeError):
        return "bbupload", [], "bbupload_unverified"
    if not isinstance(payload, dict) or payload.get("status") != "ok":
        return "bbupload", [], "bbupload_unverified"
    data = payload.get("data")
    if not isinstance(data, dict):
        return "bbupload", [], "bbupload_unverified"
    if not data.get("FileName") or not data.get("filesize"):
        # Removed file: the site still answers ok, with null name/size.
        return "bbupload", [], "dead_file"
    url = data.get("downloadUrl") or ""
    if not url:
        return "bbupload", [], "bbupload_unverified"
    return "bbupload", [{"url": url, "referer": cleaned,
                         "kind_hint": "file"}], None


# ── resolve + rank ───────────────────────────────────────────────────────────

def _fail_result(backend: str, failure: str) -> dict:
    return {"ok": False, "backend": backend,
            "group": independence_group(backend), "kind": None, "url": None,
            "mime": None, "size": None, "resumable": False, "renditions": [],
            "failure": failure, "failure_dead": failure == "dead_file",
            "temporary": True}


def resolve_copy(saved_url: str, fetch=default_fetch, probe=None) -> dict:
    """Unwrap one saved link fresh and probe the media it yields.

    Returns a passing copy dict, or a failing dict whose ``failure`` names
    the class (never a guessed URL, never a stale address). A failing copy
    is simply not ranked — only a passing probe can win.
    """
    probe_fn = probe or (lambda u, r, k: probe_media(u, r, k, fetch=fetch))
    backend0 = classify_backend(saved_url)
    if backend0 == "streamhg":
        backend, candidates, failure = _unwrap_iqsmart(saved_url, fetch)
    elif backend0 == "modiplay":
        backend, candidates, failure = _unwrap_modiplay(saved_url, fetch)
    elif backend0 == "vidrift":
        backend, candidates, failure = _unwrap_vidrift(saved_url, fetch)
    elif backend0 == "vidbolt":
        backend, candidates, failure = _unwrap_vidbolt(saved_url, fetch)
    elif backend0 == "hubcdn":
        backend, candidates, failure = _unwrap_hubcdn(saved_url, fetch)
    elif backend0 == "vidly":
        backend, candidates, failure = _unwrap_vidly(saved_url, fetch)
    elif backend0 == "vibuxer":
        backend, candidates, failure = _unwrap_vibuxer(saved_url, fetch)
    elif backend0 == "pixeldrain":
        backend, candidates, failure = _unwrap_pixeldrain(saved_url, fetch)
    elif backend0 == "fastdl-beer":
        backend, candidates, failure = _unwrap_fastdl(saved_url, fetch)
    elif backend0 == "hubdrive":
        backend, candidates, failure = _unwrap_hubdrive(saved_url, fetch)
    elif backend0 == "megaup":
        backend, candidates, failure = _unwrap_megaup(saved_url, fetch)
    elif backend0 == "bbupload":
        backend, candidates, failure = _unwrap_bbupload(saved_url, fetch)
    elif backend0 == "1fichier":
        # No password plumbing exists anywhere in the repo, so password=None
        # at dispatch; a password-protected file fails "needs_password".
        backend, candidates, failure = _unwrap_1fichier(saved_url, fetch,
                                                        password=None)
    else:
        return _fail_result(backend0, "unknown_backend")
    if failure:
        return _fail_result(backend, failure)
    expanded = []
    for cand in candidates:
        if "_hanerix_page" in cand:
            hanerix_cands, hanerix_failure = _unwrap_hanerix_page(
                cand["_hanerix_page"], fetch)
            if hanerix_failure:
                continue
            expanded.extend(hanerix_cands)
        else:
            expanded.append(cand)
    if not expanded:
        return _fail_result(backend, "unverified")
    last_failure = "unverified"
    for cand in expanded:
        verdict = probe_fn(cand["url"], cand.get("referer"),
                           cand.get("kind_hint", "playlist"))
        if verdict.get("ok"):
            # A HubCDN R2 win is permanent (unsigned public bucket, no
            # expiry — unlike HubCloud FSL's 8 h signature), so it never
            # needs a re-resolve timer; a Google win is minted per request
            # and stays temporary. Mirrors ride on the result so the
            # caller can bank all four destinations from one resolve.
            permanent = (backend == "pixeldrain" or
                         (backend == "hubcdn"
                          and _host_of(cand["url"]).endswith(".r2.dev")))
            result = {"ok": True, "backend": backend,
                      "group": independence_group(backend),
                      "kind": verdict["kind"], "url": cand["url"],
                      "mime": verdict["mime"], "size": verdict["size"],
                      "resumable": bool(verdict["resumable"]),
                      "renditions": verdict["renditions"],
                      "failure": None, "failure_dead": False,
                      "temporary": not permanent}
            if cand.get("mirrors"):
                result["mirrors"] = cand["mirrors"]
            if backend == "vidbolt":
                # Bank-all (2026-11-23): the one /api/resolve response
                # already published every rendition; the non-winning ones
                # ride on the result (same shape as hubcdn's `mirrors`) so
                # the caller can bank or fail over to them from this
                # resolve instead of re-resolving per quality. Each
                # carries its own `expires_at` (None when its URL carries
                # no parsable lifetime).
                others = [c for c in expanded
                          if c.get("url") and c["url"] != cand["url"]]
                if others:
                    result["siblings"] = others
            return result
        last_failure = verdict.get("failure") or "unverified"
    return _fail_result(backend, last_failure)


def _max_width(copy: dict) -> int:
    widths = [w for w, _h in (copy.get("renditions") or [])]
    return max(widths) if widths else 0


def _watch_order(copy: dict):
    preference = (WATCH_PREFERENCE.index(copy["backend"])
                  if copy["backend"] in WATCH_PREFERENCE else len(WATCH_PREFERENCE))
    has_renditions = 0 if copy.get("renditions") else 1
    return (has_renditions, -_max_width(copy), preference)


def select_watch(copies: list) -> tuple:
    """Winner + up to 2 backups for watch, one per independence group."""
    passing = [c for c in (copies or []) if c.get("ok")]
    ordered = sorted(passing, key=_watch_order)
    chosen, seen = [], set()
    for copy in ordered:
        if copy["group"] in seen:
            continue
        seen.add(copy["group"])
        chosen.append(copy)
        if len(chosen) == 3:
            break
    if not chosen:
        return None, []
    return chosen[0], chosen[1:]


def select_download(copies: list) -> tuple:
    """Winner + up to 2 backups for download: single files only, and only
    the stage-1 download backends (PixelDrain resumable; fastdl.beer,
    hubdrive, megaup not).
    A vidrift direct file is a watch candidate here, not a download winner:
    its download path is still provisional and stays open, not assumed."""
    passing = [c for c in (copies or [])
               if c.get("ok") and c.get("kind") == "file"
               and c.get("backend") in DOWNLOAD_PREFERENCE]
    preference = {name: i for i, name in enumerate(DOWNLOAD_PREFERENCE)}
    ordered = sorted(
        passing,
        key=lambda c: (0 if c.get("resumable") else 1,
                       preference.get(c["backend"], len(preference))))
    chosen, seen = [], set()
    for copy in ordered:
        if copy["group"] in seen:
            continue
        seen.add(copy["group"])
        chosen.append(copy)
        if len(chosen) == 3:
            break
    if not chosen:
        return None, []
    return chosen[0], chosen[1:]


def resolve_release(rows: list, fetch=default_fetch, probe=None,
                    deadline: float | None = None) -> dict:
    """Resolve every copy of one release fresh, then rank watch + download.

    ``rows`` are download_sources-shaped dicts (source_url, final_url,
    service, status, id). Stamped status never ranks: a row is tried on
    its fresh unwrap+probe alone, and only a passing probe can win.
    At most 10 rows are tried, so one release cannot stall a request.
    ``deadline`` is an absolute ``time.monotonic`` value: past it, no
    new sibling is started (Session 101 — the same 10 s budget the
    Stage-2 ladder already had; a sibling started just before the
    deadline still runs to its own probe verdict, as there).

    Siblings resolve in parallel (max 4 workers): ``resolve_copy`` is
    network-only — unwrap + probe, never a DB read or write — so it is
    safe on worker threads, and the write-back happens in the caller.
    The recursive resolver itself is never run in a thread (it shares
    the caller's SQLite connection). One sibling raising must not kill
    the failover: its exception is swallowed and the other copies still
    get their chance. Results are re-sorted into input order before
    ranking, so winners are deterministic. The deadline gates
    *starting* a sibling, not finishing one: a sibling started before
    it runs to its own probe verdict, exactly as the serial ladder did.
    """
    todo = [row for row in list(rows or [])[:10]
            if ((row or {}).get("source_url") or (row or {}).get("final_url"))]
    if not todo:
        return {"copies": [], "failures": [],
                "watch_winner": None, "watch_backups": [],
                "download_winner": None, "download_backups": []}

    def _one(index_row):
        index, row = index_row
        saved = (row or {}).get("source_url") or (row or {}).get("final_url") or ""
        result = resolve_copy(saved, fetch=fetch, probe=probe)
        return index, dict(result, row_id=(row or {}).get("id"))

    outcomes = []
    if len(todo) == 1:
        outcomes.append(_one((0, todo[0])))
    else:
        workers = min(4, len(todo))
        pool = ThreadPoolExecutor(max_workers=workers)
        try:
            pending = {}
            queue = list(enumerate(todo))

            def _submit_next() -> bool:
                if not queue:
                    return False
                if deadline is not None and time.monotonic() > deadline:
                    return False
                item = queue.pop(0)
                pending[pool.submit(_one, item)] = item[0]
                return True

            for _ in range(workers):
                if not _submit_next():
                    break
            while pending:
                done, _not_done = wait(pending, return_when=FIRST_COMPLETED)
                for future in done:
                    del pending[future]
                    try:
                        outcomes.append(future.result())
                    except Exception:
                        continue
                    _submit_next()
        finally:
            # Stragglers finish their own bounded fetches in the
            # background; do not wait for them past the deadline.
            pool.shutdown(wait=False, cancel_futures=True)
    outcomes.sort(key=lambda item: item[0])
    copies, failures = [], []
    for _index, result in outcomes:
        (copies if result.get("ok") else failures).append(result)
    watch_winner, watch_backups = select_watch(copies)
    dl_winner, dl_backups = select_download(copies)
    return {"copies": copies, "failures": failures,
            "watch_winner": watch_winner, "watch_backups": watch_backups,
            "download_winner": dl_winner, "download_backups": dl_backups}
