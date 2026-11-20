package com.raddstream.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * REA #5 (Netflix, Nov 2026): the "Downloads For You" ledger — what
 * Smart Downloads fetched, in fetch order. The runner writes one row per
 * enqueued episode and sweeps rows whose handle is no longer kept; Home
 * reads the flow to build the row. A re-fetch of the same episode replaces
 * its row (REPLACE), so the ledger never holds two rows for one episode.
 */
@Dao
interface DownloadsForYouDao {

    @Query("SELECT * FROM downloads_for_you ORDER BY fetchedAt DESC")
    fun observeAll(): Flow<List<DownloadsForYouEntry>>

    @Query("SELECT * FROM downloads_for_you ORDER BY fetchedAt DESC")
    suspend fun listAll(): List<DownloadsForYouEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DownloadsForYouEntry)

    @Query("DELETE FROM downloads_for_you WHERE handle = :handle")
    suspend fun deleteByHandle(handle: String)

    @Query("DELETE FROM downloads_for_you WHERE postId = :postId AND season = :season AND episode = :episode")
    suspend fun deleteEpisode(postId: Int, season: Int, episode: Int)

    @Query("DELETE FROM downloads_for_you")
    suspend fun clear()
}
