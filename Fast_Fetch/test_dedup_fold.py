#!/usr/bin/env python3
"""Offline tests for dedup_fold.py — :memory: SQLite only.

Run from the repo root (the gate) or from the fold work directory:
    PYTHONDONTWRITEBYTECODE=1 python3 -S -B test_dedup_fold.py
with PYTHONPATH including the repo's Fast_Fetch (for works,
catalog_v2, catalog_api). No file writes, no network, no live DB.

Coverage: the rule (merges on lang/industry/cast/director/imdb/tmdb;
refuses when tied, on type mismatch, conflicting IDs, flag
disagreement, or an identity-suspect member; different years never
merge), survivor choice, transitive components and transitive
conflicts, the apply (re-pointing, collision-safe merges for
aliases/sites/seasons/episodes/parts/segments, warm-demand summing,
work_key re-keying, blank-metadata fill, roll-ups, journal
idempotency, stale-plan rejection, bridge rebuild/invalidate), the
verification queries, and parity between apply_fold and the
standalone fold.sql.
"""
from __future__ import annotations

import re
import sqlite3
import unittest
from pathlib import Path

import catalog_v2
import dedup_fold
from dedup_fold import (Evidence, Member, apply_fold, pair_evidence,
                        plan_components, verify_fold)

try:
    import analyze_fold  # the analysis script; only in the work dir
except ImportError:  # pragma: no cover - the repo copy has no analysis
    analyze_fold = None

FOLD_SQL = Path(__file__).with_name("dedup_fold.sql")

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


def fresh_db():
    """An in-memory catalog from the real DDL plus the two tables the
    projection does not own (shared_warm_demand, cat_work_languages)."""
    conn = sqlite3.connect(":memory:")
    conn.executescript(catalog_v2.DDL)
    conn.execute(SHARED_WARM_DEMAND_DDL)
    conn.execute(WORK_LANGUAGES_DDL)
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


def add_work(conn, id, title, year, work_type="movie", languages=None,
             industry=None, imdb_id=None, tmdb_id=None, is_18_plus=0,
             identity_suspect=0, release_count=0, site_count=0, **extra):
    values = dict(id=id, work_key="wk:%d" % id, title=title, year=year,
                  work_type=work_type, languages=languages,
                  industry=industry, imdb_id=imdb_id, tmdb_id=tmdb_id,
                  is_18_plus=is_18_plus,
                  identity_suspect=identity_suspect,
                  release_count=release_count, site_count=site_count)
    values.update(extra)
    return insert_row(conn, "cat_works", **values)


def ev(work_type="movie", languages=(), industry=(), cast=(), director=(),
       imdb_id="", tmdb_id="", is_18_plus=0, identity_suspect=0):
    return Evidence(work_type=work_type,
                    languages=frozenset(languages),
                    industry=frozenset(industry),
                    cast=frozenset(cast),
                    director=frozenset(director),
                    imdb_id=imdb_id, tmdb_id=tmdb_id,
                    is_18_plus=is_18_plus,
                    identity_suspect=identity_suspect)


def mem(id, evidence, release_count=0, site_count=0):
    return Member(id, evidence, release_count, site_count)


def dump(conn, table, exclude=()):
    """All rows of a table as sorted tuples, minus excluded columns."""
    cols = [c[1] for c in _columns(conn, table) if c[1] not in exclude]
    rows = conn.execute(
        "SELECT %s FROM %s" % (", ".join(cols), table)).fetchall()
    return sorted(rows)


class TestRule(unittest.TestCase):
    def test_lang_merges(self):
        comps, held, _ = plan_components([
            mem(1, ev(languages={"hindi"}), release_count=5),
            mem(2, ev(languages={"hindi"}), release_count=2),
        ])
        self.assertEqual(len(comps), 1)
        self.assertEqual(comps[0][0].id, 1)
        self.assertEqual([m.id for m in comps[0][1]], [2])
        self.assertEqual(comps[0][2], "lang")
        self.assertEqual(dict(held), {})

    def test_industry_merges(self):
        comps, _held, _ = plan_components([
            mem(1, ev(industry={"bollywood"})),
            mem(2, ev(industry={"bollywood"})),
        ])
        self.assertEqual(comps[0][2], "industry")

    def test_cast_match_is_exact_on_tokens(self):
        # pair_evidence compares normalized tokens exactly; the
        # normalization happens when Evidence is built (person_tokens),
        # not here — so unnormalized spellings do NOT match.
        comps, _held, _ = plan_components([
            mem(1, ev(cast={"shah rukh khan"})),
            mem(2, ev(cast={"Shah Rukh Khan"})),
        ])
        self.assertEqual(comps, [])

    def test_cast_merges_normalized(self):
        comps, _held, _ = plan_components([
            mem(1, ev(cast={"shah rukh khan"})),
            mem(2, ev(cast={"shah rukh khan"})),
        ])
        self.assertEqual(comps[0][2], "cast")

    def test_director_merges(self):
        comps, _held, _ = plan_components([
            mem(1, ev(director={"rajkumar hirani"})),
            mem(2, ev(director={"rajkumar hirani"})),
        ])
        self.assertEqual(comps[0][2], "director")

    def test_imdb_merges(self):
        comps, _held, _ = plan_components([
            mem(1, ev(imdb_id="tt1234567")),
            mem(2, ev(imdb_id="tt1234567")),
        ])
        self.assertEqual(comps[0][2], "imdb")

    def test_tmdb_merges(self):
        comps, _held, _ = plan_components([
            mem(1, ev(tmdb_id=550)),
            mem(2, ev(tmdb_id=550)),
        ])
        self.assertEqual(comps[0][2], "tmdb")

    def test_tied_refuses(self):
        comps, held, _ = plan_components([
            mem(1, ev(languages={"hindi"})),
            mem(2, ev(languages={"tamil"})),
        ])
        self.assertEqual(comps, [])
        self.assertEqual(dict(held), {"tied": 2})

    def test_type_mismatch_refuses(self):
        comps, held, type_pairs = plan_components([
            mem(1, ev("movie", languages={"hindi"})),
            mem(2, ev("series", languages={"hindi"})),
        ])
        self.assertEqual(comps, [])
        self.assertEqual(dict(held), {"type": 2})
        self.assertEqual(dict(type_pairs), {("movie", "series"): 1})

    def test_conflicting_imdb_refuses(self):
        comps, held, _ = plan_components([
            mem(1, ev(languages={"hindi"}, imdb_id="tt1111111")),
            mem(2, ev(languages={"hindi"}, imdb_id="tt2222222")),
        ])
        self.assertEqual(comps, [])
        self.assertEqual(dict(held), {"imdb": 2})

    def test_conflicting_tmdb_refuses(self):
        comps, held, _ = plan_components([
            mem(1, ev(languages={"hindi"}, tmdb_id=11)),
            mem(2, ev(languages={"hindi"}, tmdb_id=22)),
        ])
        self.assertEqual(comps, [])
        self.assertEqual(dict(held), {"tmdb": 2})

    def test_flag_disagreement_refuses(self):
        comps, held, _ = plan_components([
            mem(1, ev(languages={"hindi"}, is_18_plus=0)),
            mem(2, ev(languages={"hindi"}, is_18_plus=1)),
        ])
        self.assertEqual(comps, [])
        self.assertEqual(dict(held), {"flag": 2})

    def test_suspect_refuses(self):
        comps, held, _ = plan_components([
            mem(1, ev(languages={"hindi"})),
            mem(2, ev(languages={"hindi"}, identity_suspect=1)),
        ])
        self.assertEqual(comps, [])
        self.assertEqual(dict(held), {"suspect": 2})

    def test_transitive_component_folds_with_union_rule(self):
        # A-B share lang, B-C share cast, A-C share nothing: one
        # component of three, whose rule is the union of its edges.
        comps, _held, _ = plan_components([
            mem(1, ev(languages={"hindi"}), release_count=9),
            mem(2, ev(languages={"hindi"}, cast={"a b"}),
                release_count=5),
            mem(3, ev(cast={"a b"}), release_count=1),
        ])
        self.assertEqual(len(comps), 1)
        self.assertEqual(comps[0][0].id, 1)
        self.assertEqual(sorted(m.id for m in comps[0][1]), [2, 3])
        self.assertEqual(comps[0][2], "cast+lang")

    def test_transitive_conflict_folds_nothing(self):
        # A-B share lang, B-C share cast, but A-C carry conflicting
        # imdb_ids: the component hides an internal conflict, so
        # nothing folds.
        comps, held, _ = plan_components([
            mem(1, ev(languages={"hindi"}, imdb_id="tt1111111")),
            mem(2, ev(languages={"hindi"}, cast={"a b"})),
            mem(3, ev(cast={"a b"}, imdb_id="tt2222222")),
        ])
        self.assertEqual(comps, [])
        self.assertEqual(dict(held), {"component-imdb": 3})

    def test_survivor_is_richest_then_lowest_id(self):
        comps, _held, _ = plan_components([
            mem(5, ev(languages={"hindi"}), release_count=2,
                site_count=9),
            mem(3, ev(languages={"hindi"}), release_count=2,
                site_count=9),
            mem(9, ev(languages={"hindi"}), release_count=1,
                site_count=99),
        ])
        self.assertEqual(comps[0][0].id, 3)  # tied richness: lowest id
        self.assertEqual(sorted(m.id for m in comps[0][1]), [5, 9])

    def test_pair_evidence_guard_beats_positive(self):
        positive, guard = pair_evidence(
            ev(languages={"hindi"}, imdb_id="tt1111111"),
            ev(languages={"hindi"}, imdb_id="tt2222222"))
        self.assertEqual(positive, frozenset())
        self.assertEqual(guard, "imdb")

    def test_language_tokens_values(self):
        cases = {
            "Hindi": {"hindi"},
            "Hindi + English": {"hindi", "english"},
            '["Hindi", "Tamil"]': {"hindi", "tamil"},
            "tel": {"telugu"},
            "Dual Audio": set(),
            "Original": set(),
            "Unknown": set(),
            None: set(),
            "": set(),
        }
        for value, expected in cases.items():
            self.assertEqual(dedup_fold.language_tokens_for(value),
                             expected, repr(value))

    def test_person_tokens(self):
        self.assertEqual(dedup_fold.person_tokens('["A B", "C D"]'),
                         {"a b", "c d"})
        self.assertEqual(dedup_fold.person_tokens("A B, C D"),
                         {"a b", "c d"})
        self.assertEqual(dedup_fold.person_tokens("[]"), set())
        self.assertEqual(dedup_fold.person_tokens("Unknown"), set())
        self.assertEqual(dedup_fold.person_tokens("  A   B  "),
                         {"a b"})
        self.assertEqual(dedup_fold.person_tokens(None), set())

    def test_industry_tokens(self):
        self.assertEqual(dedup_fold.industry_tokens("Bollywood"),
                         {"bollywood"})
        self.assertEqual(dedup_fold.industry_tokens("Unknown", "Dubbed", ""),
                         set())
        self.assertEqual(dedup_fold.industry_tokens(None), set())

    @unittest.skipIf(analyze_fold is None,
                     "analyze_fold.py not beside this test")
    def test_language_tokens_match_analysis(self):
        # The fallback parser (used only when works.py is
        # unavailable) must agree with the shared normalizer on every
        # value shape that does not need an abbreviation expansion
        # (tel -> telugu lives in works.py only).
        values = ["Hindi", "Hindi + English", '["Hindi", "Tamil"]',
                  "Dual Audio", "Original", "Unknown", "Multi",
                  "Hindi, Tamil", "Punjabi", "Bengali",
                  "[]", None, "", "Hindi+Tamil", "Tamil (Dubbed)"]
        for value in values:
            self.assertEqual(
                dedup_fold.language_tokens_for(value),
                dedup_fold._language_tokens_fallback(value),
                repr(value))

    @unittest.skipIf(analyze_fold is None,
                     "analyze_fold.py not beside this test")
    def test_analysis_uses_the_fold_rule(self):
        # Single source of truth: the analysis imports the rule.
        self.assertIs(analyze_fold.plan_group,
                      dedup_fold.plan_components)


class TestApply(unittest.TestCase):
    def setUp(self):
        self.conn = fresh_db()

    def tearDown(self):
        self.conn.close()

    def test_apply_basic_movie_fold(self):
        c = self.conn
        add_work(c, 1, "Dune", 2021, languages="English",
                 release_count=2, site_count=2)
        add_work(c, 2, "Dune", 2021, languages="English",
                 release_count=1, site_count=1)
        insert_row(c, "cat_releases", work_id=1, resolution="1080p",
                   quality_score=80, streamable_count=1)
        insert_row(c, "cat_releases", work_id=1, resolution="480p",
                   quality_score=40, downloadable_count=1)
        insert_row(c, "cat_releases", work_id=2, resolution="720p",
                   quality_score=60, streamable_count=1)
        insert_row(c, "cat_work_sites", work_id=1, source_site="a",
                   post_id=10, page_url="https://a/1")
        insert_row(c, "cat_work_sites", work_id=2, source_site="b",
                   post_id=20, page_url="https://b/2")
        before = c.execute("SELECT COUNT(*) FROM cat_works").fetchone()[0]
        stats = apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                           bridge="invalidate")
        self.assertEqual(stats["applied"], 1)
        self.assertEqual(stats["survivors"], 1)
        self.assertEqual(c.execute("SELECT COUNT(*) FROM cat_works")
                         .fetchone()[0], before - 1)
        self.assertIsNone(c.execute("SELECT id FROM cat_works WHERE id=2")
                          .fetchone())
        releases = c.execute(
            "SELECT work_id, resolution FROM cat_releases"
            " ORDER BY resolution").fetchall()
        self.assertEqual(releases, [(1, "1080p"), (1, "480p"),
                                    (1, "720p")])
        row = c.execute(
            "SELECT release_count, episode_count, site_count,"
            " max_resolution, best_quality_score, available_stream,"
            " available_download FROM cat_works WHERE id=1").fetchone()
        self.assertEqual(row, (3, 0, 2, "1080p", 80, 1, 1))
        journal = c.execute(
            "SELECT survivor_id, merged_id, rule FROM dedup_fold_journal"
            ).fetchall()
        self.assertEqual(journal, [(1, 2, "lang")])
        id_map = c.execute(
            "SELECT kind, old_id, new_id FROM cat_id_map").fetchall()
        self.assertEqual(id_map, [("work", 2, 1)])
        self.assertEqual(verify_fold(c, [(1, 2, "lang")],
                                     works_before=before), [])

    def test_apply_fills_blank_metadata(self):
        c = self.conn
        add_work(c, 1, "Dune", 2021, synopsis="", poster_url="p1",
                 imdb_id="tt1234567", release_count=2, site_count=2)
        add_work(c, 2, "Dune", 2021, synopsis="Arrakis.",
                 poster_url="p2", imdb_id="tt1234567",
                 release_count=1, site_count=1)
        apply_fold(c, [(1, 2, "imdb")], folded_at=1000.0,
                   bridge="invalidate")
        row = c.execute(
            "SELECT synopsis, poster_url FROM cat_works WHERE id=1"
            ).fetchone()
        self.assertEqual(row, ("Arrakis.", "p1"))  # blank filled, kept kept

    def test_apply_alias_and_site_collisions(self):
        c = self.conn
        add_work(c, 1, "Dune", 2021, release_count=1)
        add_work(c, 2, "Dune", 2021, release_count=1)
        insert_row(c, "cat_work_aliases", work_id=1, alias="Dune",
                   source_site="a")
        insert_row(c, "cat_work_aliases", work_id=2, alias="Dune",
                   source_site="a")  # collides with the survivor's
        insert_row(c, "cat_work_aliases", work_id=2, alias="Dune 2",
                   source_site="b")  # does not collide
        insert_row(c, "cat_work_sites", work_id=1, source_site="a",
                   post_id=10, page_url="https://a/1")
        insert_row(c, "cat_work_sites", work_id=2, source_site="a",
                   post_id=10, page_url="https://a/1")  # collides
        insert_row(c, "cat_work_sites", work_id=2, source_site="b",
                   post_id=20, page_url="https://b/2")
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        self.assertEqual(
            sorted(c.execute("SELECT alias, source_site"
                             " FROM cat_work_aliases").fetchall()),
            [("Dune", "a"), ("Dune 2", "b")])
        self.assertEqual(
            sorted(c.execute("SELECT page_url FROM cat_work_sites")
                   .fetchall()),
            [("https://a/1",), ("https://b/2",)])

    def test_apply_season_episode_merge(self):
        c = self.conn
        add_work(c, 1, "Show", 2020, work_type="series", release_count=2)
        add_work(c, 2, "Show", 2020, work_type="series", release_count=1)
        add_work(c, 3, "Show", 2020, work_type="series", release_count=1)
        s1 = insert_row(c, "cat_seasons", work_id=1, number=1)
        s2 = insert_row(c, "cat_seasons", work_id=1, number=2)
        s3 = insert_row(c, "cat_seasons", work_id=2, number=1)
        s4 = insert_row(c, "cat_seasons", work_id=2, number=3)
        s5 = insert_row(c, "cat_seasons", work_id=3, number=1)
        e1 = insert_row(c, "cat_episodes", work_id=1, season_id=s1,
                        number=1)
        e2 = insert_row(c, "cat_episodes", work_id=1, season_id=s1,
                        number=2)
        e3 = insert_row(c, "cat_episodes", work_id=1, season_id=s2,
                        number=1)
        e4 = insert_row(c, "cat_episodes", work_id=2, season_id=s3,
                        number=2)  # collides with e2
        e5 = insert_row(c, "cat_episodes", work_id=2, season_id=s3,
                        number=3)
        e6 = insert_row(c, "cat_episodes", work_id=2, season_id=s4,
                        number=1)
        e7 = insert_row(c, "cat_episodes", work_id=3, season_id=s5,
                        number=1)  # collides with e1
        r1 = insert_row(c, "cat_releases", work_id=1, resolution="1080p")
        r2 = insert_row(c, "cat_releases", work_id=2, resolution="720p")
        r3 = insert_row(c, "cat_releases", work_id=2, resolution="480p")
        r4 = insert_row(c, "cat_releases", work_id=3, resolution="720p")
        insert_row(c, "cat_release_episodes", release_id=r1, episode_id=e2,
                   ordinal=1)
        insert_row(c, "cat_release_episodes", release_id=r2, episode_id=e4,
                   ordinal=1)
        insert_row(c, "cat_release_episodes", release_id=r3, episode_id=e5,
                   ordinal=1)
        insert_row(c, "cat_release_episodes", release_id=r4, episode_id=e7,
                   ordinal=1)
        apply_fold(c, [(1, 2, "lang"), (1, 3, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        seasons = sorted(c.execute(
            "SELECT number FROM cat_seasons WHERE work_id=1").fetchall())
        self.assertEqual(seasons, [(1,), (2,), (3,)])
        episodes = sorted(c.execute(
            "SELECT s.number, e.number FROM cat_episodes e"
            " JOIN cat_seasons s ON s.id = e.season_id"
            " WHERE e.work_id=1").fetchall())
        self.assertEqual(episodes, [(1, 1), (1, 2), (1, 3), (2, 1),
                                    (3, 1)])
        links = sorted(c.execute(
            "SELECT release_id, episode_id FROM cat_release_episodes"
            ).fetchall())
        self.assertEqual(links, [(r1, e2), (r2, e2), (r3, e5), (r4, e1)])
        self.assertEqual(verify_fold(
            c, [(1, 2, "lang"), (1, 3, "lang")]), [])

    def test_apply_parts_collision(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2020, release_count=1)
        insert_row(c, "cat_parts", work_id=1, number=1)
        insert_row(c, "cat_parts", work_id=2, number=1)  # collides
        insert_row(c, "cat_parts", work_id=2, number=2)
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        self.assertEqual(sorted(c.execute(
            "SELECT number FROM cat_parts WHERE work_id=1").fetchall()),
            [(1,), (2,)])

    def test_apply_segments_collision(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2020, release_count=1)
        insert_row(c, "cat_media_segments", work_id=1, season_number=-1,
                   episode_number=-1, segment_type="intro", start_ms=1000)
        insert_row(c, "cat_media_segments", work_id=2, season_number=-1,
                   episode_number=-1, segment_type="intro", start_ms=2000)
        insert_row(c, "cat_media_segments", work_id=2, season_number=-1,
                   episode_number=-1, segment_type="credits",
                   start_ms=3000)
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        rows = sorted(c.execute(
            "SELECT segment_type, start_ms FROM cat_media_segments"
            " WHERE work_id=1").fetchall())
        self.assertEqual(rows, [("credits", 3000), ("intro", 1000)])

    def test_apply_warm_demand_sums(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2020, release_count=1)
        insert_row(c, "shared_warm_demand", day="2026-11-20", hour=10,
                   work_id=1, band="1080p", cold_presses=5)
        insert_row(c, "shared_warm_demand", day="2026-11-20", hour=10,
                   work_id=2, band="1080p", cold_presses=3)
        insert_row(c, "shared_warm_demand", day="2026-11-20", hour=10,
                   work_id=2, band="480p", cold_presses=7)
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        rows = sorted(c.execute(
            "SELECT day, hour, work_id, band, cold_presses"
            " FROM shared_warm_demand").fetchall())
        self.assertEqual(rows, [("2026-11-20", 10, 1, "1080p", 8),
                                ("2026-11-20", 10, 1, "480p", 7)])

    def test_apply_work_key_tables(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2020, release_count=1)
        insert_row(c, "cat_offsets", work_key="wk:1", season_number=0,
                   episode_number=5, release_label="label-a",
                   offset_seconds=120)
        insert_row(c, "cat_offsets", work_key="wk:2", season_number=0,
                   episode_number=5, release_label="label-a",
                   offset_seconds=130)  # collides: dropped
        insert_row(c, "cat_offsets", work_key="wk:2", season_number=0,
                   episode_number=6, release_label="label-a",
                   offset_seconds=140)  # re-keyed
        insert_row(c, "cat_offset_votes", work_key="wk:1",
                   season_number=0, episode_number=5,
                   release_label="label-a", offset_seconds=120)
        insert_row(c, "cat_offset_votes", work_key="wk:2",
                   season_number=0, episode_number=5,
                   release_label="label-a", offset_seconds=120)  # collides
        insert_row(c, "cat_offset_votes", work_key="wk:2",
                   season_number=0, episode_number=5,
                   release_label="label-a", offset_seconds=130)  # re-keyed
        insert_row(c, "cat_entitlement_rules", work_key="wk:1",
                   release_label="label-a", model="free", region="IN")
        insert_row(c, "cat_entitlement_rules", work_key="wk:2",
                   release_label="label-a", model="premium", region="IN")
        insert_row(c, "cat_review_resolutions", kind="human",
                   work_key="wk:2", signature="sig-1", resolved_at=100.0)
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        self.assertEqual(sorted(c.execute(
            "SELECT episode_number, offset_seconds FROM cat_offsets"
            " WHERE work_key='wk:1'").fetchall()),
            [(5, 120), (6, 140)])
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM cat_offsets WHERE work_key='wk:2'"
            ).fetchone()[0], 0)
        self.assertEqual(sorted(c.execute(
            "SELECT offset_seconds FROM cat_offset_votes"
            " WHERE work_key='wk:1'").fetchall()),
            [(120,), (130,)])
        self.assertEqual(sorted(c.execute(
            "SELECT model FROM cat_entitlement_rules"
            " WHERE work_key='wk:1'").fetchall()),
            [("free",), ("premium",)])
        self.assertEqual(c.execute(
            "SELECT work_key FROM cat_review_resolutions").fetchall(),
            [("wk:1",)])
        self.assertEqual(verify_fold(c, [(1, 2, "lang")]), [])

    def test_apply_idempotent(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2020, release_count=1)
        insert_row(c, "cat_releases", work_id=2, resolution="720p")
        first = apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                           bridge="invalidate")
        second = apply_fold(c, [(1, 2, "lang")], folded_at=2000.0,
                            bridge="invalidate")
        self.assertEqual((first["applied"], first["skipped"]), (1, 0))
        self.assertEqual((second["applied"], second["skipped"]), (0, 1))
        self.assertEqual(c.execute("SELECT COUNT(*) FROM cat_works")
                         .fetchone()[0], 1)
        self.assertEqual(verify_fold(c, [(1, 2, "lang")]), [])

    def test_apply_rejects_stale_pairs(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2021, release_count=1)  # different year
        with self.assertRaises(ValueError):
            apply_fold(c, [(1, 2, "lang")], bridge="invalidate")
        add_work(c, 3, "Film", 2020, work_type="series", release_count=1)
        with self.assertRaises(ValueError):
            apply_fold(c, [(1, 3, "lang")], bridge="invalidate")
        add_work(c, 4, "Film", 2020, is_18_plus=1, release_count=1)
        with self.assertRaises(ValueError):
            apply_fold(c, [(1, 4, "lang")], bridge="invalidate")
        add_work(c, 5, "Film", 2020, identity_suspect=1, release_count=1)
        with self.assertRaises(ValueError):
            apply_fold(c, [(1, 5, "lang")], bridge="invalidate")
        with self.assertRaises(ValueError):  # missing member
            apply_fold(c, [(1, 999, "lang")], bridge="invalidate")
        # Nothing was journaled by the rejected attempts.
        self.assertEqual(c.execute(
            "SELECT COUNT(*) FROM dedup_fold_journal").fetchone()[0], 0)

    def test_apply_journaled_under_different_survivor_raises(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2020, release_count=1)
        add_work(c, 3, "Film", 2020, release_count=1)
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        with self.assertRaises(ValueError):
            apply_fold(c, [(3, 2, "lang")], bridge="invalidate")

    def test_bridge_rebuild_stamps(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, languages="Hindi", release_count=1)
        add_work(c, 2, "Film", 2020, languages="Tamil", release_count=1)
        insert_row(c, "cat_projection_meta", key="projected_at",
                   value="12345.0")
        insert_row(c, "cat_projection_meta", key="language_bridge_at",
                   value="12345.0")
        insert_row(c, "cat_work_languages", work_id=1, language="hindi")
        insert_row(c, "cat_work_languages", work_id=2, language="tamil")
        stats = apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                           bridge="rebuild")
        self.assertGreater(stats["bridge_rows"], 0)
        rows = sorted(c.execute(
            "SELECT work_id, language FROM cat_work_languages").fetchall())
        self.assertEqual(rows, [(1, "hindi")])
        stamp = c.execute(
            "SELECT value FROM cat_projection_meta"
            " WHERE key='language_bridge_at'").fetchone()
        self.assertEqual(stamp, ("12345.0",))

    def test_bridge_invalidate(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, languages="Hindi", release_count=1)
        add_work(c, 2, "Film", 2020, languages="Tamil", release_count=1)
        insert_row(c, "cat_projection_meta", key="projected_at",
                   value="12345.0")
        insert_row(c, "cat_projection_meta", key="language_bridge_at",
                   value="12345.0")
        insert_row(c, "cat_work_languages", work_id=1, language="hindi")
        insert_row(c, "cat_work_languages", work_id=2, language="tamil")
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        rows = c.execute(
            "SELECT work_id, language FROM cat_work_languages").fetchall()
        self.assertEqual(rows, [(1, "hindi")])
        self.assertIsNone(c.execute(
            "SELECT value FROM cat_projection_meta"
            " WHERE key='language_bridge_at'").fetchone())

    def test_verify_catches_orphans(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2020, release_count=1)
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        self.assertEqual(verify_fold(c, [(1, 2, "lang")]), [])
        insert_row(c, "cat_releases", work_id=2, resolution="720p")
        problems = verify_fold(c, [(1, 2, "lang")])
        self.assertTrue(any("cat_releases" in p for p in problems),
                        problems)

    def test_verify_catches_wrong_counts(self):
        c = self.conn
        add_work(c, 1, "Film", 2020, release_count=1)
        add_work(c, 2, "Film", 2020, release_count=1)
        insert_row(c, "cat_releases", work_id=2, resolution="720p")
        apply_fold(c, [(1, 2, "lang")], folded_at=1000.0,
                   bridge="invalidate")
        problems = verify_fold(c, [(1, 2, "lang")], works_before=3)
        self.assertTrue(any("cat_works has" in p for p in problems),
                        problems)

    @unittest.skipIf(not FOLD_SQL.exists(), "fold.sql not written yet")
    def test_fold_sql_parity(self):
        """apply_fold and the standalone fold.sql must produce the
        same end state on the same fixture (bridge='invalidate')."""
        sql = FOLD_SQL.read_text(encoding="utf-8")
        steps = sql.split("-- BEGIN STEPS")[1].split("-- END STEPS")[0]
        pairs = [(1, 2, "lang"), (1, 3, "lang")]

        def build():
            c = fresh_db()
            add_work(c, 1, "Show", 2020, work_type="series",
                     synopsis="", poster_url="p1", release_count=2,
                     site_count=2)
            add_work(c, 2, "Show", 2020, work_type="series",
                     synopsis="A show.", poster_url="p2", release_count=1,
                     site_count=1)
            add_work(c, 3, "Show", 2020, work_type="series",
                     synopsis="", poster_url="p3", release_count=1,
                     site_count=1)
            s1 = insert_row(c, "cat_seasons", work_id=1, number=1)
            s3 = insert_row(c, "cat_seasons", work_id=2, number=1)
            s5 = insert_row(c, "cat_seasons", work_id=3, number=1)
            e1 = insert_row(c, "cat_episodes", work_id=1, season_id=s1,
                            number=1)
            e2 = insert_row(c, "cat_episodes", work_id=1, season_id=s1,
                            number=2)
            e4 = insert_row(c, "cat_episodes", work_id=2, season_id=s3,
                            number=2)
            e7 = insert_row(c, "cat_episodes", work_id=3, season_id=s5,
                            number=1)
            r1 = insert_row(c, "cat_releases", work_id=1,
                            resolution="1080p", quality_score=80,
                            streamable_count=1)
            r2 = insert_row(c, "cat_releases", work_id=2,
                            resolution="720p", quality_score=60)
            r3 = insert_row(c, "cat_releases", work_id=3,
                            resolution="480p", quality_score=40)
            insert_row(c, "cat_release_episodes", release_id=r1,
                       episode_id=e2, ordinal=1)
            insert_row(c, "cat_release_episodes", release_id=r2,
                       episode_id=e4, ordinal=1)
            insert_row(c, "cat_release_episodes", release_id=r3,
                       episode_id=e7, ordinal=1)
            insert_row(c, "cat_work_aliases", work_id=2, alias="Show",
                       source_site="a")
            insert_row(c, "cat_work_sites", work_id=2, source_site="a",
                       post_id=10, page_url="https://a/2")
            insert_row(c, "cat_media_segments", work_id=2,
                       season_number=-1, episode_number=-1,
                       segment_type="intro", start_ms=1000)
            insert_row(c, "shared_warm_demand", day="2026-11-20", hour=10,
                       work_id=2, band="1080p", cold_presses=3)
            insert_row(c, "cat_projection_meta", key="projected_at",
                       value="12345.0")
            insert_row(c, "cat_projection_meta", key="language_bridge_at",
                       value="12345.0")
            insert_row(c, "cat_work_languages", work_id=1,
                       language="hindi")
            insert_row(c, "cat_work_languages", work_id=2,
                       language="tamil")
            return c

        tables = ("cat_works", "cat_releases", "cat_work_sites",
                  "cat_work_aliases", "cat_seasons", "cat_episodes",
                  "cat_parts", "cat_release_episodes", "cat_links",
                  "cat_entitlements", "cat_review_queue",
                  "cat_media_segments", "shared_warm_demand",
                  "cat_work_languages", "cat_id_map",
                  "dedup_fold_journal", "cat_projection_meta",
                  "cat_offsets", "cat_offset_votes",
                  "cat_entitlement_rules", "cat_review_resolutions")

        a = build()
        apply_fold(a, pairs, folded_at=1000.0, bridge="invalidate")
        b = build()
        b.execute(
            "CREATE TEMP TABLE _fold_pairs ("
            " survivor_id INTEGER NOT NULL, member_id INTEGER NOT NULL,"
            " rule TEXT NOT NULL, PRIMARY KEY (member_id))")
        b.executemany(
            "INSERT INTO _fold_pairs (survivor_id, member_id, rule)"
            " VALUES (?, ?, ?)", pairs)
        b.executescript(steps)
        for table in tables:
            exclude = ()
            if table == "cat_works":
                exclude = ("updated_at",)
            if table == "dedup_fold_journal":
                exclude = ("folded_at",)
            self.assertEqual(dump(a, table, exclude),
                             dump(b, table, exclude), table)

        # The VERIFY section must come back clean on the folded DB.
        # Plain COUNT queries return exactly one row with n = 0;
        # every other query (including the GROUP BY duplicate check)
        # returns no rows at all. _fold_pairs is still present: the
        # STEPS section drops it only after VERIFY.
        verify = sql.split("-- BEGIN VERIFY")[1].split("-- END VERIFY")[0]
        checked = 0
        for raw in verify.split(";"):
            stmt = "\n".join(
                ln for ln in raw.splitlines()
                if not ln.strip().startswith("--")).strip()
            if not stmt:
                continue
            self.assertTrue(stmt.upper().startswith("SELECT"),
                            stmt[:60])
            rows = b.execute(stmt).fetchall()
            if "COUNT(*) AS n" in stmt and "GROUP BY" not in stmt:
                self.assertEqual(len(rows), 1, stmt[:60])
                self.assertEqual(rows[0][-1], 0, stmt[:60])
            else:
                self.assertEqual(rows, [], stmt[:60])
            checked += 1
        self.assertGreater(checked, 10, "VERIFY section ran")
        a.close()
        b.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
