package com.raddstream.app.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.raddstream.app.BuildConfig
import com.raddstream.app.R
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.RegionChoices
import com.raddstream.app.data.RemoteFeatures
import com.raddstream.app.data.downloads.DOWNLOADS_FOR_YOU_CAP_OPTIONS_GB
import com.raddstream.app.network.models.DEFAULT_DISCLAIMER_EMAIL
import com.raddstream.app.player.AudioBoost
import com.raddstream.app.player.AudioBoostUi
import com.raddstream.app.share.LanShareStatus
import com.raddstream.app.ui.components.PinPadDialog
import com.raddstream.app.ui.components.RaddDialogTitle
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.theme.RaddChipBorderSoft
import com.raddstream.app.ui.theme.RaddChipLabelSoft
import com.raddstream.app.ui.theme.RaddSettingsHelper
import com.raddstream.app.ui.theme.RaddTextFamily
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.viewmodel.AccountCardCopy
import com.raddstream.app.viewmodel.AccountUiState
import com.raddstream.app.viewmodel.ImageQuality
import com.raddstream.app.viewmodel.KeepTtl
import com.raddstream.app.viewmodel.KidsLockViewModel
import com.raddstream.app.viewmodel.KidsPinOutcome
import com.raddstream.app.viewmodel.MembershipCardCopy
import com.raddstream.app.viewmodel.SettingsUiState
import com.raddstream.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** The subtitle chips, in the order the row shows them. Readable by the region test, which
 * pins that a card can only ever seed a word this row can actually display.
 * F23 (Batch B): Kannada joins the list, matching the audio list — the subtitle row had
 * every other South Indian language but this one. */
internal val subtitleOptions = listOf("Off", "English", "Hindi", "Urdu", "Tamil", "Telugu", "Kannada")

/**
 * Robo run 5: the M3 default disabled button text (onSurface at 38%) reads
 * 2.98:1 on the dark fill — under the 4.5:1 floor. The two buttons that can
 * sit disabled on this screen ("Save" with no catalog typed,
 * "Sign in" with no code typed) brighten their disabled text to onSurface at
 * this alpha, which clears 4.5:1 against the disabled fill (≈5.7:1, pinned
 * by DisabledButtonContrastTest) while the washed-out fill still reads as
 * disabled. Internal so that test can read the same value the buttons use.
 */
internal const val DisabledButtonContentAlpha = 0.65f

/**
 * Settings review (Gemini 5.5/10, 2026-11-16): the screen's text roles.
 * Round 2 (ST4) splits the old single 12sp Medium rung in two: group
 * titles — the section headings — are 16sp SemiBold (M3 titleMedium
 * 16/24); sub-section and in-card row titles — card titles,
 * [PreferenceRowTitle] — are 14sp Medium (M3 titleSmall 14/20).
 * Helpers stay 12sp Regular in #A3ABA8 at 16sp line-height (the M3
 * bodySmall/labelSmall 12/16 pair). All three go through brandSp so
 * typography.scale still reaches them. A4-F7: 13sp/18sp → 12sp/16sp —
 * 13 and 18 are off the M3 scale (Type.kt).
 * Internal so the round-2 test can pin the scale directly.
 */
internal val settingsGroupTitleStyle = TextStyle(
    fontFamily = RaddTextFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = brandSp(16),
    lineHeight = brandSp(24),
)

/** In-card row titles and card titles — 14sp Medium, M3 titleSmall 14/20. */
internal val settingsRowTitleStyle = TextStyle(
    fontFamily = RaddTextFamily,
    fontWeight = FontWeight.Medium,
    fontSize = brandSp(14),
    lineHeight = brandSp(20),
)

internal val settingsHelperStyle = TextStyle(
    fontFamily = RaddTextFamily,
    fontWeight = FontWeight.Normal,
    fontSize = brandSp(12),
    lineHeight = brandSp(16),
    color = RaddSettingsHelper,
)

/**
 * ST2 (round 2): the entrance stagger between groups — each group fades +
 * slides in [SettingsGroupStaggerMs] after the previous one. Named, not an
 * inline literal, so the round-3 test can pin the value directly.
 * Internal for the same reason as the type styles above.
 */
internal const val SettingsGroupStaggerMs = 70L

/**
 * ST5: the search match — a card matches when the query is blank (everything
 * shows) or when every word of the query appears in the card's title or
 * keywords, case-insensitively. One function so the group headers and the
 * cards read the same answer, and the no-results state trusts it too.
 */
internal fun settingsQueryMatches(query: String, title: String, vararg keywords: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val haystack = (listOf(title) + keywords).joinToString(" ").lowercase()
    return q.split(" ").all { it.isNotEmpty() && it in haystack }
}

/**
 * Settings round 2 (2027): every action the settings rows take, as one
 * holder — ~50 callbacks is unreadable as individual parameters, and a
 * holder lets [SettingsContent] render from plain state with no ViewModel.
 * Every field defaults to a no-op so a test can build only the actions it
 * drives. The wrapper binds each field to the same ViewModel method the
 * screen called inline — the split changes no behaviour.
 */
internal data class SettingsActions(
    val onSetImageQuality: (ImageQuality) -> Unit = {},
    val onSetDownloadOnMobileData: (Boolean) -> Unit = {},
    val onSetDataSaver: (Boolean) -> Unit = {},
    val onSetKeepTtl: (KeepTtl) -> Unit = {},
    val onSetAudioBoost: (Float) -> Unit = {},
    val onSetDynamicColor: (Boolean) -> Unit = {},
    val onSetAutoplayNext: (Boolean) -> Unit = {},
    val onSetAutoplayPreviews: (Boolean) -> Unit = {},
    val onSetAutoPip: (Boolean) -> Unit = {},
    val onSetDefaultQuality: (String) -> Unit = {},
    val onSetSubtitleSize: (Int) -> Unit = {},
    val onSetWifiOnlyDownloads: (Boolean) -> Unit = {},
    val onSetAutoDeleteWatched: (Boolean) -> Unit = {},
    val onSetSmartDownloadsEnabled: (Boolean) -> Unit = {},
    val onSetDownloadsForYouCapGb: (Int) -> Unit = {},
    val onSetLanShareEnabled: (Boolean) -> Unit = {},
    val onSetAnalyticsConsent: (Boolean) -> Unit = {},
    val onOpenContactForm: () -> Unit = {},
    val onCloseContactForm: () -> Unit = {},
    val onOpenDisclaimer: () -> Unit = {},
    val onCloseDisclaimer: () -> Unit = {},
    val onRevealServerField: () -> Unit = {},
    val onHideServerField: () -> Unit = {},
    val onServerUrlChanged: (String) -> Unit = {},
    val onTestAndSaveServer: () -> Unit = {},
    val onOpenLinkForm: () -> Unit = {},
    val onCloseLinkForm: () -> Unit = {},
    val onQuickSignIn: () -> Unit = {},
    val onCreateAccount: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onQuickInputChanged: (String) -> Unit = {},
    val onAccountLabelChanged: (String) -> Unit = {},
    val onSaveAccountLabel: () -> Unit = {},
    val onLinkNameChanged: (String) -> Unit = {},
    val onLinkPhoneChanged: (String) -> Unit = {},
    val onLinkPinChanged: (String) -> Unit = {},
    val onLinkProfile: () -> Unit = {},
    val onHideRevealedKey: () -> Unit = {},
    val onClearStatus: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
)

/**
 * Settings round 2 (2027): the screen is split — [SettingsScreen] binds the ViewModels and
 * [SettingsContent] renders from plain state, so Paparazzi can shoot the whole screen from
 * a fake [SettingsUiState] and Robolectric can drive the search / error branches without
 * Hilt. The split changes no behaviour: every callback the content takes is the same
 * ViewModel method the screen called inline.
 */
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
    /** Session 123: the account screens — dashboard, profile. */
    onOpenDashboard: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onOpenLanguages: () -> Unit = {},
    onOpenWatchHistory: () -> Unit = {},
    /** Debug-only harness entry; the NavGraph only navigates here in DEBUG. */
    onOpenResolveTest: () -> Unit = {},
    /** Debug-only harness entry for the press test; same DEBUG-only reachability. */
    onOpenPressTest: () -> Unit = {},
    /** Debug-only diagnostics screen entry (APP-13); same DEBUG-only reachability. */
    onOpenDiagnostics: () -> Unit = {},
    /** True only when the diagnostics implementation is enabled (review B-2). */
    showDiagnostics: Boolean = false,
    /**
     * Settings review (Nov 2026, D2): Help's Contact row lands here with
     * the contact card already in view — the viewer asked for the contact
     * card, not for the top of Settings. False for every other entry.
     */
    scrollToContact: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
    /** MX #7: the PIN + mode controller. The card below is its screen. */
    kidsLockViewModel: KidsLockViewModel = hiltViewModel(),
    /**
     * P0-1: Settings is a push destination from the Profile hub now, not
     * a tab. The hub passes a back action; null keeps the old tab look
     * (previews, tests that render the screen standalone).
     */
    onBackClick: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    // ST5: the search query lives in the wrapper so tests can drive SettingsContent
    // with a fixed query; the content itself never owns it.
    var searchQuery by remember { mutableStateOf("") }

    // D2: the account code is shown once — leaving Settings hides it, so
    // a code is never left on screen after the viewer walks away. The
    // effect runs on dispose, which is also what retires a trial-minted
    // code's one-shot (see hideRevealedKey).
    DisposableEffect(Unit) {
        onDispose { viewModel.hideRevealedKey() }
    }
    SettingsContent(
        state = state,
        features = features,
        actions = SettingsActions(
            onSetImageQuality = viewModel::setImageQuality,
            onSetDownloadOnMobileData = viewModel::setDownloadOnMobileData,
            onSetDataSaver = viewModel::setDataSaver,
            onSetKeepTtl = viewModel::setKeepTtl,
            onSetAudioBoost = viewModel::setAudioBoost,
            onSetDynamicColor = viewModel::setDynamicColor,
            onSetAutoplayNext = viewModel::setAutoplayNext,
            onSetAutoplayPreviews = viewModel::setAutoplayPreviews,
            onSetAutoPip = viewModel::setAutoPip,
            onSetDefaultQuality = viewModel::setDefaultQuality,
            onSetSubtitleSize = viewModel::setSubtitleSize,
            onSetWifiOnlyDownloads = viewModel::setWifiOnlyDownloads,
            onSetAutoDeleteWatched = viewModel::setAutoDeleteWatched,
            onSetSmartDownloadsEnabled = viewModel::setSmartDownloadsEnabled,
            onSetDownloadsForYouCapGb = viewModel::setDownloadsForYouCapGb,
            onSetLanShareEnabled = viewModel::setLanShareEnabled,
            onSetAnalyticsConsent = viewModel::setAnalyticsConsent,
            onOpenContactForm = viewModel::openContactForm,
            onCloseContactForm = viewModel::closeContactForm,
            onOpenDisclaimer = viewModel::openDisclaimer,
            onCloseDisclaimer = viewModel::closeDisclaimer,
            onRevealServerField = viewModel::revealServerField,
            onHideServerField = viewModel::hideServerField,
            onServerUrlChanged = viewModel::onServerUrlChanged,
            onTestAndSaveServer = viewModel::testAndSaveServer,
            onOpenLinkForm = viewModel::openLinkForm,
            onCloseLinkForm = viewModel::closeLinkForm,
            onQuickSignIn = viewModel::quickSignIn,
            onCreateAccount = viewModel::createAccount,
            onSignOut = viewModel::signOut,
            onQuickInputChanged = viewModel::onQuickInputChanged,
            onAccountLabelChanged = viewModel::onAccountLabelChanged,
            onSaveAccountLabel = viewModel::saveAccountLabel,
            onLinkNameChanged = viewModel::onLinkNameChanged,
            onLinkPhoneChanged = viewModel::onLinkPhoneChanged,
            onLinkPinChanged = viewModel::onLinkPinChanged,
            onLinkProfile = viewModel::linkProfile,
            onHideRevealedKey = viewModel::hideRevealedKey,
            onClearStatus = viewModel::clearStatus,
            onRetryLoad = viewModel::retryLoadPrefs,
        ),
        searchQuery = searchQuery,
        onSearchQueryChange = { searchQuery = it },
        kidsLockCard = { KidsLockCard(viewModel = kidsLockViewModel) },
        profileLinkDialog = {
            ProfileLinkDialog(account = state.account, viewModel = viewModel, serverUrl = state.serverUrl)
        },
        onOpenDownloads = onOpenDownloads,
        onOpenVipUpgrade = onOpenVipUpgrade,
        onOpenRegionChoice = onOpenRegionChoice,
        onOpenDashboard = onOpenDashboard,
        onOpenProfile = onOpenProfile,
        onOpenHelp = onOpenHelp,
        onOpenLanguages = onOpenLanguages,
        onOpenWatchHistory = onOpenWatchHistory,
        onOpenResolveTest = onOpenResolveTest,
        onOpenPressTest = onOpenPressTest,
        onOpenDiagnostics = onOpenDiagnostics,
        showDiagnostics = showDiagnostics,
        scrollToContact = scrollToContact,
        onBackClick = onBackClick,
    )
}

/**
 * The screen itself, from plain state — no ViewModel, no Hilt. Every action the rows
 * take arrives as [SettingsActions]; the Kids Lock card and the profile-link dialog
 * arrive as slots (both need a real ViewModel in the app, and a fake one in tests).
 * The search query is owned by the caller so tests can fix it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    features: RemoteFeatures,
    actions: SettingsActions,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    kidsLockCard: @Composable () -> Unit,
    profileLinkDialog: @Composable () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenVipUpgrade: () -> Unit,
    onOpenRegionChoice: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenLanguages: () -> Unit,
    onOpenWatchHistory: () -> Unit,
    onOpenResolveTest: () -> Unit,
    onOpenPressTest: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    showDiagnostics: Boolean,
    scrollToContact: Boolean,
    /** P0-1: the back action when pushed (Profile hub); null renders no back arrow. */
    onBackClick: (() -> Unit)? = null,
) {
    // Audit #51: the server switcher lives behind Advanced — closed until
    // the viewer asks for it, so a casual tap can never disconnect the app.
    var showAdvanced by remember { mutableStateOf(false) }
    // UI-fix P1-T1: the Account card's key machinery lives behind these
    // two doors — guests see Sign in / Create account only, signed-in
    // viewers see Sign out, and the rest waits behind a tap.
    var showSignInForm by remember { mutableStateOf(false) }
    var showAccountDetails by remember { mutableStateOf(false) }
    // B5: the About card's build details (build number, theme name) sit
    // behind a tap on the version line, never in the painted line.
    var showBuildDetails by remember { mutableStateOf(false) }
    // D2: the contact card's and the column's top edges in window
    // coordinates, in pixels — recorded on layout so the deep link can
    // scroll to the card. The offset is derived from the two window
    // bounds (boundsInWindow, the same call the player uses for its own
    // bounds), not from a position-in-parent helper.
    var contactCardTopInWindow by remember { mutableStateOf<Float?>(null) }
    var columnTopInWindow by remember { mutableStateOf<Float?>(null) }
    var deepLinkScrolled by remember { mutableStateOf(false) }
    // The column's own scroll state, hoisted so the deep-link effect above
    // the Scaffold can drive it.
    val scrollState = rememberScrollState()

    // A status line this screen used to print inside the About card as a button that did
    // nothing. It is a transient answer to an action, so it lives where every other screen
    // in this app puts one: a snack bar, shown once, then cleared back through the ViewModel
    // so rotating the device does not re-announce a tap from ten minutes ago.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.statusMessage) {
        val message = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        actions.onClearStatus()
    }
    // D2: the deep link from Help's Contact row. The bounds are known
    // only after layout, so the scroll waits for them — and it runs once
    // per entry, because the screen is recreated on each navigation.
    LaunchedEffect(scrollToContact, contactCardTopInWindow, columnTopInWindow) {
        if (!scrollToContact || deepLinkScrolled) return@LaunchedEffect
        val cardTop = contactCardTopInWindow ?: return@LaunchedEffect
        val columnTop = columnTopInWindow ?: return@LaunchedEffect
        // The card's offset inside the column's content is its window
        // offset from the column's top plus however far the column has
        // already scrolled; scrolling there puts the card at the top of
        // the viewport. The second layout pass reports the card already
        // at the top, which recomputes the same target — the once-flag
        // is what stops the effect from re-running on its own echo.
        val target = (cardTop - columnTop + scrollState.value).roundToInt().coerceAtLeast(0)
        scrollState.scrollTo(target)
        deepLinkScrolled = true
    }
    // ST2: the entrance stagger reads the system reduce-motion setting once —
    // under reduced motion the groups appear with no movement at all.
    val reduceMotion = rememberReduceMotion()
    // Round 6: the theme's motionScheme — the expressive spatial spec for
    // section expand/collapse and the effects spec for the error entrance.
    // Every AnimatedVisibility below reads these tokens, never a local spring.
    val motionScheme = MaterialTheme.motionScheme
    // ST5: the search field clears focus on the Search IME action.
    val focusManager = LocalFocusManager.current
    // ST5: which cards the query keeps — one val per card, so the group headers and
    // the cards read the same answer and a group hides when every card in it does.
    // The debug card is DEBUG-only and never filtered.
    val showContentPreference = settingsQueryMatches(searchQuery, "content preference", "region", "watch", "change")
    val showLanguages = settingsQueryMatches(searchQuery, "languages", "audio", "subtitles")
    val showPlaybackDisplay = settingsQueryMatches(searchQuery, "playback display", "image quality", "battery saver", "mobile data", "data saver", "downloads kept", "dynamic colour", "wallpaper")
    // P1-15 (audit B-18): the 0–200% audio boost lives in its own Audio
    // card now, not inside Playback & display — so it gets its own filter
    // flag, and "audio level" leaves the card above.
    val showAudio = settingsQueryMatches(searchQuery, "audio", "audio level", "volume boost", "sound", "loud")
    val showPlayer = settingsQueryMatches(searchQuery, "player", "autoplay", "next episode", "previews", "picture-in-picture", "default quality", "subtitle size")
    val showKidsLock = settingsQueryMatches(searchQuery, "kids lock", "family", "pin", "child", "mode")
    val showWatchHistory = settingsQueryMatches(searchQuery, "watch history", "watched")
    val showOffline = settingsQueryMatches(searchQuery, "offline", "downloads", "wi-fi", "wifi", "delete watched", "smart downloads", "budget", "open downloads")
    val showShare = settingsQueryMatches(searchQuery, "share", "network", "lan", "devices")
    val showVip = settingsQueryMatches(searchQuery, "vip", "membership", "upgrade", "prices")
    val showAccount = settingsQueryMatches(searchQuery, "account", "sign in", "create account", "sign out", "sync", "code", "label", "phone", "pin", "dashboard", "profile", "guest")
    val showHelp = settingsQueryMatches(searchQuery, "help", "read")
    val showContact = settingsQueryMatches(searchQuery, "contact", "email", "write", "report", "problem", "payment", "takedown")
    val showDisclaimer = settingsQueryMatches(searchQuery, "disclaimer", "read", "legal")
    val showAnalytics = settingsQueryMatches(searchQuery, "anonymous", "usage totals", "analytics", "share", "plays", "crashes")
    val showAbout = settingsQueryMatches(searchQuery, "about", "version", "build", "made for watching")
    val showAdvancedCard = settingsQueryMatches(searchQuery, "advanced", "catalog", "server", "address", "connected", "change", "save")
    val showPlaybackGroup = showContentPreference || showLanguages || showPlaybackDisplay || showAudio || showPlayer || showKidsLock || showWatchHistory
    val showDownloadsGroup = showOffline || showShare
    val showAboutGroup = showHelp || showContact || showDisclaimer || showAnalytics || showAbout || showAdvancedCard
    val showAccountGroup = showVip || showAccount
    val showAppearanceGroup = settingsQueryMatches(searchQuery, "appearance", "theme", "dynamic colour", "wallpaper", "dark", "jade")
    val anyCardVisible = showPlaybackGroup || showDownloadsGroup || showAboutGroup || showAccountGroup || showAppearanceGroup

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
                navigationIcon = {
                    // P0-1: Settings is a push destination from the Profile
                    // hub now, not a tab — a pushed Settings needs a way
                    // back. Null keeps the old tab look for previews and
                    // tests that render the content standalone.
                    onBackClick?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_arrow_left),
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        // ST6: the error overlay lives in this Box, over the dimmed column — the J1
        // pattern: the settings stay visible behind the scrim, never a blank screen,
        // and the scrim eats taps so nothing behind the card can be tapped by mistake.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // Settings review (Nov 2026, D3): the top fade is gone. It was
            // drawn on the scrolling column itself, so it scrolled away with
            // the content it was meant to veil — at rest it put a veil over
            // the top of the first heading, and while scrolling it did
            // nothing at all. The column's 24dp top air is what keeps the
            // sections off the app bar now.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 34.dp)
                    .onGloballyPositioned { columnTopInWindow = it.boundsInWindow().top }
                    .alpha(if (state.loadError != null) 0.38f else 1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            // ST5: the search field — a pill with a search icon and a clear
            // button. It filters the ~15 cards below by title + keywords;
            // empty groups hide, and a query that matches nothing gets a
            // no-results state with a Clear search. The placeholder is the
            // TalkBack label — a TextField announces its placeholder as its name.
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search settings") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_search),
                        contentDescription = null,
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_close),
                                contentDescription = "Clear search",
                            )
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsGroup(index = 0, visible = showAccountGroup) {
                SettingsGroupHeader(
                    text = "Account",
                    iconRes = R.drawable.ic_action_user,
                )
                if (showAccount) {
                    // ST1: the group says it first — no account is needed here.
                    Text(
                        if (state.account.signedIn) {
                            "Signed in — your saved list and history also sync to your account."
                        } else {
                            "No account needed — everything works without one."
                        },
                        style = settingsHelperStyle,
                    )
                }
                if (showVip) {
                    // ── VIP membership ─────────────────────────────────────────
                    // Free launch: the whole card (prices, payment instructions,
                    // upgrade button) hides while the quality gate is OFF — every
                    // viewer is premium then, and there is nothing to sell.
                    if (state.paywallVisible) {
                        SettingsCard(title = "VIP membership") {
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
                        Button(onClick = onOpenVipUpgrade, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(if (state.planIsVip) "View membership" else "Upgrade to VIP")
                        }
                        }
                    }
                }
                if (showAccount) {
                    // ── The optional sync account ──────────────────────────────
                    SettingsCard(title = "Account") {
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

                        // Free launch: the Dashboard / Profile doors hide while the
                        // quality gate is OFF — the account screens they open are
                        // hidden while every viewer is premium. The VIP Membership
                        // card above is gated on the same flag. (The Coins door
                        // lived here until coins were removed 2026-11-17.)
                        AccountNavRow(
                            paywallVisible = state.paywallVisible,
                            onOpenDashboard = onOpenDashboard,
                            onOpenProfile = onOpenProfile,
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
                                label = { Text("Your account code") },
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    val clipboard = LocalClipboardManager.current
                                    // D2: copying is the end of the reveal — the
                                    // code hides the moment it is copied, so it is
                                    // never left on screen after the viewer has it.
                                    TextButton(onClick = {
                                        clipboard.setText(AnnotatedString(key))
                                        actions.onHideRevealedKey()
                                    },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Copy")
                                    }
                                },
                            )
                            // Honest, and it must stay honest: the warning names the transport the
                            // catalog in use actually speaks — plain HTTP in the clear, or TLS for
                            // the shipped default — instead of implying a protection the transport
                            // does not provide.
                            Text(
                                SettingsViewModel.revealWarning(state.serverUrl),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(
                                onClick = actions.onHideRevealedKey,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Done showing this code")
                            }
                        }

                        // UI-fix P1-T1: progressive disclosure. A guest sees "Sign in"
                        // and "Sign up" only — the key field, the label and the
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
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),

                                    ) {
                                        Text("Sign in")
                                    }
                                    OutlinedButton(
                                        onClick = actions.onCreateAccount,
                                        enabled = !account.isBusy,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),

                                    ) {
                                        // P2 (Nov 2026): "Create account" wrapped to two
                                        // lines ("Create / account") in this half-width
                                        // button — "Sign up" is the same door
                                        // (onCreateAccount), one line at any font scale.
                                        Text("Sign up")
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = account.quickInput,
                                    onValueChange = actions.onQuickInputChanged,
                                    label = { Text("Account code") },
                                    placeholder = { Text("The 16-character account code from your other device") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                // The sentence this card used to print — "anyone who knows it can derive the
                                // same key" — described a feature that is now deleted, so it is replaced by
                                // the two facts that are true of what is left: a number cannot open an
                                // account, and the code is the whole account.
                                Text(
                                    "Enter the account code this app showed once, when it created the account. A mobile " +
                                        "number is not a code and this app cannot turn one into a code: knowing " +
                                        "someone's number gets them nowhere. An account is only as strong as " +
                                        "its code, so keep the code somewhere safe, and sign out on any device " +
                                        "that is not yours.",
                                    style = settingsHelperStyle,
                                )
                                AccountLabelField(
                                    value = account.labelInput,
                                    onValueChange = actions.onAccountLabelChanged,
                                    onSave = null,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Button(
                                        onClick = actions.onQuickSignIn,
                                        enabled = !account.isBusy && account.quickInput.isNotBlank(),
                                        // Robo run 5: the disabled text on this button read
                                        // 2.98:1 — see DisabledButtonContentAlpha.
                                        colors = ButtonDefaults.buttonColors(
                                            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = DisabledButtonContentAlpha,
                                            ),
                                        ),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),

                                    ) {
                                        BusyLabel(account.isBusy, "Sign in")
                                    }
                                    OutlinedButton(
                                        onClick = actions.onCreateAccount,
                                        enabled = !account.isBusy,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),

                                    ) {
                                        // P2 (Nov 2026): see the guest row above —
                                        // "Sign up" is the same door, one line.
                                        Text("Sign up")
                                    }
                                }
                                TextButton(
                                    onClick = { showSignInForm = false },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Cancel") 
                                }
                            }
                        } else {
                            // A5: no "Sync now" — syncing happens on its own, on
                            // sign-in and when this card opens, so the button was
                            // a tap the viewer should never have had to find.
                            Button(
                                onClick = actions.onSignOut,
                                enabled = !account.isBusy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                            ) {
                                Text("Sign out")
                            }
                            TextButton(onClick = { showAccountDetails = !showAccountDetails },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(if (showAccountDetails) "Hide account details" else "Account details")
                            }
                            // Round 6: the account details expand/collapse
                            // with AnimatedVisibility — fade + vertical expand
                            // on the expressive specs. Under reduced motion
                            // it is instant.
                            AnimatedVisibility(
                                visible = showAccountDetails,
                                enter = fadeIn(
                                    animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                                ) + expandVertically(
                                    animationSpec = if (reduceMotion) snap() else motionScheme.defaultSpatialSpec(),
                                ),
                                exit = fadeOut(
                                    animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                                ) + shrinkVertically(
                                    animationSpec = if (reduceMotion) snap() else motionScheme.defaultSpatialSpec(),
                                ),
                            ) {
                                if (account.revealedKey == null && account.keyHint.isNotEmpty()) {
                                    Text(
                                        "Account code on this device: ${account.keyHint}. The full code was shown once, when it " +
                                            "was created, and is not stored anywhere it can be shown again.",
                                        style = settingsHelperStyle,
                                    )
                                }
                                AccountLabelField(
                                    value = if (account.labelInput.isEmpty()) account.accountLabel else account.labelInput,
                                    onValueChange = actions.onAccountLabelChanged,
                                    onSave = actions.onSaveAccountLabel,
                                )
                                // ── the phone + PIN profile link ─────────────────────────
                                //
                                // Offered only to a device that holds a key: the route writes onto the account the
                                // bearer names, so with no credential there is nothing to link and the honest thing
                                // is to hide the form rather than let it fail.
                                Spacer(Modifier.height(12.dp))
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
                                    onClick = actions.onOpenLinkForm,
                                    enabled = !account.isLinking,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                                ) {
                                    Text(if (account.showLinkForm) "Close" else AccountCardCopy.LINK_BUTTON)
                                }
                                if (account.showLinkForm) {
                                    profileLinkDialog()
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
                    }
                }
            }

            SettingsGroup(index = 1, visible = showPlaybackGroup) {
                SettingsGroupHeader(
                    text = "Playback",
                    iconRes = R.drawable.ic_action_sliders,
                )
                if (showContentPreference) {
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
                    SettingsCard(title = "Content preference") {
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
                        TextButton(
                            onClick = onOpenRegionChoice,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            // Settings review (Nov 2026, D11): a real chevron, not
                            // the "→" text glyph — every other affordance on the
                            // screen is a Material icon.
                            Text("Change what you watch")
                            Icon(
                                painter = painterResource(R.drawable.ic_action_chevron_right),
                                contentDescription = null,
                            )
                        }
                    }
                }
                if (showLanguages) {
                    // ── Languages ──────────────────────────────────────────────
                    // The ONE place that shows the languages in force. The Region
                    // card picks them, the player sheet changes them mid-title, and
                    // the Languages screen changes them here — all three write the
                    // same keys, and this card shows the effective values, so a
                    // viewer never has to guess which one won.
                    // Mockup: the card carries its translate icon at the top-right.
                    SettingsCard(title = "Languages") {
                        // F5a (Batch A): same effective values as the Languages
                        // screen's top line, so the two can never disagree. The
                        // provenance ("your choice" / "<Region> default") is still
                        // computed with the values, but it is not painted: a viewer
                        // reads a language, not where it came from.
                        Text(
                            "Audio: ${state.prefs.audioLanguage} · " +
                                "Subtitles: ${state.prefs.subtitleLang}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onOpenLanguages,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

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
                }
                if (showPlaybackDisplay) {
                    SettingsCard(title = "Playback & display") {
                        Spacer(Modifier.height(12.dp))
                        PreferenceRowTitle("Image quality")
                        ChipRow(ImageQuality.entries.toList().map { it.label }, ImageQuality.entries.indexOf(state.prefs.imageQuality)) { idx ->
                            actions.onSetImageQuality(ImageQuality.entries.toList()[idx])
                        }
                        // UI audit (Nov 2026, Settings #2): the row never said what
                        // "Battery saver" does. It caps posters at 240px
                        // (UserPreferences.posterMaxWidthPx) against 640px High /
                        // 400px Medium — smaller posters, less data and battery.
                        Text(
                            "Battery saver loads smaller posters to save data and battery.",
                            style = settingsHelperStyle,
                        )

                        Spacer(Modifier.height(12.dp))
                        // Review fix: the title above this toggle repeated its own
                        // label ("Poster loading on mobile data" over "Load poster
                        // images over mobile data") — the toggle stands alone now,
                        // with a one-line helper in plain words.
                        SwitchRow(
                            checked = state.prefs.downloadOnMobileData,
                            onCheckedChange = actions.onSetDownloadOnMobileData,
                            label = "Load poster images over mobile data",
                        )
                        // A keep is gated by the Wi-Fi-only switch in the Offline card above,
                        // not by this one — this switch only ever governed the thumbnails.
                        Text(
                            "Only poster thumbnails use mobile data. Downloads follow the Wi-Fi-only switch.",
                            style = settingsHelperStyle,
                        )

                        Spacer(Modifier.height(12.dp))
                        // Review fix: same shape as the poster toggle — the "Data
                        // saver" title repeated the switch label, and the helper
                        // exposed server jargon ("the server is asked for its
                        // data-saving copy"). One line, plain words.
                        SwitchRow(
                            checked = state.prefs.dataSaver,
                            onCheckedChange = actions.onSetDataSaver,
                            label = "Save mobile data while watching",
                        )
                        Text(
                            "On mobile data, titles start at 480p. You can still switch up in the player.",
                            style = settingsHelperStyle,
                        )

                        Spacer(Modifier.height(12.dp))
                        PreferenceRowTitle("How long downloads are kept")
                        ChipRow(KeepTtl.entries.map { it.label }, KeepTtl.entries.indexOf(state.prefs.keepTtl)) { idx ->
                            actions.onSetKeepTtl(KeepTtl.entries.toList()[idx])
                        }
                        // The retention is temp/cache-scoped (verified against
                        // TtlPolicy: the sweep only ever ages AUTO_PREVIEW bytes,
                        // and a file the viewer explicitly kept is never
                        // auto-deleted) — so the label says what it is, plainly.
                        Text(
                            "Only files the app fetched on its own. Downloads you kept are never removed.",
                            style = settingsHelperStyle,
                        )

                    }
                }
                if (showAudio) {
                    // P1-15 (audit B-18): the 0–200% audio boost lives here
                    // now — its own Audio card, not a row inside Playback &
                    // display and not a slider in the player. The player only
                    // reads the stored level; this is the one place that
                    // writes it. Past 100% the gain is software boost, so the
                    // warning shows only while the level is above 100%.
                    SettingsCard(title = "Audio") {
                        Spacer(Modifier.height(12.dp))
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
                                    onValueChange = actions.onSetAudioBoost,
                                    valueRange = 0f..AudioBoostUi.MAX_OFFERED_LEVEL,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                )
                            }
                        }
                        // Still worth saying: the boost rides on the viewer's own
                        // system volume, so the caption states what is applied, not
                        // what was asked — and the applied total is the requested
                        // level, the part above the player's ceiling coming from the
                        // enhancement processor.
                        Text(
                            AudioBoostUi.honestSummary(state.prefs.audioBoost, 1f),
                            style = settingsHelperStyle,
                        )
                        if (state.prefs.audioBoost > 1f) {
                            Text(
                                "Above 100% can distort the sound and may damage your hearing. Turn it down if the sound crackles.",
                                style = settingsHelperStyle,
                            )
                        }

                        Text(
                            "Saved on this device.",
                            style = settingsHelperStyle,
                        )
                    }
                }
                if (showPlayer) {
                    // ── Player defaults ──────────────────────────────────────
                    // The switches the player itself obeys (plan §5 step 10, P36): autoplay-next
                    // gates the auto-advance, autoplay-previews gates the Home hero, default
                    // quality is the rung a new title starts on when Play was Auto, and subtitle
                    // size is the same number the player sheet's slider writes.
                    SettingsCard(title = "Player") {
                        SwitchRow(
                            checked = state.prefs.autoplayNext,
                            onCheckedChange = actions.onSetAutoplayNext,
                            label = "Play the next episode automatically",
                        )
                        Text(
                            "When this is off, an episode ends on the Next Episode button instead of moving on by itself.",
                            style = settingsHelperStyle,
                        )
                        SwitchRow(
                            checked = state.prefs.autoplayPreviews,
                            onCheckedChange = actions.onSetAutoplayPreviews,
                            label = "Auto-play previews on Home",
                        )
                        Text(
                            "When this is off, the Home hero stays on the first poster until you swipe it.",
                            style = settingsHelperStyle,
                        )
                        // P2-5 (B-23): auto-enter picture-in-picture, on by
                        // default for VOD. The system setting still wins at
                        // entry time, and live never auto-enters.
                        SwitchRow(
                            checked = state.prefs.autoPip,
                            onCheckedChange = actions.onSetAutoPip,
                            label = "Auto picture-in-picture",
                        )
                        Text(
                            "When this is on, leaving the player while a title is playing keeps it playing in a small window.",
                            style = settingsHelperStyle,
                        )

                        Spacer(Modifier.height(12.dp))
                        PreferenceRowTitle("Default quality")
                        val qualityBands = listOf("auto", "480p", "720p", "1080p", "2160p")
                        val qualityLabels = listOf("Auto", "480p", "720p", "1080p", "4K")
                        ChipRow(qualityLabels, qualityBands.indexOf(state.prefs.defaultQuality).coerceAtLeast(0)) { idx ->
                            actions.onSetDefaultQuality(qualityBands[idx])
                        }
                        // UI audit (Nov 2026, Settings #3): "Auto lets the player
                        // pick" never said what Auto starts at on Wi-Fi. Player-side
                        // confirmation (PlayerPolicyBridge.configure, Start-low):
                        // Auto arms on the cheapest rung and climbs on measured
                        // throughput — so Auto starts low. A fixed choice keeps its
                        // band exactly.
                        Text(
                            "The quality a new title starts on. Auto starts low and steps up as your connection allows; a fixed choice starts there and stays.",
                            style = settingsHelperStyle,
                        )

                        Spacer(Modifier.height(12.dp))
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
                            // Parent fixup (Nov 2026): the same edge hazard the
                            // Audio slider had — the thumb at 100% sat ~16dp from
                            // the screen edge, inside the back-gesture zone. Same
                            // fix: the track is inset 12dp inside the 16dp card
                            // padding (28dp from the viewport edge) and the touch
                            // area is 48dp tall. The % label keeps its place at the
                            // row's start; only the slider moves.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Slider(
                                    value = state.prefs.subtitleSizePercent.toFloat(),
                                    onValueChange = { actions.onSetSubtitleSize(it.toInt()) },
                                    valueRange = 50f..200f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                )
                            }
                        }
                        Text(
                            "The same size the player sheet offers while watching; it applies from the next playback.",
                            style = settingsHelperStyle,
                        )
                    }

                    // MX #7 — Kids Lock: the parent PIN + mode. Its card owns the
                    // whole flow (setup, enter, exit, change, remove); the pad is
                    // PinPadDialog, the decisions are KidsLockViewModel's.
                }
                if (showKidsLock) {
                    kidsLockCard()
                }
                if (showWatchHistory) {
                    // ── Watch history ────────────────────────────────────────────────
                    // The history is this device's own record; Settings is where a viewer
                    // looks for a list that is not a catalog rail.
                    SettingsCard(title = "Watch history") {
                        Text(
                            "Everything you watched on this phone, newest first — including titles you finished.",
                            style = settingsHelperStyle,
                        )
                        OutlinedButton(
                            onClick = onOpenWatchHistory,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                        ) {
                            Text("Open watch history")
                        }
                    }
                }
            }

            SettingsGroup(index = 2, visible = showDownloadsGroup) {
                SettingsGroupHeader(
                    text = "Downloads",
                    iconRes = R.drawable.ic_action_download,
                )
                if (showOffline) {
                    // ── Downloads entry ────────────────────────────────────────
                    if (features.downloads) {
                        SettingsCard(title = "Offline") {
                            Text(
                                "Manage downloaded titles, view storage use and expiry.",
                                style = settingsHelperStyle,
                            )
                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                checked = state.prefs.wifiOnlyDownloads,
                                onCheckedChange = actions.onSetWifiOnlyDownloads,
                                label = "Download only on Wi-Fi",
                            )
                            Text(
                                "Downloads happen only when this phone is on Wi-Fi. Turn it off to download on mobile data too.",
                                style = settingsHelperStyle,
                            )
                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                checked = state.prefs.autoDeleteWatched,
                                onCheckedChange = actions.onSetAutoDeleteWatched,
                                label = "Delete watched downloads",
                            )
                            Text(
                                "A finished download deletes itself to free space. A finished series episode deletes its own download too — only the episode you just watched, never the rest of the season.",
                                style = settingsHelperStyle,
                            )
                            Spacer(Modifier.height(12.dp))
                            SwitchRow(
                                checked = state.prefs.smartDownloadsEnabled,
                                onCheckedChange = actions.onSetSmartDownloadsEnabled,
                                label = "Smart Downloads",
                            )
                            Text(
                                "When on, the next episode of a series you're watching downloads itself on Wi-Fi, so it's ready offline. Only one episode per series at a time, and only while this phone has free space.",
                                style = settingsHelperStyle,
                            )
                            Spacer(Modifier.height(12.dp))
                            // REA #5 (Netflix, Nov 2026): the Downloads For You
                            // budget — how much space the automatic downloads may
                            // use. The runner counts only episodes still on the
                            // device, and an episode whose size the server never
                            // stated counts as nothing, never as a guess.
                            // UI audit (Nov 2026, Settings #4): the budget label was
                            // a grey 12sp caption while every other ChipRow title
                            // uses PreferenceRowTitle — the chips looked untitled.
                            PreferenceRowTitle("Downloads For You may use")
                            ChipRow(
                                options = DOWNLOADS_FOR_YOU_CAP_OPTIONS_GB.map { "$it GB" },
                                selectedIndex = DOWNLOADS_FOR_YOU_CAP_OPTIONS_GB
                                    .indexOf(state.prefs.downloadsForYouCapGb)
                                    .takeIf { it >= 0 } ?: 1,
                                onSelect = { idx ->
                                    actions.onSetDownloadsForYouCapGb(DOWNLOADS_FOR_YOU_CAP_OPTIONS_GB[idx])
                                },
                            )
                            Text(
                                "When the budget is full, the next episode waits — delete a downloaded episode or raise the limit.",
                                style = settingsHelperStyle,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Open Downloads")
                            }
                        }
                    }
                }
                if (showShare) {
                    // ── Share kept files on the local network ──────────────────
                    // Gone, not disabled, when the operator switched sharing off: the row would otherwise
                    // read "on" while LanShareRunner has already closed the socket it promises to open.
                    if (features.sharing) {
                        // D10: the card carries its icon at the top-right, like
                        // every other card — it had none.
                        SettingsCard(title = "Share on your network") {
                            SwitchRow(
                                checked = state.lanShareEnabled,
                                onCheckedChange = actions.onSetLanShareEnabled,
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
                }
            }

            SettingsGroup(index = 3, visible = showAppearanceGroup) {
                SettingsGroupHeader(
                    text = "Appearance",
                    iconRes = R.drawable.ic_action_sun,
                )
                // Appearance — the one place the app's look is decided.
                SettingsCard(title = "Appearance") {
                    // Wallpaper colour is a brand decision, not a user one: this brand file
                    // locks the palette (features.dynamic_color=false), and a switch that is
                    // overruled by the build it ships in is worse than no switch. It appears
                    // the moment a re-skin permits it.
                    if (Brand.dynamicColorAllowed) {
                        SwitchRow(
                            checked = state.prefs.dynamicColor,
                            onCheckedChange = actions.onSetDynamicColor,
                            label = "Dynamic colour from wallpaper",
                        )
                    } else {
                        // ST1: no switch to show — the brand locks the palette, so the
                        // card says so instead of offering a control that does nothing.
                        Text(
                            "Jade is the app's colour — your wallpaper doesn't change it.",
                            style = settingsHelperStyle,
                        )
                    }
                }
            }

            SettingsGroup(index = 4, visible = showAboutGroup) {
                SettingsGroupHeader(
                    text = "About",
                    iconRes = R.drawable.ic_action_info,
                )
                if (showHelp) {
                    // ── Help ───────────────────────────────────────────────────────
                    // Read help first, write to us second: most questions are answered on
                    // the Help screen, and the Contact form is for a person's own problem.
                    SettingsCard(title = "Help") {
                        Text(
                            // Settings review (Nov 2026, D1): the helper names VIP
                            // only while the gate is ON — during the free launch the
                            // VIP card is hidden everywhere else, so naming it here
                            // would sell a door that is not on this screen.
                            if (state.paywallVisible) "How to watch, download, and use VIP."
                            else "How to watch and download.",
                            style = settingsHelperStyle,
                        )
                        OutlinedButton(
                            onClick = onOpenHelp,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                        ) {
                            Text("Read help")
                        }
                    }
                }
                if (showContact) {
                    // ── Contact us ───────────────────────────────────────────────────
                    // D2: the deep-link target from Help's Contact row — the card
                    // reports its window position so the screen can scroll to it.
                    SettingsCard(
                        title = "Contact us",
                        modifier = Modifier.onGloballyPositioned {
                            contactCardTopInWindow = it.boundsInWindow().top
                        },
                    ) {
                        Text(
                            "Report a problem, ask about a payment, or ask for a title to be taken down. No account needed.",
                            style = settingsHelperStyle,
                        )
                        OutlinedButton(
                            onClick = actions.onOpenContactForm,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

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
                                onDismiss = actions.onCloseContactForm,
                            )
                        }
                    }
                }
                if (showDisclaimer) {
                    // ── Disclaimer ───────────────────────────────────────────────────
                    // The card shows the effective disclaimer: the panel's text when
                    // the operator wrote one, else the shipped default — so there is
                    // always one to read, and "No disclaimer published yet" can no
                    // longer happen.
                    // Settings review (Nov 2026, D10): each of the three Info
                    // cards gets an icon that says what it is — the disclaimer is
                    // a document, the totals are analytics, About keeps the "i".
                    SettingsCard(title = "Disclaimer") {
                        val disclaimer = state.disclaimer.effective()
                        // B8: no version in the painted line — acceptance is
                        // already tracked per version, so the number says nothing
                        // a viewer can act on.
                        Text(
                            "What this app is, and what it is not.",
                            style = settingsHelperStyle,
                        )
                        OutlinedButton(
                            onClick = actions.onOpenDisclaimer,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                        ) {
                            Text("Read the disclaimer")
                        }
                        if (state.showDisclaimer) {
                            DisclaimerDialog(
                                disclaimer = disclaimer,
                                confirmLabel = "Close",
                                onConfirm = actions.onCloseDisclaimer,
                                onDismissRequest = actions.onCloseDisclaimer,
                            )
                        }
                    }
                }
                if (showAnalytics) {
                    // ── Anonymous usage totals (Tracking A) ──────────────────────
                    // Totals only: how many plays, how often playback fails, how
                    // often the app crashes. No name, number, location, title, or
                    // search — and the switch is the same answer the first-run
                    // dialog asked, changeable here at any time.
                    SettingsCard(title = "Anonymous usage totals") {
                        Text(
                            "Share anonymous totals with ${Brand.name}: plays, " +
                                "playback failures, and crashes. Nothing that can " +
                                "identify you or what you watch is ever sent.",
                            style = settingsHelperStyle,
                        )
                        SwitchRow(
                            checked = state.analyticsConsent == true,
                            onCheckedChange = actions.onSetAnalyticsConsent,
                            label = "Share anonymous totals",
                        )
                    }
                }
                if (showAbout) {
                    // ── About / Disclaimer (accurate, no misleading claims) ─────
                    SettingsCard(
                        title = Brand.copy("settings_about_heading", "About ${Brand.name}"),
                    ) {
                        Text(
                            Brand.copy(
                                "settings_about_body",
                                "${Brand.shortName} finds movies and shows from public catalogs and plays them in one place.",
                            ),
                            style = settingsHelperStyle,
                        )
                        // B5: the painted line is the version name only. The build
                        // number and theme name sit behind a tap on the line —
                        // support asks for them, a viewer never does.
                        Text(
                            aboutVersionLine(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = if (showBuildDetails) "Hide build details" else "Show build details",
                            ) { showBuildDetails = !showBuildDetails },
                        )
                        // ST5: the brief's line, under the version — the screen's
                        // sign-off, not a setting.
                        Text(
                            "Made for watching.",
                            style = settingsHelperStyle,
                        )
                        // Round 6: the build details expand/collapse with
                        // AnimatedVisibility — fade + vertical expand on the
                        // expressive specs, so the section grows instead of
                        // appearing. Under reduced motion it is instant.
                        AnimatedVisibility(
                            visible = showBuildDetails,
                            enter = fadeIn(
                                animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                            ) + expandVertically(
                                animationSpec = if (reduceMotion) snap() else motionScheme.defaultSpatialSpec(),
                            ),
                            exit = fadeOut(
                                animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                            ) + shrinkVertically(
                                animationSpec = if (reduceMotion) snap() else motionScheme.defaultSpatialSpec(),
                            ),
                        ) {
                            Text(
                                aboutBuildDetailsLine(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (showAdvancedCard) {
                    // ── Advanced ───────────────────────────────────────────────
                    // Audit #51: the catalog switcher lives behind Advanced, so a
                    // casual tap can never disconnect the app from its catalog.
                    TextButton(
                        onClick = { showAdvanced = !showAdvanced },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                    ) {
                        Text(if (showAdvanced) "Hide advanced settings" else "Show advanced settings")
                    }
                    // Round 6: the Advanced section expands/collapses with
                    // AnimatedVisibility — fade + vertical expand on the
                    // expressive specs, so the catalog card grows instead of
                    // appearing. Under reduced motion it is instant.
                    AnimatedVisibility(
                        visible = showAdvanced,
                        enter = fadeIn(
                            animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                        ) + expandVertically(
                            animationSpec = if (reduceMotion) snap() else motionScheme.defaultSpatialSpec(),
                        ),
                        exit = fadeOut(
                            animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                        ) + shrinkVertically(
                            animationSpec = if (reduceMotion) snap() else motionScheme.defaultSpatialSpec(),
                        ),
                    ) {
                                    // ── Your catalog ──────────────────────────────────────
                                    SettingsCard(title = "Your catalog") {
                                        // The address is never printed here: a saved catalog reads as
                                        // "Connected", and the field opens only when the viewer asks
                                        // to change it. The save still tests the address before it
                                        // keeps it, and the test's message never includes the address
                                        // either.
                                        if (!state.showServerField) {
                                            Text(
                                                text = if (state.serverUrl.isNotBlank()) "Connected to your catalog." else "No catalog connected yet.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            OutlinedButton(onClick = actions.onRevealServerField, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                            ) {
                                                Text("Change")
                                            }
                                        } else {
                                            OutlinedTextField(
                                                value = state.serverUrl,
                                                onValueChange = actions.onServerUrlChanged,
                                                label = { Text("Catalog address") },
                                                placeholder = { Text("https://catalog.example.com") },
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
                                                onClick = actions.onTestAndSaveServer,
                                                enabled = !state.isTesting && state.serverUrl.isNotBlank(),
                                                // Robo run 5: the disabled text on this button read
                                                // 2.98:1 — see DisabledButtonContentAlpha.
                                                colors = ButtonDefaults.buttonColors(
                                                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(
                                                        alpha = DisabledButtonContentAlpha,
                                                    ),
                                                ),
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                                            ) {
                                                if (state.isTesting) {
                                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                                } else {
                                                    Icon(painter = painterResource(R.drawable.ic_action_check), contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Save")
                                                }
                                            }
                                            // Settings review (Nov 2026, D6): the
                                            // field is a door that closes — Cancel
                                            // returns to the calm summary line, and
                                            // a successful Save closes it too
                                            // (see hideServerField).
                                            TextButton(
                                                onClick = actions.onHideServerField,
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                                            ) {
                                                Text("Cancel")
                                            }
                                        }

                                        // No counts here: works/releases/links are the operator's numbers,
                                        // not something a viewer needs to see on their own Settings screen.
                                        // The save above still tests the address for its own message.
                                    }

                    }
                }
            }

            // ── Resolve test (debug-only harness, plan §8) ─────────────
            // A manual field-test screen, never a production feature: it
            // mints direct links from the baked sample list and records the
            // measurements. The row exists only in DEBUG builds.
            // ST1 (round 3): the harness sits BELOW the user-facing groups
            // now — debug-only content was the screen's first impression.
            if (BuildConfig.DEBUG) {
                SettingsCard(title = stringResource(R.string.resolve_test_title)) {
                    Text(
                        stringResource(R.string.resolve_test_intro),
                        style = settingsHelperStyle,
                    )
                    OutlinedButton(
                        onClick = onOpenResolveTest,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                    ) {
                        Text(stringResource(R.string.resolve_test_open))
                    }
                    OutlinedButton(
                        onClick = onOpenPressTest,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                    ) {
                        Text(stringResource(R.string.press_test_open))
                    }
                    // APP-13 (review B-2): the button renders only when the
                    // diagnostics implementation is enabled — in release the
                    // bus is the NoOp and this row never appears.
                    if (showDiagnostics) {
                        OutlinedButton(
                            onClick = onOpenDiagnostics,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),

                        ) {
                            Text(stringResource(R.string.diagnostics_open))
                        }
                    }
                }
            }

            // ST5: a query that matches nothing says so, with a way back —
            // a dead end with no exit is worse than no search at all.
            if (searchQuery.isNotBlank() && !anyCardVisible) {
                SettingsCard(title = "No results") {
                    Text(
                        "Nothing in Settings matches \"$searchQuery\".",
                        style = settingsHelperStyle,
                    )
                    TextButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Clear search")
                    }
                }
            }
        }
            // ST6: the load-error overlay — the J1 pattern. The settings stay
            // visible behind the scrim (the column above is dimmed to 0.38),
            // the scrim eats taps, and the card names the failure + offers Retry.
            // The veil is invisible to TalkBack (clearAndSetSemantics) and the
            // card is its SIBLING, not its child — clearAndSetSemantics on a
            // parent hides the whole subtree, card included, so the card would
            // never be announced (and no test could find its text).
            // Round 6: the load-error overlay fades + scales in on the
            // expressive effects spec (the card starts at 0.96x and settles
            // to 1.0x) instead of appearing instantly. Under reduced motion
            // it is instant. In Paparazzi the error is present from the
            // first composition, so no enter animation runs and the golden
            // captures the settled state.
            AnimatedVisibility(
                visible = state.loadError != null,
                enter = fadeIn(
                    animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                ) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                ),
                exit = fadeOut(
                    animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                ) + scaleOut(
                    targetScale = 0.96f,
                    animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
                ),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // The veil only: full-screen scrim, eats taps, hidden
                    // from TalkBack. A sibling of the card, so the card's
                    // semantics survive.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                            .clickable(interactionSource = null, indication = null) { }
                            .clearAndSetSemantics { },
                    ) { }
                    Surface(
                        shape = RoundedCornerShape(RaddTokens.Radius.Card),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_action_cloud_off),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                "Couldn't load your settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                state.loadError ?: "",
                                style = settingsHelperStyle,
                            )
                            Button(
                                onClick = actions.onRetryLoad,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * P1-14 (B-39): one header per group — the M3 list-item leading icon + the
 * 16sp SemiBold group title. The 40dp pastel circle is gone: it read as
 * 2023, and the section's identity lives in the icon + title, once, not
 * on every card. The icon is a plain 24dp glyph in onSurfaceVariant —
 * no tint per group, no background disc.
 */
@Composable
private fun SettingsGroupHeader(
    text: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(text, style = settingsGroupTitleStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * ST1/ST2 (round 2): one group — its staggered entrance + its
 * AnimatedVisibility live here, so the body reads as five group calls.
 * The entrance is Home's rail pattern: in inspection mode the group
 * renders immediately, because Paparazzi never advances its frozen clock
 * — in a golden every group would otherwise stay invisible. On a phone
 * each group fades + slides in [SettingsGroupStaggerMs] after the previous
 * one; under reduced motion there is no stagger and no slide, only an
 * instant fade.
 *
 * P1-14 (B-39): the mega-card is gone. The group is ONE flat surface
 * (surfaceContainer, no border, no radius step) and the cards inside it
 * are transparent rows separated by dividers — the ramp used to read
 * background -> shell (surfaceContainerHigh + 1dp outlineVariant border)
 * -> card (surfaceContainerHighest), three tonal steps with double 16dp
 * insets. The [visible] flag is the search filter, and it is INSTANT
 * now: a plain `if` toggles the group's content, so typing a query
 * shows/hides groups with no spring. The AnimatedVisibility below is
 * the entrance only (keyed on `entered`, which never flips back), so
 * the filter can never replay the entrance slide on keystroke.
 */
@Composable
private fun SettingsGroup(
    index: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inInspectionMode = LocalInspectionMode.current
    val reduceMotion = rememberReduceMotion()
    // Round 6: the entrance runs on the theme's motionScheme — the
    // expressive spatial spring (which overshoots and settles) for the
    // slide and the expressive effects spring for the fade. Same token
    // values RaddMotion carried, now read from the M3 contract itself.
    val motionScheme = MaterialTheme.motionScheme
    var entered by remember { mutableStateOf(inInspectionMode || reduceMotion) }
    LaunchedEffect(Unit) {
        if (inInspectionMode || reduceMotion) return@LaunchedEffect
        delay(index * SettingsGroupStaggerMs)
        entered = true
    }
    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(
            animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
        ) + if (reduceMotion) {
            EnterTransition.None
        } else {
            slideInVertically(
                initialOffsetY = { it / 4 },
                animationSpec = motionScheme.defaultSpatialSpec(),
            )
        },
        exit = fadeOut(
            animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
        ) + if (reduceMotion) {
            ExitTransition.None
        } else {
            slideOutVertically(
                targetOffsetY = { it / 4 },
                animationSpec = motionScheme.defaultSpatialSpec(),
            )
        },
        modifier = modifier,
    ) {
        // P1-14: the search filter is a plain conditional — instant, no
        // enter/exit animation on keystroke.
        if (visible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RaddTokens.Radius.Card))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // P1-14 (B-39): the inner card is flat now — no fill, no radius, no
    // second inset. It is a grouped-list row on the group's single
    // surfaceContainer surface, separated from the row above by a divider
    // (the header, the previous card, or the intro text). The title is
    // the sub-section title (14sp Medium, ST4).
    Column(
        modifier = modifier
            .fillMaxWidth()
            // ST2: the card grows smoothly when a door inside it opens
            // (sign-in form, account details, build details, contact
            // form, server field) — the size change is a spring, not a jump.
            // Round 6: the spring is the theme's motionScheme spatial spec.
            .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
        Column(
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = settingsRowTitleStyle, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            }
            content()
        }
    }
}

/**
 * MX #7 — Kids Lock: one step of the PIN flow the card is in. Setup is
 * two entries (first, confirm); change is three (current, new,
 * confirm); enter, exit and remove are one. The card holds the step +
 * the entries typed so far; the ViewModel decides each one.
 */
private enum class KidsLockStep {
    SETUP_FIRST, SETUP_CONFIRM, ENTER, EXIT, CHANGE_CURRENT, CHANGE_NEW, CHANGE_CONFIRM, REMOVE
}

/**
 * MX #7 — Kids Lock: the card in Settings.
 *
 * Three states, three button sets: no PIN yet → set one up; PIN set,
 * mode off → enter the mode, change the PIN, or remove the lock; mode
 * on → exit the mode or change the PIN. Every transition is PIN-gated
 * through [KidsLockViewModel] — the card never compares a PIN itself,
 * and the pad ([PinPadDialog]) is the only place digits are entered.
 * The no-reset warning is on the card, not buried in a dialog: a
 * forgotten PIN is only reset by clearing the app's data, because this
 * app has no account to prove ownership to.
 */
@Composable
private fun KidsLockCard(viewModel: KidsLockViewModel) {
    val pinSet by viewModel.pinSet.collectAsState(initial = false)
    val modeActive by viewModel.modeActive.collectAsState(initial = false)
    var step by remember { mutableStateOf<KidsLockStep?>(null) }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var currentPin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SettingsCard(title = "Kids Lock") {
        Text(
            when {
                modeActive -> "Kids Mode is ON — only family-friendly titles show, and Settings, Downloads and purchases stay locked behind your PIN."
                pinSet -> "Kids Lock is set up. Kids Mode is off — the full catalog shows."
                else -> "Hand the phone to a child without handing them the catalog: Kids Mode shows only family-friendly titles and locks Settings, Downloads and purchases behind a 4-digit PIN."
            },
            style = settingsHelperStyle,
        )
        Text(
            "If you forget your PIN, the only reset is clearing the app's data — write it down.",
            style = settingsHelperStyle,
        )
        Spacer(Modifier.height(12.dp))
        if (!pinSet) {
            OutlinedButton(
                onClick = { step = KidsLockStep.SETUP_FIRST; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Set up Kids Lock")
            }
        } else if (!modeActive) {
            OutlinedButton(
                onClick = { step = KidsLockStep.ENTER; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Enter Kids Mode")
            }
            OutlinedButton(
                onClick = { step = KidsLockStep.CHANGE_CURRENT; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Change PIN")
            }
            OutlinedButton(
                onClick = { step = KidsLockStep.REMOVE; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Remove Kids Lock")
            }
        } else {
            OutlinedButton(
                onClick = { step = KidsLockStep.EXIT; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Exit Kids Mode")
            }
            OutlinedButton(
                onClick = { step = KidsLockStep.CHANGE_CURRENT; error = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Change PIN")
            }
        }
    }

    val activeStep = step
    if (activeStep != null) {
        val (title, subtitle) = when (activeStep) {
            KidsLockStep.SETUP_FIRST -> "Set up Kids Lock" to
                "Choose a 4-digit PIN. Only you will know it — it opens Kids Mode and turns it off."
            KidsLockStep.SETUP_CONFIRM -> "Confirm your PIN" to
                "Enter the same 4-digit PIN again."
            KidsLockStep.ENTER -> "Enter Kids Mode" to
                "Enter your PIN to switch on Kids Mode."
            KidsLockStep.EXIT -> "Exit Kids Mode" to
                "Enter your PIN to switch Kids Mode off."
            KidsLockStep.CHANGE_CURRENT -> "Change PIN" to
                "Enter your current PIN first."
            KidsLockStep.CHANGE_NEW -> "Change PIN" to
                "Choose a new 4-digit PIN."
            KidsLockStep.CHANGE_CONFIRM -> "Change PIN" to
                "Enter the new PIN again."
            KidsLockStep.REMOVE -> "Remove Kids Lock" to
                "Enter your PIN to remove Kids Lock and its PIN."
        }
        PinPadDialog(
            title = title,
            subtitle = subtitle,
            error = error,
            lockedOutSeconds = (viewModel.lockoutRemainingMs() / 1_000).toInt(),
            onPinComplete = { pin ->
                scope.launch {
                    when (activeStep) {
                        KidsLockStep.SETUP_FIRST -> {
                            firstPin = pin
                            step = KidsLockStep.SETUP_CONFIRM
                            error = null
                        }
                        KidsLockStep.SETUP_CONFIRM -> when (viewModel.setupPin(firstPin ?: "", pin)) {
                            KidsPinOutcome.Success -> {
                                step = null; firstPin = null; error = null
                            }
                            KidsPinOutcome.Mismatch -> {
                                error = "The two PINs did not match — start again."
                                step = KidsLockStep.SETUP_FIRST; firstPin = null
                            }
                            KidsPinOutcome.InvalidFormat -> {
                                error = "Enter four digits."
                                step = KidsLockStep.SETUP_FIRST; firstPin = null
                            }
                            else -> error = "Something went wrong — try again."
                        }
                        KidsLockStep.ENTER -> when (viewModel.enterKidsMode(pin)) {
                            KidsPinOutcome.Success -> { step = null; error = null }
                            KidsPinOutcome.WrongPin -> error = "Wrong PIN — try again."
                            KidsPinOutcome.LockedOut -> error = null
                            else -> error = "Enter four digits."
                        }
                        KidsLockStep.EXIT -> when (viewModel.exitKidsMode(pin)) {
                            KidsPinOutcome.Success -> { step = null; error = null }
                            KidsPinOutcome.WrongPin -> error = "Wrong PIN — try again."
                            KidsPinOutcome.LockedOut -> error = null
                            else -> error = "Enter four digits."
                        }
                        KidsLockStep.CHANGE_CURRENT -> when (viewModel.verifyPin(pin)) {
                            KidsPinOutcome.Success -> {
                                currentPin = pin
                                step = KidsLockStep.CHANGE_NEW
                                error = null
                            }
                            KidsPinOutcome.WrongPin -> error = "Wrong PIN — try again."
                            KidsPinOutcome.LockedOut -> error = null
                            else -> error = "Enter four digits."
                        }
                        KidsLockStep.CHANGE_NEW -> {
                            firstPin = pin
                            step = KidsLockStep.CHANGE_CONFIRM
                            error = null
                        }
                        KidsLockStep.CHANGE_CONFIRM -> when (
                            viewModel.changePin(currentPin ?: "", firstPin ?: "", pin)
                        ) {
                            KidsPinOutcome.Success -> {
                                step = null; firstPin = null; currentPin = null; error = null
                            }
                            KidsPinOutcome.Mismatch -> {
                                error = "The two PINs did not match — start again."
                                step = KidsLockStep.CHANGE_NEW; firstPin = null
                            }
                            KidsPinOutcome.WrongPin -> {
                                error = "Wrong PIN — try again."
                                step = KidsLockStep.CHANGE_CURRENT
                                currentPin = null; firstPin = null
                            }
                            KidsPinOutcome.InvalidFormat -> {
                                error = "Enter four digits."
                                step = KidsLockStep.CHANGE_NEW; firstPin = null
                            }
                            else -> error = "Something went wrong — try again."
                        }
                        KidsLockStep.REMOVE -> when (viewModel.removeKidsLock(pin)) {
                            KidsPinOutcome.Success -> { step = null; error = null }
                            KidsPinOutcome.WrongPin -> error = "Wrong PIN — try again."
                            KidsPinOutcome.LockedOut -> error = null
                            else -> error = "Enter four digits."
                        }
                    }
                }
            },
            onDismiss = {
                step = null; firstPin = null; currentPin = null; error = null
            },
        )
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
        TextButton(
            onClick = onSave,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Save label") 
        }
    }
}

@Composable
private fun PreferenceRowTitle(text: String) {
    // ST4 (round 2): in-card row titles are 14sp Medium — one rung below
    // the 16sp SemiBold group titles, one above the 12sp helpers.
    Text(text, style = settingsRowTitleStyle, color = MaterialTheme.colorScheme.onSurface)
}

/**
 * The audio level as a bare percent ("82%") — the same number [AudioBoost.labelFor]
 * prints after "Volume ", so the two can never disagree. A non-finite level
 * collapses to the baseline through [AudioBoost.clampLevel], never NaN%.
 */
internal fun audioLevelPercent(level: Float): String =
    "${(AudioBoost.clampLevel(level) * 100f).roundToInt()}%"

/**
 * Settings review (Nov 2026, D9): the one version line both About
 * surfaces print — the version name only. The build number and the
 * theme name are not painted: they live behind a tap on the line
 * ([aboutBuildDetailsLine]), so a screenshot from either surface
 * answers the same question, and support still gets its numbers.
 */
internal fun aboutVersionLine(): String =
    "Version ${Brand.versionName}"

/**
 * The build details the painted version line hides: the build number
 * and the theme name, shown only when the viewer taps the version line
 * (Settings) — never in the painted line itself.
 */
internal fun aboutBuildDetailsLine(): String =
    "Version ${Brand.versionName} (${Brand.versionCode}) • ${Brand.schemeName}"

@Composable
internal fun SwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    val haptics = LocalHapticFeedback.current
    // Round 6: the toggle springs — the switch pops with a visible
    // overshoot (expressive spatial spring, damping < 1) when it flips,
    // then settles. The resting scale is 1.12x off, 1.20x on, so the on
    // state reads a touch larger. Under reduced motion there is no
    // spring and the scale stays 1.12x.
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    val switchScale by animateFloatAsState(
        targetValue = if (reduceMotion) 1.12f else if (checked) 1.2f else 1.12f,
        animationSpec = if (reduceMotion) snap() else motionScheme.defaultSpatialSpec(),
        label = "switchSpring",
    )
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
        // 16sp Medium, onSurface.
        //
        // UI audit (Nov 2026, Settings #5): the label was hardcoded
        // onPrimaryContainer — the selected chip's label in the token's own role,
        // if the theme ever goes light. The onSurface role owns it now.
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
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).clearAndSetSemantics { },
        )
        Switch(
            checked = checked,
            onCheckedChange = { next ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(next)
            },
            // ST2 (round 2): the M3 Expressive thumb — a check in the thumb
            // when on, and the whole switch scaled 1.12× ("big soft thumbs").
            // The old mockup kept the thumb plain; the check is the state,
            // readable without the track colour alone.
            thumbContent = if (checked) {
                {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_check),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else null,
            modifier = Modifier
                .semantics { contentDescription = label }
                .scale(switchScale),
        )
    }
}

/**
 * The Account card's doors to the Dashboard and Profile screens.
 * Free launch: the whole row hides while the quality gate is OFF — the
 * account screens those doors open are hidden while every viewer is
 * premium — so the gate decision lives here, where the paywall tests can
 * pin it without rendering the whole Settings screen. (The Coins door
 * lived here until coins were removed 2026-11-17.)
 */
@Composable
internal fun AccountNavRow(
    paywallVisible: Boolean,
    onOpenDashboard: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    if (!paywallVisible) return
    // Session 123: the account screens live here — the dashboard that
    // answers "where do I stand?", and the profile.
    Row {
        TextButton(onClick = onOpenDashboard,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Dashboard")
        }
        TextButton(onClick = onOpenProfile,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Profile")
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
    // unselected softens to #787878 / #B3B3B3. UI audit (Nov 2026,
    // Settings #1) raised the border #333333 → #6E6E6E → #787878: the
    // #333333 border was 1.38:1 vs the card, and #6E6E6E measured 2.94:1
    // on the round-2 primaryContainer fill — both under the M3 3:1 bar.
    // The token is Settings-only in practice (its one use is this border),
    // so raising it moves no other screen's chips.
    // Internal so the review test can pin the height and the selection.
    FlowRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { idx, label ->
            SettingsChip(
                label = label,
                selected = idx == selectedIndex,
                onClick = { onSelect(idx) },
            )
        }
    }
}

/**
 * Round 6: one chip — its press state lives here so each chip owns its
 * own interaction source (a remember inside the FlowRow loop would mix
 * states up when the options change). Pressed, the pill scales to 0.96x
 * on the expressive effects spring and springs back on release; at rest
 * it is exactly 1.0x, so no golden moves. Under reduced motion there is
 * no press scale at all.
 */
@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionScheme = MaterialTheme.motionScheme
    val reduceMotion = rememberReduceMotion()
    val chipScale by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else if (pressed) 0.96f else 1f,
        animationSpec = if (reduceMotion) snap() else motionScheme.defaultEffectsSpec(),
        label = "chipPress",
    )
    FilterChip(
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = RaddChipLabelSoft,
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = RaddChipBorderSoft,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.5.dp,
        ),
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, modifier = Modifier.padding(vertical = 12.dp)) },
        interactionSource = interactionSource,
        modifier = Modifier.heightIn(min = 48.dp).scale(chipScale),
    )
}

@Composable
/**
 * Renders the socket's real state as one short line. Reports what is actually bound, not what was
 * asked: a share that could not bind says so plainly, and a live share says how many kept files
 * another device on the same Wi-Fi can play. No address and no port — a viewer cannot act on
 * either, and printing them teaches nothing but the network's shape.
 */
private fun lanShareStatusLine(status: LanShareStatus): String = when (status) {
    LanShareStatus.Off -> "Off — nothing is shared on your network."
    LanShareStatus.Failed -> "Sharing couldn't start. No files are being shared."
    is LanShareStatus.Listening ->
        "Sharing on — devices on your Wi-Fi can play " +
            "${status.servableCount} kept ${if (status.servableCount == 1) "file" else "files"}."
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
                modifier = Modifier.heightIn(min = 48.dp),
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
            TextButton(onClick = viewModel::closeLinkForm, enabled = !account.isLinking,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
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
 * wants it, and this slice deletes no machinery. (Settings review, Nov
 * 2026, D7: the state is kept deliberately — only the dead language
 * setters went. The form is the return path, not dead weight.)
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
                        painter = painterResource(R.drawable.ic_action_mail),
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
                        "$shownEmail",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_action_copy),
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
