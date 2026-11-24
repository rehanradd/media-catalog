package com.raddstream.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raddstream.app.R
import com.raddstream.app.player.AudioBoost
import com.raddstream.app.player.AudioBoostUi
import com.raddstream.app.player.AudioTrackMatcher
import com.raddstream.app.player.AudioTrackPolicy
import com.raddstream.app.player.chapterTicks
import com.raddstream.app.player.GestureMapper
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.audio.EQ_GAIN_MAX_DB
import com.raddstream.app.player.audio.EQ_GAIN_MIN_DB
import com.raddstream.app.player.audio.EqPresets
import com.raddstream.app.player.QualityOption
import com.raddstream.app.player.SleepMode
import com.raddstream.app.player.SleepTimerState
import com.raddstream.app.player.StartDeadline
import com.raddstream.app.player.SubtitleColor
import com.raddstream.app.player.SubtitleEdge
import com.raddstream.app.player.SubtitleFont
import com.raddstream.app.player.subtitles.SubtitleOffset
import com.raddstream.app.player.qualityCostPerHour
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.viewmodel.PlayerViewModel
import com.raddstream.app.viewmodel.formatTime
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


// ── Sheets ─────────────────────────────────────────────────────────────────────

/**
 * The playback-speed sheet (Stitch popups mockup): a bottom sheet with
 * a drag handle (ModalBottomSheet's own, via PlayerSheet), a title with
 * an X, and the six speeds as vertical rows — the current one jade with
 * a check. The old horizontal chip strip is gone. The speed itself is
 * untouched: it still resets to 1.0 per title, and the 2× boost note
 * stays below the rows.
 */
@Composable
internal fun SpeedSheet(state: PlayerUiState, vm: PlayerViewModel) {
    PlayerSheet(onDismiss = { vm.hideSpeedSheet() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            SheetTitle(stringResource(R.string.player_speed_title), onDismiss = { vm.hideSpeedSheet() })
            GestureMapper.SHEET_PRESETS.forEach { preset ->
                SheetRow(
                    label = stringResource(R.string.player_speed_short, preset),
                    selected = state.speed == preset,
                ) { vm.setSpeed(preset) }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.player_boost_note),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = brandSp(12),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QualitySheet(state: PlayerUiState, vm: PlayerViewModel) {
    PlayerSheet(onDismiss = { vm.hideQualitySheet() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            SheetTitle(stringResource(R.string.player_quality_title))
            QualityRow(
                title = stringResource(R.string.player_quality_auto),
                detail = if (state.qualityAuto) stringResource(R.string.player_quality_auto_detail) else null,
                selected = state.qualityAuto,
                onClick = { vm.selectQualityBand("auto") },
            )
            // 26: Data Saver sits next to Auto — the viewer who is on mobile data
            // caps Auto at 480p from the same sheet that picks the quality.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.player_data_saver),
                        color = Color.White,
                        fontSize = brandSp(14),
                    )
                    Text(
                        stringResource(R.string.player_data_saver_detail),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = brandSp(12),
                    )
                }
                Switch(checked = state.dataSaver, onCheckedChange = { vm.toggleDataSaver() })
            }
            state.qualityRungs.forEach { rung ->
                val selected = !state.qualityAuto && rung.band == state.qualityBand
                QualityRow(
                    title = rung.label?.takeIf { it.isNotBlank() } ?: rung.band.uppercase(),
                    // The cost shows before the tap (plan §5 step 9) — the "Playing"
                    // word only joins it when this rung is the one playing now.
                    // 2-button detail (Nov 2026): the size when the server
                    // named one (never invented), and the format in plain
                    // words — "plays in this app" for a playlist stream,
                    // "plays in any app" for a direct file. A rung whose
                    // copy was never probed claims neither.
                    detail = listOfNotNull(
                        qualityCostPerHour(rung.band),
                        rung.sizeBytes?.takeIf { it > 0 }?.let { QualityOption.formatBytes(it) },
                        when (rung.isHls) {
                            true -> stringResource(R.string.player_plays_in_this_app)
                            false -> stringResource(R.string.player_plays_in_any_app)
                            null -> null
                        },
                        if (selected) stringResource(R.string.player_playing) else null,
                    ).joinToString(" · ").takeIf { it.isNotEmpty() },
                    selected = selected,
                    onClick = { vm.selectQualityBand(rung.band) },
                )
            }
            Text(
                stringResource(R.string.player_quality_note),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = brandSp(12),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun QualityRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .heightIn(min = MinControlTarget)
            // D-43: a selected rung is filled, not just green text.
            .background(
                if (selected) RaddCurrent500.copy(alpha = 0.15f) else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (selected) RaddCurrent500 else Color.White,
            fontSize = brandSp(14),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, color = Color.White.copy(alpha = 0.5f), fontSize = brandSp(12))
        }
        Spacer(Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, null, tint = RaddCurrent500, modifier = Modifier.size(18.dp))
    }
}

/**
 * One source in the Switch source sheet (Stitch popups mockup): the
 * quality label, the file's real size when the bundle knows it, a small
 * grey "Playing" pill on the print that is playing now, and the jade
 * check — the same selected-row language as [QualityRow], with the pill
 * the mockup draws instead of a plain "Playing" suffix. No size is ever
 * invented: sizeBytes 0 means the bundle never measured it, so the row
 * shows the label alone. Internal, like the other mockup surfaces, so
 * the row can be composed in a test.
 */
@Composable
internal fun SwitchSourceRow(title: String, sizeText: String?, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .heightIn(min = MinControlTarget)
            .background(
                if (playing) RaddCurrent500.copy(alpha = 0.15f) else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (playing) RaddCurrent500 else Color.White,
            fontSize = brandSp(14),
            fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (sizeText != null) {
            Spacer(Modifier.width(8.dp))
            Text(sizeText, color = Color.White.copy(alpha = 0.5f), fontSize = brandSp(12))
        }
        if (playing) {
            Spacer(Modifier.width(8.dp))
            Surface(
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    stringResource(R.string.player_playing),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = brandSp(11),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (playing) Icon(Icons.Default.Check, null, tint = RaddCurrent500, modifier = Modifier.size(18.dp))
    }
}

/**
 * The sleep-timer sheet (Stitch popups mockup): a bottom sheet with a
 * drag handle, a title with an X, and the rows in the mockup's order —
 * Off first (checked while the timer is off), then 15/30/45/60 minutes,
 * then End of episode. A running countdown marks its own preset: the
 * smallest offered preset the remaining time still fits in, so the check
 * follows the timer instead of freezing at the pick. The fading line and
 * the fade note stay; the behaviour is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepSheet(state: PlayerUiState, vm: PlayerViewModel) {
    PlayerSheet(onDismiss = { vm.hideSleepSheet() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp),
        ) {
            SheetTitle(stringResource(R.string.player_sleep_timer), onDismiss = { vm.hideSleepSheet() })
            if (state.sleep.mode == SleepMode.COUNTDOWN) {
                Text(
                    stringResource(R.string.player_sleep_fading, state.sleep.remainingMs.formatTime()),
                    color = RaddCurrent500,
                    fontSize = brandSp(14),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            // The preset the countdown is running from: remaining time only
            // ever falls, so it is the smallest offered preset it still
            // fits in. Zero remaining means the timer fired — no preset.
            val runningPreset = if (state.sleep.mode == SleepMode.COUNTDOWN && state.sleep.remainingMs > 0) {
                SleepTimerState.SHEET_PRESETS_MS.filter { it >= state.sleep.remainingMs }.minOrNull()
            } else {
                null
            }
            SheetRow(
                label = stringResource(R.string.player_off_label),
                selected = state.sleep.mode == SleepMode.OFF,
            ) { vm.cancelSleep() }
            SleepTimerState.SHEET_PRESETS_MS.forEach { preset ->
                SheetRow(
                    label = stringResource(R.string.player_sleep_minutes, preset / 60_000),
                    selected = preset == runningPreset,
                ) { vm.startSleepCountdown(preset) }
            }
            SheetRow(
                label = stringResource(R.string.player_sleep_at_end),
                selected = state.sleep.mode == SleepMode.END_OF_VIDEO,
            ) { vm.sleepAtVideoEnd() }
            Text(
                stringResource(R.string.player_sleep_note, state.sleep.fadeMs / 1000),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = brandSp(12),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleSettingsSheet(state: PlayerUiState, vm: PlayerViewModel) {
    val ctx = LocalContext.current
    val externalSubtitleFallback = stringResource(R.string.player_external_subtitle)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(ctx, uri) ?: externalSubtitleFallback
            val hint = name.substringAfterLast('.', "srt").lowercase()
            vm.addExternalSubtitle(url = uri.toString(), label = name, formatHint = hint)
        }
    }
    PlayerSheet(onDismiss = { vm.hideSubtitleSheet() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SheetTitle(stringResource(R.string.player_audio_subtitles_title))
            // Hate-audit H25: the audio-language switch lives here now, one
            // tap from the Subtitles primary — the Plex pattern was audio
            // buried two taps deep in More. The full audio controls (stereo,
            // audio-only, boost) stay in the Tracks sheet behind More; this
            // section is the language switch only.
            if (state.audioTracks.isNotEmpty()) {
                SheetSection(stringResource(R.string.player_audio_title))
                state.audioTracks.forEach { track ->
                    SheetRow(
                        label = AudioTrackMatcher.displayableLanguageTag(track.language)
                            ?.let { stringResource(R.string.player_track_with_language, track.label, it) }
                            ?: track.label,
                        selected = track.id == state.selectedAudioId || track.selected,
                    ) { vm.selectAudioTrack(track) }
                }
                // The preferred-audio note names the preference only when it
                // names a real language: a stored preference of "und" or
                // "Unknown" is a missing value, not a language, so the line
                // is hidden instead of reading "Preferred audio: und".
                val preferredAudioName = state.preferredAudioLanguage?.let {
                    AudioTrackPolicy.nameFor(it) ?: AudioTrackMatcher.displayableLanguageTag(it)
                }
                if (preferredAudioName != null) {
                    Text(
                        stringResource(
                            R.string.player_preferred_audio,
                            preferredAudioName,
                        ),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = brandSp(11),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            // App-design audit #6: a subtitle sheet with no tracks is one
            // clear action, not an empty panel with a size slider for a
            // track that does not exist. The size/position/style controls
            // below only appear once a track is loaded.
            val hasTrack = state.subtitleTracks.isNotEmpty() ||
                state.customSubtitleLoaded != null || state.nativeExternals.isNotEmpty()
            if (!hasTrack) {
                Text(
                    stringResource(R.string.player_subtitle_none),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = brandSp(12),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.player_subtitle_browse))
                }
                Spacer(Modifier.height(8.dp))
                return@Column
            }
            SheetSection(stringResource(R.string.player_sheet_section_tracks))
            state.subtitleTracks.forEach { track ->
                SheetRow(label = track.label, selected = track.id == state.selectedSubtitleId) {
                    vm.selectSubtitleTrack(track)
                }
            }
            if (state.selectedSubtitleId != null) {
                SheetRow(label = stringResource(R.string.player_subtitle_turn_off)) { vm.selectSubtitleTrack(null) }
            }

            Spacer(Modifier.height(8.dp))
            SheetSection(stringResource(R.string.player_sheet_section_appearance))
            Text(
                stringResource(R.string.player_subtitle_size, state.subtitle.sizePercent),
                color = Color.White,
                fontSize = brandSp(14),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Slider(
                value = state.subtitle.sizePercent.toFloat(),
                onValueChange = { vm.setSubtitleSettings(state.subtitle.copy(sizePercent = it.toInt())) },
                valueRange = 50f..200f,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Text(
                stringResource(R.string.player_subtitle_position, (state.subtitle.bottomFraction * 100).toInt()),
                color = Color.White,
                fontSize = brandSp(14),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Slider(
                value = state.subtitle.bottomFraction,
                onValueChange = { vm.setSubtitleSettings(state.subtitle.copy(bottomFraction = it)) },
                valueRange = 0f..0.3f,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            // One control owns the offset: this row hands over to the sheet with the live
            // slider and the ±0.05s/±0.5s buttons, so the two can never disagree.
            SheetRow(label = stringResource(R.string.player_subtitle_sync, SubtitleOffset.format(state.subtitle.delayMs))) {
                vm.showSubtitleOffsetSheet()
            }

            val switchHaptics = LocalHapticFeedback.current
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_subtitle_bold), color = Color.White, fontSize = brandSp(14), modifier = Modifier.weight(1f))
                Switch(
                    checked = state.subtitle.bold,
                    onCheckedChange = {
                        switchHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.setSubtitleSettings(state.subtitle.copy(bold = it))
                    },
                    thumbContent = if (state.subtitle.bold) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null,
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_subtitle_background), color = Color.White, fontSize = brandSp(14), modifier = Modifier.weight(1f))
                Switch(
                    checked = state.subtitle.textBackground,
                    onCheckedChange = {
                        switchHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.setSubtitleSettings(state.subtitle.copy(textBackground = it))
                    },
                    thumbContent = if (state.subtitle.textBackground) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null,
                )
            }

            Text(
                stringResource(R.string.player_subtitle_colour),
                color = Color.White,
                fontSize = brandSp(14),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SubtitleColor.entries.forEach { colour ->
                    SubtitleColourSwatch(
                        colour = colour,
                        selected = colour == state.subtitle.colour,
                        onSelect = { vm.setSubtitleSettings(state.subtitle.copy(colour = colour)) },
                    )
                }
            }

            Text(
                stringResource(R.string.player_subtitle_font),
                color = Color.White,
                fontSize = brandSp(14),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SubtitleFont.entries.forEach { font ->
                SheetRow(
                    label = font.label,
                    selected = font == state.subtitle.font,
                ) { vm.setSubtitleSettings(state.subtitle.copy(font = font)) }
            }

            Text(
                stringResource(R.string.player_subtitle_edge),
                color = Color.White,
                fontSize = brandSp(14),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SubtitleEdge.entries.forEach { edge ->
                SheetRow(
                    label = edge.label,
                    selected = edge == state.subtitle.edge,
                ) { vm.setSubtitleSettings(state.subtitle.copy(edge = edge)) }
            }

            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_subtitle_embedded_styles), color = Color.White, fontSize = brandSp(14), modifier = Modifier.weight(1f))
                Switch(
                    checked = state.subtitle.useEmbeddedStyles,
                    onCheckedChange = {
                        switchHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.setSubtitleSettings(state.subtitle.copy(useEmbeddedStyles = it))
                    },
                    thumbContent = if (state.subtitle.useEmbeddedStyles) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null,
                )
            }
            Text(
                stringResource(R.string.player_subtitle_embedded_styles_note),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = brandSp(11),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.player_subtitle_gesture_hint),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = brandSp(11),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(8.dp))
            SheetRow(label = stringResource(R.string.player_subtitle_load)) {
                picker.launch(arrayOf("*/*"))
            }
            if (state.customSubtitleLoaded != null) {
                SheetRow(label = stringResource(R.string.player_subtitle_remove, state.customSubtitleLoaded ?: "")) { vm.removeCustomSubtitle() }
                Text(
                    stringResource(R.string.player_subtitle_sync_note),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = brandSp(11),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TracksSheet(state: PlayerUiState, vm: PlayerViewModel) {
    PlayerSheet(onDismiss = { vm.hideTracksSheet() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SheetTitle(stringResource(R.string.player_audio_tracks))
            if (state.audioTracks.isEmpty()) {
                Text(
                    stringResource(R.string.player_track_none),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = brandSp(12),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.audioTracks.forEach { track ->
                SheetRow(
                    label = track.language
                        ?.let { stringResource(R.string.player_track_with_language, track.label, it) }
                        ?: track.label,
                    selected = track.id == state.selectedAudioId || track.selected,
                ) { vm.selectAudioTrack(track) }
            }
            if (state.audioTracks.isNotEmpty()) {
                SheetRow(label = stringResource(R.string.player_cycle_audio), enabled = state.canCycleAudio) {
                    vm.cycleAudioTrack()
                }
                // The preferred-audio note names the preference only when it
                // names a real language: a stored preference of "und" or
                // "Unknown" is a missing value, not a language, so the line
                // is hidden instead of reading "Preferred audio: und".
                val preferredAudioName = state.preferredAudioLanguage?.let {
                    AudioTrackPolicy.nameFor(it) ?: AudioTrackMatcher.displayableLanguageTag(it)
                }
                if (preferredAudioName != null) {
                    Text(
                        stringResource(
                            R.string.player_preferred_audio,
                            preferredAudioName,
                        ),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = brandSp(11),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SheetRow(
                label = if (state.stereoOnly) stringResource(R.string.player_stereo_on) else stringResource(R.string.player_stereo_off),
            ) { vm.toggleStereoOnly() }
            Text(
                stringResource(R.string.player_stereo_note),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = brandSp(11),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SheetRow(
                label = if (state.audioOnly) stringResource(R.string.player_audio_only_on) else stringResource(R.string.player_audio_only_off),
            ) { vm.toggleAudioOnly() }

            // ── Audio level. Media3's setVolume contract stops at 100%, so the
            //    part of the level above unity goes to the enhancement
            //    processor as software gain (MX idea #8): the slider runs
            //    the full 0..200% and every stop on it is applied. The
            //    caption states the applied percent.
            Spacer(Modifier.height(14.dp))
            SheetSection(stringResource(R.string.player_audio_level))
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    AudioBoost.labelFor(state.audioBoostLevel),
                    color = RaddCurrent500,
                    fontSize = brandSp(14),
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = state.audioBoostLevel.coerceIn(0f, AudioBoostUi.MAX_OFFERED_LEVEL),
                    onValueChange = { vm.setAudioBoostLevel(it) },
                    valueRange = 0f..AudioBoostUi.MAX_OFFERED_LEVEL,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                AudioBoostUi.honestSummary(state.audioBoostLevel, state.userVolume),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = brandSp(11),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * MX #6 (chrome diet): a moment is shareable only when the playing rendition
 * carries a stream handle to link to. A local file has none (streamId is
 * blank or "local") — the same guard `PlayerViewModel.shareMoment()` applies
 * before emitting. Pure, so the sheet and the tests read the same rule.
 */
internal fun canShareMoment(state: PlayerUiState): Boolean {
    val streamId = state.activeRendition?.playback?.streamId?.trim()
    return !streamId.isNullOrBlank() && streamId != "local"
}

/**
 * The More sheet (plan §4.3): every control that is not one of the six primaries,
 * grouped exactly like MX's overflow — Playback / Subtitle / Audio / Tools. Nothing
 * was deleted in step 3, only moved here from the bottom row; every row below calls
 * the same ViewModel function its button used to call. A row that opens another
 * sheet or dialog hides More first (the PrintLogSheet pattern), so two sheets never
 * stack; a row that acts directly closes More too, so the viewer sees the picture,
 * not the menu, after the tap. Lock is not here — it lives in the top bar already.
 * The audio-level slider lives inside the TracksSheet the Audio tracks row opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreSheet(state: PlayerUiState, vm: PlayerViewModel, onEnterPip: () -> Unit) {
    PlayerSheet(onDismiss = { vm.hideMoreSheet() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SheetSection(stringResource(R.string.player_more_playback))
            SheetRow(label = stringResource(R.string.player_speed_label, state.speed)) {
                vm.hideMoreSheet()
                vm.showSpeedSheet()
            }
            SheetRow(label = stringResource(R.string.player_sleep_timer)) {
                vm.hideMoreSheet()
                vm.showSleepSheet()
            }
            SheetRow(label = stringResource(R.string.player_frame_back)) {
                vm.hideMoreSheet()
                vm.stepFrame(-1)
            }
            SheetRow(label = stringResource(R.string.player_frame_forward)) {
                vm.hideMoreSheet()
                vm.stepFrame(1)
            }
            // A-B repeat (§5 P14): set the two points of the loop. The sheet stays
            // open for both taps — setting A then B is one visit, not two — and the
            // row labels show the points once they are set, so the segment is visible.
            SheetRow(
                label = state.abRepeat.aMs?.let {
                    stringResource(R.string.player_ab_point, "A", it.formatTime())
                } ?: stringResource(R.string.player_ab_set_a),
            ) {
                vm.setAbPointA()
            }
            SheetRow(
                label = state.abRepeat.bMs?.let {
                    stringResource(R.string.player_ab_point, "B", it.formatTime())
                } ?: stringResource(R.string.player_ab_set_b),
            ) {
                vm.setAbPointB()
            }
            if (state.abRepeat.hasA || state.abRepeat.hasB) {
                SheetRow(label = stringResource(R.string.player_ab_clear)) {
                    vm.clearAbRepeat()
                }
            }
            SheetRow(label = stringResource(R.string.player_rewind_10)) {
                vm.hideMoreSheet()
                vm.seekBy(-10_000)
            }
            SheetRow(label = stringResource(R.string.player_forward_10)) {
                vm.hideMoreSheet()
                vm.seekBy(10_000)
            }
            SheetRow(label = stringResource(R.string.player_aspect_label, state.aspect.label)) {
                vm.hideMoreSheet()
                vm.cycleAspect()
            }
            SheetRow(label = stringResource(R.string.player_orientation_label, state.orientation.label)) {
                vm.hideMoreSheet()
                vm.toggleOrientation()
            }
            if (state.pureCinemaAllowed) {
                SheetRow(label = stringResource(R.string.player_cinema_mode)) {
                    vm.hideMoreSheet()
                    vm.toggleViewMode()
                }
            }
            SheetSection(stringResource(R.string.player_subtitles_title))
            SheetRow(
                label = stringResource(
                    R.string.player_subtitles_label,
                    subtitleStatusLabel(state, stringResource(R.string.player_off)),
                ),
            ) {
                vm.hideMoreSheet()
                vm.showSubtitleSheet()
            }
            SheetRow(
                label = stringResource(
                    R.string.player_subtitle_sync,
                    SubtitleOffset.format(state.subtitle.delayMs),
                ),
            ) {
                vm.hideMoreSheet()
                vm.showSubtitleOffsetSheet()
            }
            SheetSection(stringResource(R.string.player_audio_title))
            SheetRow(label = stringResource(R.string.player_audio_tracks)) {
                vm.hideMoreSheet()
                vm.showTracksSheet()
            }
            SheetRow(label = stringResource(R.string.player_cycle_audio), enabled = state.canCycleAudio) {
                vm.hideMoreSheet()
                vm.cycleAudioTrack()
            }
            SheetRow(label = stringResource(R.string.player_audio_only_toggle)) {
                vm.hideMoreSheet()
                vm.toggleAudioOnly()
            }
            SheetRow(label = stringResource(R.string.player_stereo_only_toggle)) {
                vm.hideMoreSheet()
                vm.toggleStereoOnly()
            }
            // MX idea #8: the equalizer lives here, in the Audio section —
            // one tap from the boost slider's sheet, never a permanent
            // TopBar button (the chrome diet). The row shows the current
            // preset so the viewer knows what is playing.
            SheetRow(
                label = stringResource(
                    R.string.player_equalizer_with_preset,
                    eqPresetLabel(state.eqState.presetId),
                ),
            ) {
                vm.hideMoreSheet()
                vm.showEqSheet()
            }
            SheetSection(stringResource(R.string.player_more_tools))
            // PL-11: "Next episode" and "Calibrate" are series rows — a movie or
            // a local file has no next episode to play and nothing to calibrate.
            if (state.isSeriesScope) {
                SheetRow(label = stringResource(R.string.player_next_episode)) {
                    vm.hideMoreSheet()
                    vm.requestNextEpisode()
                }
            }
            SheetRow(label = stringResource(R.string.player_play_from_row)) {
                vm.hideMoreSheet()
                vm.showPlayFromDialog()
            }
            if (state.isSeriesScope) {
                SheetRow(label = stringResource(R.string.player_calibrate_row)) {
                    vm.hideMoreSheet()
                    vm.openCalibration()
                }
            }
            SheetRow(label = stringResource(R.string.player_prints_title)) {
                vm.hideMoreSheet()
                vm.showPrintSheet()
            }
            // MX #6 (chrome diet, quick-win F3): the row is hidden for local
            // bundles — the VM's shareMoment() guard is the second door, and
            // this is the first door its doc already promised. A row that can
            // only no-op is chrome that lies.
            if (canShareMoment(state)) {
                SheetRow(label = stringResource(R.string.player_share_moment)) {
                    vm.hideMoreSheet()
                    vm.shareMoment()
                }
            }
            SheetRow(label = stringResource(R.string.player_retry_row)) {
                vm.hideMoreSheet()
                vm.retryCurrentCandidates()
            }
            SheetRow(label = stringResource(R.string.player_open_external_row)) {
                vm.hideMoreSheet()
                vm.openWithExternalPlayer()
            }
            SheetRow(label = stringResource(R.string.player_pip)) {
                vm.hideMoreSheet()
                onEnterPip()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PrintLogSheet(state: PlayerUiState, vm: PlayerViewModel) {
    // PL-6: the ranks, the failover log and the failover policy are debug, not
    // viewer-facing — they sit behind a long-press on the "Prints" title. The
    // sheet itself shows only quality, format and which print is playing.
    var debugVisible by remember { mutableStateOf(false) }
    PlayerSheet(onDismiss = { vm.hidePrintSheet() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SheetTitle(
                stringResource(R.string.player_prints_title),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { debugVisible = !debugVisible },
                ),
                onDismiss = { vm.hidePrintSheet() },
            )
            // The start budget's proof (§5 P5): route-open → first-frame, measured on this
            // device. Absent until the first start lands — a switch mid-scene must not
            // overwrite it, so the ViewModel only records genuine starts.
            // Dev-cleanup (Nov 2026, F1): a start-timing stat is an owner diagnostic, not
            // viewer copy — it shows only behind the same long-press gate as the
            // failover log below.
            if (debugVisible) {
                state.lastFirstFrameMs?.let { firstFrameMs ->
                    Text(
                        stringResource(
                            R.string.player_first_frame,
                            StartDeadline.formatSeconds(firstFrameMs),
                        ),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = brandSp(12),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            state.bundle?.renditions?.forEach { r ->
                val active = r.rank == state.activeRendition?.rank
                // PL-6: the row names the quality and the format, and marks the
                // print that is playing. The rank is debug — it shows only
                // behind the long-press above. Hate-audit H12/H17: the rows
                // are tappable now — the sheet is "Switch source", and a tap
                // loads that print at the current position; the HLS tag is
                // gone (jargon — the quality label is enough). The row is a
                // SwitchSourceRow (Stitch popups mockup): the file's real
                // size when the bundle knows it, a small grey "Playing" pill
                // on the print that is playing, and the jade check.
                val rowText = buildString {
                    append(r.displayLabel())
                    if (debugVisible) append("  ·  #").append(r.rank)
                }
                SwitchSourceRow(
                    title = rowText,
                    sizeText = r.print.sizeBytes.takeIf { it > 0 }?.let { QualityOption.formatBytes(it) },
                    playing = active,
                    onClick = { vm.switchRendition(r) },
                )
            }
            // The note the header used to carry, as the mockup's footer:
            // switching keeps the viewer's place. It sits right under the
            // source rows, before the chapters and the debug sections.
            Text(
                stringResource(R.string.player_switch_keeps_place),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = brandSp(12),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Chapters (plan §5 step 11): the pack's episode boundaries as rows that
            // seek on tap. Absent for a single-episode file — no boundaries, no list.
            // The ticks come from the same pure function the scrubber draws, so the
            // two can never disagree about where a chapter starts.
            val chapters = remember(state.activeRendition?.coverage?.episodes, state.durationMs) {
                chapterTicks(state.activeRendition?.coverage?.episodes.orEmpty(), state.durationMs)
            }
            if (chapters.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SheetSection(stringResource(R.string.player_chapters_title))
                chapters.forEach { tick ->
                    SheetRow(label = stringResource(R.string.player_chapter_row, tick.title, tick.offsetMs.formatTime())) {
                        vm.hidePrintSheet()
                        vm.seekTo(tick.offsetMs)
                    }
                }
            }
            // PL-6: the failover log and the policy are debug — a viewer needs to
            // know which print is playing, not the ladder it climbed. They show
            // only behind the long-press on the "Prints" title.
            if (debugVisible && state.failoverLog.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SheetSection(stringResource(R.string.player_failover_log))
                state.failoverLog.forEach { record ->
                    Text(
                        stringResource(
                            R.string.player_failover_record,
                            record.rank,
                            record.label,
                            record.reason,
                            record.action,
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = brandSp(12),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }
            if (debugVisible) {
                state.bundle?.failurePolicy?.let { p ->
                    Text(
                        stringResource(R.string.player_failover_policy, p.retrySame, p.backoffMs, p.maxRanks),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = brandSp(11),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SheetRow(label = stringResource(R.string.player_play_from_row)) { vm.showPlayFromDialog() }
            if (state.canSwitchQuality) {
                SheetRow(label = stringResource(R.string.player_change_quality, state.qualityLabel)) {
                    vm.hidePrintSheet()
                    vm.showQualitySheet()
                }
            }
            SheetRow(label = stringResource(R.string.player_calibrate_row)) { vm.openCalibration() }
            SheetRow(label = stringResource(R.string.player_retry_row)) { vm.retryCurrentCandidates() }
            SheetRow(label = stringResource(R.string.player_open_external_row)) { vm.openWithExternalPlayer() }
        }
    }
}

/**
 * A sheet's title row — the name on the left, and an X on the right
 * where the sheet takes one (speed, sleep, switch-source: the Stitch
 * sheets all carry it). Sheets without an X (subtitle, tracks, quality,
 * more) pass nothing and look exactly as before. The caller's modifier
 * (the switch-source sheet's long-press debug toggle) stays on the
 * title text, not the X.
 */
@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = brandSp(16),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).then(modifier),
        )
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    stringResource(R.string.player_close),
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * MX idea #8 — the equalizer sheet. One on/off row, the five named
 * presets, and five band sliders (−12..+12 dB, 1 dB steps). Every move
 * applies live through the ViewModel and is persisted, so the mix
 * survives the next episode and the next session. A slider move off a
 * preset becomes Custom; tapping a preset re-enables the EQ.
 *
 * Kids Lock: this sheet is only reachable from the More sheet, and the
 * whole chrome (More button included) is not composed while the player
 * is locked, so the EQ inherits the gate — there is no separate lock
 * check here.
 */
@Composable
internal fun EqSheet(state: PlayerUiState, vm: PlayerViewModel) {
    PlayerSheet(onDismiss = { vm.hideEqSheet() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SheetTitle(stringResource(R.string.player_equalizer))
            val switchHaptics = LocalHapticFeedback.current
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        if (state.eqState.enabled) R.string.player_eq_on else R.string.player_eq_off,
                    ),
                    color = Color.White,
                    fontSize = brandSp(14),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.eqState.enabled,
                    onCheckedChange = {
                        switchHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.setEqEnabled(it)
                    },
                    thumbContent = if (state.eqState.enabled) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                    } else null,
                )
            }
            Spacer(Modifier.height(8.dp))
            SheetSection(stringResource(R.string.player_eq_presets_title))
            EqPresets.ALL.forEach { preset ->
                SheetRow(
                    label = eqPresetLabel(preset.id),
                    selected = state.eqState.presetId == preset.id,
                ) { vm.setEqPreset(preset.id) }
            }
            Spacer(Modifier.height(8.dp))
            SheetSection(stringResource(R.string.player_eq_bands_title))
            state.eqState.gainsDb.forEachIndexed { index, gain ->
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(eqBandLabelRes(index)),
                        color = Color.White,
                        fontSize = brandSp(14),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.player_eq_gain, gain.roundToInt()),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = brandSp(12),
                    )
                }
                Slider(
                    value = gain,
                    onValueChange = { vm.setEqBandGain(index, it) },
                    valueRange = EQ_GAIN_MIN_DB..EQ_GAIN_MAX_DB,
                    // 24 dB of travel in 1 dB steps.
                    steps = 23,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** The preset's display name, by id; anything unrecognised reads Custom. */
@Composable
private fun eqPresetLabel(presetId: String): String = when (presetId) {
    EqPresets.FLAT.id -> stringResource(R.string.player_eq_preset_flat)
    EqPresets.NORMAL.id -> stringResource(R.string.player_eq_preset_normal)
    EqPresets.BASS_BOOST.id -> stringResource(R.string.player_eq_preset_bass)
    EqPresets.TREBLE_BOOST.id -> stringResource(R.string.player_eq_preset_treble)
    EqPresets.VOCAL.id -> stringResource(R.string.player_eq_preset_vocal)
    else -> stringResource(R.string.player_eq_preset_custom)
}

/** The band's frequency label, by index into the five EQ bands. */
private fun eqBandLabelRes(index: Int): Int = when (index) {
    0 -> R.string.player_eq_band_60
    1 -> R.string.player_eq_band_230
    2 -> R.string.player_eq_band_910
    3 -> R.string.player_eq_band_3600
    else -> R.string.player_eq_band_14000
}

/**
 * A section break inside a sheet — 12sp, Semibold, dimmed — where a second
 * SheetTitle would shout with the same weight as the sheet's own title.
 * Sheet titles name the sheet; sections organise what is inside it.
 */
@Composable
private fun SheetSection(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.6f),
        fontSize = brandSp(12),
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SheetRow(label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                label,
                color = when {
                    selected -> RaddCurrent500
                    !enabled -> Color.White.copy(alpha = 0.4f)
                    else -> Color.White
                },
                fontSize = brandSp(14),
            )
        },
        leadingContent = if (selected) {
            { Icon(Icons.Default.Check, null, tint = RaddCurrent500) }
        } else {
            null
        },
        // A disabled row stays visible and simply does not consume the press: hiding the
        // affordance would leave the viewer hunting for a button that has nothing to do.
        // D-43: a selected row is filled, not just green text — the state is visible
        // at a glance, not only when the viewer reads the check mark.
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(
                if (selected) RaddCurrent500.copy(alpha = 0.15f) else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier),
    )
}
