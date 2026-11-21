#!/usr/bin/env python3
"""Offline tests for onepiece_identity_fold.sql — :memory: SQLite only.

Run from the repo root (the gate) or from the fold work directory:
    PYTHONDONTWRITEBYTECODE=1 python3 -S -B test_onepiece_identity_fold.py
with PYTHONPATH including the repo's Fast_Fetch (for catalog_v2).
No file writes, no network, no live DB.

Coverage: the step-0 guard (aborts on a stale work count or a stale
post value), Phase A ledger stamps (the nine id-less live-action
posts, post 8523's TMDB fix, the three anime posts' IMDb clear, the
eight year-1999 anime posts' TMDB stamp, and the untouched controls
— post 130993, work 59190), Phase B fold (members gone, survivors'
roll-ups recomputed from the fixture rows, seasons/episodes merged
by number — including 307's flat 157-episode list growing 58940's
season 1 — review rows re-pointed and still OPEN, warm demand
summed, bridge stamp dropped, journal written), the VERIFY section
(clean on the folded fixture), and the re-run (safe no-op).

The fixture carries the LIVE recorded roll-ups on cat_works
(release_count/episode_count as measured 21 Nov 2026) with only a
handful of actual rows behind them: the guard reads the stored
roll-ups, exactly as it does live, and step 10 recomputes them from
the fixture rows. The live expected counts (69654: 264 releases /
18 episodes; 58940: 9595 / 1156) are asserted by the gate's
LIVE-VERIFY block, not by this fixture — see the .sql file.
"""
from __future__ import annotations

import sqlite3
import unittest
from pathlib import Path

import catalog_v2

FOLD_SQL = Path(__file__).with_name("onepiece_identity_fold.sql")

SHARED_WARM_DEMAND_DDL = """
CREATE TABLE IF NOT EXISTS shared_warm_demand (
    day TEXT NOT NULL, hour INTEGER NOT NULL, work_id INTEGER NOT NULL,
    band TEXT NOT NULL, cold_presses INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, hour, work_id, band))
"""

WORK_LANGUAGES_DDL = """
CREATE TABLE IF NOT EXISTS cat_work_languages (
    work_id INTEGER NOT NULL, language TEXT NOT NULL,
    PRIMARY KEY (work_id, language))
"""

# The ledger half the fold touches. Only the columns the .sql file
# reads or writes; the real posts table carries far more.
POSTS_DDL = """
CREATE TABLE IF NOT EXISTS posts (
    id INTEGER PRIMARY KEY, title TEXT, year INTEGER,
    content_type TEXT, is_animated INTEGER,
    imdb_id TEXT, tmdb_id INTEGER, url TEXT)
"""


def fresh_db():
    """An in-memory catalog from the real DDL plus the two tables the
    projection does not own and the minimal ledger table."""
    conn = sqlite3.connect(":memory:")
    conn.executescript(catalog_v2.DDL)
    conn.execute(SHARED_WARM_DEMAND_DDL)
    conn.execute(WORK_LANGUAGES_DDL)
    conn.execute(POSTS_DDL)
    return conn


def _columns(conn, table):
    return conn.execute("PRAGMA table_info(%s)" % table).fetchall()


def insert_row(conn, table, **values):
    """Insert a row, filling unmentioned columns with a type-appropriate
    zero value (or the column default). Returns the rowid."""
    names, params = [], []
    for _cid, name, ctype, _notnull, dflt, pk in _columns(conn, table):
        if name in values:
            names.append(name)
            params.append(values[name])
        elif pk and (ctype or "").upper() == "INTEGER":
            continue  # autoincrement
        elif dflt is not None:
            continue  # let the column default apply
        elif "INT" in (ctype or "").upper():
            names.append(name)
            params.append(0)
        elif any(t in (ctype or "").upper()
                 for t in ("REAL", "FLOA", "DOUB")):
            names.append(name)
            params.append(0.0)
        else:
            names.append(name)
            params.append("")
    conn.execute(
        "INSERT INTO %s (%s) VALUES (%s)"
        % (table, ",".join(names), ",".join("?" * len(names))), params)
    return conn.execute("SELECT last_insert_rowid()").fetchone()[0]


def add_work(conn, id, title, year, work_key, work_type="anime",
             imdb_id=None, tmdb_id=None, identity_suspect=0,
             release_count=0, episode_count=0, site_count=0):
    return insert_row(conn, "cat_works", id=id, work_key=work_key,
                      title=title, year=year, work_type=work_type,
                      imdb_id=imdb_id, tmdb_id=tmdb_id,
                      identity_suspect=identity_suspect,
                      release_count=release_count,
                      episode_count=episode_count, site_count=site_count)


def build():
    """The One Piece cluster, live ids/keys, handfuls of rows."""
    c = fresh_db()
    add_work(c, 69654, "One Piece Live Action", 2026, "tmdb:111110",
             tmdb_id=111110, release_count=114, episode_count=16,
             site_count=1)
    add_work(c, 7344, "One Piece", 2026, "series:one piece:2026",
             work_type="series", identity_suspect=1,
             release_count=17, episode_count=2, site_count=1)
    add_work(c, 7345, "One Piece", 2023, "imdb:tt11737520",
             work_type="series", imdb_id="tt11737520",
             identity_suspect=1, release_count=44, episode_count=0,
             site_count=1)
    add_work(c, 1242, "One Piece (2023)", 2023, "series:one piece:2023",
             identity_suspect=1, release_count=89, episode_count=18,
             site_count=1)
    add_work(c, 58940, "One Piece", 2025, "tmdb:37854",
             tmdb_id=37854, identity_suspect=1,
             release_count=8304, episode_count=1065, site_count=1)
    add_work(c, 307, "One Piece", None, "series:one piece:ny",
             identity_suspect=1, release_count=1291, episode_count=157,
             site_count=1)
    # Control: one print, truncated title — the fold must not touch it.
    add_work(c, 59190, "One Piece Or", None, "series:one piece or:ny",
             identity_suspect=1, release_count=1, episode_count=0,
             site_count=1)

    def season(work_id, number, episode_numbers):
        sid = insert_row(c, "cat_seasons", work_id=work_id, number=number)
        for n in episode_numbers:
            insert_row(c, "cat_episodes", work_id=work_id,
                       season_id=sid, number=n)
        return sid

    season(69654, 1, (1, 2))
    season(69654, 2, (1, 2))
    season(7344, 0, (1, 2))
    season(7344, 2, ())
    season(7345, 0, ())
    season(7345, 1, ())
    season(7345, 2, ())
    season(1242, 0, (1,))
    season(1242, 1, (1, 2))
    season(1242, 2, (1, 2))
    season(58940, 1, (1, 2))
    season(58940, 2, (1,))
    season(307, 1, (1, 2, 3))

    def release(work_id, resolution, quality_score,
                streamable=0, downloadable=0):
        return insert_row(c, "cat_releases", work_id=work_id,
                          resolution=resolution,
                          quality_score=quality_score,
                          streamable_count=streamable,
                          downloadable_count=downloadable)

    release(69654, "1080p", 80, streamable=1)
    release(69654, "720p", 60, downloadable=1)
    release(7344, "1080p", 70)  # the 172681 shape: label only
    release(7345, "720p", 50)
    release(1242, "1080p", 75)
    release(1242, "480p", 40)
    release(58940, "1080p", 90, streamable=1, downloadable=1)
    release(58940, "720p", 65)
    release(307, "720p", 55)
    release(59190, "480p", 30)

    for work_id, site, url in (
            (69654, "multimovies", "https://m/69654"),
            (7344, "hdtvb", "https://h/7344"),
            (7345, "vegamovies", "https://v/7345"),
            (1242, "kisskh", "https://k/1242"),
            (58940, "vegamovies", "https://v/58940"),
            (307, "animahd", "https://a/307"),
            (59190, "x", "https://x/59190")):
        insert_row(c, "cat_work_sites", work_id=work_id,
                   source_site=site, post_id=1, page_url=url)
    for work_id in (69654, 7344, 7345, 1242, 307, 58940):
        insert_row(c, "cat_work_aliases", work_id=work_id,
                   alias="One Piece", source_site="a")
    for work_id, kind in ((7344, "season_unresolved"),
                          (7344, "season_pack_span_unresolved"),
                          (7345, "season_unresolved"),
                          (1242, "season_unresolved"),
                          (307, "season_pack_span_unresolved"),
                          (69654, "season_unresolved"),
                          (58940, "season_pack_span_unresolved")):
        # Explicit NULL: an open row has resolved_at NULL, and the
        # fold's identity_suspect roll-up reads exactly that.
        insert_row(c, "cat_review_queue", work_id=work_id, kind=kind,
                   resolved_at=None)
    insert_row(c, "cat_projection_meta", key="projected_at",
               value="12345.0")
    insert_row(c, "cat_projection_meta", key="language_bridge_at",
               value="12345.0")
    insert_row(c, "cat_work_languages", work_id=69654, language="hindi")
    insert_row(c, "cat_work_languages", work_id=7344, language="tamil")
    insert_row(c, "cat_work_languages", work_id=58940,
               language="japanese")
    insert_row(c, "cat_work_languages", work_id=307, language="hindi")
    insert_row(c, "shared_warm_demand", day="2026-11-20", hour=10,
               work_id=69654, band="1080p", cold_presses=3)
    insert_row(c, "shared_warm_demand", day="2026-11-20", hour=10,
               work_id=7344, band="1080p", cold_presses=2)
    insert_row(c, "shared_warm_demand", day="2026-11-20", hour=11,
               work_id=7345, band="720p", cold_presses=4)
    insert_row(c, "cat_offsets", work_key="series:one piece:2026",
               season_number=2, episode_number=1,
               release_label="S02 pack", offset_seconds=0)
    insert_row(c, "cat_offsets", work_key="tmdb:111110",
               season_number=2, episode_number=1,
               release_label="S02 pack", offset_seconds=0)
    insert_row(c, "cat_offsets", work_key="series:one piece:ny",
               season_number=1, episode_number=3,
               release_label="E03", offset_seconds=0)

    # Ledger posts, pre-state as measured live 21 Nov 2026.
    for pid in (8522, 11439, 100858, 1505, 2046, 11441, 108678, 159116,
                159084):
        # Explicit NULLs: insert_row fills an unmentioned TEXT column
        # with "", and the live ledger stores a missing id as NULL.
        insert_row(c, "posts", id=pid, title="One Piece", year=2026,
                   content_type="series",
                   is_animated=1 if pid in (1505, 2046) else 0,
                   imdb_id=None, tmdb_id=None)
    insert_row(c, "posts", id=8523, title="One Piece S01 (2023)",
               year=2023, content_type="series", is_animated=0,
               imdb_id="tt11737520", tmdb_id=37854)
    for pid in (1551, 61197, 146081):
        insert_row(c, "posts", id=pid, title="One Piece", year=1999,
                   content_type="series", is_animated=1,
                   imdb_id="tt11737520", tmdb_id=37854)
    for pid in (328, 514, 729, 849, 1459, 1756, 146080, 161328):
        insert_row(c, "posts", id=pid, title="One Piece", year=1999,
                   content_type="series", is_animated=1,
                   imdb_id=None, tmdb_id=None)
    # Control: ambiguous post — the fold must NOT touch it.
    insert_row(c, "posts", id=130993,
               title="One Piece Season 2: Into the Grand Line",
               year=2023, content_type="series", is_animated=0,
               imdb_id="tt11737520", tmdb_id=111110)
    insert_row(c, "posts", id=999999, title="Control", year=2020,
               content_type="movie", is_animated=0,
               imdb_id="tt0000000", tmdb_id=123)
    return c


def run_fold(conn):
    """Execute the pairs + steps slice of the .sql file."""
    sql = FOLD_SQL.read_text(encoding="utf-8")
    steps = sql.split("-- BEGIN PAIRS")[1].split("-- END STEPS")[0]
    conn.executescript(steps)


def run_verify(conn):
    """Execute every VERIFY statement; return the problems found.

    COUNT queries always return one row — clean means n = 0 (the last
    column). Every other query returns rows only when something is
    wrong."""
    sql = FOLD_SQL.read_text(encoding="utf-8")
    verify = sql.split("-- BEGIN VERIFY")[1].split("-- END VERIFY")[0]
    problems = []
    for raw in verify.split(";"):
        stmt = "\n".join(
            ln for ln in raw.splitlines()
            if not ln.strip().startswith("--")).strip()
        if not stmt:
            continue
        rows = conn.execute(stmt).fetchall()
        if " as n" in stmt.lower():
            rows = [r for r in rows if r[-1] != 0]
        if rows:
            problems.append((stmt.splitlines()[0][:60], rows))
    return problems


def dump(conn, table, exclude=()):
    cols = [r[1] for r in conn.execute(
        "PRAGMA table_info(%s)" % table).fetchall()
        if r[1] not in exclude]
    rows = conn.execute(
        "SELECT %s FROM %s ORDER BY rowid" % (",".join(cols), table)
        ).fetchall() if cols else []
    return rows


TABLES = ("cat_works", "cat_releases", "cat_work_sites",
          "cat_work_aliases", "cat_seasons", "cat_episodes",
          "cat_parts", "cat_release_episodes", "cat_links",
          "cat_entitlements", "cat_review_queue",
          "cat_media_segments", "shared_warm_demand",
          "cat_work_languages", "cat_id_map",
          "dedup_fold_journal", "cat_projection_meta",
          "cat_offsets", "cat_offset_votes",
          "cat_entitlement_rules", "cat_review_resolutions",
          "posts")


class OnePieceIdentityFoldTests(unittest.TestCase):
    def test_fold_runs_and_verifies_clean(self):
        c = build()
        run_fold(c)
        self.assertEqual(run_verify(c), [])

        # Members gone; survivors carry the recomputed roll-ups.
        gone = c.execute(
            "SELECT COUNT(*) FROM cat_works WHERE id IN "
            "(7344, 7345, 1242, 307)").fetchone()[0]
        self.assertEqual(gone, 0)
        row = c.execute(
            "SELECT title, year, imdb_id, tmdb_id, release_count,"
            " episode_count, site_count, identity_suspect"
            " FROM cat_works WHERE id = 69654").fetchone()
        self.assertEqual(row, ("One Piece Live Action", 2023,
                               "tt11737520", 111110, 6, 6, 4, 1))
        row = c.execute(
            "SELECT title, year, work_key, tmdb_id, release_count,"
            " episode_count, site_count, identity_suspect"
            " FROM cat_works WHERE id = 58940").fetchone()
        self.assertEqual(row, ("One Piece", 2025, "tmdb:37854",
                               37854, 3, 4, 2, 1))
        # Season 1 of the anime grew 2 -> 3: 307's flat list merged
        # by number (1 and 2 collided, 3 is new).
        s1 = c.execute(
            "SELECT COUNT(*) FROM cat_episodes e"
            " JOIN cat_seasons s ON s.id = e.season_id"
            " WHERE s.work_id = 58940 AND s.number = 1").fetchone()[0]
        self.assertEqual(s1, 3)
        # The live-action survivor has S0 2, S1 2, S2 2.
        seasons = c.execute(
            "SELECT s.number, COUNT(e.id) FROM cat_seasons s"
            " LEFT JOIN cat_episodes e ON e.season_id = s.id"
            " WHERE s.work_id = 69654 GROUP BY s.number"
            " ORDER BY s.number").fetchall()
        self.assertEqual(seasons, [(0, 2), (1, 2), (2, 2)])
        # Review rows re-pointed and still OPEN (no fake resolution).
        open_rows = c.execute(
            "SELECT COUNT(*) FROM cat_review_queue"
            " WHERE work_id = 69654 AND resolved_at IS NULL"
            ).fetchone()[0]
        self.assertEqual(open_rows, 5)
        open_rows = c.execute(
            "SELECT COUNT(*) FROM cat_review_queue"
            " WHERE work_id = 58940 AND resolved_at IS NULL"
            ).fetchone()[0]
        self.assertEqual(open_rows, 2)
        # Journal, id map, bridge stamp, warm demand.
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM dedup_fold_journal").fetchone()[0], 4)
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM cat_id_map WHERE kind = 'work'"
            ).fetchone()[0], 4)
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM cat_projection_meta"
            " WHERE key = 'language_bridge_at'").fetchone()[0], 0)
        self.assertEqual(c.execute(
            "SELECT cold_presses FROM shared_warm_demand"
            " WHERE work_id = 69654 AND hour = 10").fetchone()[0], 5)
        self.assertEqual(c.execute(
            "SELECT cold_presses FROM shared_warm_demand"
            " WHERE work_id = 69654 AND hour = 11").fetchone()[0], 4)
        # Offsets: the member row colliding with the survivor's is
        # dropped; the anime member's row is re-keyed.
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM cat_offsets"
            " WHERE work_key = 'tmdb:111110'").fetchone()[0], 1)
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM cat_offsets"
            " WHERE work_key = 'tmdb:37854'").fetchone()[0], 1)
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM cat_offsets"
            " WHERE work_key LIKE 'series:one piece%'").fetchone()[0], 0)
        # Exactly one work titled 'One Piece' remains (the anime).
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM cat_works WHERE title = 'One Piece'"
            ).fetchone()[0], 1)

    def test_phase_a_stamps_the_posts(self):
        c = build()
        run_fold(c)
        for pid in (8522, 11439, 100858, 1505, 2046, 11441, 108678,
                    159116, 159084):
            row = c.execute(
                "SELECT imdb_id, tmdb_id, is_animated FROM posts"
                " WHERE id = ?", (pid,)).fetchone()
            self.assertEqual(row, ("tt11737520", 111110, 0), pid)
        row = c.execute(
            "SELECT imdb_id, tmdb_id FROM posts WHERE id = 8523"
            ).fetchone()
        self.assertEqual(row, ("tt11737520", 111110))
        for pid in (1551, 61197, 146081):
            row = c.execute(
                "SELECT imdb_id, tmdb_id FROM posts WHERE id = ?",
                (pid,)).fetchone()
            self.assertEqual(row, (None, 37854), pid)
        for pid in (328, 514, 729, 849, 1459, 1756, 146080, 161328):
            row = c.execute(
                "SELECT imdb_id, tmdb_id FROM posts WHERE id = ?",
                (pid,)).fetchone()
            self.assertEqual(row, (None, 37854), pid)

    def test_controls_are_untouched(self):
        c = build()
        run_fold(c)
        row = c.execute(
            "SELECT title, release_count, identity_suspect FROM cat_works"
            " WHERE id = 59190").fetchone()
        self.assertEqual(row, ("One Piece Or", 1, 1))
        row = c.execute(
            "SELECT imdb_id, tmdb_id, is_animated FROM posts"
            " WHERE id = 130993").fetchone()
        self.assertEqual(row, ("tt11737520", 111110, 0))
        row = c.execute(
            "SELECT imdb_id, tmdb_id FROM posts WHERE id = 999999"
            ).fetchone()
        self.assertEqual(row, ("tt0000000", 123))

    def test_guard_aborts_on_stale_work_count(self):
        c = build()
        c.execute("UPDATE cat_works SET release_count = 18"
                  " WHERE id = 7344")
        with self.assertRaises(sqlite3.IntegrityError):
            run_fold(c)

    def test_guard_aborts_on_stale_post_value(self):
        c = build()
        c.execute("UPDATE posts SET tmdb_id = 999999 WHERE id = 8523")
        with self.assertRaises(sqlite3.IntegrityError):
            run_fold(c)

    def test_guard_aborts_on_missing_member(self):
        c = build()
        c.execute("DELETE FROM cat_works WHERE id = 307")
        with self.assertRaises(sqlite3.IntegrityError):
            run_fold(c)

    def test_rerun_is_a_no_op(self):
        c = build()
        run_fold(c)
        before = {t: dump(c, t, exclude=("updated_at", "folded_at"))
                  for t in TABLES}
        c.execute("DROP TABLE IF EXISTS _fold_pairs")
        run_fold(c)
        after = {t: dump(c, t, exclude=("updated_at", "folded_at"))
                 for t in TABLES}
        self.assertEqual(before, after)
        self.assertEqual(run_verify(c), [])

    def test_live_verify_markers_carry_the_live_counts(self):
        """The gate's LIVE-VERIFY block must keep the measured live
        counts (69654: 264 releases / 18 episodes; 58940: 9595 /
        1156) — if they drift, the guard's numbers are stale too."""
        sql = FOLD_SQL.read_text(encoding="utf-8")
        live = sql.split("-- BEGIN LIVE-VERIFY")[1].split(
            "-- END LIVE-VERIFY")[0]
        for needle in ("release_count <> 264",
                       "episode_count <> 18",
                       "release_count <> 9595",
                       "episode_count <> 1156"):
            self.assertIn(needle, live)


if __name__ == "__main__":
    unittest.main(verbosity=2)
