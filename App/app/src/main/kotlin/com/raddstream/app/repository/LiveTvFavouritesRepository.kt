package com.raddstream.app.repository

import com.raddstream.app.data.local.LiveTvFavourite
import com.raddstream.app.data.local.LiveTvFavouriteDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Live TV starred list. The same shape as the posts-side
 * [FavoritesRepository], minus the sync half: a channel has no server-minted
 * handle, so there is nothing to report and nothing to revalidate — Room is
 * the whole truth, and a star survives a process death, a tab change, and a
 * fresh channel fetch, because the row is keyed by the registry id, not by
 * the list that happened to be on screen.
 */
@Singleton
class LiveTvFavouritesRepository @Inject constructor(
    private val dao: LiveTvFavouriteDao,
) {

    fun observeFavoriteIds(): Flow<Set<Int>> =
        dao.observeFavoriteIds().map { it.toSet() }

    /**
     * Stars or unstars the channel; returns true when it is starred
     * afterwards. The read-then-write is one suspend call from the
     * ViewModel's scope, so a double-tap cannot star the same id twice —
     * the second read already sees the first write.
     */
    suspend fun toggleFavorite(channelId: Int): Boolean {
        return if (dao.isFavorite(channelId)) {
            dao.deleteById(channelId)
            false
        } else {
            dao.insert(LiveTvFavourite(channelId = channelId))
            true
        }
    }
}
