"""B1: cursor (keyset) pagination on v1 /api/v1/posts + the v2 listing,
and the band-aware play walk.

(a) Two consecutive cursor pages are disjoint, in stable order, with no
duplicates or skips — for newest, title, rating and trending, and for a
deduped search. (b) Cursor + offset return the same first page, and the
page after a cursor is the offset page after it. (c) The play walk tries
the best band first: a kitted-out 720p BluRay outscores a bare 1080p
print on `quality_score` (the score is a composite prior, not band
order), and the walk still takes the 1080p print — while a VERIFIED
lower-band print still beats an unverified higher one, because the
verified preference is about freshness, not the trial order.

Stdlib only, temp DB, no network. The v1 fixture is twelve posts with
distinct years, titles and ratings (including NULL and '' ratings, the
two values the rating keyset must tell apart) plus three duplicate
prints of the first three titles filed by a second site, so the deduped
search has groups to collapse. The play fixture is one work with two
prints: 720p BluRay and bare 1080p.
"""

import os
import sqlite3
import tempfile
import time
import unittest
import unittest.mock
from pathlib import Path

import api_v2
import catalog_api
import catalog_v2
import fast_fetch
import resolved_cache
import viewer
from viewer import InvalidPostsCursor


def setUpModule():
    """Branch 2: ``record_resolved_cache`` now probes the URL at bank
    time. This suite banks fixture URLs offline, so the probe is faked
    as passing — the refusal itself is proven by
    ``test_resolved_cache.TestBankRefusesNonMedia``."""
    global _probe_patcher
    _probe_patcher = unittest.mock.patch.object(
        resolved_cache, "_media_probe", return_value=(True, None))
    _probe_patcher.start()


def tearDownModule():
    _probe_patcher.stop()


HOST = "http://our.host"
SECRET = "test-secret-for-cursor-pagination"

NAMES = ["Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot",
         "Golf", "Hotel", "India", "Juliet", "Kilo", "Lima"]
# (rating, sources): the rating column is set by direct UPDATE after
# ingest, so the values are exactly the TEXT / NULL / '' the live
# column holds; sources give every post a distinct copy count for
# the trending order. Two NULL ratings (ids 9 and 11) exercise the
# NULL-to-NULL id tiebreak across a page boundary.
RATINGS = ["9.0", "8.5", "8.0", "7.5", "7.0", "6.5", "6.0", "5.5",
           None, "", None, "4.0"]


def cdn(tag, *, status="active", temporary=0):
    url = f"https://video-downloads.googleusercontent.com/{tag}"
    return {"source_name": "CDN", "service": "google_cdn",
            "resource_id": tag, "source_url": url, "final_url": url,
            "playback_type": "direct", "is_temporary": temporary,
            "status": status}


class CursorPaginationFixture(unittest.TestCase):
    """One temp catalog per test: full isolation, no shared mutable state."""

    def setUp(self):
        os.environ["RADD_STREAM_SECRET"] = SECRET
        self.addCleanup(os.environ.pop, "RADD_STREAM_SECRET", None)
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "cursor.db"
        self._patches = [
            unittest.mock.patch.object(fast_fetch, "DB_PATH", self.db_path),
            unittest.mock.patch.object(viewer, "DB_PATH", self.db_path),
        ]
        for patcher in self._patches:
            patcher.start()
        fast_fetch.init_db(self.db_path)
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        catalog_v2.ensure_schema(self.conn)
        self.conn.commit()
        self.post_ids = []
        for index, name in enumerate(NAMES):
            self.post_ids.append(self._add_post(
                "siteone", f"https://siteone.example/cursor-{name.lower()}",
                clean_title=f"Cursor {name}", year=2024 - index,
                n_sources=index + 1))
        # Three duplicate prints of the first three titles, filed by a
        # second site: the deduped search collapses them into their
        # originals' groups, so 15 rows are 12 works. They go in by
        # direct SQL, not `save_scraped_data`: the ingester matches on
        # the canonical title/year key and would merge them into the
        # originals instead of filing second rows.
        for index, name in enumerate(NAMES[:3]):
            self._add_duplicate_post(
                self.post_ids[index], site="sitetwo",
                url=f"https://sitetwo.example/cursor-{name.lower()}")
        for post_id, rating in zip(self.post_ids, RATINGS):
            self.conn.execute(
                "UPDATE posts SET imdb_rating = ? WHERE id = ?",
                (rating, post_id))
        self.conn.commit()
        catalog_api.configure_public(HOST, SECRET)

    def tearDown(self):
        self.conn.close()
        for patcher in self._patches:
            patcher.stop()
        self.tmp.cleanup()

    def _add_post(self, site, url, *, clean_title, year, n_sources=1):
        data = {
            "url": url,
            "slug": url.rstrip("/").split("/")[-1],
            "title": f"{clean_title} ({year})",
            "clean_title": clean_title,
            "year": year,
            "content_type": "movie",
            "seasons": None,
            "poster_url": f"https://img.{site}.watch/x.jpg",
            "backdrop_urls": None,
            "imdb_rating": 7.4,
            "genres": '["Action"]',
            "synopsis": f"{clean_title}: fixture",
            "is_18_plus": 0,
            "is_animated": 0,
            "source_site": site,
            "industry": "Bollywood",
            "country": "India",
            "language": "Hindi",
            "canonical_key": fast_fetch.generate_canonical_key(
                clean_title, year),
            "tmdb_id": None,
            "imdb_id": None,
            "releases": [{
                "release_title": f"{clean_title} 1080p",
                "season": "", "episode": "",
                "quality": "1080p", "codec": "x264", "size": "1.5GB",
                "languages": '["Hindi"]',
                "intermediate_url": "", "origin_url": "",
                "source_site": "",
                "sources": [cdn(f"{clean_title}-{i}")
                            for i in range(n_sources)],
            }],
        }
        return fast_fetch.save_scraped_data(
            data, enrich_metadata=False, ensure_db=False)

    def _add_duplicate_post(self, orig_id, *, site, url):
        """A second site's filing of the same film, as a separate row.

        Copies the original post (new url/slug/site only) plus one
        fresh 1080p release with one CDN source, so the row is a full
        citizen for the list, the dedupe and the projection — which
        still merges it into the original's work by title+year.
        """
        row = self.conn.execute(
            "SELECT * FROM posts WHERE id = ?", (orig_id,)).fetchone()
        cols = [key for key in row.keys() if key != "id"]
        values = [url if col == "url"
                  else url.rstrip("/").split("/")[-1] if col == "slug"
                  else site if col == "source_site"
                  else row[col] for col in cols]
        marks = ", ".join("?" for _ in cols)
        post_id = self.conn.execute(
            f"INSERT INTO posts ({', '.join(cols)}) VALUES ({marks})",
            values).lastrowid
        release_id = self.conn.execute(
            "INSERT INTO releases (post_id, release_title, season,"
            " episode, quality, codec, size, languages,"
            " intermediate_url, origin_url, source_site)"
            " VALUES (?, ?, '', '', '1080p', 'x264', '1.5GB',"
            " '[\"Hindi\"]', '', '', '')",
            (post_id, f"{row['clean_title']} 1080p")).lastrowid
        tag = f"dupe-{row['clean_title'].replace(' ', '-').lower()}"
        src_url = f"https://video-downloads.googleusercontent.com/{tag}"
        self.conn.execute(
            "INSERT INTO download_sources (release_id, source_name,"
            " service, resource_id, source_url, final_url,"
            " playback_type, is_temporary, status)"
            " VALUES (?, 'CDN', 'google_cdn', ?, ?, ?, 'direct', 0,"
            " 'active')", (release_id, tag, src_url, src_url))
        self.conn.commit()
        return post_id

    def _project(self, rev=1):
        self.conn.commit()
        return catalog_v2.project(self.conn, rev=rev, verbose=False)

    # ---- walking helpers ------------------------------------------------

    def _offset_pages(self, limit, **kwargs):
        """Every offset page until the list ends, as (ids, total)."""
        pages, total, page = [], None, 1
        while True:
            answer = viewer.api_v1_get_posts(limit=limit, page=page, **kwargs)
            pages.append([item["id"] for item in answer["results"]])
            total = answer["total"]
            if page >= answer["total_pages"]:
                break
            page += 1
        return pages, total

    def _cursor_pages(self, limit, **kwargs):
        """Every cursor page until next_cursor runs out, as (ids, total)."""
        pages, total = [], None
        answer = viewer.api_v1_get_posts(limit=limit, **kwargs)
        while True:
            pages.append([item["id"] for item in answer["results"]])
            total = answer["total"]
            if not answer["next_cursor"]:
                break
            answer = viewer.api_v1_get_posts(
                limit=limit, cursor=answer["next_cursor"], **kwargs)
        return pages, total

    def _assert_walk_matches(self, limit, **kwargs):
        offset_pages, offset_total = self._offset_pages(limit, **kwargs)
        cursor_pages, cursor_total = self._cursor_pages(limit, **kwargs)
        self.assertEqual(cursor_pages, offset_pages,
                         f"cursor walk != offset walk for {kwargs}")
        flat = [pid for page in cursor_pages for pid in page]
        self.assertEqual(len(flat), len(set(flat)),
                         "cursor walk repeats a row")
        self.assertEqual(cursor_total, offset_total)
        return flat

    # ---- (a)+(b) v1 walks -------------------------------------------------

    def test_newest_cursor_walk_matches_offset(self):
        flat = self._assert_walk_matches(5, sort="newest")
        self.assertEqual(len(flat), 15)

    def test_title_cursor_walk_matches_offset(self):
        flat = self._assert_walk_matches(5, sort="title")
        self.assertEqual(len(flat), 15)

    def test_rating_cursor_walk_matches_offset(self):
        flat = self._assert_walk_matches(5, sort="rating")
        self.assertEqual(len(flat), 15)
        # '' (CAST 0.0) sorts before NULL under the rating DESC —
        # NULL is the smallest value, so DESC puts it last — and the
        # two NULL rows tiebreak by id DESC.
        empty_id = self.post_ids[9]
        null_high_id = self.post_ids[10]
        null_low_id = self.post_ids[8]
        self.assertLess(flat.index(empty_id), flat.index(null_high_id))
        self.assertLess(flat.index(null_high_id), flat.index(null_low_id))

    def test_rating_cursor_crosses_null_boundary(self):
        # The ''/NULL boundary sits across a page edge here, so the
        # keyset — not just the in-page order — must tell them apart:
        # limit 13 ends page 1 on the '' row, limit 14 on the first
        # NULL row. A keyset that read NULL-before-0.0 would skip the
        # NULL rows from the '' cursor, or repeat the '' row from a
        # NULL cursor.
        pages13, _ = self._cursor_pages(13, sort="rating")
        self.assertEqual(pages13[1], [self.post_ids[10], self.post_ids[8]])
        pages14, _ = self._cursor_pages(14, sort="rating")
        self.assertEqual(pages14[1], [self.post_ids[8]])

    def test_trending_cursor_walk_matches_offset(self):
        # The offset walk reads the cached order; the cursor walk reads
        # the grouped HAVING path. Same order, no dup or skip — 13
        # rows, not 15: the trending page dedupes per page, so the
        # same-page duplicate of Bravo drops while the cross-page
        # duplicates of Charlie and Alpha stay (the pre-existing
        # `_dedupe_page_rows` scope: cross-page repeats are out of
        # scope, and the cursor walk reproduces the offset walk
        # exactly, repeats included).
        flat = self._assert_walk_matches(5, sort="trending")
        self.assertEqual(len(flat), 13)
        # Most copies first: the last-ingested post carries 12 sources.
        self.assertEqual(flat[0], self.post_ids[11])

    def test_first_page_cursor_matches_offset(self):
        for sort in ("newest", "title", "rating", "trending"):
            with self.subTest(sort=sort):
                first = viewer.api_v1_get_posts(limit=5, sort=sort)
                second_offset = viewer.api_v1_get_posts(
                    limit=5, page=2, sort=sort)
                second_cursor = viewer.api_v1_get_posts(
                    limit=5, sort=sort, cursor=first["next_cursor"])
                self.assertEqual(
                    [item["id"] for item in first["results"]],
                    [item["id"] for item in viewer.api_v1_get_posts(
                        limit=5, page=1, sort=sort)["results"]])
                self.assertEqual(second_cursor["page"], 2)
                self.assertEqual(
                    [item["id"] for item in second_cursor["results"]],
                    [item["id"] for item in second_offset["results"]])

    def test_bad_cursor_rejected(self):
        with self.assertRaises(InvalidPostsCursor):
            viewer.api_v1_get_posts(limit=5, cursor="not-a-cursor")
        with self.assertRaises(InvalidPostsCursor):
            viewer.api_v1_get_posts(limit=5, cursor="AAAA")
        newest = viewer.api_v1_get_posts(limit=5, sort="newest")
        with self.assertRaises(InvalidPostsCursor):
            # Minted for newest, spent on title.
            viewer.api_v1_get_posts(limit=5, sort="title",
                                    cursor=newest["next_cursor"])
        movie = viewer.api_v1_get_posts(limit=5, content_type="movie")
        with self.assertRaises(InvalidPostsCursor):
            # Minted under content_type=movie, spent unfiltered.
            viewer.api_v1_get_posts(limit=5, cursor=movie["next_cursor"])

    def test_deduped_search_cursor_walk(self):
        # 15 rows, 12 works: the search dedupes by work, and the cursor
        # walk must cover exactly the same works as the offset walk.
        flat = self._assert_walk_matches(5, query="cursor")
        self.assertEqual(len(flat), 12)
        first = viewer.api_v1_get_posts(limit=5, query="cursor")
        self.assertEqual(first["total"], 12)

    def test_last_page_has_no_cursor(self):
        answer = viewer.api_v1_get_posts(limit=5, page=3, sort="newest")
        self.assertIsNone(answer["next_cursor"])
        self.assertFalse(answer["has_more"])

    # ---- (a)+(b) v2 listing ----------------------------------------------

    def _v2_walk(self, fn, limit, **kwargs):
        pages, total = [], None
        answer = fn(self.conn, limit=limit, **kwargs)
        while True:
            pages.append([item["id"] for item in answer["items"]])
            total = answer["total_estimate"]
            if not answer.get("next_cursor"):
                break
            answer = fn(self.conn, limit=limit,
                        cursor=answer["next_cursor"], **kwargs)
        return pages, total

    def test_v2_browse_cursor_walk_covers_every_work_once(self):
        self._project()
        pages, total = self._v2_walk(catalog_api.browse, 4)
        flat = [wid for page in pages for wid in page]
        self.assertEqual(total, 12)
        self.assertEqual(len(flat), 12)
        self.assertEqual(len(set(flat)), 12)

    def test_v2_search_cursor_walk_covers_every_match_once(self):
        self._project()
        pages, total = self._v2_walk(catalog_api.search, 4, q="cursor")
        flat = [wid for page in pages for wid in page]
        self.assertEqual(total, 12)
        self.assertEqual(len(flat), 12)
        self.assertEqual(len(set(flat)), 12)

    # ---- (c) band-aware play walk ------------------------------------------

    def _band_twins(self):
        """One work, two prints: 720p BluRay outscores bare 1080p."""
        data = {
            "url": "https://siteone.example/band-twins",
            "slug": "band-twins",
            "title": "Band Twins (2024)",
            "clean_title": "Band Twins",
            "year": 2024,
            "content_type": "movie",
            "seasons": None,
            "poster_url": "https://img.siteone.watch/x.jpg",
            "backdrop_urls": None,
            "imdb_rating": 7.4,
            "genres": '["Action"]',
            "synopsis": "Band Twins: fixture",
            "is_18_plus": 0,
            "is_animated": 0,
            "source_site": "siteone",
            "industry": "Bollywood",
            "country": "India",
            "language": "Hindi",
            "canonical_key": fast_fetch.generate_canonical_key(
                "Band Twins", 2024),
            "tmdb_id": None,
            "imdb_id": None,
            "releases": [
                {
                    "release_title": "Band Twins 720p BluRay",
                    "season": "", "episode": "",
                    "quality": "720p", "codec": "x264", "size": "1.5GB",
                    "languages": '["Hindi"]',
                    "intermediate_url": "", "origin_url": "",
                    "source_site": "",
                    "sources": [cdn("twins-720p")],
                },
                {
                    "release_title": "Band Twins 1080p",
                    "season": "", "episode": "",
                    "quality": "1080p", "codec": "x264", "size": "2.5GB",
                    "languages": '["Hindi"]',
                    "intermediate_url": "", "origin_url": "",
                    "source_site": "",
                    "sources": [cdn("twins-1080p")],
                },
            ],
        }
        fast_fetch.save_scraped_data(
            data, enrich_metadata=False, ensure_db=False)
        self.conn.commit()
        self._project()
        work = self.conn.execute(
            "SELECT id FROM cat_works WHERE title = 'Band Twins'"
            ).fetchone()
        self.assertIsNotNone(work, "Band Twins work missing after project")
        releases = self.conn.execute(
            "SELECT id, resolution, quality_label, quality_score"
            " FROM cat_releases WHERE work_id = ? ORDER BY id",
            (work["id"],)).fetchall()
        self.assertEqual(len(releases), 2)
        by_res = {row["resolution"]: row for row in releases}
        return work["id"], by_res["720p"], by_res["1080p"]

    def test_play_walk_tries_best_band_first(self):
        work_id, low, high = self._band_twins()
        # The inversion this test exists for: the 720p BluRay's
        # composite score beats the bare 1080p print's.
        self.assertGreater(int(low["quality_score"]),
                           int(high["quality_score"]),
                         "fixture does not invert score vs band")
        self.assertEqual(api_v2._band_order(self.conn, [low["id"], high["id"]]),
                         [high["id"], low["id"]])
        bundle = api_v2._play_first_playable(
            self.conn, [low["id"], high["id"]], episode_id=None,
            include_adult=False)
        self.assertEqual(bundle["release_id"], high["id"])

    def test_play_walk_verified_lower_band_still_wins(self):
        work_id, low, high = self._band_twins()
        # Bank a fresh resolution for the 720p print's only link: the
        # verified preference is untouched by band ordering, so the
        # walk returns the verified 720p print, not the 1080p one.
        link = self.conn.execute(
            "SELECT id FROM download_sources WHERE resource_id = ?",
            ("twins-720p",)).fetchone()
        self.assertIsNotNone(link)
        resolved_cache.record_resolved_cache(
            self.conn, int(link["id"]),
            {"url": "https://video-downloads.googleusercontent.com/twins-720p",
             "resolved_at": time.time(),
             "expires_in": 3600, "http_status": 200})
        self.conn.commit()
        bundle = api_v2._play_first_playable(
            self.conn, [low["id"], high["id"]], episode_id=None,
            include_adult=False)
        self.assertEqual(bundle["release_id"], low["id"])


if __name__ == "__main__":
    unittest.main()
