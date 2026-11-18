package com.raddstream.app.data.follow

import com.raddstream.app.network.models.ReleaseDetail

/**
 * One season/episode scope, as the catalog can actually name it. A null
 * episode is a whole-season pack — the season arrived as one file, not as
 * numbered episodes — and a pack outranks any single episode of the same
 * season: the whole season being available is newer than any one episode
 * of it. A release the server never gave a season to has no scope at all
 * and never counts (the "Unknown print" rows are C-work, not this slice).
 */
data class EpisodeScope(
    val season: Int,
    val episode: Int? = null,
) : Comparable<EpisodeScope> {
    override fun compareTo(other: EpisodeScope): Int {
        val bySeason = season.compareTo(other.season)
        if (bySeason != 0) return bySeason
        // Null episode = whole-season pack, newer than any numbered episode.
        return when {
            episode == null && other.episode == null -> 0
            episode == null -> 1
            other.episode == null -> -1
            else -> episode.compareTo(other.episode)
        }
    }

    /** The words the notification carries: "Season 3 Episode 5 is out" or "Season 3 is out". */
    fun label(): String = if (episode != null) {
        "Season $season Episode $episode is out"
    } else {
        "Season $season is out"
    }
}

/**
 * The pure half of the follow check: what the newest release on a page
 * is, and whether it is newer than what the viewer has already seen.
 * No Android, no network, no Room — unit-tested without Robolectric.
 */
object NewEpisodeCheck {

    /** The newest scope across these releases, or null when none names a season. */
    fun newestScope(releases: List<ReleaseDetail>): EpisodeScope? {
        var newest: EpisodeScope? = null
        for (release in releases) {
            val season = release.seasonNumber ?: continue
            val scope = EpisodeScope(season, release.episodeNumber)
            if (newest == null || scope > newest) newest = scope
        }
        return newest
    }

    /** True only when [newest] is strictly newer than [lastSeen]. */
    fun isNew(lastSeen: EpisodeScope?, newest: EpisodeScope?): Boolean {
        if (newest == null) return false
        if (lastSeen == null) return false
        return newest > lastSeen
    }
}
