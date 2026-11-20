package com.raddstream.app.player

import com.raddstream.app.network.models.Post

/**
 * REA #1 (Netflix, Nov 2026): the post-play screen's "More to watch" tiles.
 *
 * Netflix ends a title on a post-play screen — a countdown ring, the next
 * episode named, and preview tiles for what to watch next — instead of a
 * bare "Play again" button. This app already had the countdown ring, the
 * named next episode and the Cancel / Play now pills (Session 123 PL-1,
 * Session 126 PL-12); what it did not have was the tiles. These are the
 * pure rules for them, so the merge, the cap and the in-playback pill
 * window are all JVM-unit-testable without Android.
 *
 * The tiles are the server's own "Because you watch" answer for the title
 * that just ended (GET /api/v1/related, already used by Home) — real
 * titles from the catalog, never invented: a failed fetch is an empty row,
 * never a placeholder tile. The next episode is NOT a tile here: both end
 * cards already show it as the "Up next" hero, so repeating it as a tile
 * would say the same thing twice.
 */
data class PostPlayPreview(
    val postId: Int,
    val title: String,
    val posterUrl: String?,
    val subtitle: String?,
)

/** At most this many preview tiles on the post-play screen. */
const val POST_PLAY_MAX_PREVIEWS = 3

/**
 * The in-playback Next Episode pill appears in the last this-many
 * milliseconds of an episode whose successor is already prepared — the
 * same moment Netflix surfaces its Next button, early enough to be
 * reached before the end card, late enough not to sit on the picture
 * through the episode.
 */
const val IN_PLAYBACK_NEXT_WINDOW_MS = 30_000L

/** One related title as a post-play tile: its poster, its clean title, "2026 · Series". */
fun Post.toPostPlayPreview(): PostPlayPreview = PostPlayPreview(
    postId = id,
    title = displayTitle,
    posterUrl = posterUrl?.takeIf { it.isNotBlank() },
    subtitle = listOfNotNull(yearLabel, typeLabel).joinToString(" · ").takeIf { it.isNotBlank() },
)

/**
 * The tiles to show: the related titles in server order, de-duplicated by
 * catalog id, capped at [POST_PLAY_MAX_PREVIEWS]. The order is the
 * server's — this function never re-ranks, so what the viewer sees is
 * what the server recommended, in the order it recommended it.
 */
fun mergePostPlayPreviews(similar: List<Post>, max: Int = POST_PLAY_MAX_PREVIEWS): List<PostPlayPreview> =
    similar.distinctBy { it.id }.take(max).map { it.toPostPlayPreview() }

/**
 * Whether the in-playback Next Episode pill is owed right now: the
 * successor is prepared (its title is published), the file is in its
 * last [IN_PLAYBACK_NEXT_WINDOW_MS], and the player is neither ended
 * nor locked — an ended or locked player has no transport jump to offer.
 */
fun showInPlaybackNext(
    positionMs: Long,
    durationMs: Long,
    hasNextEpisodeTitle: Boolean,
    isEnded: Boolean,
    locked: Boolean,
): Boolean = !isEnded && !locked && hasNextEpisodeTitle && durationMs > 0 &&
    positionMs >= durationMs - IN_PLAYBACK_NEXT_WINDOW_MS
