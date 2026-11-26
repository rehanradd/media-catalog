package com.raddstream.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.data.RegionChoice
import com.raddstream.app.data.RegionChoices
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.viewmodel.RegionUiState
import com.raddstream.app.viewmodel.RegionViewModel

/**
 * The first-run choice: what this viewer came to watch, in three cards.
 *
 * One question, three answers, no brochure. Each card is a name and one short
 * line about what it holds — nothing else. The promise on the card and the
 * record in storage cannot drift apart, because both come from
 * [com.raddstream.app.data.RegionChoices]: the screen never invents its own words.
 *
 * Stitch sub-screens mockup (Nov 2026): a top bar with a back arrow and the
 * title "Content preference", one card per region with the choice in force
 * wearing a jade check and a jade left edge, and a full-width jade "Done"
 * button. The mockup's card names (Pakistan / India / International) are
 * Stitch's invention — the real choices are Desi, East Asian and
 * International with their real taglines, and the checks follow the stored
 * region, never a hard-coded pick. This screen serves two entrances
 * (first-run setup and Settings), so the back arrow leaves the way the
 * screen was entered leaves: pop when Settings is behind it, Home when it
 * is the whole stack — the same rule [onContinue] already carries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionSelectionScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Content preference") },
                navigationIcon = {
                    IconButton(onClick = onContinue) {
                        Icon(painterResource(R.drawable.ic_action_arrow_left), contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        RegionContent(
            state = state,
            onSelect = { viewModel.select(it) },
            onDone = { viewModel.saveAndContinue(onContinue) },
            onSkip = { viewModel.skip(onContinue) },
            modifier = modifier.padding(contentPadding),
        )
    }
}

/**
 * The screen without its ViewModel, so a test can drive it with a plain
 * [RegionUiState]: the three cards, the Done button and Skip.
 */
@Composable
internal fun RegionContent(
    state: RegionUiState,
    onSelect: (String) -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Session 154 (R-1): the stagger rides the motion token (250ms) and
    // reduced-motion users get the fade only, never the rise — the
    // HomeScreen rails pattern.
    val reduceMotion = rememberReduceMotion()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Audit #28: the one scrolling screen without a Scaffold — the
            // bottom Skip sat behind the system nav bar on gesture
            // navigation. The 28 dp vertical padding stays; the nav bar
            // gets its own room on top of it.
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RegionChoices.ALL.forEachIndexed { index, choice ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(
                    tween(
                        durationMillis = RaddMotion.DurationDefault,
                        delayMillis = index * 90,
                    ),
                ) + if (reduceMotion) {
                    EnterTransition.None
                } else {
                    slideInVertically(
                        tween(
                            durationMillis = RaddMotion.DurationDefault,
                            delayMillis = index * 90,
                            easing = RaddMotion.EmphasizedDecelerate,
                        ),
                    ) { it / 3 }
                },
            ) {
                RegionChoiceCard(
                    choice = choice,
                    selected = choice.id == state.selectedId,
                    onSelect = { onSelect(choice.id) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onDone,
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Done")
            }
        }
        // Session 121 (P-24): the question is a convenience, never a door — a viewer
        // who does not want to choose skips straight to the default (Desi).
        TextButton(
            onClick = onSkip,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Skip")
        }
        if (state.saveFailed) {
            // A message that appears under the thumb the viewer just used is invisible to a screen
            // reader unless it is announced, so this text node is a polite live region.
            Text(
                text = "Couldn't save your choice — please try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // Audit #39: ONE Skip. There used to be a second "Skip for now" below
        // this one; both did the same thing (save Desi, continue), so the
        // second is deleted, not hidden.
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * One selectable card: a name, one line about what it holds, and nothing
 * else. The whole surface is the tap target, and it carries a radio-button
 * role with a real selected state: a screen reader has to say which of the
 * three is the answer, not read the check mark as a separate picture.
 *
 * The selected card wears the brand jade — a left edge bar and the check
 * badge, both animated in — so the answer reads at a glance instead of
 * needing a second look.
 */
@Composable
internal fun RegionChoiceCard(
    choice: RegionChoice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val jade = MaterialTheme.colorScheme.primary
    // Session 154 (R-1): the check badge rides the motion tokens and
    // reduced-motion users get a 30ms alpha crossfade, never a scale.
    val reduceMotion = rememberReduceMotion()
    val borderColor by animateColorAsState(
        if (selected) jade else MaterialTheme.colorScheme.outlineVariant,
        label = "regionCardBorder",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .testTag("regionChoice_${choice.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            borderColor,
        ),
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The mockup's selected edge: a jade bar down the card's left
            // side. An unselected card keeps the same width transparent so
            // the text never shifts when the pick moves.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (selected) jade else MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = choice.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = choice.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = selected,
                enter = if (reduceMotion) {
                    fadeIn(tween(RaddMotion.FadeWhenReduced))
                } else {
                    scaleIn(RaddMotion.default()) + fadeIn(RaddMotion.default())
                },
                exit = if (reduceMotion) {
                    fadeOut(tween(RaddMotion.FadeWhenReduced))
                } else {
                    scaleOut(RaddMotion.quick()) + fadeOut(RaddMotion.quick())
                },
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(jade),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_check),
                        // The row's own selected state already says this out loud; a second
                        // "Selected" from the picture is the same word twice.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
