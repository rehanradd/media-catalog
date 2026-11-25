#!/usr/bin/env python3
"""
test_harpalgeo_import.py — Unit tests for the HarPal Geo importer (Session 118).

Tests:
  1. Program links: homepage yields program links only (episode links excluded).
  2. Episode links: teaser links dropped, full episodes kept.
  3. Stream extraction: the 5centscdn playlist wins when two .m3u8s are present.
  4. Episode title parse: episode number + air date from the <title>.
  5. Program data: one series post, one release per full episode, direct HLS.
  6. No playable episode -> None.
  7. Save path: a saved program in a temp staging DB (posts/releases/sources).
  8. Program sync: mocked homepage -> sitemap_cache rows; mocked homepage +
     /programs pages -> union rows, dedup-stop at the first no-new page.
  9. Registry: the harpalgeo entry exists with the live base.
  10. Episode attribution (2026-11-23): other programs' episodes on the
      page (LATEST EPISODES widget) are excluded by program slug.
  11. Program metadata (2026-11-23): synopsis, cast, director, credits
      from the program page; release_date from the earliest air date;
      title from <h1>, slug title-cased when no <h1>.

100% Python Standard Library. Zero external dependencies. Offline — temp SQLite
files only, no network, no catalog read. Fixture stream URLs use the reserved
.test TLD; they exercise the extractor's shape, never a real playlist.
"""

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock

import fast_fetch
import import_harpalgeo

SAMPLE_HG_HOME_HTML = """
<!DOCTYPE html>
<html><head><title>Home Har Pal Geo</title></head><body>
<a href="https://harpalgeo.tv/program/Bano">Bano</a>
<a href="https://harpalgeo.tv/program/Aik-Thi-Rania">Aik Thi Rania</a>
<a href="https://harpalgeo.tv/program/Bano/26866">Bano Episode 26</a>
<a href="https://harpalgeo.tv/program/Bano">Bano</a>
</body></html>
"""

SAMPLE_HG_PROGRAMS_HTML = """
<!DOCTYPE html>
<html><head><title>Programs - Har Pal Geo</title></head><body>
<a href="https://harpalgeo.tv/program/Bano">Bano</a>
<a href="https://harpalgeo.tv/program/Zombeid">Zombeid</a>
<a href="https://harpalgeo.tv/programs/2">2</a>
</body></html>
"""

SAMPLE_HG_PROGRAMS_P2_HTML = """
<!DOCTYPE html>
<html><head><title>Programs - Har Pal Geo</title></head><body>
<a href="https://harpalgeo.tv/program/Bano">Bano</a>
<a href="https://harpalgeo.tv/program/Zombeid">Zombeid</a>
</body></html>
"""

SAMPLE_HG_PROGRAM_HTML = """
<!DOCTYPE html>
<html><head>
<title>Bano Episode, Bano Drama Cast, Bano Schedule, Bano OST</title>
<meta property="og:image" content="https://harpalgeo.tv/wp-content/uploads/bano-poster.jpg">
</head><body>
<a href="https://harpalgeo.tv/program/Bano/26866">Episode 26 | Haris Waheed - Hina Tariq</a>
<a href="https://harpalgeo.tv/program/Bano/26855">Episode 25 | Haris Waheed - Hina Tariq</a>
<a href="https://harpalgeo.tv/program/Bano/26865"><img title="Episode 26 Teaser - 7th November 2026 - HAR PAL GEO"></a>
<a href="https://harpalgeo.tv/program/Nashtar/26900">Nashtar Episode 36</a>
</body></html>
"""

# Rich program fixture (field population + attribution, 2026-11-23):
# modelled on the live /program/Bano page — <h1> title, synopsis_detail
# div with story + credits, a "<Program> Cast" panel, own episodes, and
# a LATEST EPISODES widget carrying another program's episode.
SAMPLE_HG_PROGRAM_RICH_HTML = """
<!DOCTYPE html>
<html><head>
<title>Bano Episode, Bano Drama Cast, Bano Schedule, Bano OST</title>
<meta property="og:image" content="https://harpalgeo.tv/assets/uploads/programs/641_9919269_programs.jpg">
</head><body>
<h1>Bano</h1>
<div class="def_hd"><h2>synopsis</h2></div>
<div class="synopsis_detail"><div class="inner">
<h3>Bano synopsis</h3>
Bano is a compelling tale of love, sacrifice, family, and betrayal. Bano, a young
woman from a middle-class family, works hard to secure a better future.
<br /> <br /> Production House: BJ Production
<br /> <br /> Producer: Babbar Javaid
<br /> <br /> Director: Furqan Adam
<br /> <br /> Writer: Atiq Inayat </div> </div>
<div class="care_accord"><div class="panel-group" id="accordion">
<div class="panel panel-default"><div class="panel-heading">
<h3><a data-toggle="collapse" href="#collapse1">Bano Cast</a></h3></div>
<div id="collapse1" class="panel-collapse collapse"><div class="panel-body"><ul>
<li><p> Hina Tariq</p></li><li><p>Haris Waheed</p></li><li><p>Aqeel Yousaf</p></li>
</ul></div></div></div></div></div>
<div class="others program_video_list"><div class="def_hd"><h3>Episodes &amp; Teasers</h3></div>
<a href="https://harpalgeo.tv/program/Bano/26866" title="Bano Episode 26 | Haris Waheed - Hina Tariq | HAR PAL GEO">Episode 26</a>
<a href="https://harpalgeo.tv/program/Bano/26855" title="Bano Episode 25 | Haris Waheed - Hina Tariq | HAR PAL GEO">Episode 25</a>
<a href="https://harpalgeo.tv/program/Bano/26865" title="Bano Episode 26 Teaser - 7th November 2026 - HAR PAL GEO">Teaser</a>
</div>
<div class="row"><h3>LATEST EPISODES</h3>
<a href="https://harpalgeo.tv/program/Nashtar/26900" title="Nashtar Episode 36 Teaser - 22nd November 2026 - HAR PAL GEO">Nashtar Ep 36</a>
<a href="https://harpalgeo.tv/program/Uswah/26901" title="Uswah Episode 12 - 22nd November 2026 - HAR PAL GEO">Uswah Ep 12</a>
</div>
</body></html>
"""

SAMPLE_HG_EP26_HTML = """
<!DOCTYPE html>
<html><head>
<title>Bano Episode 26 | Haris Waheed Hina Tariq Aqeel Yousaf |, Har Pal Geo Drama - 8th Nov 2026 - Watch Online</title>
<script src="https://harpalgeo.tv/videojs/video.min.js"></script>
</head><body>
<video class="video-js"><source src="https://vjgnbjkgo97y-hls-push.5centscdn.test/mp4/raw/131/132/fake_combined.smil/playlist.m3u8" type="application/x-mpegURL"></video>
<script>var alt = "https://othercdn.test/bano26/playlist.m3u8";</script>
</body></html>
"""

SAMPLE_HG_EP25_HTML = """
<!DOCTYPE html>
<html><head>
<title>Bano Episode 25 | Haris Waheed Hina Tariq Aqeel Yousaf |, Har Pal Geo Drama - 7th Nov 2026 - Watch Online</title>
</head><body>
<video class="video-js"><source src="https://vjgnbjkgo97y-hls-push.5centscdn.test/mp4/raw/131/132/fake25_combined.smil/playlist.m3u8" type="application/x-mpegURL"></video>
</body></html>
"""

SAMPLE_HG_EP_NO_STREAM_HTML = """
<!DOCTYPE html>
<html><head><title>Bano Episode 24 | Haris Waheed |, Har Pal Geo Drama - 6th Nov 2026 - Watch Online</title></head>
<body><p>Coming soon</p></body></html>
"""


class TestHarPalGeoExtract(unittest.TestCase):
    def test_homepage_yields_programs_not_episodes(self):
        programs = import_harpalgeo.extract_program_links(SAMPLE_HG_HOME_HTML)
        self.assertEqual(len(programs), 2)
        self.assertIn("https://harpalgeo.tv/program/Bano", programs)
        self.assertIn("https://harpalgeo.tv/program/Aik-Thi-Rania", programs)
        self.assertFalse(any("/26866" in p for p in programs))

    def test_episode_links_drop_teasers(self):
        eps = import_harpalgeo.extract_episode_links(SAMPLE_HG_PROGRAM_HTML, program_slug="Bano")
        urls = [e["url"] for e in eps]
        self.assertEqual(len(urls), 2)
        self.assertIn("https://harpalgeo.tv/program/Bano/26866", urls)
        self.assertIn("https://harpalgeo.tv/program/Bano/26855", urls)
        self.assertNotIn("https://harpalgeo.tv/program/Bano/26865", urls)

    def test_episode_links_exclude_other_programs(self):
        # The LATEST EPISODES widget lists other programs' episodes on
        # the same page (1,546/3,437 releases pointed at the wrong
        # program before the slug filter). A full Uswah episode must not
        # be collected for Bano, even though it is not a teaser.
        eps = import_harpalgeo.extract_episode_links(
            SAMPLE_HG_PROGRAM_RICH_HTML, program_slug="Bano")
        urls = [e["url"] for e in eps]
        self.assertEqual(urls, [
            "https://harpalgeo.tv/program/Bano/26866",
            "https://harpalgeo.tv/program/Bano/26855",
        ])
        self.assertNotIn("https://harpalgeo.tv/program/Uswah/26901", urls)
        self.assertNotIn("https://harpalgeo.tv/program/Nashtar/26900", urls)

    def test_episode_links_without_slug_keep_legacy_behaviour(self):
        eps = import_harpalgeo.extract_episode_links(SAMPLE_HG_PROGRAM_RICH_HTML)
        urls = [e["url"] for e in eps]
        self.assertIn("https://harpalgeo.tv/program/Uswah/26901", urls)

    def test_stream_prefers_5centscdn_playlist(self):
        stream = import_harpalgeo.extract_episode_stream(SAMPLE_HG_EP26_HTML)
        self.assertIsNotNone(stream)
        self.assertIn("5centscdn.test", stream)
        self.assertTrue(stream.endswith("playlist.m3u8"))

    def test_stream_missing_when_no_m3u8(self):
        self.assertIsNone(import_harpalgeo.extract_episode_stream(SAMPLE_HG_EP_NO_STREAM_HTML))

    def test_episode_title_parse(self):
        info = import_harpalgeo.parse_episode_title(
            SAMPLE_HG_EP26_HTML, "https://harpalgeo.tv/program/Bano/26866")
        self.assertEqual(info["episode"], 26)
        self.assertEqual(info["air_date"], "8th Nov 2026")

    def test_program_data_one_release_per_episode(self):
        episode_pages = {
            "https://harpalgeo.tv/program/Bano/26866": SAMPLE_HG_EP26_HTML,
            "https://harpalgeo.tv/program/Bano/26855": SAMPLE_HG_EP25_HTML,
        }
        data = import_harpalgeo.extract_program_data_harpalgeo(
            "https://harpalgeo.tv/program/Bano", SAMPLE_HG_PROGRAM_HTML, episode_pages)
        self.assertIsNotNone(data)
        self.assertEqual(data["source_site"], "harpalgeo")
        self.assertEqual(data["content_type"], "series")
        self.assertEqual(data["clean_title"], "Bano")
        self.assertEqual(data["language"], "Urdu")
        self.assertEqual(len(data["releases"]), 2)
        eps = sorted(r["episode"] for r in data["releases"])
        self.assertEqual(eps, ["E25", "E26"])
        for rel in data["releases"]:
            self.assertEqual(rel["quality"], "720p")
            src = rel["sources"][0]
            self.assertEqual(src["service"], "5centscdn")
            self.assertEqual(src["playback_type"], "direct")
            self.assertTrue(src["final_url"].endswith("playlist.m3u8"))

    def test_program_with_no_playable_episode_is_none(self):
        episode_pages = {
            "https://harpalgeo.tv/program/Bano/26841": SAMPLE_HG_EP_NO_STREAM_HTML,
        }
        prog_html = SAMPLE_HG_PROGRAM_HTML.replace("26866", "26841").replace("26855", "26842")
        data = import_harpalgeo.extract_program_data_harpalgeo(
            "https://harpalgeo.tv/program/Bano", prog_html, episode_pages)
        self.assertIsNone(data)


class TestHarPalGeoProgramMetadata(unittest.TestCase):
    def test_metadata_from_rich_program_page(self):
        meta = import_harpalgeo.extract_program_metadata(
            SAMPLE_HG_PROGRAM_RICH_HTML, "Bano")
        self.assertTrue(meta["synopsis"].startswith(
            "Bano is a compelling tale of love, sacrifice, family, and betrayal."))
        self.assertNotIn("Production House", meta["synopsis"])
        self.assertEqual(
            meta["cast_names"], "Hina Tariq, Haris Waheed, Aqeel Yousaf")
        self.assertEqual(meta["director"], "Furqan Adam")
        raw = json.loads(meta["raw_meta"])
        self.assertEqual(raw["production_house"], "BJ Production")
        self.assertEqual(raw["producer"], "Babbar Javaid")
        self.assertEqual(raw["writer"], "Atiq Inayat")

    def test_metadata_absent_when_page_has_no_blocks(self):
        meta = import_harpalgeo.extract_program_metadata(
            SAMPLE_HG_PROGRAM_HTML, "Bano")
        self.assertIsNone(meta["synopsis"])
        self.assertIsNone(meta["director"])
        self.assertIsNone(meta["raw_meta"])
        # No cast panel and no title-attribute cast in the plain fixture.
        self.assertIsNone(meta["cast_names"])

    def test_cast_falls_back_to_episode_title_attributes(self):
        page = """
        <html><body>
        <a href="https://harpalgeo.tv/program/Bano/26866"
           title="Bano Episode 26 | Haris Waheed - Hina Tariq - Aqeel Yousaf | HAR PAL GEO">Ep 26</a>
        </body></html>
        """
        meta = import_harpalgeo.extract_program_metadata(page, "Bano")
        self.assertEqual(
            meta["cast_names"], "Haris Waheed, Hina Tariq, Aqeel Yousaf")

    def test_air_date_to_iso(self):
        self.assertEqual(
            import_harpalgeo.air_date_to_iso("8th Nov 2026"), "2026-11-08")
        self.assertEqual(
            import_harpalgeo.air_date_to_iso("1st January 2025"), "2025-01-01")
        self.assertIsNone(import_harpalgeo.air_date_to_iso("soon"))
        self.assertIsNone(import_harpalgeo.air_date_to_iso(None))

    def test_rich_program_data_carries_metadata_and_release_date(self):
        episode_pages = {
            "https://harpalgeo.tv/program/Bano/26866": SAMPLE_HG_EP26_HTML,
            "https://harpalgeo.tv/program/Bano/26855": SAMPLE_HG_EP25_HTML,
        }
        data = import_harpalgeo.extract_program_data_harpalgeo(
            "https://harpalgeo.tv/program/Bano", SAMPLE_HG_PROGRAM_RICH_HTML, episode_pages)
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "Bano")  # from <h1>, proper case
        self.assertEqual(data["year"], 2026)
        self.assertIn("compelling tale of love", data["synopsis"])
        self.assertEqual(
            data["cast_names"], "Hina Tariq, Haris Waheed, Aqeel Yousaf")
        self.assertEqual(data["director"], "Furqan Adam")
        # Earliest episode air date: Ep 25 aired 7th Nov, Ep 26 8th Nov.
        self.assertEqual(data["release_date"], "2026-11-07")
        self.assertIn("BJ Production", data["raw_meta"])
        # Foreign episodes never became releases.
        self.assertEqual(len(data["releases"]), 2)
        self.assertTrue(all("Bano" in r["release_title"] for r in data["releases"]))
        # The source carries no backdrop or duration: NULL, not made up.
        self.assertIsNone(data["backdrop_urls"])
        self.assertIsNone(data["duration"])

    def test_lowercase_slug_title_is_cleaned(self):
        episode_pages = {
            "https://harpalgeo.tv/program/aasmanon-pe-likha/26866": SAMPLE_HG_EP26_HTML,
        }
        prog_html = SAMPLE_HG_PROGRAM_RICH_HTML.replace(
            "https://harpalgeo.tv/program/Bano/26866",
            "https://harpalgeo.tv/program/aasmanon-pe-likha/26866").replace(
            "https://harpalgeo.tv/program/Bano/26855",
            "https://harpalgeo.tv/program/aasmanon-pe-likha/26855").replace(
            "<h1>Bano</h1>", "")
        data = import_harpalgeo.extract_program_data_harpalgeo(
            "https://harpalgeo.tv/program/aasmanon-pe-likha",
            prog_html, episode_pages)
        self.assertIsNotNone(data)
        self.assertEqual(data["clean_title"], "Aasmanon pe Likha")

    def test_rich_program_saves_metadata_to_staging_db(self):
        episode_pages = {
            "https://harpalgeo.tv/program/Bano/26866": SAMPLE_HG_EP26_HTML,
            "https://harpalgeo.tv/program/Bano/26855": SAMPLE_HG_EP25_HTML,
        }
        data = import_harpalgeo.extract_program_data_harpalgeo(
            "https://harpalgeo.tv/program/Bano", SAMPLE_HG_PROGRAM_RICH_HTML, episode_pages)
        self.assertIsNotNone(data)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "harpalgeo.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_harpalgeo.save_post_record(data, db_path)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT synopsis, cast_names, director, release_date,"
                    " raw_meta, duration FROM posts WHERE id = ?", (post_id,)
                ).fetchone()
            self.assertIn("compelling tale", row["synopsis"])
            self.assertIn("Hina Tariq", row["cast_names"])
            self.assertEqual(row["director"], "Furqan Adam")
            self.assertEqual(row["release_date"], "2026-11-07")
            self.assertIn("BJ Production", row["raw_meta"])
            self.assertIsNone(row["duration"])


class TestHarPalGeoSave(unittest.TestCase):
    def test_saved_program_in_staging_db(self):
        episode_pages = {
            "https://harpalgeo.tv/program/Bano/26866": SAMPLE_HG_EP26_HTML,
            "https://harpalgeo.tv/program/Bano/26855": SAMPLE_HG_EP25_HTML,
        }
        data = import_harpalgeo.extract_program_data_harpalgeo(
            "https://harpalgeo.tv/program/Bano", SAMPLE_HG_PROGRAM_HTML, episode_pages)
        self.assertIsNotNone(data)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "harpalgeo.db"
            fast_fetch.DB_PATH = db_path
            fast_fetch.init_db()
            post_id = import_harpalgeo.save_post_record(data, db_path)
            self.assertGreater(post_id, 0)
            with sqlite3.connect(str(db_path)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT source_site, content_type FROM posts WHERE id = ?", (post_id,)
                ).fetchone()
                n_rel = conn.execute("SELECT count(*) FROM releases WHERE post_id = ?", (post_id,)).fetchone()[0]
                n_src = conn.execute("SELECT count(*) FROM download_sources").fetchone()[0]
            self.assertEqual(row["source_site"], "harpalgeo")
            self.assertEqual(row["content_type"], "series")
            self.assertEqual(n_rel, 2)
            self.assertEqual(n_src, 2)


class TestHarPalGeoSync(unittest.TestCase):
    def test_sync_reads_homepage_programs(self):
        client = MagicMock()
        client.base_url = "https://www.harpalgeo.tv"
        client.get_text.return_value = (200, "https://www.harpalgeo.tv/", SAMPLE_HG_HOME_HTML)
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "harpalgeo.db"
            n = import_harpalgeo.sync_programs_to_db(client, db_path)
            self.assertEqual(n, 2)
            with sqlite3.connect(str(db_path)) as conn:
                rows = conn.execute(
                    "SELECT loc, site FROM sitemap_cache WHERE site = 'harpalgeo'"
                ).fetchall()
            self.assertEqual(len(rows), 2)

    def test_sync_unions_homepage_and_programs_index(self):
        """Homepage (Bano, Aik-Thi-Rania) + /programs p1 (Bano, Zombeid) +
        /programs/2 (same two, no new) -> 3 rows, and the loop stops."""
        def _fake_get_text(url, referer=None, timeout=15):
            if url.endswith("/programs/2"):
                return (200, url, SAMPLE_HG_PROGRAMS_P2_HTML)
            if url.endswith("/programs"):
                return (200, url, SAMPLE_HG_PROGRAMS_HTML)
            return (200, url, SAMPLE_HG_HOME_HTML)

        client = MagicMock()
        client.base_url = "https://www.harpalgeo.tv"
        client.get_text.side_effect = _fake_get_text
        with tempfile.TemporaryDirectory() as td:
            db_path = Path(td) / "harpalgeo.db"
            n = import_harpalgeo.sync_programs_to_db(client, db_path)
            self.assertEqual(n, 3)
            with sqlite3.connect(str(db_path)) as conn:
                rows = conn.execute(
                    "SELECT loc, site FROM sitemap_cache WHERE site = 'harpalgeo'"
                ).fetchall()
            self.assertEqual(len(rows), 3)
            locs = [r[0] for r in rows]
            self.assertIn("https://harpalgeo.tv/program/Zombeid", locs)
            self.assertIn("https://harpalgeo.tv/program/Aik-Thi-Rania", locs)
            # The dedup-stop fired at /programs/2: exactly 3 fetches.
            self.assertEqual(client.get_text.call_count, 3)


class TestHarPalGeoRegistry(unittest.TestCase):
    def test_registry_entry_points_at_live_base(self):
        reg_path = Path(__file__).parent / "domain_registry.json"
        reg = json.loads(reg_path.read_text())
        entry = reg["sites"]["harpalgeo"]
        self.assertEqual(entry["active_base_url"], "https://www.harpalgeo.tv")
        self.assertEqual(entry["api_type"], "homepage")
        self.assertEqual(entry["status"], "active")
        self.assertIn("5centscdn", entry.get("notes", ""))


if __name__ == "__main__":
    unittest.main(verbosity=2)
