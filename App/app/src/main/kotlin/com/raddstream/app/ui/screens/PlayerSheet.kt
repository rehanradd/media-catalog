package com.raddstream.app.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.raddstream.app.ui.theme.RaddInk950
import com.raddstream.app.ui.theme.RaddPlayerSheet

@OptIn(ExperimentalMaterial3Api::class)
/**
 * One container for every player sheet. Portrait keeps the bottom sheet;
 * landscape opens a right-side panel (~40% wide, max 320dp) so the video
 * stays visible and playing on the left — a sheet over the whole picture
 * is the YouTube-landscape complaint, and a panel clipped at the right
 * edge is the MX defect. The panel scrolls inside its capped width, the
 * video side is never dimmed, and tapping it (or Back) dismisses. The
 * panel paints the same surface as the rest of the chrome (#121212) with a
 * 20dp left corner radius and a 1px white 8% border, so it reads as a
 * sheet, not a bare column of rows.
 *
 * The landscape Back lives HERE, in the sheet container — the same role
 * ModalBottomSheet's own back handling plays in portrait — while
 * PlayerScreenContent keeps the single fallback handler that exits the
 * player. A panel that is showing wins the press (registered later);
 * with nothing showing, Back pops the player instead of doing nothing.
 */
@Composable
internal fun PlayerSheet(
    onDismiss: () -> Unit,
    containerColor: Color = RaddPlayerSheet,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (!isLandscape) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(),
            containerColor = containerColor,
        ) {
            content()
        }
    } else {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize()) {
            // The video side: tapping it dismisses the panel, nothing else.
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.6f)
                    .clickableNoRipple { onDismiss() },
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f)
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(RaddInk950)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)),
            ) {
                // Robo #10 (APK 757): this Column used to scroll itself, and
                // every sheet it wraps scrolls itself too (SubtitleSettings,
                // Tracks, More, PrintLog) — a vertical scroller inside a
                // vertical scroller, measured with infinite constraints, so
                // opening any of those sheets sideways crashed with
                // "Vertically scrollable component was measured with an
                // infinity maximum height constraints". One scroller owns
                // the column: the panel never scrolls, each sheet scrolls
                // its own content (the four plain sheets gained their own
                // scroll in the same change, so a long quality list still
                // scrolls in the landscape panel).
                Column(
                    Modifier.fillMaxSize(),
                ) {
                    content()
                }
            }
        }
    }
}
