package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raddstream.app.R
import com.raddstream.app.player.CacheAffordance
import com.raddstream.app.player.CalibrationState
import com.raddstream.app.player.KeepUiState
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.ui.components.RaddDialogTitle
import com.raddstream.app.ui.theme.RaddCurrent500
import com.raddstream.app.ui.theme.RaddDanger
import com.raddstream.app.ui.theme.PlayerChromeTheme
import com.raddstream.app.ui.theme.RaddHudPillIdle
import com.raddstream.app.ui.theme.RaddPlayerDialog
import com.raddstream.app.ui.theme.RaddPlayerStage
import com.raddstream.app.ui.theme.RaddSuccess
import com.raddstream.app.ui.theme.RaddTideIndigo
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.ui.theme.paint
import com.raddstream.app.viewmodel.formatTime


// ── Keep chip — the signature "watching → keeping" affordance ─────────────────

@Composable
internal fun KeepChip(state: PlayerUiState, onKeepCurrent: () -> Unit) {
    val keep = state.keep
    if (keep is KeepUiState.Unavailable) return
    // App-design audit #7: pressing Play must never announce a background
    // download. The "Keeping" state is the player's automatic ahead-window
    // copy, not something the viewer asked for — it shows no chip here at
    // all (its progress lives in Downloads). A finished keep (Stored) or a
    // blocked one still says so, because those are facts about the title.
    // Player review D3: a keep the viewer TAPPED is theirs to watch — the
    // ring and the live rate are already built below, they were only ever
    // hidden by this return. Gate on keepRequested, which refreshKeepState
    // resets on every switch, so a stale request from a previous title
    // cannot un-silence the next title's automatic copy.
    if (keep is KeepUiState.Keeping && !state.keepRequested) return
    val chrome = PlayerChromeTheme.forId(state.playerTheme)
    // E-C4: the chip sat on a 40%-alpha tint with no bar behind it — white
    // text on the Stored tint over a bright frame was 1.23:1. The surface is
    // now chrome.card (60% black); the state tint survives only as the
    // icon/ring colour, at full strength.
    val stateTint = when (keep) {
        is KeepUiState.Stored -> RaddSuccess
        is KeepUiState.Blocked -> RaddDanger
        is KeepUiState.Keeping -> RaddTideIndigo
        else -> Color.White
    }
    Surface(
        color = chrome.card,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .padding(end = 6.dp)
            .clickableNoRipple(onClick = onKeepCurrent),
    ) {
        Row(
            Modifier
                .heightIn(min = MinControlTarget)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // SC.7: the living progress ring while keeping — real %, from the
            // pure keepingProgress, shown only when the total is known. A ring
            // at 0% with an unknown total would be the same lie Session 120
            // fixed in the chip text ("Keeping 0 B of 3.05 GB"), in a circle.
            val progress = CacheAffordance.keepingProgress(keep)
            if (progress != null) {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.size(14.dp),
                    color = stateTint,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(painter = painterResource(R.drawable.ic_action_download), contentDescription = null, tint = stateTint, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                CacheAffordance.chipText(keep),
                color = Color.White,
                fontSize = brandSp(11),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
    }
}

// ── Dialogs ────────────────────────────────────────────────────────────────────

/**
 * The resume offer as a centred card (Stitch popups mockup): "Resume
 * from 01:12:44?" bold, a jade-filled Resume pill, and a dark Start over
 * pill beneath. Tapping the card or Resume (or doing nothing for ~3 s)
 * resumes from where viewing stopped; "Start over" is the only way back
 * to the beginning. The 3 s auto-dismiss lives in the caller's
 * LaunchedEffect, so this is paint only. The old chip's X is gone — its
 * job (resume now) is the Resume pill.
 */
@Composable
internal fun ResumeChip(offerMs: Long, onResume: () -> Unit, onStartOver: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = RaddPlayerStage,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .clickableNoRipple(onResume),
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.player_resume_chip, offerMs.formatTime()),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RaddCurrent500,
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinControlTarget),
                ) {
                    Text(
                        stringResource(R.string.player_resume),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onStartOver,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RaddHudPillIdle,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinControlTarget),
                ) {
                    Text(stringResource(R.string.player_start_over))
                }
            }
        }
    }
}

/**
 * PL-2: the sponsor break dialog is gone, with the placeholder ad it rendered.
 * Episode advance no longer gates on a fake ad — the countdown advances directly
 * — and the policy seam (AdSlotPolicy, AdSlot) stays in the player package
 * for the day a real ad is wired, with its pure tests as the proof.
 */
@Composable
internal fun CalibrationDialog(
    state: CalibrationState,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(state.targetEpisode.coerceAtLeast(1).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RaddPlayerDialog,
        title = { RaddDialogTitle(stringResource(R.string.player_calibration_title), color = Color.White) },
        text = {
            Column {
                Text(
                    state.warning ?: stringResource(R.string.player_calibration_body),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = brandSp(14),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(stringResource(R.string.player_episode_number), color = Color.White.copy(alpha = 0.6f)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let { onConfirm(it) } ?: onDismiss() },
            ) { Text(stringResource(R.string.player_mark_position), color = RaddCurrent500) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_cancel), color = Color.White.copy(alpha = 0.7f)) }
        },
    )
}

@Composable
internal fun PlayFromDialog(
    onSubmit: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("0:00") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RaddPlayerDialog,
        title = { RaddDialogTitle(stringResource(R.string.player_play_from_title), color = Color.White) },
        text = {
            OutlinedTextField(
                value = text,
                // F-01: digits and ':' only, capped at 11 chars ("999:59:59"
                // plus slack) — a pasted 19-digit number can never reach
                // the parser. The parser itself is still overflow-safe
                // (see parseTimeToMs); the filter is UX, not the guard.
                onValueChange = { text = it.filter { c -> c.isDigit() || c == ':' }.take(11) },
                label = { Text(stringResource(R.string.player_play_from_hint), color = Color.White.copy(alpha = 0.6f)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(parseTimeToMs(text)) }) { Text(stringResource(R.string.player_go), color = RaddCurrent500) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_cancel), color = Color.White.copy(alpha = 0.7f)) }
        },
    )
}

/**
 * "12:34" → 754 000 ms; "1:02:03" → ms; single number = minutes; garbage → 0.
 * F-01: a part that does not fit in a Long (a ~19-digit paste) used to throw
 * NumberFormatException out of toLong() inside the confirm click handler and
 * crash the player. Every part parses with toLongOrNull now, and the
 * multiply/add use the exact forms, so an overflowing time returns 0
 * instead of throwing. Internal so PlayFromTimeTest can feed it directly.
 */
internal fun parseTimeToMs(raw: String): Long {
    val parts = raw.trim().split(':').map { it.trim() }
    if (parts.isEmpty() || parts.any { it.isEmpty() || !it.all { c -> c.isDigit() } }) return 0L
    val nums = parts.map { it.toLongOrNull() ?: return 0L }
    return try {
        when (nums.size) {
            1 -> Math.multiplyExact(nums[0], 60_000L)
            2 -> Math.multiplyExact(Math.addExact(Math.multiplyExact(nums[0], 60L), nums[1]), 1000L)
            3 -> Math.multiplyExact(
                Math.addExact(
                    Math.addExact(Math.multiplyExact(nums[0], 3600L), Math.multiplyExact(nums[1], 60L)),
                    nums[2],
                ),
                1000L,
            )
            else -> 0L
        }
    } catch (_: ArithmeticException) {
        0L
    }
}
