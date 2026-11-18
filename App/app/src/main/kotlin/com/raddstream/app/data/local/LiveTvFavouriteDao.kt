package com.raddstream.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveTvFavouriteDao {

    /** Starred channel ids, newest-starred first — the Favourites view's order. */
    @Query("SELECT channelId FROM live_tv_favourites ORDER BY starredAt DESC")
    fun observeFavoriteIds(): Flow<List<Int>>

    @Query("SELECT EXISTS(SELECT 1 FROM live_tv_favourites WHERE channelId = :channelId)")
    suspend fun isFavorite(channelId: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favourite: LiveTvFavourite)

    @Query("DELETE FROM live_tv_favourites WHERE channelId = :channelId")
    suspend fun deleteById(channelId: Int)
}
