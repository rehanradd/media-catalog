package com.raddstream.app.player.subtitles

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.screens.PlayerSheet
import kotlin.math.abs
import kotlin.math.roundToLong
import com.raddstream.app.ui.theme.RaddTokens

/**
 * The MX-style subtitle sync control: a live offset for both subtitle render paths.
 *
 * The sign follows the overlay's own convention ([com.raddstream.app.player.subtitle.SubtitleFile]):
 * a POSITIVE offset shows the text later than the picture, a NEGATIVE offset shows it earlier.
 * Every callback here is fired while the finger is still down, so the overlay re-renders and
 * the native track's decoder retimes on the next frame rather than after the sheet is dismissed.
 */
object SubtitleOffset {
    /** The slider and the two fine buttons move in fiftieths of a second (50 ms). */
    const val FINE_STEP_MS = 50L

    /** The two coarse buttons move in half seconds. */
    const val COARSE_STEP_MS = 500L

    /** Matches [com.raddstream.app.player.SubtitleUiSettings.clamped]'s delay window. */
    const val RANGE_MS = 60_000L

    fun clamp(offsetMs: Long): Long = offsetMs.coerceIn(-RANGE_MS, RANGE_MS)

    fun shift(currentMs: Long, deltaMs: Long): Long = clamp(currentMs + deltaMs)

    fun reset(): Long = 0L

    /** Slider output is rounded to fifty milliseconds so the readout never shows noise. */
    fun quantize(offsetMs: Long): Long =
        clamp((offsetMs.toDouble() / FINE_STEP_MS).roundToLong() * FINE_STEP_MS)

    /** `In sync`, `+0.5s` or `+0.05s`; the grid is 50 ms, so the readout keeps hundredths. */
    fun format(offsetMs: Long): String {
        val clamped = clamp(offsetMs)
        if (clamped == 0L) return "In sync"
        val sign = if (clamped > 0) "+" else "-"
        val absMs = abs(clamped)
        val remainder = absMs % 1000
        val fraction = if (remainder % 100L == 0L) {
            (remainder / 100L).toString()
        } else {
            (remainder / 10L).toString().padStart(2, '0')
        }
        return "$sign${absMs / 1000}.${fraction}s"
    }

    /** The plain-language direction shown under the readout. */
    fun describe(offsetMs: Long): String {
        val clamped = clamp(offsetMs)
        return when {
            clamped > 0 -> "Subtitles appear later than the picture"
            clamped < 0 -> "Subtitles appear earlier than the picture"
            else -> "Picture and subtitles are aligned"
        }
    }
}

/**
 * REA #3 (VLC, Nov 2026): the audio sync control — the same live
 * offset as [SubtitleOffset], for the sound instead of the text. The
 * sign matches the subtitle convention: a POSITIVE offset plays the
 * sound later than the picture, a NEGATIVE offset plays it earlier.
 *
 * The window is ±10 s, not the subtitles' ±60 s: the delay processor
 * holds the audio in memory, and ten seconds is already far past any
 * muxing error a host file carries — a wider window is a bigger queue
 * for a correction nobody can hear.
 */
object AudioOffset {

    /** The slider and the two fine buttons move in fiftieths of a second (50 ms). */
    const val FINE_STEP_MS = 50L

    /** The two coarse buttons move in half seconds. */
    const val COARSE_STEP_MS = 500L

    /** Matches [com.raddstream.app.player.audio.DelayAudioProcessor.RANGE_MS]. */
    const val RANGE_MS = 10_000L

    fun clamp(offsetMs: Long): Long = offsetMs.coerceIn(-RANGE_MS, RANGE_MS)

    fun shift(currentMs: Long, deltaMs: Long): Long = clamp(currentMs + deltaMs)

    fun reset(): Long = 0L

    /** Slider output is rounded to fifty milliseconds so the readout never shows noise. */
    fun quantize(offsetMs: Long): Long =
        clamp((offsetMs.toDouble() / FINE_STEP_MS).roundToLong() * FINE_STEP_MS)

    /** `In sync`, `+0.5s` or `+0.05s`; the grid is 50 ms, so the readout keeps hundredths. */
    fun format(offsetMs: Long): String {
        val clamped = clamp(offsetMs)
        if (clamped == 0L) return "In sync"
        val sign = if (clamped > 0) "+" else "-"
        val absMs = abs(clamped)
        val remainder = absMs % 1000
        val fraction = if (remainder % 100L == 0L) {
            (remainder / 100L).toString()
        } else {
            (remainder / 10L).toString().padStart(2, '0')
        }
        return "$sign${absMs / 1000}.${fraction}s"
    }

    /** The plain-language direction shown under the readout. */
    fun describe(offsetMs: Long): String {
        val clamped = clamp(offsetMs)
        return when {
            clamped > 0 -> "Sound plays later than the picture"
            clamped < 0 -> "Sound plays earlier than the picture"
            else -> "Sound and picture are aligned"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncOffsetSheet(
    audioOffsetMs: Long,
    onAudioOffsetChange: (Long) -> Unit,
    offsetMs: Long,
    hasCustomOverlay: Boolean,
    onOffsetChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(onDismiss = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        // Robo #10: the landscape panel no longer scrolls (PlayerSheet),
        // so this sheet scrolls itself like the other four — otherwise a
        // long offset sheet would clip sideways instead of scrolling.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Audio & subtitle sync",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Shift the sound or the text against the picture while playback continues.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            OffsetSection(
                title = "Audio sync",
                offset = audioOffsetMs,
                rangeMs = AudioOffset.RANGE_MS,
                fineStepMs = AudioOffset.FINE_STEP_MS,
                coarseStepMs = AudioOffset.COARSE_STEP_MS,
                format = AudioOffset::format,
                describe = AudioOffset::describe,
                quantize = AudioOffset::quantize,
                shift = AudioOffset::shift,
                reset = AudioOffset::reset,
                onOffsetChange = onAudioOffsetChange,
            )

            Spacer(Modifier.height(18.dp))
            OffsetSection(
                title = "Subtitle sync",
                offset = offsetMs,
                rangeMs = SubtitleOffset.RANGE_MS,
                fineStepMs = SubtitleOffset.FINE_STEP_MS,
                coarseStepMs = SubtitleOffset.COARSE_STEP_MS,
                format = SubtitleOffset::format,
                describe = SubtitleOffset::describe,
                quantize = SubtitleOffset::quantize,
                shift = SubtitleOffset::shift,
                reset = SubtitleOffset::reset,
                onOffsetChange = onOffsetChange,
            )

            Spacer(Modifier.height(14.dp))
            Text(
                text = if (hasCustomOverlay) {
                    "Applied live to the sound and the external subtitle file. Saved for this title — the next episode opens with the same offsets."
                } else {
                    "Applied live to the sound and the embedded track. Saved for this title — the next episode opens with the same offsets. If no text is showing yet, choose a subtitle in Subtitles first."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One sync control — readout card, slider and step buttons — shared by the audio and subtitle sections. */
@Composable
private fun OffsetSection(
    title: String,
    offset: Long,
    rangeMs: Long,
    fineStepMs: Long,
    coarseStepMs: Long,
    format: (Long) -> String,
    describe: (Long) -> String,
    quantize: (Long) -> Long,
    shift: (Long, Long) -> Long,
    reset: () -> Long,
    onOffsetChange: (Long) -> Unit,
) {
    val clamped = offset.coerceIn(-rangeMs, rangeMs)
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = format(clamped),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (clamped == 0L) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = describe(clamped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Slider(
        value = clamped.toFloat(),
        onValueChange = { onOffsetChange(quantize(it.toLong())) },
        valueRange = -rangeMs.toFloat()..rangeMs.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "-${rangeMs / 1000}s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "+${rangeMs / 1000}s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(14.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OffsetButton("-0.5s") { onOffsetChange(shift(clamped, -coarseStepMs)) }
        OffsetButton("-0.05s") { onOffsetChange(shift(clamped, -fineStepMs)) }
        ResetButton { onOffsetChange(reset()) }
        OffsetButton("+0.05s") { onOffsetChange(shift(clamped, fineStepMs)) }
        OffsetButton("+0.5s") { onOffsetChange(shift(clamped, coarseStepMs)) }
    }
}

@Composable
private fun OffsetButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ResetButton(onClick: () -> Unit) {
    Button(
        shape = RaddTokens.Shape.Pill,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text("RESET", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}
