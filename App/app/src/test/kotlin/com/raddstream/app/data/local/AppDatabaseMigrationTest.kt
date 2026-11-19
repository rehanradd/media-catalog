package com.raddstream.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Flow-audit #25, proven on a real database instead of asserted in prose: a device stamped
 * at schema version 2 with a saved favorite must climb 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 and still hold
 * that favorite at the top. The builder used here registers the same eleven migrations as
 * `DatabaseModule` but NO destructive fallback — with a gap in the ladder Room throws rather
 * than silently wiping rows, and if the hand-written 2 → 3 DDL drifts from the entities,
 * Room's schema validation throws the first time the migrated database is opened.
 *
 * The climb runs to 12 because that is the ladder that ships: version 9 is the Live TV
 * favourites table and version 10 is the followed-series table, and a device at version 2 is
 * exactly the device that has never been told whether its saved rows are adult or unavailable,
 * so it must arrive at 12 with its rows, its stamps unset, and nothing rebuilt — plus version
 * 10's `followed_series` table, empty and writable, and version 11's `catalog_fts` search
 * index, empty and prefix-searchable, and version 12's `watch_history.imdbRating` column,
 * NULL on every row the climb carried.
 *
 * x86_64-only like every Robolectric suite (see the arm64 exclusion in `app/build.gradle.kts`):
 * this is the hosted-CI proof that the pure-text schema guards cannot provide.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    @Test
    fun `a version-2 database upgrades to 12 with its favorites intact`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "upgrade_from_2.db"
        context.deleteDatabase(dbName)

        // Recreate exactly what the 1 → 2 rebuild left behind, stamped as version 2.
        val legacy = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        legacy.execSQL(
            "CREATE TABLE IF NOT EXISTS favorite_posts (id INTEGER NOT NULL, title TEXT NOT NULL, " +
                "cleanTitle TEXT, year TEXT, contentType TEXT, posterUrl TEXT, imdbRating TEXT, " +
                "addedAt INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        legacy.execSQL(
            "INSERT INTO favorite_posts (id, title, addedAt) VALUES (7, 'Saved before the upgrade', 123)",
        )
        legacy.execSQL("PRAGMA user_version = 2")
        legacy.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
            )
            .build()

        try {
            // First real access is what runs the migrations and Room's schema validation.
            val runtime = db.openHelper.writableDatabase

            runtime.query("SELECT title FROM favorite_posts WHERE id = 7").use { cursor ->
                assertTrue(
                    "the 2 → 11 climb lost a saved favorite — a destructive path ran",
                    cursor.moveToFirst(),
                )
                assertEquals("Saved before the upgrade", cursor.getString(0))
            }
            runtime.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(12, cursor.getInt(0))
            }

            // Version 8's two answers arrive UNSET. A row this old was saved by a build that
            // never asked the server anything, so a stamp invented by the migration would make
            // a never-checked favourite look checked — and that is the row revalidation exists to
            // find first. Same law the re-validation pass reads; proven on a climbed database.
            runtime.query("SELECT is18Plus, checkedAt FROM favorite_posts WHERE id = 7").use { cursor ->
                assertTrue("the 2 → 11 climb dropped the saved row", cursor.moveToFirst())
                assertTrue("a never-checked favourite must not gain an adult answer", cursor.isNull(0))
                assertTrue("a never-checked favourite must not gain a check stamp", cursor.isNull(1))
            }

            // Every table the ladder is supposed to have built exists and takes a write.
            runtime.execSQL(
                "INSERT INTO watch_history (workRef, postId, title, seasonNumber, episodeNumber, " +
                    "positionMs, durationMs, updatedAt) VALUES ('w', 7, 'Kept after upgrade', 1, 1, 10, 100, 1)",
            )
            runtime.query("SELECT title FROM watch_history WHERE workRef = 'w'").use { cursor ->
                assertTrue("version 5's table must survive the 2 → 11 climb", cursor.moveToFirst())
                assertEquals("Kept after upgrade", cursor.getString(0))
            }
            // Version 12's rating column arrives with the climb: a row the
            // climb carried reads NULL (never a 0.0 — an unrated title is
            // not a badly rated one), and a new row stores the catalog's
            // raw IMDb text and reads it back.
            runtime.query("SELECT imdbRating FROM watch_history WHERE workRef = 'w'").use { cursor ->
                assertTrue("version 12's column must arrive with the climb", cursor.moveToFirst())
                assertTrue("a pre-v12 row must carry no rating", cursor.isNull(0))
            }
            runtime.execSQL(
                "INSERT INTO watch_history (workRef, postId, title, seasonNumber, episodeNumber, " +
                    "positionMs, durationMs, updatedAt, imdbRating) " +
                    "VALUES ('wr', 7, 'Rated after upgrade', 0, 0, 10, 100, 2, '8.6')",
            )
            runtime.query("SELECT imdbRating FROM watch_history WHERE workRef = 'wr'").use { cursor ->
                assertTrue("version 12's column must take a write", cursor.moveToFirst())
                assertEquals("8.6", cursor.getString(0))
            }
            for (table in listOf("cache_tasks", "cache_files", "byte_range_manifests", "synced_progress", "ad_cadence", "unavailable_works", "live_tv_favourites", "followed_series")) {
                runtime.query("SELECT * FROM $table LIMIT 0").use { } // throws if the table is absent
            }
            // Version 9's table arrives with the climb and takes a write —
            // a star is a row, not a migration afterthought.
            runtime.execSQL(
                "INSERT INTO live_tv_favourites (channelId, starredAt) VALUES (14, 123)",
            )
            runtime.query("SELECT starredAt FROM live_tv_favourites WHERE channelId = 14").use { cursor ->
                assertTrue("version 9's table must survive the 2 → 11 climb", cursor.moveToFirst())
                assertEquals(123, cursor.getInt(0))
            }
            // Version 10's table takes a follow and reads it back.
            runtime.execSQL(
                "INSERT INTO followed_series (workRef, title, lastSeenSeason, lastSeenEpisode, followedAt) " +
                    "VALUES ('w', 'Kept after upgrade', 2, 5, 1)",
            )
            runtime.query("SELECT lastSeenEpisode FROM followed_series WHERE workRef = 'w'").use { cursor ->
                assertTrue("version 10's table must survive the 2 → 11 climb", cursor.moveToFirst())
                assertEquals(5, cursor.getInt(0))
            }
            // Version 11's search index arrives with the climb and answers a
            // prefix MATCH — the whole point of the migration is that a
            // device which has never searched can still search locally
            // after the upgrade, with no re-download.
            runtime.execSQL(
                "INSERT INTO catalog_fts (title, year, type, posterUrl, popularity) " +
                    "VALUES ('Jawan', 2023, 'movie', NULL, 4)",
            )
            runtime.query("SELECT title FROM catalog_fts WHERE catalog_fts MATCH 'jaw*'").use { cursor ->
                assertTrue("version 11's index must survive the 2 → 11 climb", cursor.moveToFirst())
                assertEquals("Jawan", cursor.getString(0))
            }
        } finally {
            db.close()
        }
    }
}
