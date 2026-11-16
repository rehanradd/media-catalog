package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.raddstream.app.viewmodel.AccountOverviewUiState
import com.raddstream.app.viewmodel.AccountViewModel

/**
 * The dashboard: one screen that answers "where do I stand?" — the
 * membership, from the server's own read, with a door to the profile.
 * (The coin balance and the streak lived here until coins and daily
 * check-in were removed 2026-11-17.) A guest (no account yet) sees the
 * same doors with the numbers replaced by the honest "sign in to see
 * this" — the screen never invents a membership for someone who has none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBackClick: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenVipUpgrade: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    DashboardContent(
        state = state,
        onBackClick = onBackClick,
        onOpenProfile = onOpenProfile,
        onOpenVipUpgrade = onOpenVipUpgrade,
    )
}

/**
 * The dashboard's body, split from the screen so tests can render it with
 * a fake state (the Home pattern: HomeScreen + HomeContent). Gate OFF
 * means every viewer is premium, so the membership card says so and
 * offers no upsell; gate ON shows the VIP state instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(
    state: AccountOverviewUiState,
    onBackClick: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenVipUpgrade: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
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
                    "Loading your account…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.signedIn && state.paywallVisible) {
                Text(
                    "Sign in from Settings to see your membership here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Membership", style = MaterialTheme.typography.labelLarge)
                    if (!state.paywallVisible) {
                        // Free launch: the gate is OFF, so every viewer is
                        // premium — the card says so and offers no upsell.
                        Text(
                            "Premium",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "All qualities and downloads included.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            if (state.isVip) "VIP — active" else "Free",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!state.isVip) {
                            Text(
                                "VIP unlocks the higher qualities and direct downloads.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onOpenVipUpgrade, modifier = Modifier.fillMaxWidth()) {
                                Text("See VIP options")
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onOpenProfile, modifier = Modifier.fillMaxWidth()) {
                Text("Your profile")
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
