#!/usr/bin/env python3
"""Conservative live dedup fold (guided-list item 16, approved 04:42 PKT 20 Nov 2026).

The rule (the owner-approved conservative fold)
----------------------------------------------
Merge works that share an EXACT title + year (case-insensitive,
trimmed) AND share a language token, the same industry token, a cast
name, a director, an imdb_id, or a tmdb_id. Same title+year can be
different movies (the Marco rule: site language/industry first, then
cast/director overlap; still tied = fold NOTHING).

Guards — a pair that trips one NEVER folds, even with positive
evidence:
- different ``work_type`` (a film and a series sharing a title+year
  are different works);
- conflicting non-empty imdb_id / tmdb_id (the safe-identity law:
  refuse conflicting IDs);
- ``is_18_plus`` disagreement (a fold must never hide a mainstream
  work behind a sibling's flag — the flag question belongs to the
  owner-run un-flag pass);
- either member ``identity_suspect=1`` (an unresolved identity review
  — the fold must not resolve it by fiat).

Positive evidence is unioned into components; a component folds only
if EVERY pair inside it passes the guards (a transitive chain that
hides an internal conflict folds nothing). The survivor is the
richest member (most releases, then most sites, then the lowest id).

What the apply does (``apply_fold`` / ``fold.sql``)
--------------------------------------------------
For each (survivor, member, rule) pair, in pair order:
- journal the merge in ``dedup_fold_journal`` (the rollback record:
  the gate backup is the rollback; the journal is the audit trail);
- re-point every child table from the member to the survivor,
  collision-safe (a member row whose unique key already exists under
  the survivor is dropped — the survivor's row wins; seasons and
  episodes merge by number, parts and segments the same way);
- re-key the ``work_key``-keyed durable tables (offsets, offset votes,
  entitlement rules, review resolutions) to the survivor's key;
- record the member -> survivor mapping in ``cat_id_map``
  (kind ``'work'`` — traceability only; no current reader consults
  it, the API resolves only kind ``'ledger_release'``);
- fill the survivor's blank metadata fields from the members
  (richest first, never overwriting a non-blank value);
- recompute the survivor's roll-ups with the projection's own
  formulas (release/episode/site counts, max resolution, best
  quality score, available stream/download, identity_suspect);
- delete the merged member rows from ``cat_works``;
- refresh the language bridge (``catalog_api.rebuild_language_bridge``,
  the projection's own rebuild) — or, when ``bridge="invalidate"``
  (the ``fold.sql`` path, which cannot call Python), delete the
  merged members' bridge rows and delete the ``language_bridge_at``
  stamp so browse falls back to the correct EXISTS path until the
  next projection rebuild.

Idempotency: a pair whose merged member is already journaled with the
same survivor is skipped; a pair whose member no longer exists but is
journaled is skipped; anything else inconsistent raises instead of
silently skipping (a stale plan must not apply).

Never run this against the live database. The gate applies it to a
staging copy (``sqlite3 <live.db> ".backup <copy.db>"`` or VACUUM
INTO), runs ``verify_fold``, and only then swaps.

Stdlib only, plus ``works`` (same directory) for language tokens.
"""
from __future__ import annotations

import json
import re
import sqlite3
import time
from collections import Counter, defaultdict
from pathlib import Path

try:
    import works as _works
except ImportError:  # pragma: no cover - works.py ships beside this module
    _works = None

FOLD_RULE = "dedup-fold-20261120"

FOLD_JOURNAL_DDL = """
CREATE TABLE IF NOT EXISTS dedup_fold_journal (
    survivor_id INTEGER NOT NULL,
    merged_id INTEGER NOT NULL,
    survivor_work_key TEXT NOT NULL,
    merged_work_key TEXT NOT NULL,
    title TEXT NOT NULL,
    year INTEGER,
    rule TEXT NOT NULL,
    folded_at REAL NOT NULL,
    PRIMARY KEY (merged_id)
)
"""

#: Industry tokens that are filing noise, not an industry.
_INDUSTRY_NOISE = frozenset({"", "unknown", "dubbed", "n/a", "none", "null"})
#: Person-name tokens that are filing noise, not a name.
_PERSON_NOISE = frozenset({"", "unknown", "[]", "n/a", "none", "null"})
_WS_RE = re.compile(r"\s+")


def industry_tokens(*values):
    """Canonical industry tokens from stored values (usually one)."""
    out = set()
    for value in values:
        if value is None:
            continue
        token = str(value).strip().casefold()
        if token and token not in _INDUSTRY_NOISE:
            out.add(token)
    return out


def person_tokens(value):
    """Normalized person names from a cast/director value.

    Accepts a JSON array (``["A", "B"]``), a comma list, or a plain
    name. ``[]`` yields nothing. Matching is exact on the normalized
    full name — no fuzzy matching, so a shared token is real evidence.
    """
    if value is None:
        return set()
    text = str(value).strip()
    if not text or text == "[]":
        return set()
    if text.startswith("["):
        try:
            parsed = json.loads(text)
        except (json.JSONDecodeError, ValueError):
            parsed = None
        if isinstance(parsed, list):
            items = [str(item) for item in parsed]
        else:
            items = [text]
    else:
        items = text.split(",")
    out = set()
    for item in items:
        token = _WS_RE.sub(" ", item.strip()).casefold()
        if token and token not in _PERSON_NOISE:
            out.add(token)
    return out


#: Noise tokens the fallback language parser drops (the same list
#: works.py uses). The fallback cannot expand abbreviations
#: (tel -> telugu) — that expansion lives in works.py only.
_FALLBACK_LANG_NOISE = frozenset({
    "unknown", "none", "null", "n/a", "other", "-", "[]", "",
    "original", "audio", "org", "fan", "dubbed", "voice", "over",
    "dual", "multi", "esub"})


def _language_tokens_fallback(*values):
    """Split on non-letters and drop noise (works.py unavailable)."""
    out = set()
    for value in values:
        if value is None:
            continue
        for piece in re.split(r"[^a-z]+", str(value).lower()):
            if piece and piece not in _FALLBACK_LANG_NOISE:
                out.add(piece)
    return out


def language_tokens_for(*values):
    """Canonical language tokens via the shared normalizer."""
    if _works is not None:
        out = set()
        for value in values:
            out.update(_works.language_tokens(value))
        return out
    return _language_tokens_fallback(*values)


class Evidence:
    """The per-work signals the fold rule reads."""

    __slots__ = ("work_type", "languages", "industry", "cast", "director",
                 "imdb_id", "tmdb_id", "is_18_plus", "identity_suspect")

    def __init__(self, *, work_type="", languages=frozenset(),
                 industry=frozenset(), cast=frozenset(), director=frozenset(),
                 imdb_id="", tmdb_id="", is_18_plus=0, identity_suspect=0):
        self.work_type = work_type or ""
        self.languages = frozenset(languages)
        self.industry = frozenset(industry)
        self.cast = frozenset(cast)
        self.director = frozenset(director)
        self.imdb_id = imdb_id or ""
        self.tmdb_id = tmdb_id or ""
        self.is_18_plus = 1 if is_18_plus else 0
        self.identity_suspect = 1 if identity_suspect else 0


class Member:
    """One candidate work: id, evidence, and richness for survivor choice."""

    __slots__ = ("id", "evidence", "release_count", "site_count")

    def __init__(self, id, evidence, release_count=0, site_count=0):
        self.id = id
        self.evidence = evidence
        self.release_count = release_count
        self.site_count = site_count


def pair_evidence(a: Evidence, b: Evidence):
    """(positive_rules, guard) for one pair of group members.

    ``positive_rules`` names the evidence kinds that say "same film"
    (empty = tied). ``guard`` names the blocking reason, or None when
    the pair may fold on positive evidence.
    """
    if (a.work_type or "") != (b.work_type or ""):
        return frozenset(), "type"
    if a.imdb_id and b.imdb_id and a.imdb_id != b.imdb_id:
        return frozenset(), "imdb"
    if a.tmdb_id and b.tmdb_id and a.tmdb_id != b.tmdb_id:
        return frozenset(), "tmdb"
    if bool(a.is_18_plus) != bool(b.is_18_plus):
        return frozenset(), "flag"
    if a.identity_suspect or b.identity_suspect:
        return frozenset(), "suspect"
    positive = set()
    if a.languages & b.languages:
        positive.add("lang")
    if a.industry & b.industry:
        positive.add("industry")
    if a.cast & b.cast:
        positive.add("cast")
    if a.director & b.director:
        positive.add("director")
    if a.imdb_id and a.imdb_id == b.imdb_id:
        positive.add("imdb")
    if a.tmdb_id and a.tmdb_id == b.tmdb_id:
        positive.add("tmdb")
    return frozenset(positive), None


def plan_components(members):
    """Fold plan for one title+year group.

    Returns (components, held, type_pairs): ``components`` is a list of
    (survivor, [merged members], rule) for foldable components;
    ``held`` maps a hold reason to the member count it held back;
    ``type_pairs`` counts the work_type pairs blocked by the type guard.
    """
    n = len(members)
    parent = list(range(n))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x, y):
        rx, ry = find(x), find(y)
        if rx != ry:
            parent[ry] = rx

    pair_rule = {}
    pair_guard = {}
    type_pairs = Counter()
    for i in range(n):
        for j in range(i + 1, n):
            positive, guard = pair_evidence(members[i].evidence,
                                            members[j].evidence)
            pair_guard[(i, j)] = guard
            if guard == "type":
                pair = tuple(sorted(
                    (members[i].evidence.work_type or "",
                     members[j].evidence.work_type or "")))
                type_pairs[pair] += 1
            if guard is None and positive:
                pair_rule[(i, j)] = "+".join(sorted(positive))
                union(i, j)
    groups = defaultdict(list)
    for i in range(n):
        groups[find(i)].append(i)
    components = []
    held = Counter()
    held_component_ids = set()
    for idxs in groups.values():
        if len(idxs) < 2:
            continue
        conflict = None
        for a in range(len(idxs)):
            for b in range(a + 1, len(idxs)):
                guard = pair_guard.get((idxs[a], idxs[b]))
                if guard is not None:
                    conflict = guard
                    break
            if conflict:
                break
        if conflict:
            held["component-" + conflict] += len(idxs)
            held_component_ids.update(members[i].id for i in idxs)
            continue
        ordered = sorted(
            (members[i] for i in idxs),
            key=lambda m: (-m.release_count, -m.site_count, m.id),
        )
        survivor = ordered[0]
        merged = ordered[1:]
        # The component's rule is the union of ALL its positive edges,
        # not just the survivor's direct edges: a member that joined
        # transitively (via a third member) has no direct edge to the
        # survivor.
        rules = set()
        for a in range(len(idxs)):
            for b in range(a + 1, len(idxs)):
                key = (min(idxs[a], idxs[b]), max(idxs[a], idxs[b]))
                if key in pair_rule:
                    rules.add(pair_rule[key])
        components.append((survivor, merged, "+".join(sorted(rules))))
    folded_ids = {m.id for _s, merged, _r in components for m in merged}
    folded_ids.update(s.id for s, _m, _r in components)
    for i, m in enumerate(members):
        if m.id in folded_ids or m.id in held_component_ids:
            continue
        reasons = set()
        for j, _other in enumerate(members):
            if i == j:
                continue
            guard = pair_guard.get((min(i, j), max(i, j)))
            if guard is not None:
                reasons.add(guard)
        if not reasons:
            held["tied"] += 1
        else:
            for reason in ("type", "imdb", "tmdb", "flag", "suspect"):
                if reason in reasons:
                    held[reason] += 1
                    break
    return components, held, type_pairs


# ── apply ───────────────────────────────────────────────────────────────────

#: Metadata fields filled on the survivor from its merged members,
#: richest first, blank-only (never overwriting a non-blank value).
_FILL_FIELDS = ("original_title", "synopsis", "genres", "languages",
                "country", "industry", "imdb_rating", "imdb_id", "tmdb_id",
                "tvdb_id", "runtime_minutes", "poster_url", "backdrop_urls")

#: Child tables re-pointed with a plain UPDATE (no UNIQUE key on
#: work_id can collide: releases keep their ids).
_PLAIN_REPOINT = ("cat_releases", "cat_links", "cat_entitlements",
                  "cat_review_queue")

#: Child tables re-pointed collision-safe (a member row whose unique
#: key already exists under the survivor is dropped): table ->
#: unique-key columns besides work_id.
_COLLISION_REPOINT = {
    "cat_work_aliases": ("alias", "source_site"),
    "cat_work_sites": ("page_url",),
    "cat_media_segments": ("season_number", "episode_number", "segment_type"),
}

#: work_key-keyed durable tables re-keyed to the survivor's key:
#: table -> the PRIMARY KEY columns besides work_key (from
#: catalog_v2.DDL — cat_offsets has no signature column; the key is
#: season/episode/release_label).
_KEYED_TABLES = {
    "cat_offsets": ("season_number", "episode_number", "release_label"),
    "cat_offset_votes": ("season_number", "episode_number",
                         "release_label", "offset_seconds"),
    "cat_entitlement_rules": ("release_label", "model", "region"),
    "cat_review_resolutions": ("kind", "signature"),
}

#: Tables the fold may touch; a missing one (older catalogs predate
#: cat_media_segments) is skipped, never created.
_KNOWN_TABLES = (
    ("cat_works",) + _PLAIN_REPOINT + tuple(_COLLISION_REPOINT)
    + ("cat_seasons", "cat_episodes", "cat_parts", "cat_release_episodes",
       "shared_warm_demand", "cat_work_languages", "cat_id_map")
    + tuple(_KEYED_TABLES)
)


def _tables(conn):
    return {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type = 'table'")}


def _columns(conn, table):
    return {r[1] for r in conn.execute("PRAGMA table_info(%s)" % table)}


def _norm_title(title):
    return (title or "").strip().casefold()


def _validate_pairs(conn, pairs):
    """Raise on the first pair the current data contradicts.

    A stale plan must not apply: the title+year must still match
    exactly, the types must still agree, the flags must still agree,
    and neither member may have become identity-suspect.
    """
    for survivor_id, member_id, rule in pairs:
        rows = conn.execute(
            "SELECT id, title, year, work_type, is_18_plus,"
            " identity_suspect FROM cat_works WHERE id IN (?, ?)",
            (survivor_id, member_id)).fetchall()
        by_id = {r[0]: r for r in rows}
        if survivor_id not in by_id or member_id not in by_id:
            raise ValueError(
                "fold pair (%d -> %d): work row missing"
                % (member_id, survivor_id))
        s, m = by_id[survivor_id], by_id[member_id]
        if _norm_title(s[1]) != _norm_title(m[1]) or s[2] != m[2]:
            raise ValueError(
                "fold pair (%d -> %d): title+year no longer match"
                % (member_id, survivor_id))
        if (s[3] or "") != (m[3] or ""):
            raise ValueError(
                "fold pair (%d -> %d): work_type no longer matches"
                % (member_id, survivor_id))
        if bool(s[4]) != bool(m[4]):
            raise ValueError(
                "fold pair (%d -> %d): is_18_plus no longer agrees"
                % (member_id, survivor_id))
        if s[5] or m[5]:
            raise ValueError(
                "fold pair (%d -> %d): member is now identity_suspect"
                % (member_id, survivor_id))


def _journal(conn, pairs, folded_at):
    """Insert journal rows for pairs not yet applied; return the map.

    The caller has already partitioned out pairs whose merged member
    is journaled (a re-run skips those); every pair here is new, and
    both works must still exist (checked by ``_validate_pairs``).
    """
    conn.execute(FOLD_JOURNAL_DDL)
    to_apply = {}
    for survivor_id, member_id, rule in pairs:
        s = conn.execute(
            "SELECT work_key, title, year FROM cat_works WHERE id = ?",
            (survivor_id,)).fetchone()
        m = conn.execute(
            "SELECT work_key, title, year FROM cat_works WHERE id = ?",
            (member_id,)).fetchone()
        if s is None or m is None:
            raise ValueError(
                "fold pair (%d -> %d): work row missing"
                % (member_id, survivor_id))
        conn.execute(
            "INSERT INTO dedup_fold_journal (survivor_id, merged_id,"
            " survivor_work_key, merged_work_key, title, year, rule,"
            " folded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (survivor_id, member_id, s[0], m[0], s[1], s[2], rule,
             folded_at))
        to_apply[member_id] = survivor_id
    return to_apply


def _repoint_plain(conn, present, to_apply):
    for table in _PLAIN_REPOINT:
        if table not in present:
            continue
        conn.execute(
            "UPDATE %s SET work_id = (" % table
            + "SELECT survivor_id FROM _fold_pairs"
            " WHERE member_id = %s.work_id) " % table
            + "WHERE work_id IN (SELECT member_id FROM _fold_pairs)")


def _repoint_collision_safe(conn, present, to_apply):
    for table, key_cols in _COLLISION_REPOINT.items():
        if table not in present:
            continue
        key_sql = " AND ".join(
            "s.%s IS t.%s" % (c, c) for c in key_cols)
        # Drop member rows whose key the survivor already has.
        conn.execute(
            "DELETE FROM %(table)s AS t WHERE EXISTS ("
            "SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id"
            " AND EXISTS (SELECT 1 FROM %(table)s s"
            " WHERE s.work_id = p.survivor_id AND %(key_sql)s))"
            % {"table": table, "key_sql": key_sql})
        # Drop member rows whose key another member folding into the
        # same survivor already has (keep the lowest rowid); without
        # this the UPDATE below would violate the UNIQUE key.
        conn.execute(
            "DELETE FROM %(table)s AS t WHERE EXISTS ("
            "SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id"
            " AND EXISTS (SELECT 1 FROM %(table)s s"
            " JOIN _fold_pairs p2 ON p2.member_id = s.work_id"
            " AND p2.survivor_id = p.survivor_id"
            " WHERE %(key_sql)s AND s.rowid < t.rowid))"
            % {"table": table, "key_sql": key_sql})
        conn.execute(
            "UPDATE %(table)s SET work_id = ("
            "SELECT survivor_id FROM _fold_pairs"
            " WHERE member_id = %(table)s.work_id) "
            "WHERE work_id IN (SELECT member_id FROM _fold_pairs)"
            % {"table": table})


def _merge_seasons_episodes(conn, present, to_apply):
    """Merge seasons/episodes by number into the survivor.

    Canonical target per (survivor, number, season_type): the
    survivor's own season if it has one, else the lowest-id member
    season with that key. Episodes merge by number inside the target
    season; a release that already points at the kept episode keeps
    its row, the duplicate episode's row is dropped.
    """
    if "cat_seasons" not in present:
        return
    conn.execute(
        "CREATE TEMP TABLE _fold_season_map AS "
        "SELECT s.id AS old_season_id, "
        "COALESCE(surv.id, first_member.id) AS new_season_id "
        "FROM cat_seasons s "
        "JOIN _fold_pairs p ON p.member_id = s.work_id "
        "LEFT JOIN cat_seasons surv ON surv.work_id = p.survivor_id "
        " AND surv.number = s.number AND surv.season_type = s.season_type "
        "LEFT JOIN (SELECT p2.survivor_id, s2.number, s2.season_type,"
        " MIN(s2.id) AS id FROM cat_seasons s2 "
        " JOIN _fold_pairs p2 ON p2.member_id = s2.work_id "
        " GROUP BY p2.survivor_id, s2.number, s2.season_type) first_member "
        " ON first_member.survivor_id = p.survivor_id "
        " AND first_member.number = s.number "
        " AND first_member.season_type = s.season_type "
        "WHERE surv.id IS NOT NULL OR first_member.id <> s.id")
    if "cat_episodes" in present:
        conn.execute(
            "CREATE TEMP TABLE _fold_episode_map AS "
            "SELECT e.id AS old_episode_id, e2.id AS new_episode_id "
            "FROM cat_episodes e "
            "JOIN _fold_season_map m ON m.old_season_id = e.season_id "
            "JOIN cat_episodes e2 ON e2.season_id = m.new_season_id "
            " AND e2.number = e.number")
        if "cat_release_episodes" in present:
            conn.execute(
                "DELETE FROM cat_release_episodes WHERE episode_id IN ("
                "SELECT old_episode_id FROM _fold_episode_map) "
                "AND EXISTS (SELECT 1 FROM cat_release_episodes r2 "
                "WHERE r2.release_id = cat_release_episodes.release_id "
                "AND r2.episode_id = (SELECT new_episode_id "
                "FROM _fold_episode_map "
                "WHERE old_episode_id = cat_release_episodes.episode_id))")
            conn.execute(
                "UPDATE cat_release_episodes SET episode_id = ("
                "SELECT new_episode_id FROM _fold_episode_map "
                "WHERE old_episode_id = cat_release_episodes.episode_id) "
                "WHERE episode_id IN ("
                "SELECT old_episode_id FROM _fold_episode_map)")
        conn.execute(
            "DELETE FROM cat_episodes WHERE id IN ("
            "SELECT old_episode_id FROM _fold_episode_map)")
        conn.execute(
            "UPDATE cat_episodes SET season_id = ("
            "SELECT new_season_id FROM _fold_season_map "
            "WHERE old_season_id = cat_episodes.season_id) "
            "WHERE season_id IN (SELECT old_season_id FROM _fold_season_map)")
        # Episodes also carry work_id (the rollup counts them by it),
        # and the season re-point above does not touch it.
        conn.execute(
            "UPDATE cat_episodes SET work_id = ("
            "SELECT survivor_id FROM _fold_pairs "
            "WHERE member_id = cat_episodes.work_id) "
            "WHERE work_id IN (SELECT member_id FROM _fold_pairs)")
        conn.execute("DROP TABLE _fold_episode_map")
    conn.execute(
        "DELETE FROM cat_seasons WHERE id IN ("
        "SELECT old_season_id FROM _fold_season_map)")
    conn.execute(
        "UPDATE cat_seasons SET work_id = ("
        "SELECT survivor_id FROM _fold_pairs "
        "WHERE member_id = cat_seasons.work_id) "
        "WHERE work_id IN (SELECT member_id FROM _fold_pairs)")
    conn.execute("DROP TABLE _fold_season_map")


def _merge_parts(conn, present, to_apply):
    if "cat_parts" not in present:
        return
    conn.execute(
        "DELETE FROM cat_parts AS t WHERE EXISTS ("
        "SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id "
        "AND EXISTS (SELECT 1 FROM cat_parts s "
        "WHERE s.work_id = p.survivor_id AND s.number = t.number))")
    conn.execute(
        "DELETE FROM cat_parts AS t WHERE EXISTS ("
        "SELECT 1 FROM _fold_pairs p WHERE p.member_id = t.work_id "
        "AND EXISTS (SELECT 1 FROM cat_parts s "
        "JOIN _fold_pairs p2 ON p2.member_id = s.work_id "
        "AND p2.survivor_id = p.survivor_id "
        "WHERE s.number = t.number AND s.id < t.id))")
    conn.execute(
        "UPDATE cat_parts SET work_id = ("
        "SELECT survivor_id FROM _fold_pairs "
        "WHERE member_id = cat_parts.work_id) "
        "WHERE work_id IN (SELECT member_id FROM _fold_pairs)")


def _merge_warm_demand(conn, present, to_apply):
    if "shared_warm_demand" not in present:
        return
    # UPDATE first: add the members' presses to the survivor rows
    # that already exist. Only then INSERT the (day, hour, band)
    # combos the survivor lacks — the other order double-counts,
    # because the UPDATE would add the member presses to the row the
    # INSERT had just created from those same presses.
    conn.execute(
        "UPDATE shared_warm_demand AS d SET cold_presses = d.cold_presses"
        " + (SELECT COALESCE(SUM(m.cold_presses), 0)"
        " FROM shared_warm_demand m JOIN _fold_pairs p"
        " ON p.member_id = m.work_id"
        " WHERE m.day = d.day AND m.hour = d.hour AND m.band = d.band"
        " AND p.survivor_id = d.work_id)"
        " WHERE EXISTS (SELECT 1 FROM shared_warm_demand m"
        " JOIN _fold_pairs p ON p.member_id = m.work_id"
        " WHERE m.day = d.day AND m.hour = d.hour AND m.band = d.band"
        " AND p.survivor_id = d.work_id)")
    conn.execute(
        "INSERT OR IGNORE INTO shared_warm_demand"
        " (day, hour, work_id, band, cold_presses) "
        "SELECT d.day, d.hour, p.survivor_id, d.band, d.cold_presses "
        "FROM shared_warm_demand d "
        "JOIN _fold_pairs p ON p.member_id = d.work_id")
    conn.execute(
        "DELETE FROM shared_warm_demand "
        "WHERE work_id IN (SELECT member_id FROM _fold_pairs)")


def _rekey_work_key_tables(conn, present, to_apply):
    """Re-key the work_key-keyed durable tables to the survivor's key.

    A member row whose key already exists under the survivor's key is
    dropped (the survivor's row wins); the rest are re-keyed. All four
    tables are empty in the live catalog today — this is defensive,
    and covered by tests so it stays correct if they ever fill.
    """
    for table, key_cols in _KEYED_TABLES.items():
        if table not in present:
            continue
        key_sql = " AND ".join(
            "s.%s IS t.%s" % (c, c) for c in key_cols)
        conn.execute(
            "DELETE FROM %(table)s AS t WHERE EXISTS ("
            "SELECT 1 FROM _fold_pairs p "
            "JOIN cat_works mw ON mw.id = p.member_id "
            "JOIN cat_works sw ON sw.id = p.survivor_id "
            "WHERE t.work_key = mw.work_key "
            "AND EXISTS (SELECT 1 FROM %(table)s s "
            "WHERE s.work_key = sw.work_key AND %(key_sql)s))"
            % {"table": table, "key_sql": key_sql})
        conn.execute(
            "UPDATE %(table)s SET work_key = ("
            "SELECT sw.work_key FROM _fold_pairs p "
            "JOIN cat_works mw ON mw.id = p.member_id "
            "JOIN cat_works sw ON sw.id = p.survivor_id "
            "WHERE mw.work_key = %(table)s.work_key) "
            "WHERE work_key IN (SELECT mw.work_key FROM _fold_pairs p "
            "JOIN cat_works mw ON mw.id = p.member_id)"
            % {"table": table})


def _fill_and_rollup(conn, present, survivors, members_by_survivor,
                     folded_at):
    """Fill survivor blanks from members, then recompute roll-ups."""
    for survivor_id in survivors:
        members = members_by_survivor[survivor_id]
        ranked = sorted(
            members,
            key=lambda r: (-r["release_count"], -r["site_count"], r["id"]))
        survivor = conn.execute(
            "SELECT * FROM cat_works WHERE id = ?", (survivor_id,)).fetchone()
        cols = [d[0] for d in conn.execute(
            "SELECT * FROM cat_works WHERE 1 = 0").description]
        row = dict(zip(cols, survivor))
        updates = {}
        for field in _FILL_FIELDS:
            if field not in cols:
                continue
            if row.get(field) not in (None, "", "Unknown"):
                continue
            for m in ranked:
                value = m.get(field)
                if value not in (None, "", "Unknown"):
                    updates[field] = value
                    break
        if row.get("is_animated") in (None, 0):
            for m in ranked:
                if m.get("is_animated"):
                    updates["is_animated"] = 1
                    break
        if updates:
            sets = ", ".join("%s = ?" % f for f in updates)
            conn.execute(
                "UPDATE cat_works SET %s WHERE id = ?" % sets,
                tuple(updates.values()) + (survivor_id,))
        conn.execute(
            """
            UPDATE cat_works SET
                release_count = (SELECT COUNT(*) FROM cat_releases r
                                  WHERE r.work_id = cat_works.id),
                episode_count = (SELECT COUNT(*) FROM cat_episodes e
                                  WHERE e.work_id = cat_works.id),
                site_count = (SELECT COUNT(DISTINCT s.source_site)
                               FROM cat_work_sites s
                               WHERE s.work_id = cat_works.id),
                max_resolution = (
                    SELECT r.resolution FROM cat_releases r
                     WHERE r.work_id = cat_works.id
                       AND r.resolution <> 'unknown'
                     ORDER BY r.quality_score DESC, r.print_version DESC,
                              r.id DESC LIMIT 1),
                best_quality_score = COALESCE(
                    (SELECT MAX(r.quality_score) FROM cat_releases r
                      WHERE r.work_id = cat_works.id), 0),
                available_stream = COALESCE((
                    SELECT 1 FROM cat_releases r WHERE r.work_id = cat_works.id
                     AND r.streamable_count > 0), 0),
                available_download = COALESCE((
                    SELECT 1 FROM cat_releases r WHERE r.work_id = cat_works.id
                     AND r.downloadable_count > 0), 0),
                identity_suspect = COALESCE((
                    SELECT 1 FROM cat_review_queue q
                     WHERE q.work_id = cat_works.id
                       AND q.resolved_at IS NULL), 0),
                updated_at = ?
             WHERE id = ?
            """,
            (folded_at, survivor_id))


def apply_fold(conn, pairs, *, folded_at=None, bridge="rebuild"):
    """Apply (survivor_id, member_id, rule) pairs to ``conn``.

    ``bridge``: ``"rebuild"`` (default) refreshes the language bridge
    with ``catalog_api.rebuild_language_bridge``; ``"invalidate"``
    deletes the merged members' bridge rows and drops the
    ``language_bridge_at`` stamp so browse falls back to the correct
    EXISTS path (the ``fold.sql`` path). Runs in one transaction;
    raises on the first inconsistency. Returns a stats dict.
    """
    if folded_at is None:
        folded_at = time.time()
    pairs = [(int(s), int(m), str(r)) for s, m, r in pairs]
    if not pairs:
        return {"applied": 0, "skipped": 0, "survivors": 0}
    if len({m for _s, m, _r in pairs}) != len(pairs):
        raise ValueError("fold pairs list a member twice")
    present = _tables(conn)
    if "cat_works" not in present:
        raise ValueError("cat_works table missing")
    stats = {"applied": 0, "skipped": 0, "survivors": 0,
             "bridge": bridge}
    with conn:
        # Partition first: a pair whose member is already journaled
        # was applied by an earlier run (its member row is gone, so
        # validation would wrongly reject it). A journaled member
        # under a DIFFERENT survivor is a plan contradiction: raise.
        conn.execute(FOLD_JOURNAL_DDL)
        journaled = {r[0]: r[1] for r in conn.execute(
            "SELECT merged_id, survivor_id FROM dedup_fold_journal")}
        new_pairs = []
        for survivor_id, member_id, rule in pairs:
            if member_id in journaled:
                if journaled[member_id] != survivor_id:
                    raise ValueError(
                        "fold pair (%d -> %d): already journaled under %d"
                        % (member_id, survivor_id, journaled[member_id]))
                stats["skipped"] += 1
            else:
                new_pairs.append((survivor_id, member_id, rule))
        _validate_pairs(conn, new_pairs)
        to_apply = _journal(conn, new_pairs, folded_at)
        if not to_apply:
            return stats
        conn.execute(
            "CREATE TEMP TABLE _fold_pairs ("
            " survivor_id INTEGER NOT NULL, member_id INTEGER NOT NULL,"
            " rule TEXT NOT NULL, PRIMARY KEY (member_id))")
        conn.executemany(
            "INSERT INTO _fold_pairs (survivor_id, member_id, rule)"
            " VALUES (?, ?, ?)",
            [(s, m, r) for (s, m, r) in pairs if m in to_apply])
        survivors = sorted({s for s, _m, _r in pairs if _m in to_apply})
        members_by_survivor = defaultdict(list)
        cols = [d[0] for d in conn.execute(
            "SELECT * FROM cat_works WHERE 1 = 0").description]
        for survivor_id, member_id, _rule in pairs:
            if member_id not in to_apply:
                continue
            row = conn.execute(
                "SELECT * FROM cat_works WHERE id = ?",
                (member_id,)).fetchone()
            members_by_survivor[survivor_id].append(dict(zip(cols, row)))
        _repoint_plain(conn, present, to_apply)
        _repoint_collision_safe(conn, present, to_apply)
        _merge_seasons_episodes(conn, present, to_apply)
        _merge_parts(conn, present, to_apply)
        _merge_warm_demand(conn, present, to_apply)
        _rekey_work_key_tables(conn, present, to_apply)
        if "cat_id_map" in present:
            catalog_rev = conn.execute(
                "SELECT COALESCE(MAX(source_rev), 0) FROM cat_works"
            ).fetchone()[0]
            for member_id, survivor_id in sorted(to_apply.items()):
                conn.execute(
                    "INSERT OR REPLACE INTO cat_id_map"
                    " (kind, old_id, new_id, rev) VALUES"
                    " ('work', ?, ?, ?)",
                    (member_id, survivor_id, catalog_rev))
        # Delete the merged member rows BEFORE the bridge rebuild:
        # rebuild_language_bridge reads cat_works, so rebuilding
        # while the members still exist would re-create bridge rows
        # for works that no longer exist. (The fill data was already
        # captured in members_by_survivor above.)
        conn.execute(
            "DELETE FROM cat_works "
            "WHERE id IN (SELECT member_id FROM _fold_pairs)")
        if "cat_work_languages" in present:
            conn.execute(
                "DELETE FROM cat_work_languages "
                "WHERE work_id IN (SELECT member_id FROM _fold_pairs)")
        if bridge == "rebuild":
            from catalog_api import rebuild_language_bridge
            stats["bridge_rows"] = rebuild_language_bridge(conn)
            # Stamp the bridge with the projection's own projected_at —
            # the same discipline _finalize uses: _bridge_usable is
            # true only when language_bridge_at == projected_at.
            if "cat_projection_meta" in present:
                projected_at = conn.execute(
                    "SELECT value FROM cat_projection_meta"
                    " WHERE key = 'projected_at'").fetchone()
                if projected_at is not None:
                    conn.execute(
                        "INSERT OR REPLACE INTO cat_projection_meta"
                        " (key, value) VALUES ('language_bridge_at', ?)",
                        (projected_at[0],))
        elif bridge == "invalidate":
            conn.execute(
                "DELETE FROM cat_projection_meta"
                " WHERE key = 'language_bridge_at'")
            stats["bridge_rows"] = None
        else:
            raise ValueError("unknown bridge mode: %r" % (bridge,))
        _fill_and_rollup(conn, present, survivors, members_by_survivor,
                         folded_at)
        conn.execute("DROP TABLE _fold_pairs")
        stats["applied"] = len(to_apply)
        stats["survivors"] = len(survivors)
    return stats


def verify_fold(conn, pairs, *, works_before=None):
    """Problems with a folded database (empty list = clean)."""
    problems = []
    pairs = [(int(s), int(m), str(r)) for s, m, r in pairs]
    merged = {m for _s, m, _r in pairs}
    survivors = {s for s, _m, _r in pairs}
    present = _tables(conn)
    if "dedup_fold_journal" not in present:
        problems.append("dedup_fold_journal table missing")
        return problems
    journaled = {r[0] for r in conn.execute(
        "SELECT merged_id FROM dedup_fold_journal")}
    if journaled != merged:
        problems.append(
            "journal covers %d of %d merged members"
            % (len(journaled & merged), len(merged)))
    if works_before is not None:
        now = conn.execute("SELECT COUNT(*) FROM cat_works").fetchone()[0]
        if now != works_before - len(merged):
            problems.append(
                "cat_works has %d rows, expected %d"
                % (now, works_before - len(merged)))
    for table in ("cat_work_aliases", "cat_work_sites", "cat_seasons",
                  "cat_episodes", "cat_parts", "cat_releases", "cat_links",
                  "cat_entitlements", "cat_review_queue",
                  "cat_media_segments", "shared_warm_demand",
                  "cat_work_languages"):
        if table not in present or not merged:
            continue
        orphans = conn.execute(
            "SELECT COUNT(*) FROM %s WHERE work_id IN (%s)"
            % (table, ",".join("?" * len(merged))),
            tuple(sorted(merged))).fetchone()[0]
        if orphans:
            problems.append("%s still references %d merged works"
                            % (table, orphans))
    for table in _KEYED_TABLES:
        if table not in present:
            continue
        # The member work rows are gone after a fold, so check the
        # journal keys: no keyed row may still name a merged work_key.
        merged_keys = {r[0] for r in conn.execute(
            "SELECT merged_work_key FROM dedup_fold_journal")}
        if not merged_keys:
            continue
        orphans = conn.execute(
            "SELECT COUNT(*) FROM %s WHERE work_key IN (%s)"
            % (table, ",".join("?" * len(merged_keys))),
            tuple(sorted(merged_keys))).fetchone()[0]
        if orphans:
            problems.append("%s still references %d merged work_keys"
                            % (table, orphans))
    dupes = conn.execute(
        "SELECT COUNT(*) FROM (SELECT LOWER(TRIM(title)) AS t, year"
        " FROM cat_works WHERE id IN (%s)"
        " GROUP BY t, year HAVING COUNT(*) > 1)"
        % ",".join("?" * len(survivors)),
        tuple(sorted(survivors))).fetchone()[0] if survivors else 0
    if dupes:
        problems.append("%d folded title+year groups still have >1 work"
                        % dupes)
    for survivor_id in sorted(survivors):
        counts = conn.execute(
            "SELECT release_count, episode_count, site_count"
            " FROM cat_works WHERE id = ?", (survivor_id,)).fetchone()
        if counts is None:
            problems.append("survivor %d is gone" % survivor_id)
            continue
        real = conn.execute(
            "SELECT (SELECT COUNT(*) FROM cat_releases"
            "  WHERE work_id = ?),"
            " (SELECT COUNT(*) FROM cat_episodes WHERE work_id = ?),"
            " (SELECT COUNT(DISTINCT source_site) FROM cat_work_sites"
            "  WHERE work_id = ?)",
            (survivor_id, survivor_id, survivor_id)).fetchone()
        if tuple(counts) != tuple(real):
            problems.append(
                "survivor %d counts %s, recomputed %s"
                % (survivor_id, tuple(counts), tuple(real)))
    return problems


def load_pairs(path):
    """Pairs from a plan JSON (``{"pairs": [...]}``) or a bare list."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(data, dict):
        data = data["pairs"]
    return [(int(s), int(m), str(r)) for s, m, r in data]


def main(argv=None):
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True,
                        help="catalog copy to fold (never the live DB)")
    parser.add_argument("--pairs", required=True,
                        help="plan JSON ({\"pairs\": [[survivor, member,"
                        " rule], ...]}) or a bare pair list")
    parser.add_argument("--apply", action="store_true",
                        help="apply the fold (default is report-only)")
    parser.add_argument("--bridge", default="rebuild",
                        choices=("rebuild", "invalidate"),
                        help="language-bridge handling after the fold")
    args = parser.parse_args(argv)
    pairs = load_pairs(args.pairs)
    conn = sqlite3.connect(args.db)
    try:
        if not args.apply:
            _validate_pairs(conn, pairs)
            print("pairs: %d, validation OK (report-only, nothing written)"
                  % len(pairs))
            return 0
        before = conn.execute("SELECT COUNT(*) FROM cat_works").fetchone()[0]
        stats = apply_fold(conn, pairs, bridge=args.bridge)
        problems = verify_fold(conn, pairs, works_before=before)
        print("applied: %(applied)d skipped: %(skipped)d"
              " survivors: %(survivors)d bridge: %(bridge)s"
              % stats)
        if problems:
            print("VERIFY FAILED:")
            for problem in problems:
                print(" - " + problem)
            return 1
        print("verify: clean")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
