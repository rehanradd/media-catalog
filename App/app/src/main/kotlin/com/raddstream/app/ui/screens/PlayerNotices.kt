package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.raddstream.app.R
import com.raddstream.app.player.HandoffWhy
import com.raddstream.app.player.StartDeadline
import com.raddstream.app.player.StartPhase
import com.raddstream.app.ui.components.RaddDialogTitle
import com.raddstream.app.ui.theme.RaddCoral400
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.RaddHudPillIdle
import com.raddstream.app.ui.theme.RaddPlayerDialog
import com.raddstream.app.ui.theme.RaddPlayerStage
import com.raddstream.app.ui.theme.brandSp


@Composable
internal fun StartNoticeCard(
    phase: StartPhase,
    title: String?,
    slowHint: Boolean = false,
    rungLabel: String? = null,
    attemptLabel: String? = null,
    // P-3: milliseconds since the load was requested (Play press) — the
    // elapsed line counts from here, so the resolve wait is counted too.
    waitElapsedMs: Long = 0,
    // 26: the three-step progress story — the viewer always knows what the wait
    // is for: finding the best version, then finding the video, then buffering. When the
    // caller passes a step label it replaces the phase's generic label.
    stepLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    onTryAnother: (() -> Unit)? = null,
    // App-design audit #2: the wait is never a trap — Cancel returns to
    // the detail screen at any point, even before the 8-second actions.
    onCancel: (() -> Unit)? = null,
) {
    val label = stepLabel ?: phase.label
    val spoken = if (title.isNullOrBlank()) label else stringResource(R.string.player_notice_spaced, label, title)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // LC-1: the notice floats over the live video frame, so it gets its own
        // solid plate — bare white over a bright frame was ~1:1. Robo run 7
        // flagged the notice's title at 1.06:1: the old plate was RaddHudCard,
        // black at 60% — over a bright frame it composites to mid-grey, and
        // the 65%-white title on that mid-grey is ~3.5:1, under the 4.5:1
        // floor (the old ">= 7:1 over any frame" only holds for full white).
        // The plate is now the solid stage colour and the title is full
        // white, so the reading no longer depends on the frame behind it.
        // The plate never animates with the chrome fade: it is either up, or
        // it is gone.
        Surface(
            color = RaddPlayerStage,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .widthIn(max = 420.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
            ) {
            PlayerLoadingIndicator()
            Spacer(Modifier.height(14.dp))
            Text(
                label,
                color = Color.White,
                // The mockup's "Finding your video…" is the card's bold
                // headline, not body text.
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            // The finding card's reassurance (Stitch popups mockup): while
            // the wait is still finding — the best version, then the video —
            // say what it is doing. Buffering is past finding, so the line
            // does not follow it there.
            if (label == stringResource(R.string.player_step_finding) ||
                label == stringResource(R.string.player_step_warming)
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.player_finding_note),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            if (!title.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    title,
                    // Robo run 7: the 65%-white title on the old translucent
                    // plate read 1.06:1. Full white on the solid plate.
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Session 121 (P-36): which copy is being tried, out of how many — the
            // wait reads as progress through the failover ladder, not a freeze.
            if (!attemptLabel.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    attemptLabel,
                    // Robo run 7: 60% white on the old plate was under the
                    // floor over a bright frame. 85% is the floor the audit
                    // allows for these sub-lines.
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            // The soft hint (§5 P5, past two seconds): the wait is normal, and here is
            // which rung is being found — so a long start reads as work, not a freeze.
            if (slowHint) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.player_start_slow),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                if (!rungLabel.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.player_quality_label, rungLabel),
                        // Robo run 7: same as the attempt line above — 60%
                        // white was under the floor over a bright frame.
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // P-3: the 20 s wait is narrated but showed no elapsed time. The
            // line counts from the request (Play press), so the resolve wait
            // is counted too — and it stands on its own threshold (past ten
            // seconds), not inside the slow-hint block, because the stage
            // clock starts at the URL and the resolve runs before it.
            val elapsedLine = StartDeadline.formatWaitElapsed(rungLabel, waitElapsedMs)
            if (elapsedLine != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    elapsedLine,
                    // Robo run 7: same as the attempt line above — 60%
                    // white was under the floor over a bright frame.
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            // The actions (§5 P5, past eight seconds): retry the same attempt, or jump to
            // the next version now instead of waiting for the silent failover to get there.
            // D-40: real buttons, not plain green text links — a press target the
            // viewer can see is a press target the viewer will find.
            if (onRetry != null || onTryAnother != null || onCancel != null) {
                Spacer(Modifier.height(12.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    onRetry?.let {
                        OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = MinControlTarget)) {
                            Text(stringResource(R.string.player_retry), color = Color.White)
                        }
                    }
                    onTryAnother?.let {
                        // The mockup's dark pill, not an outline: this is
                        // the card's one action, and the pill is the shape
                        // the viewer is meant to find.
                        Button(
                            onClick = it,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RaddHudPillIdle,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.heightIn(min = MinControlTarget),
                        ) {
                            Text(stringResource(R.string.player_start_try_another), color = Color.White)
                        }
                    }
                    onCancel?.let {
                        TextButton(onClick = it, modifier = Modifier.heightIn(min = MinControlTarget)) {
                            Text(stringResource(R.string.player_cancel), color = Color.White)
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * How tall the give-up card's action stack may grow before it scrolls.
 * Four and a half 48dp actions: on a ~360dp landscape screen the icon,
 * title and text above the stack leave about this much room, so the
 * fifth action ("Keep waiting") stays one scroll away instead of off
 * the bottom edge. See the confirmButton note in [GiveUpCard].
 */
private val GiveUpActionsMaxHeight = 216.dp

@Composable
internal fun GiveUpCard(
    message: String,
    why: HandoffWhy,
    onRetry: () -> Unit,
    onExternal: () -> Unit,
    onDismiss: () -> Unit,
    onBackToDetails: () -> Unit,
    onTryAnother: (() -> Unit)? = null,
    /**
     * REA #4: the "Play Something" shuffle — a different title from the
     * "For you" pool. Null when the ranker has no row, so the card never
     * offers a shuffle with nothing behind it.
     */
    onPlaySomething: (() -> Unit)? = null,
    // NAV-4: an expired shared link gets its own way home. Only the expired
    // card shows it — the other failures already have their own way out.
    onHomeClick: (() -> Unit)? = null,
) {
    // PL-13 (Session 114): dismissing this card must leave the player — a failed
    // play must never trap the viewer. The dialog itself never dismisses on Back
    // or on an outside tap, so the screen-level back handler owns Back (exit) and
    // the card's own Close button is the only way to keep waiting.
    AlertDialog(
        onDismissRequest = onBackToDetails,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        // Cinema (Nov 2026): the dialog reads as a surface, not a slab on
        // black — the tonal container is the surface; the 1dp outline is gone.
        containerColor = RaddPlayerDialog,
        icon = {
            // Session 121 (P-34): the logo-mark is the brand loader's job, not an
            // error's — a failure gets a warning triangle in the same coral the
            // connection-error card already uses, so the two failure states agree.
            Icon(
                painterResource(R.drawable.ic_action_warning),
                contentDescription = null,
                tint = RaddCoral400,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            // D-2: one dialog title style everywhere — display family,
            // semibold, 20sp — not the thin 16sp face this card used.
            RaddDialogTitle(
                text = when (why) {
                    HandoffWhy.DOWNLOAD_ONLY -> stringResource(R.string.player_giveup_download_only)
                    HandoffWhy.NO_CANDIDATE_LEFT -> stringResource(R.string.player_giveup_no_candidate)
                    HandoffWhy.START_TIMEOUT -> stringResource(R.string.player_giveup_timeout)
                    // NAV-4: an expired version is not a generic error — it
                    // says so, plainly, in the title.
                    HandoffWhy.EXPIRED_LINK -> stringResource(R.string.player_giveup_expired)
                    // Salaar: a rung the server refused is VIP-only — say so,
                    // plainly, in the title.
                    HandoffWhy.GATE_REFUSED -> stringResource(R.string.player_giveup_gate_refused)
                    // Batch C (2026-11-14, 429-storm audit): the per-IP play
                    // bucket is full — the server asked the app to wait.
                    HandoffWhy.RATE_LIMITED -> stringResource(R.string.player_giveup_rate_limited)
                },
                color = Color.White,
            )
        },
        text = {
            Column {
                Text(message, color = Color.White.copy(alpha = 0.85f), fontSize = brandSp(14))
                Spacer(Modifier.height(8.dp))
                Text(
                    when (why) {
                        HandoffWhy.DOWNLOAD_ONLY ->
                            stringResource(R.string.player_giveup_download_body)
                        HandoffWhy.NO_CANDIDATE_LEFT ->
                            stringResource(R.string.player_giveup_no_candidate_body)
                        HandoffWhy.START_TIMEOUT ->
                            stringResource(R.string.player_giveup_timeout_body)
                        HandoffWhy.EXPIRED_LINK ->
                            stringResource(R.string.player_giveup_expired_body)
                        HandoffWhy.GATE_REFUSED ->
                            stringResource(R.string.player_giveup_gate_refused_body)
                        HandoffWhy.RATE_LIMITED ->
                            stringResource(R.string.player_giveup_rate_limited_body)
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = brandSp(12),
                )
            }
        },
        // Session 120: five actions in one horizontal row overflowed a 360 dp
        // phone — AlertDialog lays its two button slots side by side, so
        // "Open with another player" wrapped onto four lines ("Open / with /
        // anothe / r") and "Close" printed over its final letter. All five
        // actions stack vertically instead, each full width, so every one is
        // fully visible and tappable no matter which subset is offered.
        // The dismiss slot stays empty: "Back to details" and "Close" are
        // actions in the same stack, not a second row fighting for width.
        // Samsung RTL retest (Issue 7): the player forces landscape, so
        // this dialog is short — up to six full-width 48dp actions plus
        // icon, title and two text blocks can exceed a ~360dp landscape
        // screen, and the lower actions ("Back to details", "Keep
        // waiting") fall off the bottom edge, where a tap hits the scrim
        // and — dismissOnClickOutside is false — does nothing at all.
        // The stack scrolls inside a cap, so every action stays reachable
        // on a short screen; on a tall one the cap never binds and the
        // list shows whole, exactly as before.
        confirmButton = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = GiveUpActionsMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Session 121 (P-33): five green buttons shout at once, so the two
                // recoveries that end the failure (retry the same print, or leave
                // for the title) stay green and the two alternates read as
                // secondary white; the dismiss stays the quietest white.
                // P7 (player fixes, verify-first): the card already had the S4
                // bones — error icon, per-cause title + diagnosis body,
                // landscape-safe scrolling action stack — and the one breach
                // was the Retry: jade text where the error family puts a
                // filled pill. Retry is the pill now; the alternates stay
                // quiet text rows, so one action reads as the way out.
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RaddCurrent500,
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinControlTarget),
                ) {
                    Text(
                        stringResource(R.string.player_retry),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                onTryAnother?.let {
                    TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.player_start_try_another),
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                // REA #4: this title will not play — a different title from
                // the "For you" pool is the way out that still ends in
                // playback. A secondary white action like Try Another, not
                // the green recovery: retrying this title stays primary.
                onPlaySomething?.let {
                    TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.player_play_something),
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                TextButton(onClick = onExternal, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.player_open_external),
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                TextButton(onClick = onBackToDetails, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.player_back_to_details), color = RaddCurrent500)
                }
                // NAV-4: the expired card's way home — a shared link that died
                // should not strand the viewer on a dead card.
                if (why == HandoffWhy.EXPIRED_LINK) {
                    onHomeClick?.let { goHome ->
                        TextButton(onClick = goHome, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.player_home), color = RaddCurrent500)
                        }
                    }
                }
                // Session 121 (P-19): "Close" said nothing about what happens next
                // — this button dismisses the card and keeps waiting on the slow
                // link, so it says so.
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.player_keep_waiting), color = Color.White.copy(alpha = 0.7f))
                }
            }
        },
        dismissButton = {},
    )
}
