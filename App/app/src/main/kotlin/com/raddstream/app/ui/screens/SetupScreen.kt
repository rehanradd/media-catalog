package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.R
import com.raddstream.app.brand.Brand
import com.raddstream.app.viewmodel.SetupUiState
import com.raddstream.app.viewmodel.SetupViewModel
import com.raddstream.app.ui.theme.RaddTokens

/**
 * First-run server connection. [onConnected] receives whether the viewer still has to answer
 * the region question — a fresh install goes through Region on its FIRST launch, and the caller
 * owns the routing.
 */
@Composable
fun SetupScreen(
    onConnected: (askRegion: Boolean) -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // UI-fix P0-4: the shipped catalog is probed once on arrival, with the
    // progress visible — a first-run user never meets a dead screen that
    // only asks for an address they do not have.
    LaunchedEffect(Unit) {
        if (!state.isConnected) viewModel.retryDefault()
    }

    SetupScreenContent(
        state = state,
        onServerUrlChanged = viewModel::onServerUrlChanged,
        onTestConnection = viewModel::testConnection,
        onRetryDefault = viewModel::retryDefault,
        onRevealUrlField = viewModel::revealUrlField,
        onConnected = onConnected,
        onContinue = { viewModel.continuePressed(onConnected) },
    )
}

/**
 * Flow mockup (Nov 2026): the first-run screen itself — a jade play mark in a
 * rounded tile, the welcome title, the tagline, the address field and
 * the jade Connect. Extracted from [SetupScreen] so the layout is testable
 * without the ViewModel: the screen keeps the state collection and the
 * arrival probe, and this renders only the content. The wiring is untouched:
 * the address field still opens only when the viewer asks for their own
 * catalog, and the shipped catalog is still probed without ever printing it.
 */
@Composable
internal fun SetupScreenContent(
    state: SetupUiState,
    onServerUrlChanged: (String) -> Unit,
    onTestConnection: () -> Unit,
    onRetryDefault: () -> Unit,
    onRevealUrlField: () -> Unit,
    onConnected: (askRegion: Boolean) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
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
                painter = painterResource(R.drawable.ic_player_play),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Welcome to ${Brand.name}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Watch movies, shows and live TV.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        // The address is never printed on first sight: Connect tests the
        // shipped catalog without displaying it, and the field below opens
        // only when the viewer explicitly asks for their own catalog.
        if (state.showUrlField) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChanged,
                label = { Text("Catalog address") },
                placeholder = { Text("https://catalog.example.com") },
                singleLine = true,
                enabled = !state.isChecking,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
        }

        state.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))
        // One Connect, not a Connect plus a Test: Connect already tests —
        // the shipped catalog when the field is hidden, the typed address
        // when it is open — so a second button would test the same thing
        // under a different name.
        OutlinedButton(
            onClick = if (state.showUrlField) onTestConnection else onRetryDefault,
            enabled = !state.isChecking && (!state.showUrlField || state.serverUrl.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Connect")
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            shape = RaddTokens.Shape.Pill,
            onClick = onContinue,
            enabled = state.isConnected && !state.isChecking,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("Continue", style = MaterialTheme.typography.labelLarge)
        }

        // UI-fix P0-4: never strand the user. If the shipped catalog does not
        // answer, Home still opens — saved titles and the retry there — with
        // no address to type and no dead end.
        if (!state.isConnected) {
            Spacer(Modifier.height(8.dp))
            // Offline means saved titles only: no region question now —
            // the launch gate still asks on a later launch if unanswered.
            TextButton(onClick = { onConnected(false) }) {
                Text("Continue offline — saved titles only")
            }
        }

        if (!state.showUrlField) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRevealUrlField) {
                Text("Use your own catalog")
            }
        }
    }
}
