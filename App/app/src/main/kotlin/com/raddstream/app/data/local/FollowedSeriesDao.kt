package com.raddstream.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowedSeriesDao {

    @Query("SELECT * FROM followed_series ORDER BY followedAt DESC")
    suspend fun followedOnce(): List<FollowedSeries>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_series WHERE workRef = :workRef)")
    fun isFollowed(workRef: String): Flow<Boolean>

    @Query("SELECT * FROM followed_series WHERE workRef = :workRef LIMIT 1")
    suspend fun findByWorkRef(workRef: String): FollowedSeries?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(series: FollowedSeries)

    @Query("DELETE FROM followed_series WHERE workRef = :workRef")
    suspend fun deleteByWorkRef(workRef: String)

    /**
     * Advance the newest scope this viewer has been told about. Written
     * together, because a season without its episode is a scope no
     * comparison can trust — the worker always passes both halves of the
     * scope it just saw.
     */
    @Query(
        "UPDATE followed_series SET lastSeenSeason = :season, " +
            "lastSeenEpisode = :episode WHERE workRef = :workRef",
    )
    suspend fun updateLastSeen(workRef: String, season: Int?, episode: Int?)
}
