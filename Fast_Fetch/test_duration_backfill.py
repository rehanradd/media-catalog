#!/usr/bin/env python3
"""Offline tests for duration_probe.py + backfill_durations.py + duration_apply.sql.

Run from the repo root (the gate) or from Fast_Fetch:
    PYTHONDONTWRITEBYTECODE=1 python3 -S -B test_duration_backfill.py
No file writes outside temp dirs, no network, no live DB. ffprobe is
never required: every ffprobe call goes through an injectable callable,
and the tests use fakes.

Coverage: HLS playlist parsing (EXTINF sum, master-variant pick,
live flag), MP4 mvhd v0/v1, MKV EBML (including the two regression
tests that FAIL on the prototype's original parse and PASS on the
fixed one: the vint marker bit belongs in IDs, and the Segment size
vint must be consumed before its children), the header-first direct
probe (header hit needs no ffprobe; header miss falls back to
ffprobe; both miss records ffprobe-no-duration), the uploadflix
per-link failure (same host, one OK one FAIL — the host is NOT
excluded), batch selection (fresh only, orphans/viewer/stale/already-
probed excluded, HLS first), the job run (report-only vs --apply,
idempotent re-run, work disagreement, out-of-range, live playlists,
per-host 429 backoff with Retry-After), the hard guards (sweep,
prefetch, backup, load, gate-backup copy), truth sampling (including
the <98% alert), and the gate-apply SQL (NULL-only fills, movie-only,
H:MM:SS posts, series skipped, live guard aborts, re-run fills zero,
verify all zero).
"""
from __future__ import annotations

import json
import sqlite3
import struct
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import backfill_durations
import duration_probe
from backfill_durations import (
    _HostThrottle, check_guards, probe_one, run, select_batch,
    service_active)
from duration_probe import (
    parse_media_playlist, parse_master_playlist, parse_mkv, parse_mp4,
    probe_direct, probe_hls, probe_url)

APPLY_SQL = Path(__file__).with_name("duration_apply.sql")


# ---------------------------------------------------------------- fixtures

def _mp4_bytes(duration_s, timescale=1000, version=0):
    """Minimal MP4: ftyp + moov/mvhd carrying exactly duration_s."""
    if version == 0:
        mvhd_body = struct.pack(">4xII", 0, 0)  # ver/flags, created, modified
        mvhd_body += struct.pack(">II", timescale, int(duration_s * timescale))
        mvhd_body += b"\x00" * 80
        mvhd_body = bytes([0]) + mvhd_body[1:]
    else:
        mvhd_body = bytes([1]) + b"\x00" * 3
        mvhd_body += struct.pack(">Q", 0) + struct.pack(">Q", 0)
        mvhd_body += struct.pack(">I", timescale)
        mvhd_body += struct.pack(">Q", int(duration_s * timescale))
        mvhd_body += b"\x00" * 80
    mvhd = struct.pack(">I4s", 8 + len(mvhd_body), b"mvhd") + mvhd_body
    moov = struct.pack(">I4s", 8 + len(mvhd), b"moov") + mvhd
    ftyp_body = b"isom" + struct.pack(">I", 0) + b"isomiso2"
    ftyp = struct.pack(">I4s", 8 + len(ftyp_body), b"ftyp") + ftyp_body
    return ftyp + moov


def _vint(value, length):
    """EBML vint for a size (marker stripped from the value)."""
    if length == 1:
        return bytes([0x80 | value])
    out = bytearray(length)
    marker = 0x80 >> (length - 1)
    v = value
    for i in range(length - 1, 0, -1):
        out[i] = v & 0xFF
        v >>= 8
    out[0] = marker | (v & (marker - 1))
    return bytes(out)


def _element(eid_bytes, payload):
    return eid_bytes + _vint(len(payload), 1) + payload


def _mkv_bytes(duration, timescale=1000000, float64=True):
    """Minimal MKV: EBML header + Segment (unknown size) + Info with
    Duration (+ TimestampScale). Duration is stored in TimestampScale
    units (ticks = seconds * 1e9 / timescale), as real files do: a
    6102.034 s movie at the default 1 ms scale carries 6,102,034."""
    ticks = duration * 1e9 / timescale
    dur_raw = struct.pack(">d", ticks) if float64 else struct.pack(
        ">f", ticks)
    info_payload = _element(b"\x44\x89", dur_raw)
    info_payload += _element(b"\x2a\xd7\xb1",
                             timescale.to_bytes(4, "big"))
    info = _element(b"\x15\x49\xa9\x66", info_payload)
    segment = b"\x18\x53\x80\x67" + b"\x01" + b"\xff" * 7 + info
    ebml_head = b"\x1a\x45\xdf\xa3" + _vint(0, 1)
    return ebml_head + segment


def _old_read_vint(data, off, is_id=False):
    """The prototype's ORIGINAL vint reader: bug #1 — the length-marker
    bit is stripped from IDs exactly as it is from sizes."""
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
        return first & 0x7F, 1
    value = first & (mask - 1)  # BUG: marker stripped even for IDs
    for i in range(1, length):
        if off + i >= len(data):
            return None, 0
        value = (value << 8) | data[off + i]
    return value, length


def _old_parse_mkv(data):
    """The prototype's ORIGINAL MKV walk: bug #1 (vint marker stripped
    from IDs) AND bug #2 (the Segment size vint is never consumed, so
    the walk starts inside the size bytes). Both bugs fixed in
    duration_probe.parse_mkv; this copy exists only so the regression
    tests can prove the old parse fails where the new one succeeds."""
    if len(data) < 4 or data[:4] != b"\x1a\x45\xdf\xa3":
        return None, "not-ebml"
    off = 4
    _sz, n = _old_read_vint(data, off)
    if _sz is None:
        return None, "ebml-header-bad"
    off += n + _sz
    seg_start = None
    while off + 4 <= len(data):
        eid, n = _old_read_vint(data, off, is_id=True)
        if eid is None:
            break
        off += n
        if eid == 0x18538067:
            seg_start = off  # BUG: Segment size vint not consumed
            break
        sz, n2 = _old_read_vint(data, off)
        if sz is None:
            break
        off += n2 + sz
    if seg_start is None:
        return None, "segment-not-found"
    off = seg_start
    info = None
    while off + 4 <= len(data):
        eid, n = _old_read_vint(data, off, is_id=True)
        if eid is None:
            break
        off += n
        sz, n2 = _old_read_vint(data, off)
        if sz is None:
            break
        off += n2
        if eid == 0x1549A966:
            info = (off, sz)
            break
        off += sz
    if info is None:
        return None, "info-not-found"
    start, size = info
    end = min(start + size, len(data))
    off = start
    duration = None
    timescale = 1000000
    while off + 2 <= end:
        eid, n = _old_read_vint(data, off, is_id=True)
        if eid is None:
            break
        off += n
        sz, n2 = _old_read_vint(data, off)
        if sz is None:
            break
        off += n2
        if off + sz > end:
            break
        if eid == 0x4489:
            raw = data[off:off + sz]
            duration = struct.unpack(">d", raw)[0] if sz == 8 else \
                struct.unpack(">f", raw)[0]
        elif eid == 0x2AD7B1:
            timescale = int.from_bytes(data[off:off + sz], "big")
        off += sz
    if duration is None:
        return None, "duration-not-in-info"
    return duration * timescale / 1e9, "ebml"


def _seg_bug_only_parse_mkv(data):
    """Correct vint reading, but bug #2 alone: the Segment size vint is
    never consumed, so the walk starts inside the size bytes."""
    from duration_probe import _read_vint, _unknown_size
    if len(data) < 4 or data[:4] != b"\x1a\x45\xdf\xa3":
        return None, "not-ebml"
    off = 4
    _sz, n = _read_vint(data, off)
    if _sz is None:
        return None, "ebml-header-bad"
    off += n + (0 if _unknown_size(_sz, n) else _sz)
    seg_start = None
    while off + 4 <= len(data):
        eid, n = _read_vint(data, off, is_id=True)
        if eid is None:
            break
        off += n
        if eid == 0x18538067:
            seg_start = off  # BUG: Segment size vint not consumed
            break
        sz, n2 = _read_vint(data, off)
        if sz is None:
            break
        off += n2
        if _unknown_size(sz, n2):
            break
        off += sz
    if seg_start is None:
        return None, "segment-not-found"
    off = seg_start
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
            return "found-info", None
        if _unknown_size(sz, n2):
            break
        off += sz
    return None, "info-not-found"


HLS_MEDIA = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXTINF:9.976,
seg0.ts
#EXTINF:10.024,
seg1.ts
#EXTINF:8.5,
seg2.ts
#EXT-X-ENDLIST
"""

HLS_MASTER = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
mid.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
high.m3u8
"""

HLS_LIVE = """#EXTM3U
#EXT-X-VERSION:3
#EXTINF:6.0,
seg0.ts
#EXTINF:6.0,
seg1.ts
"""

# A movie-length playlist (one 6102.034 s segment) for the job tests:
# HLS_MEDIA sums to 28.5 s, which the job correctly rejects as
# out-of-range, so the run tests need a realistic duration here.
HLS_MOVIE = """#EXTM3U
#EXT-X-VERSION:3
#EXTINF:6102.034,
seg0.ts
#EXT-X-ENDLIST
"""


def fake_fetch(mapping):
    """mapping: url -> text, or ('http', code) / ('http429', retry_after)."""
    def fetch(url, timeout=None, cap=None):
        value = mapping.get(url)
        if value is None:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        if isinstance(value, tuple) and value[0] == "http429":
            headers = {"Retry-After": str(value[1])} if len(value) > 1 \
                else {}
            raise urllib.error.HTTPError(
                url, 429, "Too Many Requests", headers, None)
        if isinstance(value, tuple) and value[0] == "http":
            raise urllib.error.HTTPError(url, value[1], "err", {}, None)
        return value, len(value.encode("utf-8")), 200
    return fetch


def fake_range(mapping):
    """mapping: url -> bytes, or ('http', code) / ('cap',) for a host
    that ignores Range (200 with a body past the cap)."""
    def range_fetch(url, nbytes=None, timeout=None):
        value = mapping.get(url)
        if value is None:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        if isinstance(value, tuple) and value[0] == "http429":
            headers = {"Retry-After": str(value[1])} if len(value) > 1 \
                else {}
            raise urllib.error.HTTPError(
                url, 429, "Too Many Requests", headers, None)
        if isinstance(value, tuple) and value[0] == "http":
            raise urllib.error.HTTPError(url, value[1], "err", {}, None)
        if isinstance(value, tuple) and value[0] == "cap":
            raise ValueError("cap-exceeded")
        return value
    return range_fetch


def fake_ffprobe(mapping, calls=None):
    """mapping: url -> duration (float), or None for 'no duration'.
    Records every call in ``calls`` when given."""
    def ffprobe(url, timeout=None):
        if calls is not None:
            calls.append(url)
        value = mapping.get(url, "missing")
        if value == "missing":
            return {"ok": False, "error": "ffprobe-missing"}
        if value is None:
            return {"ok": False, "error": "ffprobe-no-duration"}
        return {"ok": True, "duration": value,
                "video_codec": "h264", "width": 1920, "height": 1080}
    return ffprobe


FIXTURE_DDL = """
CREATE TABLE resolved_cache (
    link_id INTEGER PRIMARY KEY, url TEXT, category TEXT, kind TEXT,
    mime_type TEXT, file_size_bytes INTEGER, resolved_at REAL,
    expires_at REAL, reverify_after REAL, is_resumable INTEGER,
    http_status INTEGER, source TEXT, ip_bound INTEGER, fail_count INTEGER);
CREATE TABLE download_sources (
    id INTEGER PRIMARY KEY, release_id INTEGER NOT NULL);
CREATE TABLE releases (id INTEGER PRIMARY KEY, post_id INTEGER NOT NULL);
CREATE TABLE posts (
    id INTEGER PRIMARY KEY, url TEXT UNIQUE, title TEXT, seasons TEXT,
    duration TEXT, content_type TEXT, is_18_plus INTEGER DEFAULT 0);
CREATE TABLE cat_works (
    id INTEGER PRIMARY KEY, title TEXT, work_type TEXT,
    runtime_minutes INTEGER);
CREATE TABLE cat_work_sites (
    work_id INTEGER NOT NULL, source_site TEXT, post_id INTEGER NOT NULL,
    page_url TEXT, PRIMARY KEY (work_id, page_url));
"""


def make_db():
    """A temp-file catalog: 3 posts (movie / movie-prefilled / series),
    their releases, cache rows (fresh, stale, orphan, viewer), works."""
    import time as _time
    now = _time.time()
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    conn = sqlite3.connect(tmp.name)
    conn.executescript(FIXTURE_DDL)
    conn.executemany(
        "INSERT INTO posts (id, url, title, seasons, duration,"
        " content_type) VALUES (?, ?, ?, ?, ?, ?)",
        [(1, "https://site.example/p1", "Movie One", None, None, "movie"),
         (2, "https://site.example/p2", "Movie Two", None, "1:30:00",
          "movie"),
         (3, "https://site.example/p3", "Series Three", "1", None,
          "series")])
    conn.executemany("INSERT INTO releases (id, post_id) VALUES (?, ?)",
                     [(10, 1), (11, 2), (12, 3)])
    conn.executemany("INSERT INTO download_sources (id, release_id)"
                     " VALUES (?, ?)",
                     [(100, 10), (101, 10), (102, 11), (103, 12),
                      (104, 10)])
    conn.executemany(
        "INSERT INTO cat_works (id, title, work_type, runtime_minutes)"
        " VALUES (?, ?, ?, ?)",
        [(1000, "Movie One", "movie", None),
         (1001, "Movie Two", "movie", 90),
         (1002, "Series Three", "series", None)])
    conn.executemany(
        "INSERT INTO cat_work_sites (work_id, source_site, post_id,"
        " page_url) VALUES (?, ?, ?, ?)",
        [(1000, "site.example", 1, "https://site.example/p1"),
         (1001, "site.example", 2, "https://site.example/p2"),
         (1002, "site.example", 3, "https://site.example/p3")])
    conn.executemany(
        "INSERT INTO resolved_cache (link_id, url, kind, expires_at,"
        " resolved_at) VALUES (?, ?, ?, ?, ?)",
        [(100, "https://cdn.example/a.m3u8", "playlist", now + 3600,
          now - 300),
         (101, "https://cdn.example/b.mkv", "file", now + 3600, now - 100),
         (102, "https://cdn.example/c.mp4", "file", now + 3600, now - 200),
         (103, "https://cdn.example/d.mkv", "file", now + 3600, now - 50),
         (104, "https://cdn.example/e.mkv", "file", now - 10, now - 10),
         (105, "https://cdn.example/f.mkv", "file", now + 3600, now - 400),
         (106, "https://cdn.example/g", "viewer", now + 3600, now - 400)])
    conn.commit()
    return conn, tmp.name


def stage_row(conn, link_id, url, kind, duration_s, live=0, error=None,
              method="hls-extinf"):
    conn.execute(
        "INSERT INTO duration_probe_results (link_id, url, kind,"
        " duration_s, method, live, error, probed_at)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, 1700000000.0)",
        (link_id, url, kind, duration_s, method, live, error))


# ---------------------------------------------------------------- HLS

class HlsTests(unittest.TestCase):
    def test_extinf_sum_with_fractions(self):
        duration, nseg, is_live = parse_media_playlist(HLS_MEDIA)
        self.assertAlmostEqual(duration, 28.5, places=3)
        self.assertEqual(nseg, 3)
        self.assertFalse(is_live)

    def test_live_playlist_has_no_endlist(self):
        duration, nseg, is_live = parse_media_playlist(HLS_LIVE)
        self.assertAlmostEqual(duration, 12.0, places=3)
        self.assertTrue(is_live)

    def test_no_extinf_is_an_error_not_zero(self):
        probe = probe_hls("https://cdn.example/empty.m3u8",
                          fetch=fake_fetch(
                              {"https://cdn.example/empty.m3u8":
                               "#EXTM3U\n#EXT-X-ENDLIST\n"}))
        self.assertFalse(probe["ok"])
        self.assertEqual(probe["error"], "no-extinf-segments")

    def test_not_a_playlist_is_an_error(self):
        probe = probe_hls("https://cdn.example/page",
                          fetch=fake_fetch(
                              {"https://cdn.example/page":
                               "<html>not a playlist</html>"}))
        self.assertFalse(probe["ok"])
        self.assertEqual(probe["error"], "not-a-playlist")

    def test_http_410_is_a_dead_link_not_a_method_failure(self):
        probe = probe_hls("https://cdn.example/gone.m3u8",
                          fetch=fake_fetch(
                              {"https://cdn.example/gone.m3u8":
                               ("http", 410)}))
        self.assertFalse(probe["ok"])
        self.assertEqual(probe["error"], "http-410")

    def test_master_picks_highest_bandwidth_variant(self):
        media = HLS_MEDIA
        mapping = {
            "https://cdn.example/master.m3u8": HLS_MASTER,
            "https://cdn.example/low.m3u8": media,
            "https://cdn.example/mid.m3u8": media,
            "https://cdn.example/high.m3u8": media,
        }
        probe = probe_hls("https://cdn.example/master.m3u8",
                          fetch=fake_fetch(mapping))
        self.assertTrue(probe["ok"])
        self.assertTrue(probe["variant"])
        self.assertAlmostEqual(probe["duration"], 28.5, places=3)

    def test_master_falls_back_when_top_variant_fails(self):
        mapping = {
            "https://cdn.example/master.m3u8": HLS_MASTER,
            "https://cdn.example/low.m3u8": HLS_MEDIA,
            "https://cdn.example/mid.m3u8": HLS_MEDIA,
            "https://cdn.example/high.m3u8": ("http", 410),
        }
        probe = probe_hls("https://cdn.example/master.m3u8",
                          fetch=fake_fetch(mapping))
        self.assertTrue(probe["ok"])
        self.assertAlmostEqual(probe["duration"], 28.5, places=3)

    def test_master_all_variants_dead_is_an_error(self):
        mapping = {
            "https://cdn.example/master.m3u8": HLS_MASTER,
            "https://cdn.example/low.m3u8": ("http", 410),
            "https://cdn.example/mid.m3u8": ("http", 410),
            "https://cdn.example/high.m3u8": ("http", 410),
        }
        probe = probe_hls("https://cdn.example/master.m3u8",
                          fetch=fake_fetch(mapping))
        self.assertFalse(probe["ok"])
        self.assertTrue(probe["error"].startswith("variant-fetch-failed"))


# ---------------------------------------------------------------- MP4

class Mp4Tests(unittest.TestCase):
    def test_mvhd_v0_exact(self):
        dur, note = parse_mp4(_mp4_bytes(6102.034))
        self.assertAlmostEqual(dur, 6102.034, places=3)
        self.assertEqual(note, "mvhd-v0")

    def test_mvhd_v1_exact(self):
        dur, note = parse_mp4(_mp4_bytes(4908.758, version=1))
        self.assertAlmostEqual(dur, 4908.758, places=3)
        self.assertEqual(note, "mvhd-v1")

    def test_no_moov_in_window(self):
        ftyp_body = b"isom" + struct.pack(">I", 0) + b"isomiso2"
        ftyp = struct.pack(">I4s", 8 + len(ftyp_body), b"ftyp") + ftyp_body
        dur, note = parse_mp4(ftyp + b"\x00" * 64)
        self.assertIsNone(dur)
        self.assertEqual(note, "moov-not-in-header")

    def test_moov_without_mvhd(self):
        moov = struct.pack(">I4s", 8, b"moov")
        ftyp_body = b"isom" + struct.pack(">I", 0) + b"isomiso2"
        ftyp = struct.pack(">I4s", 8 + len(ftyp_body), b"ftyp") + ftyp_body
        dur, note = parse_mp4(ftyp + moov)
        self.assertIsNone(dur)
        self.assertEqual(note, "mvhd-not-found")


# ---------------------------------------------------------------- MKV

class MkvTests(unittest.TestCase):
    def test_duration_float64_exact(self):
        dur, note = parse_mkv(_mkv_bytes(6102.034))
        self.assertAlmostEqual(dur, 6102.034, places=3)
        self.assertEqual(note, "ebml")

    def test_duration_float32_exact(self):
        dur, note = parse_mkv(_mkv_bytes(2759.061, float64=False))
        self.assertAlmostEqual(dur, 2759.061, places=2)
        self.assertEqual(note, "ebml")

    def test_timestamp_scale_is_applied(self):
        # Duration is in TimestampScale units: ticks * scale_ns / 1e9.
        # The same 6102.034 s wall duration reads identically at a
        # 1 ms scale (6,102,034 ticks) and a 1 us scale (6,102,034,000).
        for scale in (1000000, 1000):
            dur, _note = parse_mkv(_mkv_bytes(6102.034, timescale=scale))
            self.assertAlmostEqual(dur, 6102.034, places=3)

    def test_not_ebml(self):
        dur, note = parse_mkv(b"\x00" * 64)
        self.assertIsNone(dur)
        self.assertEqual(note, "not-ebml")

    def test_info_without_duration(self):
        info_payload = _element(b"\x2a\xd7\xb1", (1000000).to_bytes(4, "big"))
        info = _element(b"\x15\x49\xa9\x66", info_payload)
        segment = b"\x18\x53\x80\x67" + b"\x01" + b"\xff" * 7 + info
        data = b"\x1a\x45\xdf\xa3" + _vint(0, 1) + segment
        dur, note = parse_mkv(data)
        self.assertIsNone(dur)
        self.assertEqual(note, "duration-not-in-info")

    def test_vint_marker_bit_belongs_in_ids(self):
        # REGRESSION (prototype bug #1): the length-marker bit is part
        # of an element ID. Duration is 0x4489; stripping the marker
        # turns it into 0x0489, which no parser recognises.
        from duration_probe import _read_vint
        self.assertEqual(_read_vint(b"\x44\x89", 0, is_id=True), (0x4489, 2))
        self.assertEqual(_old_read_vint(b"\x44\x89", 0, is_id=True),
                         (0x0489, 2))
        # ... while sizes DO strip it (0x81 means a 1-byte size of 1).
        self.assertEqual(_read_vint(b"\x81", 0), (1, 1))

    def test_segment_size_vint_is_consumed(self):
        # REGRESSION (prototype bug #2): the walk must consume the
        # Segment size vint before reading its children. A parse that
        # starts inside the size bytes never finds Info.
        data = _mkv_bytes(6102.034)
        self.assertEqual(_seg_bug_only_parse_mkv(data),
                         (None, "info-not-found"))
        dur, note = parse_mkv(data)
        self.assertAlmostEqual(dur, 6102.034, places=3)
        self.assertEqual(note, "ebml")

    def test_old_parse_fails_where_new_parse_succeeds(self):
        # Both prototype bugs together: the original walk returns
        # nothing on a fixture the fixed walk reads exactly.
        data = _mkv_bytes(6102.034)
        old_dur, _old_note = _old_parse_mkv(data)
        self.assertIsNone(old_dur)
        new_dur, _new_note = parse_mkv(data)
        self.assertAlmostEqual(new_dur, 6102.034, places=3)


# ---------------------------------------------------------------- direct probe

class ProbeDirectTests(unittest.TestCase):
    def test_mp4_header_hit_needs_no_ffprobe(self):
        calls = []
        result = probe_direct(
            "https://cdn.example/b.mp4",
            range_fetch=fake_range(
                {"https://cdn.example/b.mp4": _mp4_bytes(6102.034)}),
            ffprobe=fake_ffprobe({}, calls))
        self.assertTrue(result["ok"])
        self.assertAlmostEqual(result["duration"], 6102.034, places=3)
        self.assertEqual(result["method"], "mp4-mvhd")
        self.assertEqual(calls, [])

    def test_mkv_header_hit_needs_no_ffprobe(self):
        calls = []
        result = probe_direct(
            "https://cdn.example/b.mkv",
            range_fetch=fake_range(
                {"https://cdn.example/b.mkv": _mkv_bytes(4908.758)}),
            ffprobe=fake_ffprobe({}, calls))
        self.assertTrue(result["ok"])
        self.assertAlmostEqual(result["duration"], 4908.758, places=3)
        self.assertEqual(result["method"], "mkv-ebml")
        self.assertEqual(calls, [])

    def test_header_miss_falls_back_to_ffprobe(self):
        # moov-at-end file: the header has no duration, ffprobe seeks it.
        calls = []
        ftyp_body = b"isom" + struct.pack(">I", 0) + b"isomiso2"
        ftyp = struct.pack(">I4s", 8 + len(ftyp_body), b"ftyp") + ftyp_body
        result = probe_direct(
            "https://cdn.example/c.mp4",
            range_fetch=fake_range({"https://cdn.example/c.mp4": ftyp}),
            ffprobe=fake_ffprobe(
                {"https://cdn.example/c.mp4": 6102.034}, calls))
        self.assertTrue(result["ok"])
        self.assertEqual(result["method"], "ffprobe")
        self.assertEqual(result["header_note"], "moov-not-in-header")
        self.assertEqual(calls, ["https://cdn.example/c.mp4"])

    def test_both_miss_records_ffprobe_error_with_header_note(self):
        result = probe_direct(
            "https://web.uploadflix.net:8080/d/deadtoken",
            range_fetch=fake_range(
                {"https://web.uploadflix.net:8080/d/deadtoken":
                 b"\x1a\x45\xdf\xa3" + _vint(0, 1)}),
            ffprobe=fake_ffprobe(
                {"https://web.uploadflix.net:8080/d/deadtoken": None}))
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "ffprobe-no-duration")

    def test_range_ignorer_is_recorded_never_downloaded(self):
        calls = []
        result = probe_direct(
            "https://cold-eu-par-1.gofile.io/download/web/abc",
            range_fetch=fake_range(
                {"https://cold-eu-par-1.gofile.io/download/web/abc":
                 ("cap",)}),
            ffprobe=fake_ffprobe({}, calls))
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "range-not-supported: cap-exceeded")
        self.assertEqual(calls, [])

    def test_http_429_carries_retry_after(self):
        result = probe_direct(
            "https://cdn.example/d.mp4",
            range_fetch=fake_range(
                {"https://cdn.example/d.mp4": ("http429", 120)}),
            ffprobe=fake_ffprobe({}))
        self.assertFalse(result["ok"])
        self.assertEqual(result["error"], "http-429:retry-after=120")

    def test_probe_url_dispatches_on_kind(self):
        calls = []
        result = probe_url(
            "https://cdn.example/a",
            kind="playlist",
            fetch=fake_fetch({"https://cdn.example/a": HLS_MEDIA}),
            range_fetch=fake_range({}),
            ffprobe=fake_ffprobe({}, calls))
        self.assertTrue(result["ok"])
        self.assertEqual(result["method"], "hls-extinf")
        self.assertEqual(calls, [])

    def test_probe_url_dispatches_on_m3u8_suffix(self):
        result = probe_url(
            "https://cdn.example/a.m3u8",
            fetch=fake_fetch({"https://cdn.example/a.m3u8": HLS_MEDIA}),
            range_fetch=fake_range({}),
            ffprobe=fake_ffprobe({}))
        self.assertTrue(result["ok"])
        self.assertEqual(result["method"], "hls-extinf")


# ---------------------------------------------------------------- uploadflix

class UploadflixTests(unittest.TestCase):
    """The prototype's one genuine gap: 9 uploadflix links return no
    duration while 12 succeed — and the SAME title is OK on one mirror
    and FAIL on another. That is a per-link serving failure, not a host
    quirk: the host is NOT excluded, each link is recorded on its own."""

    def test_same_host_one_ok_one_fail_both_recorded(self):
        ok_url = "https://web.uploadflix.net:8080/d/goodtoken/file.mkv"
        bad_url = "https://cloud.uploadflix.net:8080/d/badtoken/file.mkv"
        probe = lambda url, kind=None, **kw: probe_url(  # noqa: E731
            url, kind=kind,
            range_fetch=fake_range({
                ok_url: _mkv_bytes(3873.706),
                bad_url: b"\x1a\x45\xdf\xa3" + _vint(0, 1)}),
            ffprobe=fake_ffprobe({bad_url: None}))
        good = probe(ok_url, kind="file")
        bad = probe(bad_url, kind="file")
        self.assertTrue(good["ok"])
        self.assertAlmostEqual(good["duration"], 3873.706, places=3)
        self.assertFalse(bad["ok"])
        self.assertEqual(bad["error"], "ffprobe-no-duration")
        # The failing link must not park the host for the good one: a
        # second probe of a good link on the same host still succeeds.
        again = probe(ok_url, kind="file")
        self.assertTrue(again["ok"])


# ---------------------------------------------------------------- selection

class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.conn, self.path = make_db()

    def tearDown(self):
        self.conn.close()
        Path(self.path).unlink(missing_ok=True)

    def test_fresh_rows_only_hls_first(self):
        rows = select_batch(self.conn, 100)
        ids = [r["link_id"] for r in rows]
        # 100 (playlist) first, then files newest-resolved first
        # (103 @ now-50, 101 @ now-100, 102 @ now-200); stale 104,
        # orphan 105 and viewer 106 are all excluded.
        self.assertEqual(ids, [100, 103, 101, 102])

    def test_newest_resolved_first_within_kind(self):
        # Newest banked link first: batch 1 probed oldest-first and got
        # 5% yield (75/100 HTTP 410); the prototype drove newest-first
        # and got 60%.
        rows = select_batch(self.conn, 100)
        self.assertEqual([r["link_id"] for r in rows],
                         [100, 103, 101, 102])

    def test_limit_is_honoured(self):
        rows = select_batch(self.conn, 2)
        self.assertEqual([r["link_id"] for r in rows], [100, 103])

    def test_already_probed_rows_are_excluded(self):
        backfill_durations.ensure_staging_table(self.conn)
        stage_row(self.conn, 100, "https://cdn.example/a.m3u8",
                  "playlist", 6102.0)
        self.conn.commit()
        rows = select_batch(self.conn, 100)
        self.assertEqual([r["link_id"] for r in rows], [103, 101, 102])

    def test_batch_counts(self):
        fresh, stale, probed = backfill_durations.batch_counts(self.conn)
        self.assertEqual((fresh, stale, probed), (4, 1, 0))


# ---------------------------------------------------------------- job run

def _run_probe(mapping):
    """A probe callable for run(): HLS from text, direct from bytes."""
    def probe(url, kind=None):
        if kind == "playlist":
            return probe_hls(url, fetch=fake_fetch(mapping))
        return probe_direct(
            url,
            range_fetch=fake_range(mapping),
            ffprobe=fake_ffprobe({}))
    return probe


class RunTests(unittest.TestCase):
    def setUp(self):
        self.conn, self.path = make_db()
        self.tmp = tempfile.TemporaryDirectory()
        self.journal = Path(self.tmp.name) / "j.jsonl"

    def tearDown(self):
        self.conn.close()
        Path(self.path).unlink(missing_ok=True)
        self.tmp.cleanup()

    def _mapping(self):
        return {
            "https://cdn.example/a.m3u8": HLS_MOVIE,
            "https://cdn.example/b.mkv": _mkv_bytes(6102.034),
            "https://cdn.example/c.mp4": _mp4_bytes(4908.758),
            "https://cdn.example/d.mkv": _mkv_bytes(2759.061),
        }

    def test_report_only_journals_but_stages_nothing(self):
        stats, code = run(self.path, apply=False, limit=100,
                          journal_path=self.journal,
                          probe=_run_probe(self._mapping()),
                          truth=fake_ffprobe({}), guard_reasons=[])
        self.assertEqual(code, 0)
        self.assertEqual(stats["ok"], 4)
        recs = [json.loads(line) for line in
                self.journal.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(len(recs), 4)
        self.assertTrue(all(r["duration_s"] for r in recs))
        conn = sqlite3.connect(self.path)
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        conn.close()
        self.assertNotIn("duration_probe_results", tables)

    def test_apply_stages_and_rerun_selects_zero(self):
        stats, code = run(self.path, apply=True, limit=100,
                          journal_path=self.journal,
                          probe=_run_probe(self._mapping()),
                          truth=fake_ffprobe({}), guard_reasons=[])
        self.assertEqual(code, 0)
        self.assertEqual(stats["ok"], 4)
        conn = sqlite3.connect(self.path)
        n = conn.execute("SELECT COUNT(*) FROM duration_probe_results"
                         ).fetchone()[0]
        conn.close()
        self.assertEqual(n, 4)
        stats2, code2 = run(self.path, apply=True, limit=100,
                            journal_path=self.journal,
                            probe=_run_probe(self._mapping()),
                            truth=fake_ffprobe({}), guard_reasons=[])
        self.assertEqual(code2, 0)
        self.assertEqual(stats2["selected"], 0)

    def test_live_playlist_stages_no_duration(self):
        mapping = {"https://cdn.example/a.m3u8": HLS_LIVE}
        stats, code = run(self.path, apply=True, limit=1,
                          journal_path=self.journal,
                          probe=_run_probe(mapping),
                          truth=fake_ffprobe({}), guard_reasons=[])
        self.assertEqual(code, 0)
        self.assertEqual(stats["live"], 1)
        conn = sqlite3.connect(self.path)
        row = conn.execute(
            "SELECT duration_s, live, error FROM duration_probe_results"
            " WHERE link_id = 100").fetchone()
        conn.close()
        self.assertEqual(row, (None, 1, "live-playlist"))

    def test_out_of_range_goes_to_review(self):
        mapping = {"https://cdn.example/a.m3u8":
                   HLS_MEDIA.replace("9.976", "5.0").replace(
                       "10.024", "5.0").replace("8.5", "5.0")}
        stats, code = run(self.path, apply=True, limit=1,
                          journal_path=self.journal,
                          probe=_run_probe(mapping),
                          truth=fake_ffprobe({}), guard_reasons=[])
        self.assertEqual(code, 0)
        self.assertEqual(stats["out_of_range"], 1)
        conn = sqlite3.connect(self.path)
        row = conn.execute(
            "SELECT duration_s, error FROM duration_probe_results"
            " WHERE link_id = 100").fetchone()
        conn.close()
        self.assertEqual(row, (None, "duration-out-of-range"))

    def test_work_disagreement_stages_no_duration(self):
        # Two prints of Movie One: the first stages 6102 s, the second
        # claims 7200 s — the second is a different cut (or a wrong
        # link), so it is recorded as a disagreement, not averaged.
        conn = sqlite3.connect(self.path)
        conn.execute("INSERT INTO download_sources (id, release_id)"
                     " VALUES (107, 10)")
        conn.execute("INSERT INTO resolved_cache (link_id, url, kind,"
                     " expires_at) VALUES (107, 'https://cdn.example/h.mkv',"
                     " 'file', 9999999999)")
        conn.commit()
        conn.close()
        mapping = self._mapping()
        mapping["https://cdn.example/h.mkv"] = _mkv_bytes(7200.0)
        stats, code = run(self.path, apply=True, limit=100,
                          journal_path=self.journal,
                          probe=_run_probe(mapping),
                          truth=fake_ffprobe({}), guard_reasons=[])
        self.assertEqual(code, 0)
        self.assertEqual(stats["disagreements"], 1)
        conn = sqlite3.connect(self.path)
        row = conn.execute(
            "SELECT duration_s, error FROM duration_probe_results"
            " WHERE link_id = 107").fetchone()
        conn.close()
        self.assertEqual(row, (None, "duration-disagreement"))

    def test_truth_sampling_agrees(self):
        # Truth-checks every 2nd success (truth_sample=0.5): under
        # newest-first ordering the successes are 100, 103, 101, 102,
        # so the 2nd and 4th (d.mkv, c.mp4) are the truth-checked ones.
        stats, code = run(self.path, apply=True, limit=100,
                          journal_path=self.journal,
                          probe=_run_probe(self._mapping()),
                          truth=fake_ffprobe({
                              "https://cdn.example/d.mkv": 2759.061,
                              "https://cdn.example/c.mp4": 4908.758}),
                          truth_sample=0.5, guard_reasons=[])
        self.assertEqual(code, 0)
        self.assertEqual(stats["truth_checked"], 2)
        self.assertEqual(stats["truth_agrees"], 2)

    def test_truth_alert_below_98_percent(self):
        # 10 successes, every one truth-checked, every one disagreeing:
        # agreement 0% with 10 checks trips the alert (exit 2), and the
        # staged rows are still kept for investigation.
        def probe(url, kind=None):
            return {"ok": True, "duration": 6102.0, "method": "hls-extinf"}
        truth = lambda url, kind=None: {  # noqa: E731
            "ok": True, "duration": 100.0}
        # Ten fresh links: reuse the fixture plus six more.
        conn = sqlite3.connect(self.path)
        for i in range(110, 116):
            conn.execute("INSERT INTO download_sources (id, release_id)"
                         " VALUES (?, 10)", (i,))
            conn.execute("INSERT INTO resolved_cache (link_id, url, kind,"
                         " expires_at) VALUES (?, ?, 'file', 9999999999)",
                         (i, "https://cdn.example/x%d.mkv" % i))
        conn.commit()
        conn.close()
        stats, code = run(self.path, apply=True, limit=100,
                          journal_path=self.journal,
                          probe=probe, truth=truth,
                          truth_sample=1.0, guard_reasons=[])
        self.assertEqual(code, 2)
        self.assertTrue(stats["truth_alert"])
        self.assertEqual(stats["truth_checked"], 10)
        self.assertEqual(stats["truth_agrees"], 0)
        conn = sqlite3.connect(self.path)
        n = conn.execute("SELECT COUNT(*) FROM duration_probe_results"
                         ).fetchone()[0]
        conn.close()
        self.assertEqual(n, 10)

    def test_429_parks_the_host(self):
        mapping = self._mapping()
        mapping["https://cdn.example/d.mkv"] = ("http429", 120)
        stats, code = run(self.path, apply=True, limit=100,
                          journal_path=self.journal,
                          probe=_run_probe(mapping),
                          truth=fake_ffprobe({}), guard_reasons=[])
        self.assertEqual(code, 0)
        # d.mkv (429) is the first file probed after the playlist under
        # newest-first ordering, so it parks cdn.example and b.mkv and
        # c.mp4 on the same host are skipped without a fetch; only the
        # playlist (probed before the park) succeeds.
        self.assertEqual(stats["ok"], 1)
        self.assertEqual(stats["failed"], 1)
        self.assertEqual(stats["host_parked"], 2)

    def test_guard_refusal_writes_nothing(self):
        stats, code = run(self.path, apply=True, limit=100,
                          journal_path=self.journal,
                          probe=_run_probe(self._mapping()),
                          truth=fake_ffprobe({}),
                          guard_reasons=["load 9.50 > 4.0"])
        self.assertEqual(code, 2)
        self.assertIn("load 9.50 > 4.0", stats["refused"])
        self.assertFalse(self.journal.exists())


class ProbeOneTests(unittest.TestCase):
    def test_host_parked_record_without_fetch(self):
        throttle = _HostThrottle(sleep=lambda s: None)
        throttle.park("cdn.example", 300)
        record = probe_one(
            {"link_id": 1, "url": "https://cdn.example/a.m3u8",
             "kind": "playlist"},
            probe=lambda url, kind=None: (_ for _ in ()).throw(
                AssertionError("must not fetch a parked host")),
            throttle=throttle)
        self.assertEqual(record["error"], "host-parked")
        self.assertIsNone(record["duration_s"])

    def test_retry_after_is_honoured_by_the_throttle(self):
        now = [1000.0]
        throttle = _HostThrottle(sleep=lambda s: None,
                                 now=lambda: now[0])
        record = probe_one(
            {"link_id": 1, "url": "https://cdn.example/a.m3u8",
             "kind": "playlist"},
            probe=lambda url, kind=None: {
                "ok": False, "error": "http-429:retry-after=120"},
            throttle=throttle)
        self.assertEqual(record["error"], "http-429:retry-after=120")
        # 60 s later the host is still parked; 121 s later it is not.
        now[0] = 1060.0
        self.assertTrue(throttle.wait("cdn.example"))
        now[0] = 1121.0
        self.assertFalse(throttle.wait("cdn.example"))


# ---------------------------------------------------------------- guards

class GuardTests(unittest.TestCase):
    def test_clear_machine(self):
        reasons = check_guards(load=0.5, check_service=lambda n: False,
                               cmdlines=[])
        self.assertEqual(reasons, [])

    def test_high_load_refuses(self):
        reasons = check_guards(load=9.5, check_service=lambda n: False,
                               cmdlines=[])
        self.assertEqual(reasons, ["load 9.50 > 4.0"])

    def test_link_sweep_running_refuses(self):
        reasons = check_guards(
            load=0.5,
            check_service=lambda n: n == "radd-link-sweep.service",
            cmdlines=[])
        self.assertEqual(reasons, ["radd-link-sweep.service is running"])

    def test_prefetch_and_backup_services_refuse(self):
        for service in ("radd-prefetch.service", "radd-backup-hf.service"):
            reasons = check_guards(
                load=0.5,
                check_service=lambda n, s=service: n == s,
                cmdlines=[])
            self.assertEqual(reasons, ["%s is running" % service])

    def test_gate_backup_copy_refuses(self):
        cmdlines = [
            "python3 -S /opt/radd-stream/Fast_Fetch/link_state_runner.py",
            "sqlite3 /home/ubuntu/radd-stream-data/merged_catalog.db"
            " \".backup /home/ubuntu/gate-backup-20261120-0205.db\"",
        ]
        reasons = check_guards(load=0.5, check_service=lambda n: False,
                               cmdlines=cmdlines)
        self.assertEqual(reasons, ["gate backup copy is running"])

    def test_unrelated_sqlite_does_not_refuse(self):
        cmdlines = ["sqlite3 /tmp/other.db \"SELECT 1\""]
        reasons = check_guards(load=0.5, check_service=lambda n: False,
                               cmdlines=cmdlines)
        self.assertEqual(reasons, [])


class ServiceActiveTests(unittest.TestCase):
    """service_active() reads the state word, not the exit code.

    Regression: ``systemctl is-active`` exits 3 for ``activating`` —
    the same code as ``inactive`` — so the old ``returncode == 0``
    check let a sweep that was still starting up slip past the guard
    (2026-11-21 gate: the duration backfill dry-ran while the
    fast_probe sweep was running).
    """

    def _fake_run(self, stdout, returncode=3):
        result = mock.Mock()
        result.stdout = stdout
        result.returncode = returncode
        return mock.patch.object(
            backfill_durations.subprocess, "run",
            return_value=result)

    def test_active_is_active(self):
        with self._fake_run("active\n", returncode=0):
            self.assertTrue(service_active("radd-link-sweep.service"))

    def test_activating_is_active(self):
        with self._fake_run("activating\n", returncode=3):
            self.assertTrue(service_active("radd-link-sweep.service"))

    def test_activating_case_and_whitespace(self):
        with self._fake_run("  Activating \n", returncode=3):
            self.assertTrue(service_active("radd-link-sweep.service"))

    def test_reloading_and_deactivating_are_active(self):
        for state in ("reloading", "deactivating"):
            with self._fake_run(state + "\n", returncode=3):
                self.assertTrue(service_active("radd-link-sweep.service"))

    def test_inactive_failed_unknown_are_inactive(self):
        for state in ("inactive", "failed", "unknown"):
            with self._fake_run(state + "\n", returncode=3):
                self.assertFalse(
                    service_active("radd-link-sweep.service"))

    def test_empty_stdout_falls_back_to_exit_code(self):
        with self._fake_run("", returncode=0):
            self.assertTrue(service_active("radd-link-sweep.service"))
        with self._fake_run("", returncode=3):
            self.assertFalse(service_active("radd-link-sweep.service"))

    def test_missing_systemctl_is_inactive(self):
        with mock.patch.object(
                backfill_durations.subprocess, "run",
                side_effect=OSError("no systemctl")):
            self.assertFalse(service_active("radd-link-sweep.service"))


# ---------------------------------------------------------------- apply SQL

def _verify_rows(conn):
    """Run the VERIFY section of duration_apply.sql; return the rows
    whose count is non-zero (or any row of a row-listing query).

    The script drops its temp tables at the end, so the step-1 temp
    tables are rebuilt first (from the same SQL text) — the verify
    queries read them."""
    text = APPLY_SQL.read_text(encoding="utf-8")
    step1 = text.split("CREATE TEMP TABLE _dur_works AS", 1)[1]
    step1 = step1.split("-- INFO (not a failure)", 1)[0]
    conn.executescript("DROP TABLE IF EXISTS _dur_works;"
                       "DROP TABLE IF EXISTS _dur_posts;"
                       "CREATE TEMP TABLE _dur_works AS" + step1)
    verify = text.split("-- Step 4: VERIFY.")[1].split("-- END VERIFY")[0]
    bad = []
    for stmt in verify.split(";"):
        stmt = stmt.strip()
        if not stmt.startswith("SELECT"):
            continue
        cursor = conn.execute(stmt)
        names = [d[0] for d in cursor.description]
        rows = cursor.fetchall()
        for row in rows:
            if "n" in names:
                if row[names.index("n")] != 0:
                    bad.append((stmt.splitlines()[0], row))
            else:
                bad.append((stmt.splitlines()[0], row))
    return bad


class ApplySqlTests(unittest.TestCase):
    def setUp(self):
        self.conn, self.path = make_db()
        backfill_durations.ensure_staging_table(self.conn)
        self.conn.commit()

    def tearDown(self):
        self.conn.close()
        Path(self.path).unlink(missing_ok=True)

    def _apply(self):
        sql = APPLY_SQL.read_text(encoding="utf-8")
        self.conn.executescript(sql)

    def test_fills_movie_work_and_post_only_where_null(self):
        stage_row(self.conn, 100, "https://cdn.example/a.m3u8",
                  "playlist", 6102.034)
        stage_row(self.conn, 101, "https://cdn.example/b.mkv",
                  "file", 6102.5, method="mkv-ebml")
        stage_row(self.conn, 102, "https://cdn.example/c.mp4",
                  "file", 5400.0, method="mp4-mvhd")
        self.conn.commit()
        self._apply()
        work = self.conn.execute(
            "SELECT runtime_minutes FROM cat_works WHERE id = 1000"
            ).fetchone()[0]
        self.assertEqual(work, 102)  # 6102 s -> 102 min
        post = self.conn.execute(
            "SELECT duration FROM posts WHERE id = 1").fetchone()[0]
        self.assertEqual(post, "1:41:42")
        # Pre-filled values are untouched.
        self.assertEqual(self.conn.execute(
            "SELECT runtime_minutes FROM cat_works WHERE id = 1001"
            ).fetchone()[0], 90)
        self.assertEqual(self.conn.execute(
            "SELECT duration FROM posts WHERE id = 2").fetchone()[0],
            "1:30:00")
        # The journal records exactly the two fills.
        journal = self.conn.execute(
            "SELECT kind, ref_id, value FROM duration_apply_journal"
            " ORDER BY kind, ref_id").fetchall()
        self.assertEqual(journal, [("post", 1, "1:41:42"),
                                   ("work", 1000, "102")])

    def test_short_duration_uses_m_ss(self):
        stage_row(self.conn, 100, "https://cdn.example/a.m3u8",
                  "playlist", 2832.0)
        self.conn.commit()
        self._apply()
        post = self.conn.execute(
            "SELECT duration FROM posts WHERE id = 1").fetchone()[0]
        self.assertEqual(post, "47:12")

    def test_series_work_and_post_are_skipped(self):
        stage_row(self.conn, 103, "https://cdn.example/d.mkv",
                  "file", 2759.061, method="mkv-ebml")
        self.conn.commit()
        self._apply()
        self.assertIsNone(self.conn.execute(
            "SELECT runtime_minutes FROM cat_works WHERE id = 1002"
            ).fetchone()[0])
        self.assertIsNone(self.conn.execute(
            "SELECT duration FROM posts WHERE id = 3").fetchone()[0])

    def test_live_staged_duration_aborts_the_apply(self):
        stage_row(self.conn, 100, "https://cdn.example/a.m3u8",
                  "playlist", 6102.034, live=1)
        self.conn.commit()
        # The step-0 guard CHECK fails (dedup_fold.sql pattern).
        with self.assertRaises(sqlite3.IntegrityError):
            self._apply()
        self.conn.rollback()
        self.assertIsNone(self.conn.execute(
            "SELECT runtime_minutes FROM cat_works WHERE id = 1000"
            ).fetchone()[0])

    def test_out_of_range_staged_duration_aborts_the_apply(self):
        stage_row(self.conn, 100, "https://cdn.example/a.m3u8",
                  "playlist", 30.0)
        self.conn.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            self._apply()
        self.conn.rollback()

    def test_disagreeing_work_is_skipped_not_averaged(self):
        stage_row(self.conn, 100, "https://cdn.example/a.m3u8",
                  "playlist", 6102.034)
        stage_row(self.conn, 101, "https://cdn.example/b.mkv",
                  "file", 7200.0, method="mkv-ebml")
        self.conn.commit()
        self._apply()
        self.assertIsNone(self.conn.execute(
            "SELECT runtime_minutes FROM cat_works WHERE id = 1000"
            ).fetchone()[0])

    def test_rerun_fills_zero_and_verify_is_zero(self):
        stage_row(self.conn, 100, "https://cdn.example/a.m3u8",
                  "playlist", 6102.034)
        self.conn.commit()
        self._apply()
        first = self.conn.execute(
            "SELECT COUNT(*) FROM duration_apply_journal").fetchone()[0]
        self.assertEqual(first, 2)
        self._apply()  # re-run: safe no-op
        second = self.conn.execute(
            "SELECT COUNT(*) FROM duration_apply_journal").fetchone()[0]
        self.assertEqual(second, 2)
        self.assertEqual(_verify_rows(self.conn), [])

    def test_verify_catches_a_hand_edited_runtime(self):
        stage_row(self.conn, 100, "https://cdn.example/a.m3u8",
                  "playlist", 6102.034)
        self.conn.commit()
        self._apply()
        self.conn.execute("UPDATE cat_works SET runtime_minutes = 999"
                          " WHERE id = 1000")
        self.conn.commit()
        bad = _verify_rows(self.conn)
        self.assertTrue(any("journal-works-mismatch" in str(b[0])
                            for b in bad))


# ---------------------------------------------------------------- manifest

class ManifestTests(unittest.TestCase):
    def test_module_is_registered_for_ci(self):
        import test_ci_manifest
        self.assertIn("test_duration_backfill.py",
                      test_ci_manifest.MANDATORY_RUN)


if __name__ == "__main__":
    unittest.main(verbosity=2)
