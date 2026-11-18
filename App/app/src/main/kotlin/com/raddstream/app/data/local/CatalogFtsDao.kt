package com.raddstream.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * Feel-instant S3c: reads and writes for the on-device search index.
 * Every method is a suspend call — Room runs it on its own executor, so a
 * list load that seeds the index never blocks the caller's thread.
 */
@Dao
interface CatalogFtsDao {

    /**
     * Prefix matches for one query, most popular first, then title — the
     * order a type-ahead reads in. [matchQuery] must come from
     * [buildFtsPrefixQuery]: raw keystrokes carry FTS operators, and this
     * query runs them.
     */
    @Query(
        "SELECT * FROM catalog_fts WHERE catalog_fts MATCH :matchQuery " +
            "ORDER BY popularity DESC, title ASC LIMIT :limit",
    )
    suspend fun search(matchQuery: String, limit: Int): List<CatalogFtsEntity>

    /** The most popular titles on this device — the empty-state instant row. */
    @Query("SELECT * FROM catalog_fts ORDER BY popularity DESC, title ASC LIMIT :limit")
    suspend fun topByPopularity(limit: Int): List<CatalogFtsEntity>

    @Query("SELECT COUNT(*) FROM catalog_fts")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(entries: List<CatalogFtsEntity>)

    /**
     * The dedupe key: title + year + type. `IS` (not `=`) so a row with no
     * year still matches a row with no year — `NULL = NULL` is never true,
     * and a yearless title re-seeded would otherwise duplicate forever.
     */
    @Query("DELETE FROM catalog_fts WHERE title = :title AND year IS :year AND type IS :type")
    suspend fun deleteByKey(title: String, year: Int?, type: String?)

    /**
     * One transaction per batch: delete-then-insert per row, so a re-seed
     * replaces the row instead of doubling it. There is no UNIQUE index to
     * conflict on — SQLite forbids indexes on a virtual table — so the
     * delete is the dedupe.
     */
    @Transaction
    suspend fun upsert(entries: List<CatalogFtsEntity>) {
        for (entry in entries) {
            deleteByKey(entry.title, entry.year, entry.type)
        }
        insertAll(entries)
    }
}

/** How many tokens of a typed query ever reach the index — more is noise. */
internal const val MAX_FTS_QUERY_TOKENS = 5

/**
 * Feel-instant S3c: the FTS `MATCH` query for raw keystrokes, as a pure
 * function so a test can pin it without a database.
 *
 * The input is split on anything that is not a letter, a combining mark
 * or a digit, so no quote, dash, parenthesis or other FTS operator can
 * survive into the query; each token is then prefix-starred, and the
 * tokens are ANDed (a space is an AND in `MATCH`). `aven*` matches
 * "Avengers"; `the* aveng*` matches titles holding both prefixes.
 * The tokens are deliberately BARE, not quoted: in FTS4 `"aven"*` — a
 * quoted phrase with a star — is not a prefix query at all and matches
 * nothing (measured on SQLite 3.45: `aven*` matches, `"aven"*` does not).
 * The split is the safety: a bare token holds only letters, marks and
 * digits, so no operator can hide in it — not even `or*`, `and*` or
 * `not*`, which parse as prefix terms once starred. Null when nothing
 * searchable was typed — the caller answers an empty list, never a
 * `MATCH ''` that SQLite would reject.
 */
internal fun buildFtsPrefixQuery(raw: String): String? {
    val tokens = raw.trim()
        .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
        .filter { it.isNotEmpty() }
        .take(MAX_FTS_QUERY_TOKENS)
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}
