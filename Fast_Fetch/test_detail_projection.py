"""Column projection for the detail endpoints (search-speed slice).

Pins two things:
1. The public detail payload is identical to the one built from full
   ``SELECT *`` rows — the projection drops no key the client sees.
   (It caught one miss while being written: ``download_sources.expires_at``
   is a migration-added column in the ``link`` view, and the first cut of
   the projection lost the ``"expires_at": null`` key.)
2. The detail path's SQL names only the projected columns — no
   ``SELECT *`` reaches the detail queries. The operator ``full=True``
   path still reads ``*`` and still returns the raw ledger row.
"""
import json
import re
import sqlite3
import tempfile
import unittest
import urllib.parse
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

import fast_fetch
import redact
import viewer
import works

BASE = "http://our.box"
LONG = "x" * 400
# Distinctive values in columns the public path must never read: if the
# projection regresses, these either leak into the public JSON or break
# the payload-equality test below.
MARKERS = (
    "RAW-META-MARKER", "DURATION-MARKER", "DIRECTOR-MARKER",
    "RELEASE-DATE-MARKER", "URL-MARKER", "SLUG-MARKER",
    "INTERMEDIATE-MARKER", "ORIGIN-MARKER", "FINAL-MARKER",
    "SOURCE-MARKER", "RESOURCE-MARKER", "SOURCE-NAME-MARKER",
)
POST_DETAIL_COLS = {
    "id", "title", "clean_title", "year", "content_type", "seasons",
    "poster_url", "backdrop_urls", "imdb_rating", "genres", "synopsis",
    "cast_names", "is_18_plus", "is_animated", "imdb_id", "tmdb_id",
    "industry", "country", "language",
}
RELEASE_DETAIL_COLS = {
    "id", "post_id", "release_title", "season", "episode", "quality",
    "codec", "size", "languages", "created_at",
}
SOURCE_DETAIL_COLS = {
    "id", "release_id", "service", "playback_type", "is_temporary",
    "status", "is_resumable", "mime_type", "http_status",
    "file_size_bytes", "expires_at", "last_checked_at",
}


def _normalize(payload):
    """Stream handles carry an expiry second, so two mintings differ;
    decode each handle to the row id it names instead."""
    secret = viewer.signing_secret()

    def walk(value, key=None):
        if isinstance(value, dict):
            return {k: walk(v, k) for k, v in value.items()}
        if isinstance(value, list):
            if key == "backup_handles":
                return [redact.parse_token(h, secret) for h in value]
            return [walk(v) for v in value]
        if key == "stream_id" and isinstance(value, str):
            return redact.parse_token(value, secret)
        if key == "play_url" and isinstance(value, str):
            query = urllib.parse.parse_qs(
                urllib.parse.urlparse(value).query)
            return redact.parse_token(query["id"][0], secret)
        return value

    return walk(payload)


def _reference_post_detail(conn, post_id):
    """The public post detail, built by hand from full ``SELECT *`` rows
    with the same pure helpers the endpoint uses. The SQL is what is
    under test here, not the helpers."""
    post = conn.execute(
        "SELECT * FROM posts WHERE id = ?", (post_id,)).fetchone()
    p = redact.project(post, "post_detail")
    p.update(viewer._art_pair(post, BASE))
    p["work_ref"] = viewer._work_ref(post)
    releases = conn.execute(
        "SELECT * FROM releases WHERE post_id = ?"
        " ORDER BY post_id ASC, id ASC", (post_id,)).fetchall()
    rel_ids = [int(r["id"]) for r in releases]
    sources_by_rel = {rid: [] for rid in rel_ids}
    if rel_ids:
        for s in conn.execute(
                "SELECT * FROM download_sources WHERE release_id IN ("
                + ", ".join("?" for _ in rel_ids)
                + ") ORDER BY release_id ASC, id ASC",
                rel_ids).fetchall():
            sources_by_rel[int(s["release_id"])].append(s)
    rel_list = []
    for rel in releases:
        sources = sorted(sources_by_rel[int(rel["id"])],
                         key=viewer._source_ladder_key)
        r = redact.project(rel, "release")
        r["label"] = redact.public_label(rel)
        r["sources"] = [viewer.link_view(s, BASE) for s in sources]
        rel_list.append(r)
    p["option_groups"] = viewer._option_groups(releases, rel_list)
    p["season_artwork"] = viewer._season_artwork(conn, post_id)
    p["releases"] = rel_list
    # The endpoint's cap keys: the fixture is small, so nothing is cut.
    p["release_total"] = len(rel_list)
    p["releases_truncated"] = False
    return p


class DetailProjectionTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "detail.db"
        for target in (fast_fetch, viewer):
            patcher = patch.object(target, "DB_PATH", self.db_path)
            patcher.start()
            self.addCleanup(patcher.stop)
        cinemeta = patch.object(
            fast_fetch, "fetch_cinemeta_metadata", return_value=None)
        cinemeta.start()
        self.addCleanup(cinemeta.stop)
        fast_fetch.init_db()
        self.movie_id = self._save_movie()
        self.series_id = self._save_series()

    def tearDown(self):
        self.temp_dir.cleanup()

    def _save(self, media):
        return fast_fetch.save_scraped_data(media)

    def _plant_markers(self, post_id):
        """Long distinctive values in every column the public path must
        never read (and in the operator path's raw rows, for parity)."""
        with viewer.get_db() as conn:
            for col, val in (
                    ("raw_meta", "RAW-META-MARKER-" + LONG),
                    ("duration", "DURATION-MARKER-" + LONG),
                    ("director", "DIRECTOR-MARKER-" + LONG),
                    ("release_date", "RELEASE-DATE-MARKER-" + LONG),
                    ("url", f"https://site-a.example/URL-MARKER-{post_id}-"
                     + LONG),
                    ("slug", "SLUG-MARKER-" + LONG)):
                conn.execute(
                    f"UPDATE posts SET {col} = ? WHERE id = ?",
                    (val, post_id))
            rel_ids = [r[0] for r in conn.execute(
                "SELECT id FROM releases WHERE post_id = ?", (post_id,))]
            for rid in rel_ids:
                for col, val in (
                        ("intermediate_url",
                         "https://site-a.example/INTERMEDIATE-MARKER-" + LONG),
                        ("origin_url",
                         "https://site-a.example/ORIGIN-MARKER-" + LONG)):
                    conn.execute(
                        f"UPDATE releases SET {col} = ? WHERE id = ?",
                        (val, rid))
            src_ids = [r[0] for r in conn.execute(
                "SELECT id FROM download_sources WHERE release_id IN (%s)"
                % ",".join("?" for _ in rel_ids), rel_ids)]
            for sid in src_ids:
                for col, val in (
                        ("final_url",
                         "https://cdn.example/FINAL-MARKER-" + LONG),
                        ("source_url",
                         "https://host.example/SOURCE-MARKER-" + LONG),
                        ("resource_id", "RESOURCE-MARKER-" + LONG),
                        ("source_name", "SOURCE-NAME-MARKER-" + LONG)):
                    conn.execute(
                        f"UPDATE download_sources SET {col} = ? WHERE id = ?",
                        (val, sid))
            # One probe-confirmed live copy: the ladder's rank-0 branch
            # reads `last_checked_at` + `http_status` off the raw row, so
            # the fixture must exercise it (an all-`unverified` fixture
            # never touches that branch).
            if src_ids:
                conn.execute(
                    "UPDATE download_sources SET status = 'active',"
                    " last_checked_at = '2026-11-17 00:00:00',"
                    " http_status = 200 WHERE id = ?", (src_ids[0],))

    def _save_movie(self):
        media = {
            "url": "https://site-a.example/film/", "slug": "film",
            "title": "Example Film (2024)", "clean_title": "Example Film",
            "year": 2024, "content_type": "movie", "seasons": None,
            "poster_url": None, "backdrop_urls": None,
            "imdb_rating": "7.5", "genres": "Action",
            "synopsis": "A film.", "cast_names": "Actor One, Actor Two",
            "is_18_plus": 0, "is_animated": 0,
            "source_site": "site_a", "industry": "Hollywood",
            "country": "USA", "language": "English",
            "canonical_key": fast_fetch.generate_canonical_key(
                "Example Film", 2024),
            "releases": [
                {"release_title": f"Example Film {q}", "quality": q,
                 "codec": "x264", "size": "1GB", "languages": "English",
                 "season": None, "episode": None,
                 "intermediate_url": "https://site-a.example/release",
                 "sources": [
                     {"source_name": f"Mirror {i}",
                      "service": "pixeldrain",
                      "resource_id": f"r{i}",
                      "source_url": f"https://x.example/{i}",
                      "final_url": f"https://cdn.example/{i}",
                      "is_temporary": 0}
                     for i in (1, 2, 3, 4, 5)]}
                for q in ("480p", "720p", "1080p")],
        }
        post_id = self._save(media)
        self._plant_markers(post_id)
        return post_id

    def _save_series(self):
        media = {
            "url": "https://site-a.example/series/", "slug": "series",
            "title": "Example Series (2024)",
            "clean_title": "Example Series",
            "year": 2024, "content_type": "series", "seasons": "S01",
            "poster_url": None, "backdrop_urls": None,
            "imdb_rating": None, "genres": "Drama",
            "synopsis": "A series.", "cast_names": None,
            "is_18_plus": 0, "is_animated": 0,
            "source_site": "site_a", "industry": "Hollywood",
            "country": "USA", "language": "English",
            "canonical_key": fast_fetch.generate_canonical_key(
                "Example Series", 2024),
            "releases": [
                {"release_title": "Example Series S01E01",
                 "quality": "720p", "codec": "x264", "size": "500MB",
                 "languages": "English", "season": "S01",
                 "episode": "E01",
                 "intermediate_url": "https://site-a.example/release",
                 "sources": [
                     {"source_name": "Mirror 1", "service": "pixeldrain",
                      "resource_id": "s1",
                      "source_url": "https://x.example/s1",
                      "final_url": "https://cdn.example/s1",
                      "is_temporary": 0}]},
                {"release_title": "Example Series S01E02",
                 "quality": "720p", "codec": "x264", "size": "500MB",
                 "languages": "English", "season": "S01",
                 "episode": "E02",
                 "intermediate_url": "https://site-a.example/release",
                 "sources": [
                     {"source_name": "Mirror 1", "service": "pixeldrain",
                      "resource_id": "s2",
                      "source_url": "https://x.example/s2",
                      "final_url": "https://cdn.example/s2",
                      "is_temporary": 0}]},
            ],
        }
        post_id = self._save(media)
        self._plant_markers(post_id)
        with viewer.get_db() as conn:
            conn.execute(
                "UPDATE releases SET created_at = '2026-01-01 00:00:00'"
                " WHERE post_id = ? AND episode = 'E01'", (post_id,))
            conn.execute(
                "UPDATE releases SET created_at = '2026-02-01 00:00:00'"
                " WHERE post_id = ? AND episode = 'E02'", (post_id,))
        return post_id

    def test_public_post_detail_matches_select_star_reference(self):
        detail = viewer.api_v1_get_post_detail(self.movie_id, base_url=BASE)
        self.assertTrue(detail, "detail route returned nothing")
        with viewer.get_db() as conn:
            expected = _reference_post_detail(conn, self.movie_id)
        self.assertEqual(_normalize(detail), _normalize(expected))
        blob = json.dumps(detail)
        for marker in MARKERS:
            self.assertNotIn(marker, blob, marker)

    def test_detail_sql_projects_columns(self):
        # Warm the schema cache first, so the traced call runs only the
        # detail queries (no init_db / needle reads on the traced conn).
        viewer.api_v1_get_post_detail(self.movie_id, base_url=BASE)
        viewer.api_v1_get_work_detail(self.movie_id, base_url=BASE)
        statements = []
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        conn.set_trace_callback(statements.append)

        @contextmanager
        def traced():
            try:
                yield conn
            finally:
                pass

        try:
            with patch.object(viewer, "get_db", traced):
                viewer.api_v1_get_post_detail(self.movie_id, base_url=BASE)
                viewer.api_v1_get_work_detail(self.movie_id, base_url=BASE)
        finally:
            conn.close()
        selects = [s for s in statements
                   if s.lstrip().upper().startswith("SELECT")]
        self.assertTrue(selects, "no SELECT traced on the detail calls")
        for stmt in selects:
            head = stmt.split("FROM", 1)[0]
            self.assertNotIn("*", head, stmt)
        detail_selects = [
            (stmt, POST_DETAIL_COLS) for stmt in selects
            if re.search(r"FROM posts WHERE id = \d+", stmt)
        ] + [
            (stmt, RELEASE_DETAIL_COLS) for stmt in selects
            if re.search(r"FROM releases WHERE post_id IN", stmt)
        ] + [
            (stmt, SOURCE_DETAIL_COLS) for stmt in selects
            if re.search(r"FROM download_sources WHERE release_id IN", stmt)
        ]
        # Post detail (1 post query) + work detail (seed + rep + the
        # shared loader twice): 3 post queries, 2 release queries,
        # 2 source queries.
        self.assertEqual(len(detail_selects), 7,
                         [s for s, _ in detail_selects])
        for stmt, allowed in detail_selects:
            head = stmt.split("FROM", 1)[0]
            cols = {c.strip().split(" ")[0].split(".")[-1]
                    for c in head[len("SELECT"):].split(",")}
            self.assertTrue(cols, stmt)
            self.assertEqual(cols - allowed, set(), stmt)
            self.assertEqual(cols, allowed, stmt)

    def test_full_detail_still_returns_the_raw_row(self):
        full = viewer.api_v1_get_post_detail(self.movie_id, full=True)
        blob = json.dumps(full)
        for marker in MARKERS:
            self.assertIn(marker, blob, marker)
        full_work = viewer.api_v1_get_work_detail(self.movie_id, full=True)
        blob = json.dumps(full_work)
        for marker in MARKERS:
            self.assertIn(marker, blob, marker)

    def test_work_detail_episode_guide_survives_projection(self):
        # `created_at` is only needed by the work path (`build_seasons`):
        # dropping it would silently blank the new-episode signal.
        detail = viewer.api_v1_get_work_detail(self.series_id, base_url=BASE)
        self.assertTrue(detail, "work detail route returned nothing")
        self.assertEqual(detail["episode_guide"], [
            {"season": "S01", "episodes": [
                {"episode": "E01", "status": "available"},
                {"episode": "E02", "status": "available"}]}])
        self.assertEqual(detail["latest_episode_added_at"],
                         "2026-02-01 00:00:00")
        blob = json.dumps(detail)
        for marker in MARKERS:
            self.assertNotIn(marker, blob, marker)


if __name__ == "__main__":
    unittest.main()
