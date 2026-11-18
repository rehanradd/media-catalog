package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raddstream.app.brand.Brand

/**
 * The full-screen notice shown while the operator's `maintenance_mode` switch is on.
 *
 * It replaces the whole navigation graph rather than sitting on top of it, so no screen
 * keeps polling the catalog or holding a player behind a banner. There is deliberately no
 * dismiss control that returns to the catalog: an app that could be dismissed back into the
 * catalog would not be in maintenance at all. The flag is re-read on the watcher's own clock,
 * so the notice clears by itself once the operator turns the switch off.
 *
 * [onChangeServer] is the one exit, and the master spec asks for it ("all navigation blocked
 * EXCEPT Settings"): without it a viewer pointed at a server that is stuck, or wrongly set, to
 * maintenance can never reach the server-address screen to point the app somewhere else. The
 * notice would be a brick with a nice message on it.
 *
 * [onRetry] is the other exit: re-check the flag now instead of waiting for the watcher's
 * five-minute tick. When the operator has lifted the switch the re-read writes
 * `maintenanceMode = false` and the notice clears by itself — the host observes the same
 * flow that put it here, so no navigation or state reset is needed on this side.
 *
 * Flow mockup (Nov 2026): a centred empty state — the sync mark in a rounded tile,
 * the reason in plain words — and one full-width dark Retry button with jade text
 * pinned to the bottom. The "Use a different catalog" exit is not in the mockup
 * and stays anyway, as a quiet text button under Retry: it is the only way out
 * of a wrongly-flagged catalog, and the mockup never asked to remove it.
 */
@Composable
fun MaintenanceScreen(
    onChangeServer: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "${Brand.name} is updating",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "We'll be back soon.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Retry", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (onChangeServer != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onChangeServer) {
                Text("Use a different catalog")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
