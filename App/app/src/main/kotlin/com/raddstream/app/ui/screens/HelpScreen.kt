package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContactSupport
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.brand.Brand
import com.raddstream.app.ui.components.RaddDialogTitle
import com.raddstream.app.viewmodel.HelpViewModel

/**
 * One short Help screen: four rows, each of which goes somewhere.
 *
 * Stitch sub-screens mockup (Nov 2026): "Frequently asked questions",
 * "Report a problem", "Contact us", "About" — each a card with
 * an icon and a chevron, no bottom nav. The answers to the questions are the
 * four short sections this screen always carried (Watch, Download, VIP,
 * Need more help?), so tapping the first row opens them in place
 * instead of inventing new words. A problem report goes through the same
 * contact sink the Detail screen's broken-stream report uses; Contact opens
 * the contact card in Settings, as this screen's own note always said; About
 * shows the same About text and version Settings shows.
 *
 * Most sentences are facts the app already proves elsewhere — the direct
 * downloads — so they never have to be re-checked. The two that depend on
 * the server — the free quality cap and the VIP prices — are read from it
 * when the screen opens, so they stay true when the operator flips the
 * gate or republishes prices. (Settings review, Nov 2026, D1/D8: the old
 * KDoc declared the cap and the prices static, which is exactly what let
 * them rot — the free 720p cap was still printed after the gate went OFF.)
 * The one thing it cannot answer itself is a person's own problem, so the
 * last section sends them to the contact card in Settings rather than
 * inventing an address.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBackClick: () -> Unit,
    onContactClick: () -> Unit = {},
    viewModel: HelpViewModel = hiltViewModel(),
) {
    val reportState by viewModel.reportState.collectAsState()
    val helpState by viewModel.uiState.collectAsState()
    var faqExpanded by rememberSaveable { mutableStateOf(false) }
    var showReportDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Help") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { contentPadding ->
        HelpContent(
            faqExpanded = faqExpanded,
            onFaqToggle = { faqExpanded = !faqExpanded },
            onReportClick = { showReportDialog = true },
            onContactClick = onContactClick,
            onAboutClick = { showAboutDialog = true },
            reportMessage = reportState.message,
            reportMessageIsError = reportState.messageIsError,
            gateOn = helpState.gateOn,
            monthlyPkr = helpState.monthlyPkr,
            yearlyPkr = helpState.yearlyPkr,
            modifier = Modifier.padding(contentPadding),
        )
    }

    if (showReportDialog) {
        ReportBrokenStreamDialog(
            // A report from Help names no title — the sheet is category-only
            // (the reason itself is the report), and onSubmit carries it.
            isSubmitting = reportState.isSubmitting,
            onSubmit = { note ->
                viewModel.submitReport(note)
                showReportDialog = false
            },
            onDismiss = { showReportDialog = false },
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                RaddDialogTitle(
                    Brand.copy("settings_about_heading", "About ${Brand.name}"),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        Brand.copy(
                            "settings_about_body",
                            "${Brand.shortName} finds movies and shows from public catalogs and plays them in one place.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        aboutVersionLine(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showAboutDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Close")
                }
            },
        )
    }
}

/**
 * The four rows without their ViewModel, so a test can drive them with plain
 * callbacks: FAQ expands in place, the rest report back to the caller.
 * [gateOn] is the operator's quality gate — gate ON caps free viewers at
 * 720p, gate OFF plays everything up to 4K free. [monthlyPkr]/[yearlyPkr]
 * are the panel's published VIP prices (0 = not published, and the FAQ
 * drops the numbers rather than printing a zero). Defaults are the cold
 * build that has not heard from the panel yet: gate ON, no prices.
 */
@Composable
internal fun HelpContent(
    faqExpanded: Boolean,
    onFaqToggle: () -> Unit,
    onReportClick: () -> Unit,
    onContactClick: () -> Unit,
    onAboutClick: () -> Unit,
    reportMessage: String?,
    reportMessageIsError: Boolean,
    gateOn: Boolean = true,
    monthlyPkr: Int = 0,
    yearlyPkr: Int = 0,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HelpRow(
            icon = Icons.Default.HelpOutline,
            label = "Frequently asked questions",
            onClick = onFaqToggle,
            expanded = faqExpanded,
            testTag = "help_row_faq",
        ) {
            // The answers are the four sections this screen always
            // carried — shown in place, under the row that asked for them.
            // Settings review (Nov 2026, D1/D8): Watch and VIP are the two
            // answers that depend on the server, so they are built from the
            // state the ViewModel read at open — never hardcoded.
            val watchBody = if (gateOn) {
                "Tap a title, then Play. Free viewers play up to 720p. 1080p and 4K need VIP. " +
                    "A title keeps playing where you left it — find it again under Continue watching on Home."
            } else {
                "Tap a title, then Play. Every title plays up to 4K, free — no VIP needed. " +
                    "A title keeps playing where you left it — find it again under Continue watching on Home."
            }
            val vipBody = buildString {
                val priceParts = listOfNotNull(
                    monthlyPkr.takeIf { it > 0 }?.let { "PKR ${"%,d".format(it)} a month" },
                    yearlyPkr.takeIf { it > 0 }?.let { "PKR ${"%,d".format(it)} a year" },
                )
                if (priceParts.isNotEmpty()) {
                    append("VIP is ${priceParts.joinToString(" or ")}, paid by JazzCash, EasyPaisa or Raast. ")
                } else {
                    append("VIP is a monthly or yearly membership, paid by JazzCash, EasyPaisa or Raast. ")
                }
                append("See the VIP screen for the plans and how to pay.")
            }
            HelpSection(
                title = "Watch",
                body = watchBody,
            )
            HelpSection(
                title = "Download",
                body = "Tap Download on a title to save it on this phone and watch it offline. " +
                    "Downloads are direct files — nothing extra is installed.",
            )
            HelpSection(
                title = "VIP",
                body = vipBody,
            )
            HelpSection(
                title = "Need more help?",
                // Settings review (Nov 2026, D2): the in-app contact form
                // is gone — Settings' Contact card opens the email dialog —
                // so the directions name the real path, not a removed door.
                body = "Tap Write to us in Settings — it opens your email app, addressed to the team. " +
                    "To ask for a title to be taken down, write to us the same way.",
            )
        }
        HelpRow(
            icon = Icons.Default.ErrorOutline,
            label = "Report a problem",
            onClick = onReportClick,
            testTag = "help_row_report",
        )
        HelpRow(
            icon = Icons.Default.ContactSupport,
            label = "Contact us",
            onClick = onContactClick,
            testTag = "help_row_contact",
        )
        HelpRow(
            icon = Icons.Default.Info,
            label = Brand.copy("settings_about_heading", "About ${Brand.name}"),
            onClick = onAboutClick,
            testTag = "help_row_about",
        )

        reportMessage?.let { message ->
            // A message that appears under the thumb the viewer just used is invisible to a screen
            // reader unless it is announced, so this text node is a polite live region.
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (reportMessageIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * One row: an icon tile, a label and a chevron. [expanded] is null for a row
 * that navigates; the FAQ row passes its open state and the chevron points
 * down while its answers are showing. [content], when given, renders under
 * the row inside the same card.
 */
@Composable
private fun HelpRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    expanded: Boolean? = null,
    content: @Composable (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onClick() }
                    .testTag(testTag)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                Icon(
                    imageVector = if (expanded == true) Icons.Default.KeyboardArrowDown
                    else Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded == true && content != null) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/** One headed paragraph: a title, then the plain answer under it. */
@Composable
private fun HelpSection(
    title: String,
    body: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
