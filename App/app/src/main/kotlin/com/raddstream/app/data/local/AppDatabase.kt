package com.raddstream.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.raddstream.app.data.cache.ByteRangeManifestDao
import com.raddstream.app.data.cache.ByteRangeManifestEntity
import com.raddstream.app.data.cache.CacheFileDao
import com.raddstream.app.data.cache.CacheFileEntity
import com.raddstream.app.data.cache.CacheTaskDao
import com.raddstream.app.data.cache.CacheTaskEntity

/**
 * The app's single Room database: favorites plus the offline cache engine (tasks, byte ledgers
 * and the file inventory the TTL sweep and eviction planner walk).
 *
 * Version 2 held only favorites; version 3 adds the three cache tables. The 2 → 3 step is real
 * DDL like every step after it: leaving it to the destructive fallback in the DI layer would
 * drop `favorite_posts` too, so an upgrade may never depend on that fallback — it is a last
 * resort for an unknown future version only. The favorite migration from 1 is preserved for
 * anyone already on that schema.
 *
 * Version 4 is the optional sync account. It adds two columns to `favorite_posts` (the
 * server-minted handle, and the stamp of the write that last succeeded) and the
 * `synced_progress` mirror, and it is written as real DDL rather than left to the destructive
 * fallback on purpose: a saved list is the one table in this app a viewer would notice losing,
 * and an app that wipes it because a feature was switched on has broken a promise the feature
 * never made.
 *
 * Version 5 adds `watch_history`, the local Continue watching rows. It is a new table with no
 * prior data, but it is still real DDL: the destructive fallback on this builder would take
 * `favorite_posts` with it.
 *
 * Version 6 adds the two per-work presentation facts to `favorite_posts` — `isAnimated` and
 * `subtitled`. Both are nullable with no default on purpose: an existing row was saved by a
 * build that never asked, and a saved list may not be made to claim the server told it something
 * it never sent. This is one more nullable ADD COLUMN on the table a viewer would notice losing,
 * so it is DDL like 4 and 5 rather than the destructive fallback.
 *
 * Version 7 adds `ad_cadence`, the three rows that make an ad cap survive a process death. It is
 * a new table with no prior data and no nullable column: every field is written by this build the
 * moment it is meaningful, and an absent stamp is expressed as zero rather than as NULL so
 * [com.raddstream.app.data.local.AdCadenceRows] has one shape to read. Real DDL again, because the
 * destructive fallback on this builder would still take `favorite_posts` with it.
 *
 * Version 8 is the two answers a saved row never kept: `checkedAt`, the stamp of the last time the
 * server said anything about this id, and `is18Plus`, the adult flag if a payload ever carried it.
 * Both are nullable because every row on every device was saved by a build that asked neither —
 * a stamp invented at migration time would make a never-checked row look checked, and that is
 * precisely the row the revalidation pass is supposed to find first. The same step adds
 * `unavailable_works`, the ledger of ids this device has been *refused* by name. It is real DDL
 * for the same reason 4 through 7 are: the destructive fallback on this builder would take the
 * viewer's saved list with it, and a rule about hidden rows that costs a library to install is
 * not a rule the app should ship.
 *
 * Version 9 adds `live_tv_favourites`, the starred Live TV channels (E2 slice 1). It is a new
 * table with no prior data, real DDL like 5 and 7: the destructive fallback would still take
 * `favorite_posts` with it, and a star is a promise the same as a saved title.
 *
 * Version 10 adds `followed_series`, the bell on a series Detail (E2 slice 2). It is a new table
 * with no prior data, but it is still real DDL for the same reason 5 and 7 are: the destructive
 * fallback would take `favorite_posts` with it, and a notification preference is not worth a
 * saved list.
 *
 * Version 11 adds `catalog_fts`, the on-device search index (feel-instant S3c). It is a
 * standalone FTS4 virtual table — FTS5 is absent from the system SQLite, and Room 2.8's
 * bundled driver would raise minSdk 21 → 23 for ranking FTS4 + popularity already covers.
 * The DDL below must equal Room's own output for [CatalogFtsEntity] (the `prefix` option
 * included): a drift is caught the first time a device opens the migrated database, and
 * [com.raddstream.app.data.local.AppDatabaseMigrationTest] holds that contract on CI.
 * The table starts empty on every device and fills from traffic — there is no data to
 * preserve across the step, only a schema to land.
 *
 * Version 12 adds `watch_history.imdbRating`, the Continue watching rail's rating (E2 Ratings,
 * Nov 2026): one nullable TEXT column, added in place — no row is touched, so every pre-v12
 * row reads NULL and the rail paints nothing for it, exactly as an unrated title.
 *
 * Version 13 adds `title_feedback`, the viewer's thumbs up/down (REA #2, Nov 2026). It is a
 * new table with no prior data, real DDL like 5 and 7: the destructive fallback would still
 * take `favorite_posts` with it, and a vote is the viewer's own, worth exactly as much as a
 * saved title.
 *
 * Version 14 adds `title_sync_prefs`, the per-title audio/subtitle delays (REA #3, Nov 2026).
 * It is a new table with no prior data, real DDL like 5 and 7: a correction is the viewer's
 * own, and it must survive exactly like a saved title.
 *
 * Version 15 adds `downloads_for_you`, the ledger of episodes Smart Downloads fetched (REA #5,
 * Nov 2026). It is a new table with no prior data, real DDL like 5 and 7: the row is what the
 * Home row and the storage budget both read, and a fetch the viewer never asked to lose must
 * not be dropped by an upgrade.
 */
@Database(
    entities = [
        FavoritePost::class,
        CacheTaskEntity::class,
        CacheFileEntity::class,
        ByteRangeManifestEntity::class,
        SyncedProgressEntity::class,
        WatchHistoryEntity::class,
        AdCadenceEntity::class,
        UnavailableWorkEntity::class,
        LiveTvFavourite::class,
        FollowedSeries::class,
        CatalogFtsEntity::class,
        TitleFeedback::class,
        TitleSyncPrefs::class,
        DownloadsForYouEntry::class,
    ],
    version = 15,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun cacheTaskDao(): CacheTaskDao
    abstract fun cacheFileDao(): CacheFileDao
    abstract fun byteRangeManifestDao(): ByteRangeManifestDao
    abstract fun syncedProgressDao(): SyncedProgressDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun adCadenceDao(): AdCadenceDao
    abstract fun unavailableWorkDao(): UnavailableWorkDao
    abstract fun liveTvFavouriteDao(): LiveTvFavouriteDao
    abstract fun followedSeriesDao(): FollowedSeriesDao
    abstract fun catalogFtsDao(): CatalogFtsDao
    abstract fun titleFeedbackDao(): TitleFeedbackDao
    abstract fun titleSyncPrefsDao(): TitleSyncPrefsDao
    abstract fun downloadsForYouDao(): DownloadsForYouDao

    companion object {
        /**
         * Version 1 stored `sourceSite`. The public edge stopped serving it, so the local
         * store must not keep it either — a column nothing can display is a column somebody
         * will display by accident later. SQLite below 3.35 cannot DROP COLUMN, so the table
         * is rebuilt and every row, including its `addedAt`, survives.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorite_posts_new (" +
                        "id INTEGER NOT NULL, title TEXT NOT NULL, cleanTitle TEXT, " +
                        "year TEXT, contentType TEXT, posterUrl TEXT, imdbRating TEXT, " +
                        "addedAt INTEGER NOT NULL, PRIMARY KEY(id))",
                )
                db.execSQL(
                    "INSERT INTO favorite_posts_new " +
                        "(id, title, cleanTitle, year, contentType, posterUrl, imdbRating, addedAt) " +
                        "SELECT id, title, cleanTitle, year, contentType, posterUrl, imdbRating, addedAt " +
                        "FROM favorite_posts",
                )
                db.execSQL("DROP TABLE favorite_posts")
                db.execSQL("ALTER TABLE favorite_posts_new RENAME TO favorite_posts")
            }
        }

        /**
         * 2 → 3: the three offline-cache tables, created from nothing. This is hand-written DDL
         * that must equal Room's own output for the cache entities — column types and nullability
         * implied by the Kotlin types, AUTOINCREMENT for the generated key, and the index names
         * Room derives from table plus columns. A drift is caught the first time a device opens
         * the migrated database (Room validates the result against the entities and throws), and
         * [com.raddstream.app.data.local.AppDatabaseMigrationTest] plus the schema-guard tests
         * hold that contract on CI.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cache_tasks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceKey TEXT NOT NULL, " +
                        "releaseId INTEGER NOT NULL, linkKind TEXT NOT NULL, currentUrl TEXT NOT NULL, " +
                        "resourceId TEXT, title TEXT NOT NULL, printLabel TEXT NOT NULL, " +
                        "origin TEXT NOT NULL, priority TEXT NOT NULL, state TEXT NOT NULL, " +
                        "totalSizeBytes INTEGER NOT NULL, downloadedBytes INTEGER NOT NULL, " +
                        "verifiedPrefixBytes INTEGER NOT NULL, resumeToken TEXT NOT NULL, " +
                        "attempt INTEGER NOT NULL, nextRetryAtElapsedRealtime INTEGER NOT NULL, " +
                        "enqueueElapsedRealtime INTEGER NOT NULL, createdAtWallClock INTEGER NOT NULL, " +
                        "playOffsetSeconds REAL NOT NULL, packTotalDurationSeconds REAL NOT NULL, " +
                        "lastError TEXT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_cache_tasks_sourceKey " +
                        "ON cache_tasks (sourceKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cache_tasks_state ON cache_tasks (state)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cache_tasks_priority_enqueueElapsedRealtime " +
                        "ON cache_tasks (priority, enqueueElapsedRealtime)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cache_files (" +
                        "fileId TEXT NOT NULL, sourceKey TEXT NOT NULL, taskId INTEGER, " +
                        "path TEXT NOT NULL, sizeBytes INTEGER NOT NULL, origin TEXT NOT NULL, " +
                        "state TEXT NOT NULL, isTemporary INTEGER NOT NULL, isComplete INTEGER NOT NULL, " +
                        "createdElapsedRealtime INTEGER NOT NULL, lastAccessElapsedRealtime INTEGER NOT NULL, " +
                        "watchCount INTEGER NOT NULL, ttlHours INTEGER NOT NULL, PRIMARY KEY(fileId))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cache_files_sourceKey ON cache_files (sourceKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cache_files_state ON cache_files (state)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cache_files_origin_createdElapsedRealtime " +
                        "ON cache_files (origin, createdElapsedRealtime)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS byte_range_manifests (" +
                        "taskId INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, " +
                        "committedElapsedRealtime INTEGER NOT NULL, PRIMARY KEY(taskId, start))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_byte_range_manifests_taskId " +
                        "ON byte_range_manifests (taskId)",
                )
            }
        }

        /**
         * 3 → 4: the sync-account columns and the resume-point mirror. Both favorite columns
         * are nullable, so an existing row means "local only" the moment it lands and nothing
         * in this step can change what a signed-out viewer sees.
         *
         * The second statement is one ADD COLUMN per statement because SQLite has never
         * supported adding two columns at once; a reader that expects one line here throws
         * "near ,: SQL syntax error" at open instead of telling anybody what went wrong.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_posts ADD COLUMN workRef TEXT")
                db.execSQL("ALTER TABLE favorite_posts ADD COLUMN syncedAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS synced_progress (" +
                        "workRef TEXT NOT NULL, seasonNumber INTEGER NOT NULL, " +
                        "episodeNumber INTEGER NOT NULL, positionSeconds REAL NOT NULL, " +
                        "durationSeconds REAL NOT NULL, clientUpdatedAt REAL NOT NULL, " +
                        "pulledAt INTEGER NOT NULL, PRIMARY KEY(workRef, seasonNumber, episodeNumber))",
                )
            }
        }

        /** 4 → 5: the local Continue watching rows. One statement, no existing row touched. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_history (" +
                        "workRef TEXT NOT NULL, postId INTEGER NOT NULL, title TEXT NOT NULL, " +
                        "posterUrl TEXT, seasonNumber INTEGER NOT NULL, " +
                        "episodeNumber INTEGER NOT NULL, positionMs INTEGER NOT NULL, " +
                        "durationMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(workRef, seasonNumber, episodeNumber))",
                )
            }
        }

        /**
         * 5 → 6: the two facts a tile needs and this table did not keep. Two statements because
         * SQLite has never supported adding two columns at once, and no existing row is
         * touched: a favourite saved last month arrives as undetermined, which is what it was.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_posts ADD COLUMN isAnimated INTEGER")
                db.execSQL("ALTER TABLE favorite_posts ADD COLUMN subtitled INTEGER")
            }
        }

        /**
         * 6 → 7: the ad cadence table. Three rows, one per surface, and the column names and
         * nullability are Room's own for [AdCadenceEntity] — the entities declare `TEXT NOT NULL`
         * / `INTEGER NOT NULL` for all three, so a `surface TEXT` here would throw Room's runtime
         * schema validation the first time a device opened its own database.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ad_cadence (" +
                        "surface TEXT NOT NULL, lastShownAtMs INTEGER NOT NULL, " +
                        "postersScrolled INTEGER NOT NULL, PRIMARY KEY(surface))",
                )
            }
        }

        /**
         * 7 → 8: the two answer columns on `favorite_posts` and the refusal ledger. One ADD
         * COLUMN per statement, because SQLite has never supported adding two at once, and both
         * nullable with no default: an existing row was saved by a build that never asked, and a
         * stamp written here would be a fact about a round-trip that never happened.
         *
         * Nothing in this step may rebuild or empty `favorite_posts`, and the pin in
         * [com.raddstream.app.tooling.FavoritesSchemaGuardTest] keeps it that way — the row a
         * viewer saved is the one thing in this database they would notice losing.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_posts ADD COLUMN is18Plus INTEGER")
                db.execSQL("ALTER TABLE favorite_posts ADD COLUMN checkedAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS unavailable_works (" +
                        "postId INTEGER NOT NULL, refusedAt INTEGER NOT NULL, " +
                        "reason TEXT NOT NULL, PRIMARY KEY(postId))",
                )
            }
        }

        /**
         * 8 → 9: the starred-channels table. One statement, no existing row
         * touched — a star is a row this build writes the moment it is
         * meaningful, and a channel that left the registry leaves a dead
         * row behind, never a stale card. The column names and nullability
         * are Room's own for [LiveTvFavourite].
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS live_tv_favourites (" +
                        "channelId INTEGER NOT NULL, starredAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(channelId))",
                )
            }
        }

        /**
         * 9 → 10: the followed-series table. One statement, no existing row touched — the
         * column names and nullability are Room's own for [FollowedSeries], so a drift
         * throws Room's schema validation the first time a device opens the migrated
         * database, exactly like the 2 → 3 step.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS followed_series (" +
                        "workRef TEXT NOT NULL, cleanTitle TEXT, title TEXT NOT NULL, " +
                        "posterUrl TEXT, lastSeenSeason INTEGER, lastSeenEpisode INTEGER, " +
                        "followedAt INTEGER NOT NULL, PRIMARY KEY(workRef))",
                )
            }
        }

        /**
         * 10 → 11: the on-device search index (feel-instant S3c). One statement, no
         * existing row touched — the table starts empty and fills from traffic. The
         * DDL is Room's own output for [CatalogFtsEntity]: backticked identifiers,
         * `USING FTS4`, and the `prefix` option the entity declares. Two of its
         * shapes are NOT the declaration order and must not be "fixed" back:
         * Room's expected FTS table lists the TEXT columns first
         * (`title, type, posterUrl`) and then the INTEGER ones
         * (`year, popularity`), and the option value carries backticks
         * (`prefix=`1,2,3,4``) where a hand-written DDL would use quotes —
         * both pinned by the migration test's `FtsTableInfo` comparison.
         * A drift from that output throws Room's schema validation the first
         * time a device opens the migrated database, exactly like the
         * 2 → 3 step.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `catalog_fts` USING FTS4(" +
                        "`title` TEXT NOT NULL, `type` TEXT, `posterUrl` TEXT, " +
                        "`year` INTEGER, `popularity` INTEGER NOT NULL, prefix=`1,2,3,4`)",
                )
            }
        }

        /**
         * 11 → 12: the Continue watching rail's rating (E2 Ratings, Nov
         * 2026). One nullable TEXT column on `watch_history`, added in
         * place — no row is touched, so every pre-v12 row reads NULL and
         * the rail paints nothing for it, exactly as an unrated title.
         * The column holds the catalog's raw IMDb text ("8.6"), never a
         * rendered number: UserWords.displayRating stays the choke
         * point that decides what paints. Room's schema validation
         * throws on drift the first time a device opens the migrated
         * database, exactly like the 2 → 3 step.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_history ADD COLUMN imdbRating TEXT")
            }
        }

        /**
         * 12 → 13: the thumbs table (REA #2, Nov 2026). One statement, no
         * existing row touched — the column names and nullability are
         * Room's own for [TitleFeedback], so a drift throws Room's schema
         * validation the first time a device opens the migrated database,
         * exactly like the 2 → 3 step. A down-vote is a row here, not a
         * flag on another table: the vote is the viewer's own, and it
         * must survive exactly like a saved title.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS title_feedback (" +
                        "workRef TEXT NOT NULL, thumb INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, PRIMARY KEY(workRef))",
                )
            }
        }

        /**
         * 13 → 14: the per-title sync table (REA #3, Nov 2026). One
         * statement, no existing row touched — the column names and
         * nullability are Room's own for [TitleSyncPrefs], so a drift
         * throws Room's schema validation the first time a device opens
         * the migrated database, exactly like the 2 → 3 step. An offset
         * is a row here, not a flag on another table: the correction is
         * the viewer's own, and it must survive exactly like a saved
         * title.
         */
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS title_sync_prefs (" +
                        "workRef TEXT NOT NULL, audioDelayMs INTEGER NOT NULL, " +
                        "subtitleDelayMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(workRef))",
                )
            }
        }

        /**
         * Version 15 adds `downloads_for_you`, the ledger of episodes Smart Downloads fetched
         * (REA #5). One row per fetched episode, keyed by post + season + episode, so a re-fetch
         * replaces its own row and the ledger never holds two rows for one episode. The DDL
         * below must equal Room's own output for [DownloadsForYouEntry]: a drift is caught the
         * first time a device opens the migrated database, exactly like the 2 → 3 step.
         */
        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS downloads_for_you (" +
                        "postId INTEGER NOT NULL, season INTEGER NOT NULL, " +
                        "episode INTEGER NOT NULL, title TEXT NOT NULL, " +
                        "handle TEXT NOT NULL, sizeBytes INTEGER, " +
                        "fetchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(postId, season, episode))",
                )
            }
        }
    }
}
