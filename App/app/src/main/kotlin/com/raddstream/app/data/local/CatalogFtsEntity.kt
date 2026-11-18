package com.raddstream.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

/**
 * Feel-instant S3c: the on-device search index — the titles this phone has
 * already downloaded, in an FTS4 table. FTS4, not FTS5: the system SQLite
 * on Android ships without FTS5 (measured on Android 15, SQLite 3.44.3:
 * `FTS5=no such module: fts5`, FTS4 OK), and Room 2.8's bundled driver
 * would raise minSdk 21 → 23 for ranking that FTS4 + the popularity the
 * server already returns already covers.
 *
 * A standalone content table, not a `contentEntity` shadow: the index is
 * seeded from traffic (every list/detail payload the app already downloads
 * upserts its titles), so it needs no triggers and no second table to keep
 * in sync. A fresh install starts empty and fills within a session or two.
 *
 * The columns are the search answer only — title, year, type, poster, and
 * the popularity that orders it. There is deliberately no post id or work
 * id here: a local hit is a title to search for, not a page to open, so a
 * tap runs the server search for that title (the remote half of the
 * hybrid: local FTS → instant, remote API → complete). The `prefix`
 * option is the autocomplete index: `aveng*` answers in milliseconds
 * instead of scanning.
 *
 * Dedupe is by title + year + type in [CatalogFtsDao.upsert]: the same
 * work arriving from a v1 list and a v2 list is one row, the freshest
 * wins, and the table cannot grow a duplicate per print.
 */
@Fts4(prefix = [1, 2, 3, 4])
@Entity(tableName = "catalog_fts")
data class CatalogFtsEntity(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "year") val year: Int?,
    @ColumnInfo(name = "type") val type: String?,
    @ColumnInfo(name = "posterUrl") val posterUrl: String?,
    @ColumnInfo(name = "popularity") val popularity: Int,
)
