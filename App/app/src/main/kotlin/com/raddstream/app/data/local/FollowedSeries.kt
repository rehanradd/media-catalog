package com.raddstream.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One series the viewer asked to be told about — the "Notify me" bell on
 * Detail (E2 slice 2). The row is keyed by the server-minted [workRef],
 * the only name a work has that survives across its posts: a new episode
 * lands as a new post, so an id would go stale the moment the thing the
 * viewer follows actually happens.
 *
 * [lastSeenSeason] / [lastSeenEpisode] are the newest scope the viewer has
 * already been shown (seeded from the Detail they followed from, then
 * advanced by the daily check) — a notification fires only when the
 * catalog carries something strictly newer, so a repeat run never
 * re-announces the same episode. Both null means "followed, never
 * checked": the first check seeds them silently instead of notifying
 * about episodes that were already on the page when the bell was tapped.
 * A null episode with a season is a whole-season pack: the season arrived
 * as one file, not as numbered episodes.
 */
@Entity(tableName = "followed_series")
data class FollowedSeries(
    @PrimaryKey
    val workRef: String,
    val cleanTitle: String? = null,
    val title: String,
    val posterUrl: String? = null,
    val lastSeenSeason: Int? = null,
    val lastSeenEpisode: Int? = null,
    val followedAt: Long = System.currentTimeMillis(),
)
