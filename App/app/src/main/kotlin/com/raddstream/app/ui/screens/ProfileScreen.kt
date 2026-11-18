package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.raddstream.app.viewmodel.AccountOverviewUiState
import com.raddstream.app.viewmodel.AccountViewModel

/**
 * The profile: what this account is, on the catalog's own record. Session
 * 123: the app had no profile screen — the account existed only as an account
 * code in Settings and a trial countdown. This screen shows the membership
 * and the dates the catalog recorded, and nothing the catalog did not say:
 * no name, no phone, no invented identity. (The streak and coin balance
 * lived here until coins and daily check-in were removed 2026-11-17.)
 * Signing out drops the code on this device and tells the catalog to revoke
 * it, so the account cannot be reopened from a copy of the code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBackClick: () -> Unit,
    onOpenDashboard: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    ProfileContent(
        state = state,
        onBackClick = onBackClick,
        onOpenDashboard = onOpenDashboard,
        onSignOut = { viewModel.signOut() },
    )
}

/**
 * The profile's body, split from the screen so tests can render it with a
 * fake state (the Home pattern: HomeScreen + HomeContent). Gate OFF means
 * every viewer is premium, so the membership line says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    state: AccountOverviewUiState,
    onBackClick: () -> Unit,
    onOpenDashboard: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Your profile") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isLoading) {
                Text(
                    "Loading your profile…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.signedIn) {
                Text(
                    "No account on this device yet. Sign in from Settings — an account is just an account code, no name or phone needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            // Free launch: gate OFF means every viewer is
                            // premium, so the line says Premium, not Free.
                            if (!state.paywallVisible) "Premium member"
                            else if (state.isVip) "VIP member" else "Free account",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        state.createdAt?.let { created ->
                            Text(
                                "Account created: ${formatAccountDay(created)}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.lastSeenAt?.let { seen ->
                            Text(
                                "Last opened: ${formatAccountDay(seen)}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "This app collects no name, phone, location, titles or searches. Your account is a code the catalog recognises, nothing more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = onOpenDashboard, modifier = Modifier.fillMaxWidth()) {
                    Text("Open dashboard")
                }
                Button(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign out")
                }
            }
            state.loadError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** An epoch-seconds date as "d MMM yyyy" — the account dates on the profile. */
private fun formatAccountDay(epochSeconds: Double): String {
    if (epochSeconds <= 0.0) return ""
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        .format(Date((epochSeconds * 1_000).toLong()))
}
