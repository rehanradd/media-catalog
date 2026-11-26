package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.raddstream.app.R
import com.raddstream.app.viewmodel.AccountOverviewUiState
import com.raddstream.app.viewmodel.AccountViewModel
import com.raddstream.app.ui.theme.RaddTokens

/**
 * The profile: what this account is, on the catalog's own record. Session
 * 123: the app had no profile screen — the account existed only as an account
 * code in Settings and a trial countdown. This screen shows the membership
 * and the dates the catalog recorded, and nothing the catalog did not say:
 * no name, no phone, no invented identity. (The streak and coin balance
 * lived here until coins and daily check-in were removed 2026-11-17.)
 * Signing out drops the code on this device and tells the catalog to revoke
 * it, so the account cannot be reopened from a copy of the code.
 *
 * P0-1 (audit B-1/C-1): Profile is the hub tab ("You") — avatar + account,
 * VIP, Downloads, Watch history, Settings, Help, Kids Lock. Settings is a
 * push destination from here, not a tab. The old account screen is the
 * hub's account section (same membership line, same dates, same sign-out),
 * with the hub rows below it — the Dashboard stays a push destination
 * behind "Open dashboard".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBackClick: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenVipUpgrade: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenWatchHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenKidsLock: () -> Unit = {},
    /**
     * True when this screen is showing as a bottom tab: a tab root has no
     * back arrow. Pushed from Settings or Dashboard it keeps the arrow.
     */
    isTab: Boolean = false,
    /**
     * Kids mode: the Downloads and VIP rows leave the hub — a kid's
     * downloads are unfiltered, and VIP is commerce.
     */
    kidsMode: Boolean = false,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    ProfileContent(
        state = state,
        onBackClick = onBackClick,
        onOpenDashboard = onOpenDashboard,
        onOpenVipUpgrade = onOpenVipUpgrade,
        onOpenDownloads = onOpenDownloads,
        onOpenWatchHistory = onOpenWatchHistory,
        onOpenSettings = onOpenSettings,
        onOpenHelp = onOpenHelp,
        onOpenKidsLock = onOpenKidsLock,
        isTab = isTab,
        kidsMode = kidsMode,
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
    onOpenVipUpgrade: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenWatchHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenKidsLock: () -> Unit = {},
    isTab: Boolean = false,
    kidsMode: Boolean = false,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Your profile") },
                navigationIcon = {
                    // P0-1: a tab root has no back arrow; a pushed Profile
                    // (from Settings or Dashboard) keeps it.
                    if (!isTab) {
                        IconButton(onClick = onBackClick) {
                            Icon(painterResource(R.drawable.ic_action_arrow_left), contentDescription = "Back")
                        }
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
                    "No account yet — sign in from Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // P0-1: the hub's avatar — the account has no
                            // name or photo, so the mark is a person glyph
                            // on the primary container, never an invented
                            // initial or picture.
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_user),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                            }
                        }
                        Text(
                            "Private by design — no name, phone or location, just a code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // P0-1: the hub rows — every secondary destination lives one
            // push deep from here, never in the tab bar. VIP hides while
            // the quality gate is OFF (nothing to sell — same rule as
            // Settings' VIP card); Downloads and VIP hide in kids mode.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.signedIn) {
                    ProfileHubRow(
                        label = "Open dashboard",
                        iconRes = R.drawable.ic_action_dashboard,
                        onClick = onOpenDashboard,
                    )
                }
                if (state.paywallVisible && !kidsMode) {
                    ProfileHubRow(
                        label = "VIP",
                        iconRes = R.drawable.ic_action_award,
                        onClick = onOpenVipUpgrade,
                    )
                }
                if (!kidsMode) {
                    ProfileHubRow(
                        label = "Downloads",
                        iconRes = R.drawable.ic_action_download,
                        onClick = onOpenDownloads,
                    )
                }
                ProfileHubRow(
                    label = "Watch history",
                    iconRes = R.drawable.ic_action_history,
                    onClick = onOpenWatchHistory,
                )
                ProfileHubRow(
                    label = "Settings",
                    iconRes = R.drawable.ic_tab_settings,
                    onClick = onOpenSettings,
                )
                ProfileHubRow(
                    label = "Help",
                    iconRes = R.drawable.ic_action_info,
                    onClick = onOpenHelp,
                )
                ProfileHubRow(
                    label = "Kids Lock",
                    iconRes = R.drawable.ic_action_lock,
                    onClick = onOpenKidsLock,
                )
            }
            if (state.signedIn) {
                Button(
                    shape = RaddTokens.Shape.Pill,
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

/** One hub row: a leading house icon, a label, and a chevron — 56 dp min, full-width tap. */
@Composable
private fun ProfileHubRow(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_action_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** An epoch-seconds date as "d MMM yyyy" — the account dates on the profile. */
private fun formatAccountDay(epochSeconds: Double): String {
    if (epochSeconds <= 0.0) return ""
    return SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        .format(Date((epochSeconds * 1_000).toLong()))
}
