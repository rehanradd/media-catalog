package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.RegionChoices
import com.raddstream.app.data.RemoteFeatures
import com.raddstream.app.network.models.DEFAULT_DISCLAIMER_EMAIL
import com.raddstream.app.player.AudioBoost
import com.raddstream.app.player.AudioBoostUi
import com.raddstream.app.share.LanShareStatus
import com.raddstream.app.ui.components.RaddDialogTitle
import com.raddstream.app.ui.theme.RaddChipBorderSoft
import com.raddstream.app.ui.theme.RaddChipLabelSoft
import com.raddstream.app.ui.theme.RaddSettingsCard
import com.raddstream.app.ui.theme.RaddSettingsHelper
import com.raddstream.app.ui.theme.RaddTextFamily
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.viewmodel.AccountCardCopy
import com.raddstream.app.viewmodel.AccountUiState
import com.raddstream.app.viewmodel.ImageQuality
import com.raddstream.app.viewmodel.KeepTtl
import com.raddstream.app.viewmodel.MembershipCardCopy
import com.raddstream.app.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

/** The subtitle chips, in the order the row shows them. Readable by the region test, which
 * pins that a card can only ever seed a word this row can actually display.
 * F23 (Batch B): Kannada joins the list, matching the audio list — the subtitle row had
 * every other South Indian language but this one. */
internal val subtitleOptions = listOf("Off", "English", "Hindi", "Urdu", "Tamil", "Telugu", "Kannada")

/**
 * Robo run 5: the M3 default disabled button text (onSurface at 38%) reads
 * 2.98:1 on the dark fill — under the 4.5:1 floor. The two buttons that can
 * sit disabled on this screen ("Test & Save Connection" with no server typed,
 * "Sign in" with no key typed) brighten their disabled text to onSurface at
 * this alpha, which clears 4.5:1 against the disabled fill (≈5.7:1, pinned
 * by DisabledButtonContrastTest) while the washed-out fill still reads as
 * disabled. Internal so that test can read the same value the buttons use.
 */
internal const val DisabledButtonContentAlpha = 0.65f

/**
 * Settings review (Gemini 5.5/10, 2026-11-16): the screen's two text roles.
 * Group headers — section headings, card titles, in-card row titles — are
 * 13sp Medium; helpers are 13sp Regular in #A3ABA8 at 1.4 line-height
 * (18sp). Both go through brandSp so typography.scale still reaches them.
 */
private val settingsGroupTitleStyle = TextStyle(
    fontFamily = RaddTextFamily,
    fontWeight = FontWeight.Medium,
    fontSize = brandSp(13),
    lineHeight = brandSp(18),
)

private val settingsHelperStyle = TextStyle(
    fontFamily = RaddTextFamily,
    fontWeight = FontWeight.Normal,
    fontSize = brandSp(13),
    lineHeight = brandSp(18),
    color = RaddSettingsHelper,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /**
     * The operator's board. Two cards on this screen offer a feature it can switch off, and an
     * "Open Downloads" button that enqueues nothing is worse than no button at all.
     */
    features: RemoteFeatures = RemoteFeatures(),
    onOpenDownloads: () -> Unit = {},
    onOpenVipUpgrade: () -> Unit = {},
    onOpenRegionChoice: () -> Unit = {},
    /** Session 123: the account screens — dashboard, profile, coins. */
    onOpenDashboard: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenCoins: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenLanguages: () -> Unit = {},
    onOpenWatchHistory: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Audit #51: the server switcher lives behind Advanced — closed until
    // the viewer asks for it, so a casual tap can never disconnect the app.
    var showAdvanced by remember { mutableStateOf(false) }
    // UI-fix P1-T1: the Account card's key machinery lives behind these
    // two doors — guests see Sign in / Create account only, signed-in
    // viewers see Sync / Sign out, and the rest waits behind a tap.
    var showSignInForm by remember { mutableStateOf(false) }
    var showAccountDetails by remember { mutableStateOf(false) }

    // A status line this screen used to print inside the About card as a button that did
    // nothing. It is a transient answer to an action, so it lives where every other screen
    // in this app puts one: a snack bar, shown once, then cleared back through the ViewModel
    // so rotating the device does not re-announce a tap from ten minutes ago.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.statusMessage) {
        val message = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearStatus()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // CL-7: the screen's title says "Settings" and so does its nav tab —
        // the title is paint, not a second announcement. The same treatment
        // applies to any other screen whose TopAppBar title exactly matches its
        // nav-tab label; Settings was the one Robo flagged.
        // Review fix: the screen title is 24sp SemiBold (headlineSmall), not
        // the 22sp default — the hierarchy's top rung.
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                },
            )
        },
    ) { contentPadding ->
        // Review fix: scrolled content used to shear off at the hard edge
        // under the app bar (the Image quality chips clipped with no title
        // above them). The column carries extra top air, and a short fade
        // from the background colour sits over its top edge, so content
        // dissolves under the bar instead of cutting off. Paint only — it
        // draws no clicks and the scroll underneath still works. The
        // colour is read here, in composition: drawWithContent is not a
        // @Composable scope and cannot read the theme itself.
        val fadeBackground = MaterialTheme.colorScheme.background
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(fadeBackground, Color.Transparent),
                        ),
                        size = Size(size.width, 28.dp.toPx()),
                    )
                },
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsSectionHeading("Watching")
            // The region row lives in its own card under Watching, not
            // buried in Playback & Display: it is the one row the viewer needs
            // after every region change, and it must be findable without
            // scrolling past a dozen cards (Session 114).
            // F5b (Batch A): the old title "Watching region" named a setting the
            // card does not hold — the card picks the viewer's content
            // preference (which region's catalog defaults apply), not a region
            // to watch. The title now says what the card is.
            // Mockup: the card carries its globe icon at the top-right, and
            // the change link reads as a jade link with an arrow.
            SettingsCard(title = "Content preference", icon = Icons.Default.Public) {
                val region = RegionChoices.byId(state.userRegion)
                Text(
                    region.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    region.preferenceLine(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenRegionChoice) {
                    Text("Change what you watch →")
                }
            }

            // ── Languages ──────────────────────────────────────────────
            // The ONE place that shows the languages in force. The Region
            // card picks them, the player sheet changes them mid-title, and
            // the Languages screen changes them here — all three write the
            // same keys, and this card shows the effective values, so a
            // viewer never has to guess which one won.
            // Mockup: the card carries its translate icon at the top-right.
            SettingsCard(title = "Languages", icon = Icons.Default.Translate) {
                // F5a (Batch A): same effective values and provenance as the
                // Languages screen's top line, so the two can never disagree
                // without saying why.
                Text(
                    "Audio: ${state.prefs.audioLanguage} (${state.prefs.audioProvenance}). " +
                        "Subtitles: ${state.prefs.subtitleLang} (${state.prefs.subtitleProvenance}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenLanguages,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Languages screen")
                }
                // Audit #49: the old helper was broken English ("the player
                // sheet write"). The Languages screen is the one place these
                // change (audit #50) — the chips that duplicated it are gone.
                Text(
                    "Audio, subtitles and subtitle size, in one place. Changes here apply everywhere, including the player.",
                    style = settingsHelperStyle,
                )
            }

            // Mockup: the card carries its sliders icon at the top-right.
            SettingsCard(title = "Playback & display", icon = Icons.Default.Tune) {
                Spacer(Modifier.height(10.dp))
                PreferenceRowTitle("Image quality")
                ChipRow(ImageQuality.entries.toList().map { it.label }, ImageQuality.entries.indexOf(state.prefs.imageQuality)) { idx ->
                    viewModel.setImageQuality(ImageQuality.entries.toList()[idx])
                }

                Spacer(Modifier.height(10.dp))
                // Review fix: the title above this toggle repeated its own
                // label ("Poster loading on mobile data" over "Load poster
                // images over mobile data") — the toggle stands alone now,
                // with a one-line helper in plain words.
                SwitchRow(
                    checked = state.prefs.downloadOnMobileData,
                    onCheckedChange = viewModel::setDownloadOnMobileData,
                    label = "Load poster images over mobile data",
                )
                // A keep is gated by the Wi-Fi-only switch in the Offline card above,
                // not by this one — this switch only ever governed the thumbnails.
                Text(
                    "Only poster thumbnails use mobile data. Downloads follow the Wi-Fi-only switch.",
                    style = settingsHelperStyle,
                )

                Spacer(Modifier.height(10.dp))
                // Review fix: same shape as the poster toggle — the "Data
                // saver" title repeated the switch label, and the helper
                // exposed server jargon ("the server is asked for its
                // data-saving copy"). One line, plain words.
                SwitchRow(
                    checked = state.prefs.dataSaver,
                    onCheckedChange = viewModel::setDataSaver,
                    label = "Save mobile data while watching",
                )
                Text(
                    "On mobile data, titles start at 480p. You can still switch up in the player.",
                    style = settingsHelperStyle,
                )

                Spacer(Modifier.height(10.dp))
                PreferenceRowTitle("How long downloads are kept")
                ChipRow(KeepTtl.entries.map { it.label }, KeepTtl.entries.indexOf(state.prefs.keepTtl)) { idx ->
                    viewModel.setKeepTtl(KeepTtl.entries.toList()[idx])
                }
                // The retention is temp/cache-scoped (verified against
                // TtlPolicy: the sweep only ever ages AUTO_PREVIEW bytes,
                // and a file the viewer explicitly kept is never
                // auto-deleted) — so the label says what it is, plainly.
                Text(
                    "Only files the app fetched on its own. Downloads you kept are never removed.",
                    style = settingsHelperStyle,
                )

                Spacer(Modifier.height(10.dp))
                // Mockup: the title and its percent share one row, the slider
                // runs full-width below them. The percent stays neutral (M8):
                // a readout is not a primary action, so it does not take jade.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PreferenceRowTitle("Audio level")
                        Text(
                            audioLevelPercent(state.prefs.audioBoost),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // Review fix: what the slider adjusts, in one line — the
                    // app's own playback volume, riding on the device volume.
                    Text(
                        "In-app playback volume, relative to your device volume.",
                        style = settingsHelperStyle,
                    )
                    // Review fix: the thumb at 100% used to sit ~16dp from
                    // the screen edge, inside the back-gesture zone. The
                    // track is inset 12dp inside the 16dp card padding —
                    // 28dp from the viewport edge — and the touch area is
                    // 48dp tall.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Slider(
                            value = state.prefs.audioBoost.coerceIn(0f, AudioBoostUi.MAX_OFFERED_LEVEL),
                            onValueChange = viewModel::setAudioBoost,
                            valueRange = 0f..AudioBoostUi.MAX_OFFERED_LEVEL,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        )
                    }
                }
                // Still worth saying: below the ceiling the boost is real, but it rides on the
                // viewer's own system volume, so the caption states what is applied, not what
                // was asked. The range above is already the range this build can honour.
                Text(
                    AudioBoostUi.honestSummary(state.prefs.audioBoost, 1f),
                    style = settingsHelperStyle,
                )

                // Wallpaper colour is a brand decision, not a user one: this brand file
                // locks the palette (features.dynamic_color=false), and a switch that is
                // overruled by the build it ships in is worse than no switch. It appears
                // the moment a re-skin permits it.
                if (Brand.dynamicColorAllowed) {
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        checked = state.prefs.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                        label = "Dynamic colour from wallpaper",
                    )
                }
                Text(
                    "Saved on this device, and applied the next time each one matters: image size " +
                        "from the next poster, the download duration from the next download.",
                    style = settingsHelperStyle,
                )
            }

            // ── Player defaults ──────────────────────────────────────
            // The switches the player itself obeys (plan §5 step 10, P36): autoplay-next
            // gates the auto-advance, autoplay-previews gates the Home hero, default
            // quality is the rung a new title starts on when Play was Auto, and subtitle
            // size is the same number the player sheet's slider writes.
            SettingsCard(title = "Player", icon = Icons.Default.PlayArrow) {
                SwitchRow(
                    checked = state.prefs.autoplayNext,
                    onCheckedChange = viewModel::setAutoplayNext,
                    label = "Play the next episode automatically",
                )
                Text(
                    "When this is off, an episode ends on the Next Episode button instead of moving on by itself.",
                    style = settingsHelperStyle,
                )
                SwitchRow(
                    checked = state.prefs.autoplayPreviews,
                    onCheckedChange = viewModel::setAutoplayPreviews,
                    label = "Auto-play previews on Home",
                )
                Text(
                    "When this is off, the Home hero stays on the first poster until you swipe it.",
                    style = settingsHelperStyle,
                )
                // PL-10: opt-in auto-enter picture-in-picture. Off by default: a
                // window the viewer did not open is a window they did not ask for.
                SwitchRow(
                    checked = state.prefs.autoPip,
                    onCheckedChange = viewModel::setAutoPip,
                    label = "Auto picture-in-picture",
                )
                Text(
                    "When this is on, leaving the player while a title is playing keeps it playing in a small window.",
                    style = settingsHelperStyle,
                )

                Spacer(Modifier.height(10.dp))
                PreferenceRowTitle("Default quality")
                val qualityBands = listOf("auto", "480p", "720p", "1080p", "2160p")
                val qualityLabels = listOf("Auto", "480p", "720p", "1080p", "4K")
                ChipRow(qualityLabels, qualityBands.indexOf(state.prefs.defaultQuality).coerceAtLeast(0)) { idx ->
                    viewModel.setDefaultQuality(qualityBands[idx])
                }
                Text(
                    "The quality a new title starts on. Auto lets the player pick; a fixed choice starts there and stays.",
                    style = settingsHelperStyle,
                )

                Spacer(Modifier.height(10.dp))
                PreferenceRowTitle("Subtitle size")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "${state.prefs.subtitleSizePercent}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        value = state.prefs.subtitleSizePercent.toFloat(),
                        onValueChange = { viewModel.setSubtitleSize(it.toInt()) },
                        valueRange = 50f..200f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "The same size the player sheet offers while watching; it applies from the next playback.",
                    style = settingsHelperStyle,
                )
            }

            SettingsSectionHeading("Downloads")
            // ── Downloads entry ────────────────────────────────────────
            if (features.downloads) {
                SettingsCard(title = "Offline", icon = Icons.Default.Download) {
                    Text(
                        "Manage downloaded titles, view storage use and expiry.",
                        style = settingsHelperStyle,
                    )
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        checked = state.prefs.wifiOnlyDownloads,
                        onCheckedChange = viewModel::setWifiOnlyDownloads,
                        label = "Download only on Wi-Fi",
                    )
                    Text(
                        "Downloads happen only when this phone is on Wi-Fi. Turn it off to download on mobile data too.",
                        style = settingsHelperStyle,
                    )
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        checked = state.prefs.autoDeleteWatched,
                        onCheckedChange = viewModel::setAutoDeleteWatched,
                        label = "Delete watched downloads",
                    )
                    Text(
                        "A finished download deletes itself to free space. A finished series episode deletes its own download too — only the episode you just watched, never the rest of the season.",
                        style = settingsHelperStyle,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Downloads")
                    }
                }
            }

            // ── Share kept files on the local network ──────────────────
            // Gone, not disabled, when the operator switched sharing off: the row would otherwise
            // read "on" while LanShareRunner has already closed the socket it promises to open.
            if (features.sharing) {
                SettingsCard(title = "Share on your network") {
                    SwitchRow(
                        checked = state.lanShareEnabled,
                        onCheckedChange = viewModel::setLanShareEnabled,
                        label = "Let devices on this network play downloaded files",
                    )
                    Text(
                        lanShareStatusLine(state.lanShareStatus),
                        style = settingsHelperStyle,
                    )
                    Text(
                        "Only devices on your Wi-Fi can see them.",
                        style = settingsHelperStyle,
                    )
                }
            }

            // ── Cache ──────────────────────────────────────────────────
            SettingsCard(title = "Storage & cache") {
                // The poster-image cache as a bar: what it holds now against the
                // loader's own cap. Nothing here is a guess at the device's free
                // space, and the kept videos are not in this number at all — they
                // are listed and removed on the Downloads screen.
                val cacheBytes = state.posterCacheBytes
                val cacheMax = state.posterCacheMaxBytes
                if (cacheBytes != null && cacheMax != null) {
                    Text(
                        "Poster images: ${viewModel.formatCacheSize(cacheBytes)} of " +
                            viewModel.formatCacheSize(cacheMax),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { cacheBytes.toFloat() / cacheMax.toFloat().coerceAtLeast(1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(onClick = viewModel::clearImageCache, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear poster images")
                }
                Text(
                    "This clears only the poster images the app has fetched; they come back as you " +
                        "browse. Videos you downloaded for offline are not touched here — they are removed " +
                        "individually on the Downloads screen.",
                    style = settingsHelperStyle,
                )
            }

            SettingsSectionHeading("Account")
            // ── VIP membership ─────────────────────────────────────────
            // Free launch: the whole card (prices, payment instructions,
            // upgrade button) hides while the quality gate is OFF — every
            // viewer is premium then, and there is nothing to sell.
            if (state.paywallVisible) {
                SettingsCard(title = "VIP membership", icon = Icons.Default.Star) {
                // The plan summary: what this install actually has (VIP with its
                // date, a payment waiting, the free VIP days, or free) plus the
                // operator's published prices when the server sent any. The
                // summary lives in [MembershipCardCopy] so a test can pin it:
                // a trial in progress must never read as "Free plan" here while
                // the Account card says the free days are running (Session 114).
                Text(
                    MembershipCardCopy.summary(
                        planIsVip = state.planIsVip,
                        planVipUntilMillis = state.planVipUntilMillis,
                        planPaymentPending = state.planPaymentPending,
                        trialDays = state.account.trialDays,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (state.planMonthlyPkr > 0 || state.planYearlyPkr > 0) {
                    Text(
                        buildString {
                            if (state.planMonthlyPkr > 0) {
                                append("Rs ${"%,d".format(state.planMonthlyPkr)} a month")
                            }
                            if (state.planYearlyPkr > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("Rs ${"%,d".format(state.planYearlyPkr)} a year")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Support the catalog with a monthly or yearly membership. Pay through " +
                        "JazzCash, EasyPaisa or Raast, then submit the transaction ID from " +
                        "your confirmation.",
                    style = settingsHelperStyle,
                )
                Button(onClick = onOpenVipUpgrade, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.planIsVip) "View membership" else "Upgrade to VIP")
                }
                }
            }

            // ── The optional sync account ──────────────────────────────
            SettingsCard(title = "Account", icon = Icons.Default.Person) {
                val account = state.account
                Text(
                    if (account.signedIn) {
                        "Signed in on this device. Your saved list, watch history and resume points " +
                            "are saved to your account as well as on this device."
                    } else {
                        "Guest mode. Everything in this app works without an account: your saved " +
                            "list, watch history and resume points stay on this device. Sign in only " +
                            "if you want them on another device."
                    },
                    style = settingsHelperStyle,
                )

                // Free launch: the Dashboard / Profile / Coins doors hide
                // while the quality gate is OFF — the coins and VIP machinery
                // they open is hidden while every viewer is premium. The VIP
                // Membership card above is gated on the same flag.
                AccountNavRow(
                    paywallVisible = state.paywallVisible,
                    onOpenDashboard = onOpenDashboard,
                    onOpenProfile = onOpenProfile,
                    onOpenCoins = onOpenCoins,
                )

                // ── the free days this install was granted ───────────────────────
                account.trialSecondsLeft?.let { secondsLeft ->
                    val daysLeft = account.trialDays ?: 0
                    Text(
                        if (daysLeft > 0) AccountCardCopy.trialActive(daysLeft) else AccountCardCopy.TRIAL_ENDED,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                    if (daysLeft > 0) {
                        Text(
                            AccountCardCopy.TRIAL_HONESTY,
                            style = settingsHelperStyle,
                        )
                    }
                }

                account.revealedKey?.let { key ->
                    OutlinedTextField(
                        value = key,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Your sync key") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            val clipboard = LocalClipboardManager.current
                            TextButton(onClick = { clipboard.setText(AnnotatedString(key)) }) {
                                Text("Copy")
                            }
                        },
                    )
                    // Honest, and it must stay honest: the warning names the transport the
                    // server in use actually speaks — plain HTTP in the clear, or TLS for
                    // the shipped default — instead of implying a protection the transport
                    // does not provide.
                    Text(
                        SettingsViewModel.revealWarning(state.serverUrl),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = viewModel::hideRevealedKey) { Text("Done showing this key") }
                }

                // UI-fix P1-T1: progressive disclosure. A guest sees "Sign in"
                // and "Create account" only — the key field, the label and the
                // long warnings wait behind the Sign in tap. A signed-in viewer
                // sees Sync / Sign out, and the key hint, label and phone+PIN
                // link wait behind "Account details".
                if (!account.signedIn) {
                    if (!showSignInForm) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { showSignInForm = true },
                                enabled = !account.isBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Sign in")
                            }
                            OutlinedButton(
                                onClick = viewModel::createAccount,
                                enabled = !account.isBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Create account")
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = account.quickInput,
                            onValueChange = viewModel::onQuickInputChanged,
                            label = { Text("Sync key") },
                            placeholder = { Text("The 16-character key from your other device") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // The sentence this card used to print — "anyone who knows it can derive the
                        // same key" — described a feature that is now deleted, so it is replaced by
                        // the two facts that are true of what is left: a number cannot open an
                        // account, and the key is the whole account.
                        Text(
                            "Enter the key this app showed once, when it created the account. A mobile " +
                                "number is not a key and this app cannot turn one into a key: knowing " +
                                "someone's number gets them nowhere. An account is only as strong as " +
                                "its key, so keep the key somewhere safe, and sign out on any device " +
                                "that is not yours.",
                            style = settingsHelperStyle,
                        )
                        AccountLabelField(
                            value = account.labelInput,
                            onValueChange = viewModel::onAccountLabelChanged,
                            onSave = null,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = viewModel::quickSignIn,
                                enabled = !account.isBusy && account.quickInput.isNotBlank(),
                                // Robo run 5: the disabled text on this button read
                                // 2.98:1 — see DisabledButtonContentAlpha.
                                colors = ButtonDefaults.buttonColors(
                                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = DisabledButtonContentAlpha,
                                    ),
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                BusyLabel(account.isBusy, "Sign in")
                            }
                            OutlinedButton(
                                onClick = viewModel::createAccount,
                                enabled = !account.isBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Create a random key")
                            }
                        }
                        TextButton(onClick = { showSignInForm = false }) { Text("Cancel") }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = viewModel::syncNow,
                            enabled = !account.isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            BusyLabel(account.isBusy, "Sync now")
                        }
                        OutlinedButton(
                            onClick = viewModel::signOut,
                            enabled = !account.isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Sign out")
                        }
                    }
                    TextButton(onClick = { showAccountDetails = !showAccountDetails }) {
                        Text(if (showAccountDetails) "Hide account details" else "Account details")
                    }
                    if (showAccountDetails) {
                        if (account.revealedKey == null && account.keyHint.isNotEmpty()) {
                            Text(
                                "Key on this device: ${account.keyHint}. The full key was shown once, when it " +
                                    "was created, and is not stored anywhere it can be re-displayed.",
                                style = settingsHelperStyle,
                            )
                        }
                        AccountLabelField(
                            value = if (account.labelInput.isEmpty()) account.accountLabel else account.labelInput,
                            onValueChange = viewModel::onAccountLabelChanged,
                            onSave = viewModel::saveAccountLabel,
                        )
                        // ── the phone + PIN profile link ─────────────────────────
                        //
                        // Offered only to a device that holds a key: the route writes onto the account the
                        // bearer names, so with no credential there is nothing to link and the honest thing
                        // is to hide the form rather than let it fail.
                        Spacer(Modifier.height(10.dp))
                        Text(
                            AccountCardCopy.LINK_TITLE,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            AccountCardCopy.LINK_BONUS,
                            style = settingsHelperStyle,
                        )
                        Text(
                            AccountCardCopy.LINK_PIN_MEANING,
                            style = settingsHelperStyle,
                        )
                        OutlinedButton(
                            onClick = viewModel::openLinkForm,
                            enabled = !account.isLinking,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (account.showLinkForm) "Close" else AccountCardCopy.LINK_BUTTON)
                        }
                        if (account.showLinkForm) {
                            ProfileLinkDialog(account = account, viewModel = viewModel, serverUrl = state.serverUrl)
                        }
                    }
                }

                account.message?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (account.messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    "Sync only ever names a title by the reference the server gave it, so a " +
                        "title this app cannot reference stays on this device. Your saved list " +
                        "is read from this device either way — offline, it shows exactly what it " +
                        "always showed.",
                    style = settingsHelperStyle,
                )
            }

            // ── Watch history ────────────────────────────────────────────────
            // The history is this device's own record; Settings is where a viewer
            // looks for a list that is not a catalog rail.
            SettingsCard(title = "Watch history", icon = Icons.Default.History) {
                Text(
                    "Everything you watched on this phone, newest first — including titles you finished.",
                    style = settingsHelperStyle,
                )
                OutlinedButton(
                    onClick = onOpenWatchHistory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open watch history")
                }
            }

            SettingsSectionHeading("About")
            // ── Help ───────────────────────────────────────────────────────
            // Read help first, write to us second: most questions are answered on
            // the Help screen, and the Contact form is for a person's own problem.
            SettingsCard(title = "Help", icon = Icons.Default.Help) {
                Text(
                    "How to watch, download, and use VIP and coins.",
                    style = settingsHelperStyle,
                )
                OutlinedButton(
                    onClick = onOpenHelp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Read help")
                }
            }

            // ── Contact us ───────────────────────────────────────────────────
            SettingsCard(title = "Contact us", icon = Icons.Default.Email) {
                Text(
                    "Report a problem, ask about a payment, or ask for a title to be taken down. No account needed.",
                    style = settingsHelperStyle,
                )
                OutlinedButton(
                    onClick = viewModel::openContactForm,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Write to us")
                }
                state.contact.message?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.contact.messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (state.contact.showForm) {
                    ContactDialog(
                        email = state.disclaimer.effective().email,
                        onDismiss = viewModel::closeContactForm,
                    )
                }
            }

            // ── Disclaimer ───────────────────────────────────────────────────
            // The card shows the effective disclaimer: the panel's text when
            // the operator wrote one, else the shipped default — so there is
            // always one to read, and "No disclaimer published yet" can no
            // longer happen.
            SettingsCard(title = "Disclaimer", icon = Icons.Default.Info) {
                val disclaimer = state.disclaimer.effective()
                Text(
                    "Version ${disclaimer.version} — what this app is, and what it is not.",
                    style = settingsHelperStyle,
                )
                OutlinedButton(
                    onClick = viewModel::openDisclaimer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Read the disclaimer")
                }
                if (state.showDisclaimer) {
                    DisclaimerDialog(
                        disclaimer = disclaimer,
                        confirmLabel = "Close",
                        onConfirm = viewModel::closeDisclaimer,
                        onDismissRequest = viewModel::closeDisclaimer,
                    )
                }
            }

            // ── Anonymous usage totals (Tracking A) ──────────────────────
            // Totals only: how many plays, how often playback fails, how
            // often the app crashes. No name, number, location, title, or
            // search — and the switch is the same answer the first-run
            // dialog asked, changeable here at any time.
            SettingsCard(title = "Anonymous usage totals", icon = Icons.Default.Info) {
                Text(
                    "Share anonymous totals with ${Brand.name}: plays, " +
                        "playback failures, and crashes. Nothing that can " +
                        "identify you or what you watch is ever sent.",
                    style = settingsHelperStyle,
                )
                SwitchRow(
                    checked = state.analyticsConsent == true,
                    onCheckedChange = viewModel::setAnalyticsConsent,
                    label = "Share anonymous totals",
                )
            }

            // ── About / Disclaimer (accurate, no misleading claims) ─────
            SettingsCard(
                title = Brand.copy("settings_about_heading", "About ${Brand.name}"),
                icon = Icons.Default.Info,
            ) {
                Text(
                    Brand.copy(
                        "settings_about_body",
                        "${Brand.shortName} finds movies and shows from public catalogs and plays them in one place.",
                    ),
                    style = settingsHelperStyle,
                )
                Text(
                    "Version ${Brand.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Advanced ───────────────────────────────────────────────
            // Audit #51: the server switcher lives behind Advanced, so a
            // casual tap can never disconnect the app from its catalog.
            SettingsSectionHeading("Advanced")
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAdvanced) "Hide advanced settings" else "Show advanced settings")
            }
            if (showAdvanced) {
                            // ── Server Connection ──────────────────────────────────────
                            SettingsCard(title = "Catalog server", icon = Icons.Default.Storage) {
                                // The address is never printed here: a saved server reads as
                                // "Connected", and the field opens only when the viewer asks
                                // to change it. The test still fetches the counts for its own
                                // message, which never includes the address either.
                                if (!state.showServerField) {
                                    Text(
                                        text = if (state.serverUrl.isNotBlank()) "Connected to your catalog." else "No catalog server set.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedButton(onClick = viewModel::revealServerField, modifier = Modifier.fillMaxWidth()) {
                                        Text("Change")
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = state.serverUrl,
                                        onValueChange = viewModel::onServerUrlChanged,
                                        label = { Text("Server URL") },
                                        placeholder = { Text("http://<host> — one address, any catalog you may use") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    state.testMessage?.let { msg ->
                                        Text(
                                            text = msg,
                                            color = if (state.testIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Button(
                                        onClick = viewModel::testAndSaveServer,
                                        enabled = !state.isTesting && state.serverUrl.isNotBlank(),
                                        // Robo run 5: the disabled text on this button read
                                        // 2.98:1 — see DisabledButtonContentAlpha.
                                        colors = ButtonDefaults.buttonColors(
                                            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = DisabledButtonContentAlpha,
                                            ),
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        if (state.isTesting) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                        } else {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Test & Save Connection")
                                        }
                                    }
                                }

                                // No counts here: works/releases/links are the operator's numbers,
                                // not something a viewer needs to see on their own Settings screen.
                                // The connection test above still fetches them for its own message.
                            }

            }
        }
    }
}

/**
 * Audit #50: Settings is four sections, not a junk drawer of ~15 cards.
 * One heading per section — Watching, Downloads, Account, About — plus
 * Advanced for the server switcher.
 *
 * F15 (Batch B): the heading's top padding is 16dp, not 8dp — the sections
 * read as sections, with air above each heading, instead of the heading
 * sitting on the card above it.
 *
 * Review fix: sentence case, 13sp Medium — the old all-caps labelMedium
 * read as a second, louder voice than the card titles it introduces.
 */
@Composable
private fun SettingsSectionHeading(text: String) {
    Text(
        text,
        style = settingsGroupTitleStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RaddTokens.Radius.Card))
            // Review fix: flat #1A1A1A grouped section card, no nested inner
            // boxes — the old surface fill sat one more tint inside the
            // #121212 canvas. The title stays the group header (13sp
            // Medium); no second header layer is added on top of it.
            .background(RaddSettingsCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Mockup: the title sits left, the card's icon at the top-right —
        // the icon is a label for the card, not a bullet before its title.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = settingsGroupTitleStyle, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

/**
 * A button label that shows its own request. Kept to one helper so the account card's two
 * network-touching buttons spin the same indicator the server card already uses, and a tap
 * that is still in flight never looks like a tap that did nothing.
 */
@Composable
private fun BusyLabel(busy: Boolean, label: String) {
    if (busy) {
        CircularProgressIndicator(
            Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        Text(label)
    }
}

/**
 * The optional label the viewer types about themselves, and the only place a phone number is
 * ever asked for in this app.
 *
 * It is one field and, when signed in, one button. There is no derivation behind it: the text
 * goes to `ServerSettingsRepository.saveAccountLabel` and to no endpoint, and no decision about
 * whether a request is allowed reads it. [onSave] is null in the sign-in block, where pressing
 * Sign in commits whatever was typed, and non-null once an account is open and the label can be
 * edited on its own.
 */
@Composable
internal fun AccountLabelField(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: (() -> Unit)?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // Robo run 5: this field was flagged as having no label readable by
        // screen readers, even though it carries a label — the label is
        // paint-only here (the SwitchRow pattern) and the field itself
        // carries the spoken name, so it is heard exactly once.
        // Internal so the a11y test can pin the spoken name directly.
        label = { Text("Label (optional)", modifier = Modifier.clearAndSetSemantics { }) },
        placeholder = { Text("A name or number, shown on this device only") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Label (optional)" },
    )
    Text(
        "Only so you can tell your own account apart. This stays on the device and is never sent.",
        style = settingsHelperStyle,
    )
    if (onSave != null) {
        TextButton(onClick = onSave) { Text("Save label") }
    }
}

@Composable
private fun PreferenceRowTitle(text: String) {
    // Review fix: in-card row titles are group headers too — 13sp Medium,
    // the same rung as the card titles, below the 16sp control labels.
    Text(text, style = settingsGroupTitleStyle, color = MaterialTheme.colorScheme.onSurface)
}

/**
 * The audio level as a bare percent ("82%") — the same number [AudioBoost.labelFor]
 * prints after "Volume ", so the two can never disagree. A non-finite level
 * collapses to the baseline through [AudioBoost.clampLevel], never NaN%.
 */
internal fun audioLevelPercent(level: Float): String =
    "${(AudioBoost.clampLevel(level) * 100f).roundToInt()}%"

@Composable
internal fun SwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    val haptics = LocalHapticFeedback.current
    Row(
        // Robo run 4: the row-level merged name ("<label>, on/off") never
        // reached Robo — the Switch's own toggleable node is what a screen
        // reader hears, and it only announced its bare state ("On"). So the
        // Switch itself carries the label as its spoken name, and its
        // toggleable state is what announces on/off. The painted label is
        // paint-only, so the words are heard once, on the control itself.
        // Internal so the a11y test can pin the spoken name directly.
        //
        // Review fix: the row used to paint its own tinted inner box
        // (an 8% primary rounded fill when on) — a nested container
        // inside the card. Rows sit flat on the card surface now; the
        // Switch itself carries the state. The label is a control label:
        // 16sp Medium, white.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f).clearAndSetSemantics { },
        )
        Switch(
            checked = checked,
            onCheckedChange = { next ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(next)
            },
            // Mockup: a plain switch — no check mark in the thumb. The state
            // is still the track colour + the thumb position, and the Switch
            // itself still carries the label as its spoken name.
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * The Account card's doors to the Dashboard, Profile and Coins screens.
 * Free launch: the whole row hides while the quality gate is OFF — the coins
 * and VIP machinery those screens open is hidden while every viewer is
 * premium — so the gate decision lives here, where the paywall tests can pin
 * it without rendering the whole Settings screen.
 */
@Composable
internal fun AccountNavRow(
    paywallVisible: Boolean,
    onOpenDashboard: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCoins: () -> Unit,
) {
    if (!paywallVisible) return
    // Session 123: the account screens live here — the dashboard that
    // answers "where do I stand?", the profile, and the coins.
    Row {
        TextButton(onClick = onOpenDashboard) {
            Text("Dashboard")
        }
        TextButton(onClick = onOpenProfile) {
            Text("Profile")
        }
        TextButton(onClick = onOpenCoins) {
            Text("Coins")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    // Audit #46: a plain Row clipped the longest chip ("Battery saver") on a
    // narrow phone — and a chip label must never break mid-word. A FlowRow
    // wraps the group to a second line instead, each chip sizing to its
    // own words (the contact categories below already wrap for the same
    // reason).
    //
    // Review fix, two parts. (1) Visible height: the audit read the chips
    // as ~32dp tall with a 48dp touch target (Audit #54) — that reading
    // was wrong. M3 applies the caller's modifier to the chip's Surface
    // (SelectableChip), so the pre-existing heightIn(min = 48.dp) already
    // stretched the pill itself; the new golden measures the pill at
    // ~48dp (57px at 1.173 px/dp on PIXEL_5). The label keeps its 12dp
    // vertical padding as a floor: the content is 44dp (20sp text +
    // 24dp) inside the 48dp pill, so the pill stays tall even if the
    // heightIn is ever dropped. (2) Selected state: verified against
    // was a borderless dark-green fill (primaryContainer) while the
    // unselected chips carried bright 1dp outlines, so the unselected
    // state outweighed the selected one. Selected now gets a crisp
    // 1.5dp jade border + 16% jade tint fill with a white label;
    // unselected softens to #333333 / #B3B3B3. This override is
    // Settings-only: the shared token (and its pinned preview) is
    // untouched, so no other screen's chips move.
    // Internal so the review test can pin the height and the selection.
    FlowRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { idx, label ->
            FilterChip(
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = RaddChipLabelSoft,
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    selectedLabelColor = Color.White,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = idx == selectedIndex,
                    borderColor = RaddChipBorderSoft,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.5.dp,
                ),
                selected = idx == selectedIndex,
                onClick = { onSelect(idx) },
                label = { Text(label, maxLines = 1, modifier = Modifier.padding(vertical = 12.dp)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
/**
 * Renders the socket's real state as one short line. Reports what is actually bound, not what was
 * asked: a share that could not bind says so plainly, and a live share names the concrete address and
 * the number of files currently servable so the row never advertises a URL that nothing answers on.
 */
private fun lanShareStatusLine(status: LanShareStatus): String = when (status) {
    LanShareStatus.Off -> "Off — nothing is shared on your network."
    LanShareStatus.Failed -> "On, but it could not start (port busy). No files are being shared."
    is LanShareStatus.Listening -> {
        val where = status.address?.let { "$it:${status.port}" } ?: "port ${status.port}"
        "Listening on $where · ${status.servableCount} kept ${if (status.servableCount == 1) "file" else "files"}"
    }
}

/**
 * The phone + PIN form. Three fields, four sentences, one button.
 *
 * The name field is not decoration: `accounts.link_profile_with_pin` refuses an empty display name
 * (`_check_profile_name`), so a form that collected only a number and a PIN would fail on a route
 * this app cannot change. Every warning below is the truth about the server in use — the transport
 * it speaks, a number that is not a secret, a day that is granted once — because the alternative
 * to saying it here is a viewer discovering it by being harvested.
 */
@Composable
internal fun ProfileLinkDialog(account: AccountUiState, viewModel: SettingsViewModel, serverUrl: String) {
    AlertDialog(
        onDismissRequest = viewModel::closeLinkForm,
        // D-2: one dialog title style everywhere — display family,
        // semibold, 20sp.
        title = { RaddDialogTitle(AccountCardCopy.LINK_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = account.linkName,
                    onValueChange = viewModel::onLinkNameChanged,
                    label = { Text(AccountCardCopy.LINK_NAME_LABEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = account.linkPhone,
                    onValueChange = viewModel::onLinkPhoneChanged,
                    label = { Text(AccountCardCopy.LINK_PHONE_LABEL) },
                    placeholder = { Text("03XXXXXXXXX") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = account.linkPin,
                    onValueChange = viewModel::onLinkPinChanged,
                    label = { Text(AccountCardCopy.LINK_PIN_LABEL) },
                    singleLine = true,
                    // Masked so the four digits are not readable over a shoulder on the way in. It
                    // says nothing about how they travel, which the sentence below is for.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        autoCorrect = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    AccountCardCopy.linkTransportWarning(serverUrl),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    AccountCardCopy.LINK_ONCE_PER_ACCOUNT,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    AccountCardCopy.LINK_WHICH_SERVER,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                account.linkMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (account.linkMessageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = viewModel::linkProfile,
                enabled = !account.isLinking &&
                    account.linkName.isNotBlank() &&
                    account.linkPhone.isNotBlank() &&
                    account.linkPin.isNotEmpty(),
            ) {
                BusyLabel(account.isLinking, "Send")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::closeLinkForm, enabled = !account.isLinking) {
                Text("Cancel")
            }
        },
    )
}

/**
 * The contact dialog, restyled to the Stitch mockup (2026-11-15):
 * centred, mail icon, "Contact us", the helper line, a jade pill with
 * the app's contact address + copy, dark "Close" + jade "Write email".
 *
 * The address is the disclaimer's email — the same address the
 * disclaimer names for takedowns, served by the panel (default
 * radddao1@gmail.com) — never hard-coded, and never the
 * support@raddstream.com the mockup invented. A blank panel email
 * falls back to the shipped default, the same fallback `effective()`
 * itself uses. "Write email" opens the mail app with that address,
 * the same mailto wiring the disclaimer dialog uses.
 *
 * This REPLACES the in-app contact form (five categories, subject,
 * body, POST /api/v1/contact): contact now goes by email to the same
 * address the disclaimer already names. The form state
 * (ContactUiState, submitContact) and the endpoint stay in the
 * ViewModel/API, unused — so the form can come back if the owner
 * wants it, and this slice deletes no machinery.
 */
@Composable
internal fun ContactDialog(
    email: String,
    onDismiss: () -> Unit,
) {
    // A blank panel email is never shown as an empty pill: the shipped
    // default is the app's real contact address.
    val shownEmail = email.ifBlank { DEFAULT_DISCLAIMER_EMAIL }
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Mail icon in a jade-tinted circle, as the mockup draws it.
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Text(
                    "Contact us",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Questions, takedown requests or feedback — write to us and we will reply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // The app's contact address, in a jade pill. The whole pill
                // copies it — the copy icon is the affordance, the row is the
                // target. The row carries the action's name; the painted
                // address stays readable, so each is heard once.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable(
                            role = Role.Button,
                        ) { clipboard.setText(AnnotatedString(shownEmail)) }
                        .semantics { contentDescription = "Copy email address" }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "@ $shownEmail",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Close")
                    }
                    Button(
                        onClick = {
                            runCatching { uriHandler.openUri("mailto:$shownEmail") }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Write email")
                    }
                }
            }
        }
    }
}
