package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.raddstream.app.R
import com.raddstream.app.player.AUTOPLAY_COUNTDOWN_SECONDS
import com.raddstream.app.player.PlayerGlass
import com.raddstream.app.player.PostPlayPreview
import com.raddstream.app.ui.components.CatalogImage
import com.raddstream.app.ui.layout.CardGeometry
import com.raddstream.app.ui.theme.RaddCoral400
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.PlayerChromeTheme
import com.raddstream.app.ui.theme.RaddHudPillIdle
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.RaddWhisperOnInk
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.ui.theme.paint
import com.raddstream.app.viewmodel.formatTime


@Composable
internal fun EndedButtons(
    chrome: PlayerChromeTheme,
    showNextEpisode: Boolean,
    onPlayAgain: () -> Unit,
    onNextEpisode: () -> Unit,
    // PL-12: the "Up next" preview — the next episode's title, number and still,
    // so the end card shows what is coming instead of only a bare button.
    nextEpisodeTitle: String? = null,
    nextEpisodeSeason: Int? = null,
    nextEpisodeEpisode: Int? = null,
    nextEpisodePosterUrl: String? = null,
    // REA #1: the "More to watch" tiles — the server's related titles for
    // the title that just ended. Empty until the ended load returns.
    previews: List<PostPlayPreview> = emptyList(),
    onPreviewClick: (PostPlayPreview) -> Unit = {},
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = chrome.card,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.clickableNoRipple(onPlayAgain),
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Replay10, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.player_play_again), color = Color.White, fontSize = brandSp(14))
                }
            }
            // Only when autoplay-next is off and a numbered successor exists: the
            // episode ends on this button instead of advancing on its own (plan §5
            // step 10). Same card, same tap — a choice, not an ambush.
            if (showNextEpisode) {
                if (nextEpisodeTitle != null || nextEpisodePosterUrl != null) {
                    val number = listOfNotNull(
                        nextEpisodeSeason?.let { "S$it" },
                        nextEpisodeEpisode?.let { "E$it" },
                    ).joinToString(" ")
                    Surface(
                        color = chrome.card,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.clickableNoRipple(onNextEpisode),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (nextEpisodePosterUrl != null) {
                                CatalogImage(
                                    url = nextEpisodePosterUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp, 32.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Column {
                                Text(
                                    stringResource(R.string.player_up_next),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = brandSp(12),
                                )
                                Text(
                                    text = listOfNotNull(
                                        number.ifBlank { null },
                                        nextEpisodeTitle,
                                    ).joinToString(" · "),
                                    color = Color.White,
                                    fontSize = brandSp(14),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Surface(
                    color = chrome.card,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.clickableNoRipple(onNextEpisode),
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.SkipNext, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.player_next_episode), color = Color.White, fontSize = brandSp(14))
                    }
                }
            }
            // REA #1: "More to watch" — the related titles for this title.
            // Only when the ended load returned some; a failed fetch leaves
            // the row out entirely, never a placeholder.
            PostPlayPreviewRow(previews = previews, onPreviewClick = onPreviewClick)
        }
    }
}

/**
 * REA #1: the "More to watch" row shared by both end cards — up to three
 * small poster tiles for the server's related titles, each opening that
 * title's Detail. Renders nothing when the ended load returned nothing:
 * a failed fetch is an empty row, never a placeholder tile.
 */
@Composable
private fun PostPlayPreviewRow(
    previews: List<PostPlayPreview>,
    onPreviewClick: (PostPlayPreview) -> Unit,
) {
    if (previews.isEmpty()) return
    Column {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.player_more_to_watch),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = brandSp(12),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            previews.forEach { preview ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickableNoRipple { onPreviewClick(preview) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CatalogImage(
                        url = preview.posterUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(CardGeometry.POSTER_ASPECT)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preview.title,
                        color = Color.White,
                        fontSize = brandSp(12),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    preview.subtitle?.let { subtitle ->
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = brandSp(11),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The autoplay-next countdown (Session 123, PL-1): what the viewer sees for
 * the ten seconds between an episode's end and the advance — now the
 * up-next end card (Stitch popups mockup), a bottom card, not a centred
 * one: a thin jade ring counting the seconds down around the "10s",
 * "Up next: S2:E5" bold with the episode title under it, an X that
 * cancels, and two pills: Cancel (dark) and Play now (jade).
 *
 * Session 126 (PL-12): the countdown names the episode it is counting toward,
 * and Play now skips the wait — the countdown is a convenience, not a hold.
 * The 10s countdown and the off switch are untouched — only the chrome
 * changed. The ring reads the same total the countdown was started
 * with ([AUTOPLAY_COUNTDOWN_SECONDS]), never a second number.
 */
@Composable
internal fun AutoplayCountdownCard(
    chrome: PlayerChromeTheme,
    secondsLeft: Int,
    onCancel: () -> Unit,
    nextEpisodeTitle: String? = null,
    nextEpisodeSeason: Int? = null,
    nextEpisodeEpisode: Int? = null,
    onPlayNow: (() -> Unit)? = null,
    // REA #1: the "More to watch" tiles — see EndedButtons.
    previews: List<PostPlayPreview> = emptyList(),
    onPreviewClick: (PostPlayPreview) -> Unit = {},
) {
    val seasonEpisode = if (nextEpisodeSeason != null && nextEpisodeEpisode != null) {
        stringResource(R.string.player_season_episode, nextEpisodeSeason, nextEpisodeEpisode)
    } else {
        null
    }
    val headline = if (seasonEpisode != null) {
        stringResource(
            R.string.player_up_next_title,
            stringResource(R.string.player_up_next),
            seasonEpisode,
        )
    } else {
        stringResource(R.string.player_up_next)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = chrome.card,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                // Cinema (Nov 2026): 120 → 116dp — the same seat as the
                // toasts, above the slimmer bottom bar (104dp).
                .padding(start = 16.dp, end = 16.dp, bottom = 116.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The countdown as a ring, not a sentence: the seconds
                    // are the text, the ring is the progress.
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { secondsLeft / AUTOPLAY_COUNTDOWN_SECONDS.toFloat() },
                            color = RaddCurrent500,
                            trackColor = Color.White.copy(alpha = 0.2f),
                            strokeWidth = 3.dp,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            "${secondsLeft}s",
                            color = Color.White,
                            fontSize = brandSp(14),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            headline,
                            color = Color.White,
                            fontSize = brandSp(16),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (nextEpisodeTitle != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                nextEpisodeTitle,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = brandSp(14),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(32.dp).align(Alignment.Top),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.player_close),
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RaddHudPillIdle,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.weight(1f).heightIn(min = MinControlTarget),
                    ) {
                        Text(stringResource(R.string.player_cancel))
                    }
                    onPlayNow?.let {
                        Button(
                            onClick = it,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RaddCurrent500,
                                contentColor = Color.Black,
                            ),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.weight(1f).heightIn(min = MinControlTarget),
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.player_autoplay_play_now),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                    // REA #1: "More to watch" — the related titles for the title
                    // that just ended. The row renders only when the ended load
                    // returned some; a failed fetch leaves it out entirely.
                    PostPlayPreviewRow(previews = previews, onPreviewClick = onPreviewClick)
            }
        }
    }
}

@Composable
internal fun ConnectionErrorCard(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Warning, null, tint = RaddCoral400, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            // D-47: the two failure states share one language — the give-up
            // card's body is left-aligned, so this message is too. The icon
            // stays centred, as on the give-up card.
            Text(
                message,
                color = Color.White,
                fontSize = brandSp(14),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.player_retry), color = Color.White) }
        }
    }
}

// PL-3: an offline start or an offline failure shows this immediately, with
// Retry — not just the 30-second deadline card. Waiting half a minute to be
// told there is no network is not a wait, it is a lie.
@Composable
internal fun OfflineCard(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.WifiOff, null, tint = RaddCoral400, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.player_offline_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.player_offline_body),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = brandSp(14),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.player_retry), color = Color.White) }
        }
    }
}

// PL-4: a locked player keeps a small time chip visible — position and
// remaining, without unlocking. Frosted glass on a capable device, solid on a
// low-RAM one (BM rule 9).
@Composable
internal fun LockedTimeChip(positionMs: Long, durationMs: Long, lowRam: Boolean, modifier: Modifier = Modifier) {
    val spec = PlayerGlass.spec(lowRam)
    // GlassSpec carries alphas, not ready-made modifiers: the tint is a dark
    // chip at tintAlpha (opaque when solid). Cinema (Nov 2026): the 1dp white
    // edge at borderAlpha is gone — the solid tint is the chip, no outline.
    Box(
        modifier
            .background(Color.Black.copy(alpha = spec.tintAlpha), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(
                R.string.player_time_of,
                positionMs.formatTime(),
                durationMs.formatTime(),
            ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The player's loading indicator (Session 123, D-36): a real indeterminate
 * progress ring, not the brand mark. The three-wave mark is the logo — a
 * viewer cannot tell whether it is a spinner or a picture — so the wait
 * shows the ring the platform owns, in the action colour, with the reason
 * underneath when the caller has one. The mark stays for brand moments (the
 * splash, the start card's corner) and never stands in for progress.
 */

/**
 * F29 (Batch B): the live mark — a dot and the word Live, in place of the
 * scrubber + time row a live stream does not have. It says what the stream
 * is; it promises no position, because a live window has none to show.
 * Hate-audit H16: the Stitch mockup showed a seek bar + elapsed time on
 * live — copying it would reintroduce the Discover Pakistan 2:00:12 bug
 * (073e454e). A live elapsed counter is meaningless (elapsed since what?)
 * and a bar implies a rewind that does not exist, so the mark stays.
 *
 * Review fix (16 Nov): this is now the SINGLE live mark in the whole
 * chrome — the top-bar pill and the "Live Broadcast" subtitle are gone, so
 * this pill carries the fact alone. A jade dot + white LIVE on an 80%
 * black pill, inside the bottom scrim's opaque zone. While paused the dot
 * mutes to grey and the word reads PAUSED, so a frozen live picture never
 * reads as live.
 */
@Composable
internal fun LiveMark(paused: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            // Cinema (Nov 2026): no outline — the solid 80% black fill is the pill.
            .background(Color.Black.copy(alpha = 0.80f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .heightIn(min = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                // Review fix (16 Nov): the paused dot is the palette's muted
                // grey, not a raw hex — the brand palette owns every paint.
                .background(if (paused) RaddWhisperOnInk else RaddCurrent500, CircleShape),
        )
        Text(
            text = if (paused) stringResource(R.string.player_paused).uppercase()
            else stringResource(R.string.player_live).uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.em,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PlayerLoadingIndicator(
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(RaddTokens.Touch.MinTarget),
            // The ring is jade: the finding card's spinner (Stitch popups
            // mockup), and jade is the only accent anywhere in the player.
            color = RaddCurrent500,
            strokeWidth = 3.dp,
        )
        if (!label.isNullOrBlank()) {
            Spacer(Modifier.height(RaddTokens.Space.Sm))
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
