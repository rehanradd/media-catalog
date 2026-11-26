package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.viewmodel.LanguagesUiState
import com.raddstream.app.viewmodel.LanguagesViewModel
import com.raddstream.app.viewmodel.UserPreferences
import com.raddstream.app.viewmodel.displayLanguageName

/**
 * One Languages screen: the audio language, the subtitle language and the
 * subtitle size in force, all in one place, each showing the value the next
 * playback will actually use.
 *
 * Four surfaces used to write these three values without ever showing them
 * together — the Region card, the Settings chips, the Settings subtitle-size
 * row, and the player sheet — and a viewer could change one without knowing
 * what the others said. They all write the same stored keys, so this screen
 * changes them through the same repository methods and every surface follows.
 * Nothing is removed here: the old surfaces stay until this screen is proven.
 *
 * Stitch sub-screens mockup (Nov 2026): each group is a headed card — a small
 * grey section label, then one row per option with a jade check on the choice
 * in force. The rows carry every real option (nine audio, seven subtitle);
 * the mockup's three rows per group are Stitch's illustration, not the list.
 * The subtitle size stays the slider this screen always had — the mockup drew
 * a "Medium" row with a chevron, but there is no size picker to open and the
 * stored value is a percent, so the row shows the real percent above the
 * slider instead of inventing a name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagesScreen(
    onBackClick: () -> Unit,
    viewModel: LanguagesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Languages") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(painterResource(R.drawable.ic_action_arrow_left), contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        LanguagesContent(
            state = state,
            onAudioSelect = { viewModel.setAudioLanguage(it) },
            onSubtitleSelect = { viewModel.setSubtitleLanguage(it) },
            onSizeChange = { viewModel.setSubtitleSize(it) },
            modifier = Modifier.padding(contentPadding),
        )
    }
}

/**
 * The screen without its ViewModel, so a test can drive it with a plain
 * [LanguagesUiState]: the two headed option cards and the size card.
 */
@Composable
internal fun LanguagesContent(
    state: LanguagesUiState,
    onAudioSelect: (String) -> Unit,
    onSubtitleSelect: (String) -> Unit,
    onSizeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        LanguageSection(title = "Audio") {
            LanguageOptionCard(
                rowName = "Audio language",
                options = UserPreferences.AUDIO_LANGUAGE_OPTIONS,
                selected = state.audioLanguage,
                onSelect = onAudioSelect,
            )
        }

        LanguageSection(title = "Subtitles") {
            LanguageOptionCard(
                rowName = "Subtitle language",
                options = subtitleOptions,
                selected = state.subtitleLanguage,
                onSelect = onSubtitleSelect,
            )
        }

        LanguageSection(title = "Subtitle size") {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Subtitle size",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${state.subtitleSizePercent}%",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = state.subtitleSizePercent.toFloat(),
                        onValueChange = { onSizeChange(it.toInt()) },
                        valueRange = 50f..200f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** One headed section: a small grey label, then the card under it. */
@Composable
private fun LanguageSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

/** One card of rows: a tap picks the row, the pick wears the jade check. */
@Composable
private fun LanguageOptionCard(
    // F31 (Batch B): the row's name, spoken before the language — the audio
    // card and the subtitle card hold the same words ("Hindi" in both), and a
    // screen reader hearing bare "Hindi" twice cannot tell which choice it
    // is changing. The painted label stays the language alone.
    rowName: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            options.forEachIndexed { idx, label ->
                if (idx > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = label == selected,
                            role = Role.RadioButton,
                            onClick = { onSelect(label) },
                        )
                        .semantics {
                            contentDescription = "$rowName: $label"
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // F23 (Batch B): the row paints both scripts —
                        // "Hindi · हिन्दी" — so the language is findable by a
                        // reader of either. The stored value stays the
                        // English name.
                        text = displayLanguageName(label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (label == selected) {
                        Icon(
                            painter = painterResource(R.drawable.ic_action_check),
                            contentDescription = null, // the row's selected state already says this out loud
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
