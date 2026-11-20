#!/usr/bin/env python3
"""Fast_Fetch/duration_probe.py — mint a duration from a resolved link.

Proven by the prototype (``~/workspace/duration-probe-20261120/report.md``,
2026-11-20; HLS 30/30 exact, direct 14/14 exact against ffprobe):

  (1) HLS: fetch the .m3u8 playlist (a few KB of text) and sum the #EXTINF
      durations. Zero media bytes; median 149 ms / 205 KB per link on the
      prototype — cheap enough for resolve-time use, not just backfill.
  (2) Direct files (.mp4/.mkv): Range GET the first 2 MB and parse the
      duration straight out of the container header (MP4 mvhd, MKV EBML
      Duration) — no ffprobe involved. When the header carries no duration
      (moov-at-end, MKV without a Duration element in the window, unknown
      container), fall back to ffprobe over the URL, which Range-seeks on
      its own. Never a full transfer.

The two parser bugs the prototype fixed are pinned by regression tests in
``test_duration_backfill.py`` (they fail on the old parse, pass on this
one): (a) the EBML vint length-marker bit belongs in element IDs and is
stripped only from sizes; (b) the Segment size vint must be consumed
before its children are read.

Uploadflix (prototype gap, investigated from the saved ``results.jsonl``,
not re-probed): 9 of its direct links return ``ffprobe-no-duration``
while 12 succeed — and the SAME title yields OK on one mirror and FAIL
on another, so it is a per-link serving failure, not a host-level or
container quirk the parser can fix. The header-first probe in
:func:`probe_direct` turns that opaque failure into a concrete HTTP
error on the next run (the Range GET names the status); the host is
NOT excluded, and the per-link error is recorded in the staging table
for re-resolve/retry to handle.

Stdlib only (urllib, subprocess for ffprobe). Every fetch has a timeout
and a size cap. Probes only URLs taken from our own ``resolved_cache`` —
never user-supplied URLs, never arbitrary hosts.
"""

from __future__ import annotations

import json
import re
import struct
import subprocess
import time
import urllib.error
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"}
HLS_TIMEOUT = 15            # seconds per playlist fetch
HLS_SIZE_CAP = 2 * 1024 * 1024   # 2 MB — a playlist is a few KB; more = wrong file
HEADER_BYTES = 2 * 1024 * 1024   # first 2 MB of a direct file
RANGE_TIMEOUT = 10
FFPROBE_TIMEOUT = 30        # seconds per ffprobe call
TRUTH_TOLERANCE_S = 2.0     # probe-vs-ffprobe agreement window

EXTINF_RE = re.compile(r"^#EXTINF:([0-9]+(?:\.[0-9]+)?)")
STREAM_INF_RE = re.compile(r"^#EXT-X-STREAM-INF:(.*)$")
BANDWIDTH_RE = re.compile(r"BANDWIDTH=(\d+)")


def fetch_text(url, timeout=HLS_TIMEOUT, cap=HLS_SIZE_CAP):
    """GET url, return (text, bytes_fetched, http_status) or raise."""
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        status = resp.status
        chunks = []
        total = 0
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            total += len(chunk)
            if total > cap:
                raise ValueError("size-cap exceeded (%d bytes)" % total)
            chunks.append(chunk)
        return b"".join(chunks).decode("utf-8", "replace"), total, status


def range_get(url, nbytes=HEADER_BYTES, timeout=RANGE_TIMEOUT):
    """Range GET the first ``nbytes`` of url. Returns the bytes, or raises.

    A host that ignores Range and answers 200 is read only up to the cap:
    more than ``nbytes`` bytes raises ``ValueError("cap-exceeded")`` so the
    caller records ``range-not-supported`` instead of downloading the file.
    """
    req = urllib.request.Request(
        url, headers=dict(UA, Range="bytes=0-%d" % (nbytes - 1)))
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if resp.status not in (200, 206):
            raise ValueError("http-%d" % resp.status)
        data = resp.read(nbytes + 1)
        if len(data) > nbytes:
            raise ValueError("cap-exceeded")
        return data


def resolve_url(base, rel):
    from urllib.parse import urljoin
    return urljoin(base, rel)


def parse_media_playlist(text):
    """Sum #EXTINF durations. Returns (duration_s, n_segments, is_live)."""
    total = 0.0
    n = 0
    for line in text.splitlines():
        line = line.strip()
        m = EXTINF_RE.match(line)
        if m:
            total += float(m.group(1))
            n += 1
    is_live = "#EXT-X-ENDLIST" not in text
    return total, n, is_live


def parse_master_playlist(text):
    """Return a list of (bandwidth, uri) for variants, in listed order."""
    variants = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        line = line.strip()
        m = STREAM_INF_RE.match(line)
        if m and i + 1 < len(lines):
            bw = BANDWIDTH_RE.search(m.group(1))
            uri = lines[i + 1].strip()
            if uri and not uri.startswith("#"):
                variants.append((int(bw.group(1)) if bw else 0, uri))
    return variants


def _http_error(e):
    """'http-429' (plus ':retry-after=N' when the host sent one) / 'http-N'.

    The Retry-After suffix is the only header the job needs: the per-host
    backoff parks the host for exactly that long instead of guessing.
    """
    code = e.code
    if code == 429:
        try:
            retry_after = e.headers.get("Retry-After") if e.headers else None
        except Exception:
            retry_after = None
        if retry_after:
            return "http-429:retry-after=%s" % str(retry_after).strip()
    return "http-%d" % code


def probe_hls(url, fetch=fetch_text):
    """EXTINF-sum probe. Returns a dict with a duration, or an error."""
    t0 = time.time()

    def _fail(error, **extra):
        rec = {"ok": False, "error": error,
               "ms": int((time.time() - t0) * 1000)}
        rec.update(extra)
        return rec

    try:
        text, nbytes, _status = fetch(url)
    except urllib.error.HTTPError as e:
        return _fail(_http_error(e))
    except Exception as e:  # timeout, DNS, cap, decode, ...
        return _fail(type(e).__name__ + ": " + str(e)[:120])
    if "#EXTM3U" not in text[:200] and "#EXTINF" not in text:
        return _fail("not-a-playlist", bytes=nbytes)
    variants = parse_master_playlist(text)
    variant_used = None
    if variants:
        # Highest-bandwidth variant; fall back down the list if one fails.
        variants.sort(key=lambda v: v[0], reverse=True)
        last_err = None
        for _bw, uri in variants:
            vurl = resolve_url(url, uri)
            try:
                text, nbytes2, _s = fetch(vurl)
                variant_used = vurl
                nbytes += nbytes2
                break
            except Exception as e:
                last_err = e
                continue
        else:
            return _fail("variant-fetch-failed: " + str(last_err)[:100],
                         bytes=nbytes)
    duration, nseg, is_live = parse_media_playlist(text)
    if nseg == 0:
        return _fail("no-extinf-segments", bytes=nbytes)
    return {"ok": True, "duration": round(duration, 3), "segments": nseg,
            "live": is_live, "variant": bool(variant_used),
            "method": "hls-extinf", "bytes": nbytes,
            "ms": int((time.time() - t0) * 1000)}


def ffprobe_duration(url, timeout=FFPROBE_TIMEOUT):
    """Ground-truth-ish duration via ffprobe on the URL itself."""
    t0 = time.time()
    try:
        p = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "csv=p=0", url],
            capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "ffprobe-timeout",
                "ms": int((time.time() - t0) * 1000)}
    except FileNotFoundError:
        return {"ok": False, "error": "ffprobe-missing",
                "ms": int((time.time() - t0) * 1000)}
    out = (p.stdout or "").strip()
    try:
        dur = float(out.splitlines()[0])
        if dur != dur or dur in (float("inf"), float("-inf")):
            raise ValueError("nan/inf")
        return {"ok": True, "duration": round(dur, 3),
                "ms": int((time.time() - t0) * 1000)}
    except (ValueError, IndexError):
        err = (p.stderr or "").strip().splitlines()
        return {"ok": False,
                "error": "ffprobe-no-duration" +
                ((": " + err[-1][:100]) if err else ""),
                "ms": int((time.time() - t0) * 1000)}


def ffprobe_full(url, timeout=FFPROBE_TIMEOUT):
    """Duration + stream info via ffprobe on the URL (range-seeking)."""
    t0 = time.time()
    try:
        p = subprocess.run(
            ["ffprobe", "-v", "error",
             "-show_entries", "format=duration,size",
             "-show_entries", "stream=codec_name,codec_type,width,height",
             "-of", "json", url],
            capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "ffprobe-timeout",
                "ms": int((time.time() - t0) * 1000)}
    except FileNotFoundError:
        return {"ok": False, "error": "ffprobe-missing",
                "ms": int((time.time() - t0) * 1000)}
    try:
        data = json.loads(p.stdout or "{}")
        dur = float(data["format"]["duration"])
        streams = data.get("streams", [])
        video = [s for s in streams if s.get("codec_type") == "video"]
        audio = [s for s in streams if s.get("codec_type") == "audio"]
        return {"ok": True, "duration": round(dur, 3),
                "video_codec": video[0].get("codec_name") if video else None,
                "width": video[0].get("width") if video else None,
                "height": video[0].get("height") if video else None,
                "audio_codec": audio[0].get("codec_name") if audio else None,
                "ms": int((time.time() - t0) * 1000)}
    except (KeyError, ValueError, TypeError, IndexError):
        err = (p.stderr or "").strip().splitlines()
        return {"ok": False,
                "error": "ffprobe-no-duration" +
                ((": " + err[-1][:100]) if err else ""),
                "ms": int((time.time() - t0) * 1000)}


def sniff_container(data):
    """'mp4' for an ftyp box, 'mkv' for EBML magic, else None."""
    if len(data) >= 4 and data[:4] == b"\x1a\x45\xdf\xa3":
        return "mkv"
    if len(data) >= 8 and data[4:8] == b"ftyp":
        return "mp4"
    return None


def parse_mp4(data):
    """Walk top-level boxes; return (duration_s or None, note)."""
    off = 0
    moov = None
    while off + 8 <= len(data):
        size = struct.unpack(">I", data[off:off + 4])[0]
        typ = data[off + 4:off + 8].decode("latin1")
        if size == 1:  # 64-bit largesize
            if off + 16 > len(data):
                break
            size = struct.unpack(">Q", data[off + 8:off + 16])[0]
            hdr = 16
        elif size == 0:
            size = len(data) - off
            hdr = 8
        else:
            hdr = 8
        if size < hdr or off + size > len(data) + 8:
            # box runs past our window — moov may be cut off
            if typ == "moov":
                return None, "moov-truncated-by-window"
            break
        if typ == "moov":
            moov = data[off + hdr:off + size]
            break
        off += size
    if moov is None:
        return None, "moov-not-in-header"
    # inside moov, find mvhd
    off = 0
    while off + 8 <= len(moov):
        size = struct.unpack(">I", moov[off:off + 4])[0]
        typ = moov[off + 4:off + 8].decode("latin1")
        if size < 8 or off + size > len(moov):
            break
        if typ == "mvhd":
            b = moov[off + 8:off + size]
            version = b[0]
            if version == 0 and len(b) >= 20:
                timescale, duration = struct.unpack(">II", b[12:20])
            elif version == 1 and len(b) >= 32:
                timescale, duration = struct.unpack(">IQ", b[20:32])
            else:
                return None, "mvhd-short"
            if timescale == 0:
                return None, "timescale-zero"
            return duration / timescale, "mvhd-v%d" % version
        off += size
    return None, "mvhd-not-found"


def _read_vint(data, off, is_id=False):
    """EBML variable-length int. Returns (value, length).

    For element IDs the length-marker bit is part of the value; for sizes
    it is stripped (and all-ones means unknown size). Getting this
    backwards was prototype bug #1: stripping the marker from an ID
    turns Duration (0x4489) into 0x0489, which no parser recognises.
    """
    if off >= len(data):
        return None, 0
    first = data[off]
    mask = 0x80
    length = 1
    while length <= 8 and not (first & mask):
        mask >>= 1
        length += 1
    if length > 8:
        return None, 0
    if length == 1:
        return (first if is_id else first & 0x7F), 1
    value = first if is_id else (first & (mask - 1))
    for i in range(1, length):
        if off + i >= len(data):
            return None, 0
        value = (value << 8) | data[off + i]
    return value, length


def _unknown_size(sz, length):
    return sz == (1 << (7 * length)) - 1


def parse_mkv(data):
    """EBML walk: Segment -> Info -> Duration (+ TimestampScale)."""
    if len(data) < 4 or data[:4] != b"\x1a\x45\xdf\xa3":
        return None, "not-ebml"
    off = 4
    # skip EBML header element (id 0x1A45DFA3 already consumed; read its size)
    _sz, n = _read_vint(data, off)
    if _sz is None:
        return None, "ebml-header-bad"
    off += n + (0 if _unknown_size(_sz, n) else _sz)
    # find Segment (id 0x18538067)
    seg_start = None
    while off + 4 <= len(data):
        eid, n = _read_vint(data, off, is_id=True)
        if eid is None:
            break
        off += n
        if eid == 0x18538067:
            # Consume the Segment size vint too — children start after it.
            # Skipping it was prototype bug #2: the size bytes were then
            # read as element IDs and the walk never found Info.
            _sz, n2 = _read_vint(data, off)
            if _sz is None:
                break
            seg_start = off + n2
            break
        sz, n2 = _read_vint(data, off)
        if sz is None:
            break
        off += n2
        if _unknown_size(sz, n2):  # unknown size — give up
            break
        off += sz
    if seg_start is None:
        return None, "segment-not-found"
    # inside Segment, find Info (0x1549A966)
    off = seg_start
    info = None
    while off + 4 <= len(data):
        eid, n = _read_vint(data, off, is_id=True)
        if eid is None:
            break
        off += n
        sz, n2 = _read_vint(data, off)
        if sz is None:
            break
        off += n2
        if eid == 0x1549A966:
            info = (off, sz if _unknown_size(sz, n2) else sz)
            if _unknown_size(sz, n2):
                info = (off, len(data) - off)
            break
        if _unknown_size(sz, n2):
            break
        off += sz
    if info is None:
        return None, "info-not-found"
    start, size = info
    end = min(start + size, len(data))
    off = start
    duration = None
    timescale = 1000000  # EBML default
    while off + 2 <= end:
        eid, n = _read_vint(data, off, is_id=True)
        if eid is None:
            break
        off += n
        sz, n2 = _read_vint(data, off)
        if sz is None:
            break
        off += n2
        if off + sz > end:
            break
        if eid == 0x4489:  # Duration (float)
            raw = data[off:off + sz]
            try:
                duration = struct.unpack(">d", raw)[0] if sz == 8 else \
                    struct.unpack(">f", raw)[0]
            except struct.error:
                return None, "duration-unpack-fail"
        elif eid == 0x2AD7B1:  # TimestampScale (uint)
            timescale = int.from_bytes(data[off:off + sz], "big")
        off += sz
    if duration is None:
        return None, "duration-not-in-info"
    return duration * timescale / 1e9, "ebml"


def probe_direct(url, range_fetch=range_get, ffprobe=ffprobe_full):
    """Header-first direct probe. Returns a dict with a duration, or an error.

    The header parse is tried first because it costs one 2 MB Range GET
    and no subprocess. ffprobe runs only when the header carries no
    duration — and its error is then the recorded one, with the header
    note kept alongside for diagnosis (that pair is what will resolve
    the uploadflix no-duration links on the first real run).
    """
    t0 = time.time()

    def _fail(error, **extra):
        rec = {"ok": False, "error": error,
               "ms": int((time.time() - t0) * 1000)}
        rec.update(extra)
        return rec

    try:
        data = range_fetch(url)
    except urllib.error.HTTPError as e:
        return _fail(_http_error(e))
    except ValueError as e:
        if "cap-exceeded" in str(e):
            return _fail("range-not-supported: cap-exceeded")
        return _fail("ValueError: " + str(e)[:120])
    except Exception as e:
        return _fail(type(e).__name__ + ": " + str(e)[:120])
    container = sniff_container(data)
    if container == "mp4":
        dur, note = parse_mp4(data)
        method = "mp4-mvhd"
    elif container == "mkv":
        dur, note = parse_mkv(data)
        method = "mkv-ebml"
    else:
        dur, note = None, "unknown-container"
        method = None
    if dur is not None:
        return {"ok": True, "duration": round(dur, 3), "method": method,
                "header_note": note, "bytes": len(data),
                "ms": int((time.time() - t0) * 1000)}
    fb = ffprobe(url)
    if fb["ok"]:
        fb["method"] = "ffprobe"
        fb["header_note"] = note
        return fb
    fb["header_note"] = note
    return fb


def probe_url(url, kind=None, fetch=fetch_text, range_fetch=range_get,
              ffprobe=ffprobe_full):
    """Dispatch on the resolved_cache kind ('playlist'/'file'), falling back
    to the URL shape (.m3u8) when no kind is known."""
    if kind == "playlist" or url.lower().split("?")[0].endswith(".m3u8"):
        return probe_hls(url, fetch=fetch)
    return probe_direct(url, range_fetch=range_fetch, ffprobe=ffprobe)
