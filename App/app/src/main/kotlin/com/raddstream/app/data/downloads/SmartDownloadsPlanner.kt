package com.raddstream.app.data.downloads

import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.SeasonGroup
import com.raddstream.app.viewmodel.VipQualityGate

/**
 * The next episode of one series Smart Downloads should fetch, if any.
 *
 * [handle] is the opaque handle of the best keepable copy — the same
 * selection the season-keep plan makes per episode ([seasonKeepPlan]'s
 * twin, for one episode at a time): the best print by [rankKey], its
 * first link with a real handle that is not a page link, and the
 * viewer's quality entitlement. [title] is the episode's own title for
 * the Downloads row; [sizeBytes] is null when the server did not say.
 */
data class SmartDownloadCandidate(
    val handle: String,
    val printLabel: String,
    val title: String,
    val sizeBytes: Long?,
    val season: Int,
    val episode: Int,
)

/**
 * Smart Downloads, plan E2 (feature-gap audit #1): the pure choice of
 * which episode comes next for one series — no network, no storage, no
 * settings here, so the rule is testable on its own. The runner owns
 * the gates (switch, Wi-Fi, space) and the enqueue; this object only
 * answers "which episode, if any".
 *
 * The rule: the first episode, in season/episode order, that the
 * viewer has not finished, has no keep or device download for yet, and
 * has a keepable copy. A pack-only episode is skipped exactly like the
 * season-keep plan skips it (a pack is not one episode), and a
 * VIP-locked best print is skipped, never downloaded — a free viewer
 * never gets a premium copy through the back door.
 */
object SmartDownloadsPlanner {

    /**
     * @param seasons the series' seasons as the detail layout built them.
     * @param watched (season, episode) pairs the viewer has finished.
     * @param keptHandles handles already kept or downloading, so an
     *   episode that is already on the device is never fetched twice.
     * @param after when non-null, only episodes after this (season,
     *   episode) — the episode that just ended, so the runner can ask
     *   for "what comes next" without re-reading the whole history.
     */
    fun nextEpisode(
        seasons: List<SeasonGroup>,
        watched: Set<Pair<Int, Int>>,
        keptHandles: Set<String>,
        premiumAccess: Boolean,
        freeQualityCap: String,
        after: Pair<Int, Int>? = null,
    ): SmartDownloadCandidate? {
        val ordered: List<Pair<Int, EpisodeGroup>> = seasons
            .sortedBy { it.number }
            .flatMap { season ->
                season.episodes.sortedBy { it.numberWithinSeason }
                    .map { episode -> season.number to episode }
            }
        for ((seasonNumber, episode) in ordered) {
            val key = seasonNumber to episode.numberWithinSeason
            if (after != null && key <= after) continue
            if (key in watched) continue
            if (episode.onlyPacked) continue
            if (episode.prints.flatMap { it.links }.any { it.streamId in keptHandles }) continue
            val best = episode.prints.maxByOrNull { it.rankKey } ?: continue
            if (!VipQualityGate.allowsResolution(best.resolution, premiumAccess, freeQualityCap)) continue
            val link = best.links.firstOrNull { !it.streamId.isBlank() && !it.isPageLink } ?: continue
            return SmartDownloadCandidate(
                handle = link.streamId,
                printLabel = best.qualityLabel,
                title = episode.title ?: "Episode ${episode.numberWithinSeason}",
                sizeBytes = best.sizeBytes,
                season = seasonNumber,
                episode = episode.numberWithinSeason,
            )
        }
        return null
    }
}
