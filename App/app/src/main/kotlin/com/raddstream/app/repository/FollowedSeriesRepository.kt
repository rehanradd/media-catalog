package com.raddstream.app.repository

import com.raddstream.app.data.follow.NewEpisodeCheck
import com.raddstream.app.data.local.FollowedSeries
import com.raddstream.app.data.local.FollowedSeriesDao
import com.raddstream.app.network.models.PostDetail
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * The follow list, beside [FavoritesRepository]: the same shape (a Room
 * table the viewer owns, a Flow the screen observes, a toggle the bell
 * calls), for a different question — not "keep this title" but "tell me
 * when this series grows".
 *
 * Following seeds the last-seen scope from the Detail the viewer is
 * looking at, so the episodes already on that page are never announced
 * as new: the first notification names something that landed after the
 * tap, not something that was already there.
 */
class FollowedSeriesRepository @Inject constructor(
    private val followedDao: FollowedSeriesDao,
) {

    fun isFollowed(workRef: String): Flow<Boolean> = followedDao.isFollowed(workRef)

    /** Follow, seeding last-seen from the detail in front of the viewer. Returns true. */
    suspend fun follow(detail: PostDetail): Boolean {
        val workRef = detail.workRef?.takeIf { it.isNotBlank() } ?: return false
        val newest = NewEpisodeCheck.newestScope(detail.releases)
        followedDao.insert(
            FollowedSeries(
                workRef = workRef,
                cleanTitle = detail.cleanTitle,
                title = detail.displayTitle,
                posterUrl = detail.posterUrl,
                lastSeenSeason = newest?.season,
                lastSeenEpisode = newest?.episode,
            ),
        )
        return true
    }

    /** Unfollow. Returns false — the bell's new state. */
    suspend fun unfollow(workRef: String): Boolean {
        followedDao.deleteByWorkRef(workRef)
        return false
    }

    /** The bell's toggle: follow when not followed, unfollow when followed. */
    suspend fun toggleFollow(detail: PostDetail): Boolean {
        val workRef = detail.workRef?.takeIf { it.isNotBlank() } ?: return false
        val currentlyFollowed = followedDao.findByWorkRef(workRef) != null
        return if (currentlyFollowed) {
            unfollow(workRef)
        } else {
            follow(detail)
        }
    }
}
