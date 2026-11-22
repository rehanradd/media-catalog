"""Offline tests for H4 R6 direct-file preference.

The rule: measured evidence corrects the ingest label, in both directions.
A link labelled ``url`` whose endpoint serves video bytes IS a direct file and
ranks as one; a link labelled ``direct`` whose endpoint serves a page (or a
playlist) is a mislabelled bridge and ranks as a link. A bridge page is
``text/html`` by definition, so a ``url`` link with a page mime is NOT
demoted — only a ``direct`` label is contradicted by one. Unmeasured links
keep their label, and a failed direct still falls back to the next verified
candidate — the preference never removes the fallback.
"""

import os
import sqlite3
import sys
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import redact
import resolved_cache
import tiers
import viewer

SECRET = "test-secret-for-direct-file"


def link(**fields) -> dict:
    base = {"id": 1, "release_id": 7, "playback_type": "direct",
            "status": "active", "is_resumable": 0, "is_temporary": 0,
            "http_status": None, "file_size_bytes": None, "mime_type": None,
            "last_checked_at": None, "created_at": None}
    base.update(fields)
    return base


class ScoreCorrectionTests(unittest.TestCase):
    """`tiers.score()` — the ladder's measured-mime kind correction."""

    def test_a_url_link_serving_video_bytes_outranks_a_direct_link_serving_a_page(self):
        page = link(id=1, playback_type="url", mime_type="video/mp4")
        direct = link(id=2, playback_type="direct", mime_type="text/html")
        self.assertGreater(tiers.score(page, "1080p"),
                           tiers.score(direct, "1080p"))

    def test_a_direct_link_serving_video_agrees_with_its_label(self):
        direct = link(id=3, playback_type="direct", mime_type="video/mp4")
        plain = link(id=4, playback_type="direct")
        self.assertEqual(tiers.score(direct, "1080p"),
                         tiers.score(plain, "1080p"))

    def test_a_bridge_page_keeps_its_url_weight(self):
        # A bridge page is text/html by definition and still plays via
        # unwrap — the page mime demotes only a "direct" label, never a
        # "url" one.
        page = link(id=5, playback_type="url", mime_type="text/html")
        plain = link(id=6, playback_type="url")
        self.assertEqual(tiers.score(page, "1080p"),
                         tiers.score(plain, "1080p"))

    def test_a_direct_playlist_ranks_above_a_file_not_as_a_link(self):
        # HLS-first (user order 2026-11-22): a "direct" label on a playlist
        # is an adaptive manifest, not a mislabelled file — it promotes
        # above a direct file instead of demoting to a link. This replaces
        # the old test_a_direct_playlist_ranks_as_a_link_not_a_file, whose
        # premise (playlist == url weight) the order reverses.
        playlist = link(id=7, playback_type="direct",
                        mime_type="application/vnd.apple.mpegurl")
        plain_url = link(id=8, playback_type="url")
        direct = link(id=9, playback_type="direct")
        self.assertGreater(tiers.score(playlist, "1080p"),
                           tiers.score(plain_url, "1080p"))
        self.assertGreater(tiers.score(playlist, "1080p"),
                           tiers.score(direct, "1080p"))

    def test_unknown_and_octet_stream_mimes_change_nothing(self):
        for mime in (None, "", "application/octet-stream", "image/png"):
            measured = link(id=9, playback_type="url", mime_type=mime)
            plain = link(id=10, playback_type="url")
            self.assertEqual(tiers.score(measured, "1080p"),
                             tiers.score(plain, "1080p"), mime)

    def test_the_v2_content_type_key_corrects_the_same_way(self):
        # The v2 projection carries the measured mime as `content_type`
        # (catalog_v2._measured_evidence), not `mime_type`.
        projected = link(id=11, playback_type="url", mime_type=None)
        projected["content_type"] = "video/webm"
        plain = link(id=12, playback_type="url")
        self.assertGreater(tiers.score(projected, "1080p"),
                           tiers.score(plain, "1080p"))

    def test_a_dead_link_still_scores_zero_with_a_video_mime(self):
        dead = link(id=13, playback_type="url", mime_type="video/mp4",
                    status="dead", is_resumable=1)
        self.assertEqual(tiers.score(dead, "1080p"), 0)

    def test_a_download_only_link_is_never_promoted_by_a_video_mime(self):
        download = link(id=14, playback_type="download",
                        mime_type="video/mp4")
        plain = link(id=15, playback_type="download")
        self.assertEqual(tiers.score(download, "1080p"),
                         tiers.score(plain, "1080p"))

    def test_for_release_picks_the_measured_file_as_winner(self):
        page = link(id=16, playback_type="direct", mime_type="text/html")
        file_link = link(id=17, playback_type="url", mime_type="video/mp4")
        ladder = tiers.for_release({"quality": "1080p"},
                                   [page, file_link])
        self.assertEqual(ladder["tiers"][0]["link"]["id"], 17)


class SqlOrderTests(unittest.TestCase):
    """`tiers.candidate_order_sql()` — the failover walk's measured order."""

    def setUp(self):
        self.conn = sqlite3.connect(":memory:")
        self.conn.execute(
            "CREATE TABLE download_sources (id INTEGER, release_id INTEGER,"
            " playback_type TEXT, status TEXT, service TEXT,"
            " is_temporary INTEGER, is_resumable INTEGER,"
            " last_checked_at TEXT, created_at TEXT, mime_type TEXT)")
        self.conn.executemany(
            "INSERT INTO download_sources VALUES (?,?,?,?,?,?,?,?,?,?)",
            [(1, 1, "direct", "active", "zz", 0, 0,
              "2026-01-01 00:00:00", "2026-01-01 00:00:00", "text/html"),
             (2, 1, "url", "active", "zz", 0, 0,
              "2026-01-01 00:00:00", "2026-01-01 00:00:00", "video/mp4"),
             (3, 1, "direct", "active", "zz", 0, 0,
              "2026-01-01 00:00:00", "2026-01-01 00:00:00", "video/mp4"),
             (4, 1, "url", "active", "zz", 0, 0,
              "2026-01-01 00:00:00", "2026-01-01 00:00:00", "text/html"),
             (5, 1, "direct", "active", "zz", 0, 0,
              "2026-01-01 00:00:00", "2026-01-01 00:00:00", None)])

    def _order(self, sql):
        return [row[0] for row in self.conn.execute(
            "SELECT id FROM download_sources ORDER BY " + sql)]

    def test_the_measured_file_is_tried_first(self):
        # The url-labelled video file leads; the mislabelled direct page
        # trails it; the bridge page stays last with its own kind.
        self.assertEqual(self._order(tiers.candidate_order_sql()),
                         [2, 3, 5, 1, 4])

    def test_the_aliased_form_qualifies_mime_type(self):
        aliased = tiers.candidate_order_sql("s")
        self.assertIn("s.mime_type", aliased)
        self.assertNotIn(" mime_type", aliased)

    def test_the_band_form_keeps_the_band_first(self):
        sql = tiers.candidate_order_sql(band="720p")
        self.assertLess(sql.index("5centscdn"), sql.index("fsl_r2"))
        self.assertLess(sql.index("fsl_r2"), sql.index("hubcloud_10g"))


class VerifyPassTests(unittest.TestCase):
    """`viewer._verify_option_tiers` — the fresh banked-kind nudge."""

    def setUp(self):
        os.environ["RADD_STREAM_SECRET"] = SECRET
        self.addCleanup(os.environ.pop, "RADD_STREAM_SECRET", None)
        self.conn = sqlite3.connect(":memory:")
        self.conn.execute(
            "CREATE TABLE download_sources (id INTEGER, source_url TEXT)")
        for source_id in (11, 12, 13):
            self.conn.execute(
                "INSERT INTO download_sources VALUES (?, ?)",
                (source_id, "https://bridge.example.com/watch/%d" % source_id))
        self.now = time.time()

    def _bank(self, source_id, kind, url, *, resolved_at=None,
              expires_at=None):
        data = {"url": url, "kind": kind,
                "mime_type": ("video/mp4" if kind == "file"
                              else "application/vnd.apple.mpegurl"),
                "service": "pixeldrain", "is_temporary": False}
        if resolved_at is not None:
            data["resolved_at"] = resolved_at
        if expires_at is not None:
            data["expires_at"] = expires_at
        resolved_cache.record_resolved_cache(
            self.conn, source_id, data, source="jit", now=self.now)

    def _tier(self, first_id, *backup_ids):
        def _link(source_id):
            return {"stream_id": redact.mint_token(source_id, SECRET)}
        return {"band": "1080p", "link": _link(first_id),
                "backups": [_link(source_id) for source_id in backup_ids]}

    def _verify_all(self, link, conn):
        return {"verified": True, "reason": "stub",
                "verified_at": time.time()}

    def _run(self, tier_rows, verifier=None):
        return viewer._verify_option_tiers(
            tier_rows, verifier=verifier or self._verify_all,
            conn=self.conn, host_of=lambda link: "same.host", inline=True)

    def _winner_id(self, kept):
        return redact.parse_token(kept[0]["link"]["stream_id"], SECRET,
                                  signed_only=True)

    def test_a_fresh_banked_file_wins_over_a_fresh_banked_playlist(self):
        self._bank(11, "playlist",
                   "https://cdn.example.com/film.m3u8")
        self._bank(12, "file",
                   "https://cdn.example.com/film-1080.mp4")
        kept, poisoned = self._run([self._tier(11, 12)])
        self.assertFalse(poisoned)
        # The playlist leads the ladder; the file's fresh banked kind
        # promotes it to the win.
        self.assertEqual(self._winner_id(kept), 12)

    def test_a_stale_banked_file_earns_no_bonus(self):
        self._bank(11, "playlist",
                   "https://cdn.example.com/film.m3u8")
        self._bank(12, "file", "https://cdn.example.com/film-1080.mp4",
                   resolved_at=self.now - 2 * 86400)
        kept, _ = self._run([self._tier(11, 12)])
        self.assertEqual(self._winner_id(kept), 11)

    def test_an_expired_presigned_row_earns_no_bonus(self):
        # The presigned window elapsed: the row must never serve, and it
        # must not win a band on a dead token either.
        self._bank(11, "playlist",
                   "https://cdn.example.com/film.m3u8")
        self._bank(12, "file", "https://cdn.example.com/film-1080.mp4",
                   resolved_at=self.now - 7200,
                   expires_at=self.now - 3600)
        kept, _ = self._run([self._tier(11, 12)])
        self.assertEqual(self._winner_id(kept), 11)

    def test_a_failed_direct_falls_back_to_the_verified_playlist(self):
        self._bank(11, "file", "https://cdn.example.com/film-1080.mp4")
        self._bank(12, "playlist",
                   "https://cdn.example.com/film.m3u8")

        def _verify(link, conn):
            source_id = redact.parse_token(
                link["stream_id"], SECRET, signed_only=True)
            if source_id == 11:
                return {"verified": False, "reason": "dead"}
            return {"verified": True, "reason": "stub",
                    "verified_at": time.time()}

        kept, _ = self._run([self._tier(11, 12)], verifier=_verify)
        self.assertEqual(self._winner_id(kept), 12)

    def test_no_connection_means_no_bonus_and_ladder_order_stands(self):
        kept, _ = viewer._verify_option_tiers(
            [self._tier(11, 12)], verifier=self._verify_all, conn=None,
            host_of=lambda link: "same.host", inline=True)
        self.assertEqual(self._winner_id(kept), 11)


if __name__ == "__main__":
    unittest.main()
