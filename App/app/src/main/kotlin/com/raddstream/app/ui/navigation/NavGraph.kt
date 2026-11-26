package com.raddstream.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raddstream.app.BuildConfig
import com.raddstream.app.R
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.RemoteFeatures
import com.raddstream.app.diagnostics.DiagnosticsBus
import com.raddstream.app.network.models.PanelTheme
import com.raddstream.app.network.models.Post
import com.raddstream.app.player.PlayerSessionStore
import com.raddstream.app.resolve.PressTestScreen
import com.raddstream.app.resolve.ResolveTestScreen
import com.raddstream.app.ui.components.PinPadDialog
import com.raddstream.app.ui.components.rememberReduceMotion
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.viewmodel.KidsLockViewModel
import com.raddstream.app.viewmodel.KidsPinOutcome
import kotlinx.coroutines.launch
import com.raddstream.app.ui.screens.ActorScreen
import com.raddstream.app.ui.screens.BrowseScreen
import com.raddstream.app.ui.screens.DashboardScreen
import com.raddstream.app.ui.screens.DeadLinkScreen
import com.raddstream.app.ui.screens.DetailScreen
import com.raddstream.app.ui.screens.DownloadsScreen
import com.raddstream.app.ui.screens.EmbedWebViewScreen
import com.raddstream.app.ui.screens.HelpScreen
import com.raddstream.app.ui.screens.HomeScreen
import com.raddstream.app.ui.screens.LanguagesScreen
import com.raddstream.app.ui.screens.LiveTvScreen
import com.raddstream.app.ui.screens.LocalFilesScreen
import com.raddstream.app.ui.screens.MyListScreen
import com.raddstream.app.ui.screens.PlayerScreen
import com.raddstream.app.ui.screens.ProfileScreen
import com.raddstream.app.ui.screens.RegionSelectionScreen
import com.raddstream.app.ui.screens.SearchScreen
import com.raddstream.app.ui.screens.SeasonScreen
import com.raddstream.app.ui.screens.SettingsScreen
import com.raddstream.app.ui.screens.SetupScreen
import com.raddstream.app.ui.screens.VipUpgradeScreen
import com.raddstream.app.ui.screens.WatchHistoryScreen
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddTokens

private data class BottomNavItem(
    val label: String,
    /** The destination's route, used to tell whether the tab is selected. */
    val route: String,
    val icon: ImageVector,
    /**
     * H3 (2027 Home fix): the FILLED variant, painted when the tab is
     * selected — icon-fill-on-select, the M3 Expressive pattern (the nav
     * keeps its 64×32 spring pill AND the icon fills; the video
     * adjudication). Defaults to the outline icon for callers that pass
     * only one glyph.
     */
    val selectedIcon: ImageVector = icon,
    /**
     * The address the tab navigates to. Usually the same as [route] — but a
     * destination with an optional argument (Search's `query`) must be
     * navigated to by its plain address, never by the template string:
     * navigating to "search?query={query}" would search for the literal
     * text "{query}".
     */
    val navigateRoute: String = route,
)

/** Test tag on a bottom-nav tab's indicator pill, so the a11y test can measure it directly. */
internal const val BottomNavIndicatorTag = "bottomNavIndicator"

/** Test tag on a bottom-nav tab's label, so the large-font test can measure it directly. */
internal const val BottomNavLabelTag = "bottomNavLabel"

/**
 * P0-3 (audit B-5/C-5): the lateral destinations — a switch between two
 * of these is a tab switch and fades through, never slides. (Search's
 * entry is its route pattern with the optional arguments; the NavHost
 * reports the pattern as the destination's route.)
 */
private val tabRoutes: Set<String> = setOf(
    Screen.Home.route,
    Screen.Search.route,
    Screen.Downloads.route,
    Screen.Profile.route,
)

/** P0-3: the player destinations — fade-through, never slide. */
private val playerRoutes: Set<String> = setOf(
    Screen.Player.route,
    Screen.PlayerLocal.route,
)

/** P0-3: tab-switch fade-through, incoming leg, ms (M3 fade-through is ~90 out / ~210 in). */
private const val TabFadeInMs = 180

/** P0-3: tab-switch fade-through, outgoing leg, ms. */
private const val TabFadeOutMs = 90

/** P0-3: player fade-through, ms, both legs. */
private const val PlayerFadeMs = 200

/**
 * P0-3: push container-transform settle scale — the entering screen grows
 * from 96% while fading in (and shrinks back to it on pop), so the poster
 * shared-element morph carries the motion instead of a slide.
 */
private const val PushSettleScale = 0.96f

/**
 * APP-09 (mobile-tester run 4, 00:26 PKT 24 Nov): the bottom-nav label
 * shrinks to fit its slot instead of truncating — "Settings" read
 * "Settin..." at 1.3x font scale on a small phone, because four tabs share
 * the width and labelMedium at 1.3x needs more than a quarter of it. The
 * label backs off in 15% steps until it fits, never below this floor; the
 * ellipsis stays as the last resort for a slot narrower than the floor can
 * fit. The floor is still a readable 10sp (via brandSp, the only sanctioned
 * way to write an sp size — see BrandDriftTest), and the spoken label (the
 * tab's contentDescription) is never truncated.
 */
private val BottomNavLabelMinFontSize = brandSp(10)

/**
 * The bottom bar's container (density, Nov 2026). M3 1.3.0's
 * NavigationBar floors its Row at minHeight 80dp (NavigationBarHeight,
 * verified against the M3 source), so the 64dp Expressive bar cannot be
 * built on it — a 64dp tab inside it would sit in an 80dp Row and the
 * bar would not shrink at all. This container is the same shape minus
 * the floor: a Surface over a Row that sizes to its content (the 64dp
 * tabs) plus the system nav-bars inset. The inset arrives as a Modifier,
 * not a WindowInsets value: `WindowInsets.navigationBars` as a default
 * value is reported "Unresolved reference" by kotlinc in this module
 * even though the symbol exists in the API reference (and M3 uses it as
 * NavigationBar's own default), so the default modifier is
 * navigationBarsPadding() — the same inset, applied the way the rest of
 * this codebase already does it — and tests inject a fake inset by
 * passing windowInsetsPadding(WindowInsets(...)) instead. The 8dp item
 * spacing is M3's own NavigationBarItemHorizontalPadding. The tabs keep
 * their full 64dp even with a 48dp inset (Robo run 6 lesson: never force
 * the bar to an exact total height). Internal so BottomNavTouchTargetTest
 * builds the real bar instead of a copy of it.
 */
@Composable
internal fun RaddBottomBar(
    modifier: Modifier = Modifier.navigationBarsPadding(),
    content: @Composable RowScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(RaddTokens.Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * Density (Nov 2026): the bar is M3 Expressive's 64dp and the pill is
 * the spec 56×32dp — the pill is visual only, the tab is the touch
 * target at 64dp. (History: Robo runs 4–6 measured 32dp. Run 6 proved
 * the measured node is the tab, not the pill: forcing the bar to exactly
 * 80dp total crushed the tabs to 32dp on a 3-button-nav device, because
 * M3 applies the nav-bars inset inside the bar — see RaddBottomBar.
 * The bar no longer uses NavigationBarItem at all: this tab owns its own
 * clickable — the full 64dp bar height — and its own indicator pill.
 * Never fillMaxHeight() on a tab — that expands the bar to fill the
 * screen (run 4). The label keeps M3's labelMedium, shrinking in 15%
 * steps (APP-09) when it would otherwise ellipsize at large font scales.
 * A Robo re-run on a 3-button-nav device is the verification owed for the 64dp/56×32
 * change.)
 * Internal so BottomNavTouchTargetTest can pin the pill size directly.
 */
@Composable
internal fun RowScope.BottomNavTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    // H3: the filled variant, painted when selected (defaults to the
    // outline icon so existing callers keep compiling).
    selectedIcon: ImageVector = icon,
) {
    val reduceMotion = rememberReduceMotion()
    // Restyle 2026-11-13: the selected icon settles onto its spring (M3
    // Expressive fast spatial) instead of snapping to 1.12x.
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = if (reduceMotion) snap() else RaddMotion.fastSpatial(),
        label = "navIconScale",
    )
    val pillColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = if (reduceMotion) snap() else RaddMotion.defaultEffects(),
        label = "navPillColor",
    )
    Box(
        modifier = Modifier
            .weight(1f)
            // Density (Nov 2026): the 64dp tab height is the app's own
            // token — M3 Expressive's ContainerHeight. The bar sizes to
            // this content plus the system nav-bars inset (RaddBottomBar),
            // so the inset never crushes the tabs (Robo run 6).
            .height(RaddTokens.Touch.NavTabHeight)
            .clickable(onClick = onClick, role = Role.Tab)
            // The tab itself carries the spoken name (the SwitchRow pattern):
            // the painted icon and label are silent, so the name is heard
            // exactly once. No mergeDescendants — the pill stays its own
            // node so the test can measure it.
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RaddTokens.Space.Micro),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RaddTokens.Space.Micro),
        ) {
            Box(
                modifier = Modifier
                    // Density (Nov 2026): the pill is M3 Expressive's
                    // 64×32dp visual (H3: was 56dp) — cap width at 64dp while allowing
                    // 2dp breathing room on narrow 320dp screens.
                    .padding(horizontal = RaddTokens.Space.Micro)
                    .widthIn(max = RaddTokens.Touch.NavIndicatorWidth)
                    .fillMaxWidth()
                    .height(RaddTokens.Touch.NavIndicatorHeight)
                    .background(
                        color = pillColor,
                        // Session 154 (N-1): the indicator pill is fully
                        // round — 24dp is not on the M3 shape scale (20 → 28).
                        shape = RaddTokens.Shape.Pill,
                    )
                    .testTag(BottomNavIndicatorTag),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selected) selectedIcon else icon,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .size(24.dp) // token-exempt: icon size, not spacing
                        .graphicsLayer(scaleX = iconScale, scaleY = iconScale),
                )
            }
            // APP-09: shrink-to-fit. The label starts at labelMedium and
            // backs off in 15% steps while it overflows its slot, so a
            // large font scale shrinks the label instead of truncating it
            // ("Settings" -> "Settin..." at 1.3x on a small phone, run 4).
            // The state is keyed on the font scale as well as the label,
            // so shrinking back down restores the design size instead of
            // keeping a stale shrink. PosterCard's word-boundary trim is
            // the house pattern for onTextLayout fixes: a guarded state
            // change that can only move one way, so it can never loop —
            // here the guard is the floor, and the ellipsis stays as the
            // last resort below it.
            val fontScale = LocalDensity.current.fontScale
            val labelStyle = MaterialTheme.typography.labelMedium
            var labelFontSize by remember(label, fontScale) {
                mutableStateOf(labelStyle.fontSize)
            }
            Text(
                label,
                style = labelStyle,
                fontSize = labelFontSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                // The ellipsis is the signal: the didOverflowWidth guard
                // never fired — the golden from record 45344429076 still
                // read "Settin..." with it in place. isLineEllipsized(0)
                // is true exactly when the single line shows the ellipsis,
                // and it is the house pattern (PosterCard's word-boundary
                // trim, DetailScreen's hasVisualOverflow). The guard is the
                // floor, and the ellipsis stays as the last resort below it.
                onTextLayout = { result ->
                    if (result.lineCount > 0 && result.isLineEllipsized(0) &&
                        labelFontSize > BottomNavLabelMinFontSize
                    ) {
                        // TextUnit has operator compareTo but does not
                        // implement Comparable<TextUnit>, so the generic
                        // coerceAtLeast does not resolve on it — floor
                        // with the comparison instead (CI 45308364603).
                        val shrunk = labelFontSize * 0.85f
                        labelFontSize = if (shrunk < BottomNavLabelMinFontSize) {
                            BottomNavLabelMinFontSize
                        } else {
                            shrunk
                        }
                    }
                },
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                // The tag MUST sit before clearAndSetSemantics: the clear
                // wipes every semantics set after it in the chain, so a tag
                // after it is never on the node at all (LiveTvScreen's
                // liveTv_channelMeta, same trap, CI 44236763707/44238820887).
                // Before the clear, the tag survives and the text stays
                // paint-only, which is what the touch-target test queries.
                modifier = Modifier.testTag(BottomNavLabelTag).clearAndSetSemantics { },
            )
        }
    }
}

/**
 * The whole navigable app.
 *
 * [features] is the operator's board, read once by the activity and handed down: the switches
 * have to be obeyed by the tab bar and by the screens' own entry points together, or a hidden
 * tab beside a still-live "Open Downloads" card is a feature that is half removed. The routes
 * themselves stay registered even for a switched-off feature — a back stack that already holds
 * that screen must not name a destination the graph cannot resolve, and a kept file stays
 * playable no matter what the board says about making new ones.
 *
 * [openRequest] is a video file another app asked us to play, already judged by
 * [LocalVideoOpen]; [onOpenConsumed] reports that it has been put on the back stack. The graph
 * does not decide anything about the file — it routes what the activity handed over, on the same
 * `player_local` route a finished download uses.
 *
 * NAV-5: [deepLinkRequest] is a `radd://` link [DeepLinks.parse] read, routed the same way;
 * [unknownDeepLink] is one it could not read, routed to the dead-link destination instead of
 * silently opening Home.
 */

/**
 * Phase 7 (M1): open a row from any list. A v2 row has no v1 post id — its `id`
 * is the negative work-id sentinel — so it opens by work id; a v1 row opens by
 * post id, exactly as before. The sentinel itself must never be navigated to:
 * the detail screen reads it as "no post".
 *
 * Home fix (Nov 2026): direct play — the rail tile's play button opens
 * Detail with [autoplay], so the tap starts the title instead of landing
 * on the two buttons. Detail fires its primary action once when the layout
 * has loaded; an explicit play tap is the only route that sets it — the
 * hero's Watch Now and the shuffle still land on the two buttons.
 */
private fun NavHostController.navigateToPost(post: Post, autoplay: Boolean = false) {
    val route = if (post.workId != null) {
        Screen.Detail.createRouteForWork(post.workId, autoplay = autoplay)
    } else {
        Screen.Detail.createRoute(post.id, autoplay = autoplay)
    }
    // Session 157 (S4): one tap, one detail. A double-tap on a poster must
    // not stack two identical detail entries — the second entry's ViewModel
    // loads the same title again while the first is still loading, and Back
    // then lands on the same detail instead of the list. Same rule as the
    // player route (Session 114): a different title is a different route,
    // so related titles still open.
    navigate(route) {
        launchSingleTop = true
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RaddStreamNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
    features: RemoteFeatures = RemoteFeatures(),
    /** The operator's theme, for the hero knobs Home reads from it. */
    theme: PanelTheme = PanelTheme(),
    /** The viewer's own autoplay-previews switch (plan §5 step 10): either it or the operator's hero flag being off stops the carousel. */
    autoplayPreviews: Boolean = true,
    openRequest: LocalVideoOpen? = null,
    onOpenConsumed: () -> Unit = {},
    deepLinkRequest: DeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    unknownDeepLink: String? = null,
    onUnknownDeepLinkConsumed: () -> Unit = {},
    /** MX #7 — Kids Lock: when on, the bottom bar shrinks to Home / Search /
     *  Settings, My List and Live TV leave the bar (their rows are
     *  unfiltered), Settings opens only behind the PIN, and the Home banner
     *  offers the PIN-guarded exit. Off, every route below is exactly the
     *  app it was — the flag changes nothing. */
    kidsMode: Boolean = false,
    kidsLockViewModel: KidsLockViewModel = hiltViewModel(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // APP-13: record navigation for the debug-only diagnostics timeline.
    // The record call itself no-ops in release; the listener costs one
    // add/remove per NavGraph lifetime either way.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            DiagnosticsBus.recordNavigation(destination.route)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    // MX #7 — Kids Lock: the session-scoped adult unlock. Settings is the
    // one door that stays reachable in kids mode, and it opens only after
    // this flips true — once per kids session, never persisted, and it
    // resets the moment kids mode turns off, so handing the phone back
    // never hands the Settings open with it.
    var adultUnlocked by remember { mutableStateOf(false) }
    LaunchedEffect(kidsMode) {
        if (!kidsMode) adultUnlocked = false
    }
    // Entering kids mode pops everything above Home: Downloads (now a
    // tab) and Settings cannot linger on the stack for a kid to reach
    // with Back.
    LaunchedEffect(kidsMode) {
        if (kidsMode) navController.popBackStack(Screen.Home.route, false)
    }
    // The exit-PIN dialog, raised by the Home banner. Null means no dialog.
    var exitPinRequested by remember { mutableStateOf(false) }

    // Exactly once per request: the nonce is what makes the same file tapped twice a second
    // request, and clearing it here is what stops a later recomposition from pushing a duplicate
    // copy of the player onto the stack. Single-top for the same reason — a viewer who opens the
    // file that is already playing should stay on it, not stack a second one behind the first.
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        navController.navigate(request.route) { launchSingleTop = true }
        onOpenConsumed()
    }

    // NAV-5: a parsed deep link routes exactly once, the same way. Stream links
    // also match the player route's own navDeepLink, so the NavController may
    // already be there — launchSingleTop makes the second navigate a no-op,
    // not a duplicate player. Detail and Browse have no navDeepLink pattern,
    // so this effect is their only way in.
    LaunchedEffect(deepLinkRequest) {
        val request = deepLinkRequest ?: return@LaunchedEffect
        when (request) {
            is DeepLink.Detail -> navController.navigate(Screen.Detail.createRoute(request.postId)) {
                launchSingleTop = true
            }
            is DeepLink.Stream -> navController.navigate(
                Screen.Player.createRoute(request.streamId, request.title),
            ) {
                launchSingleTop = true
            }
            is DeepLink.Browse -> navController.navigate(Screen.Browse.createRoute(request.type)) {
                launchSingleTop = true
            }
        }
        onDeepLinkConsumed()
    }

    // NAV-5: a link that parsed to nothing still goes somewhere — the dead-link
    // destination, which says so with a way home, instead of silently opening
    // Home as if nothing happened.
    LaunchedEffect(unknownDeepLink) {
        if (unknownDeepLink == null) return@LaunchedEffect
        navController.navigate(Screen.DeadLink.route) { launchSingleTop = true }
        onUnknownDeepLinkConsumed()
    }

    // P0-1/P0-2/P1-1: the tab set is locked in code — Home · Search ·
    // Downloads · Profile (PanelTabs.resolve). There is no operator list
    // any more: the panel's `tabs` key is gone from the app, so nothing
    // can reorder the set or resurrect a removed tab, and Live TV is a
    // route, not a tab (its rail lands on Home separately). The routes
    // stay registered even for a tab the operator hid — a back stack
    // that already holds that screen must not name a destination the
    // graph cannot resolve.
    // The tabs wear the brand's own icons (res/drawable/ic_tab_*.xml,
    // the stroke set from the brand pack — the `?attr/colorControlNormal`
    // tint the pack files carried was stripped on copy-in, because this app
    // has no such attr; the NavigationBarItem tint is the only tint). The
    // vectors are stroke-black; Icon's tint recolours them to the bar's
    // selected/unselected colours, exactly as the Material icons did.
    // Downloads and Profile have no filled tab variants, so they wear
    // their house action glyphs (ic_action_download, ic_action_user) in
    // both states — the same stroke language, no new drawables.
    val bottomNavItems = PanelTabs.resolve(features.search)
        // MX #7 — Kids Lock: Downloads leaves the bar in kids mode — a
        // kid's downloads are unfiltered, so they are not reachable in
        // kids mode at all. Home, Search and Profile stay.
        .filterNot { kidsMode && it == "downloads" }
        .map { id ->
        when (id) {
            "search" -> BottomNavItem("Search", Screen.Search.route, ImageVector.vectorResource(R.drawable.ic_tab_search), selectedIcon = ImageVector.vectorResource(R.drawable.ic_tab_search_filled), navigateRoute = Screen.Search.PLAIN_ROUTE)
            "downloads" -> BottomNavItem("Downloads", Screen.Downloads.route, ImageVector.vectorResource(R.drawable.ic_action_download))
            "profile" -> BottomNavItem("Profile", Screen.Profile.route, ImageVector.vectorResource(R.drawable.ic_action_user))
            else -> BottomNavItem("Home", Screen.Home.route, ImageVector.vectorResource(R.drawable.ic_tab_home), selectedIcon = ImageVector.vectorResource(R.drawable.ic_tab_home_filled))
        }
    }

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                Column {
                    // Restyle 2026-11-13: a 1dp specular edge on the bar's
                    // top — the faint light line that makes the bar read as
                    // glass sitting over the content instead of a flat slab.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp) // token-exempt: divider thickness, not spacing
                            .background(Color.White.copy(alpha = 0.08f)),
                    )
                    // Density (Nov 2026): the bar is RaddBottomBar now, not
                    // M3's NavigationBar — NavigationBar floors its Row at
                    // minHeight 80dp, so the 64dp bar could not be built on
                    // it. The container sizes to its content plus the
                    // system nav-bars inset (Robo run 6: forcing the bar
                    // to exactly 80dp total crushed the tabs to 32dp on a
                    // 3-button-nav device, because M3 applies the nav-bars
                    // inset inside the bar). No height is forced here and
                    // the tabs keep their full 64dp.
                    // Never fillMaxHeight() on a tab — that expands the bar
                    // to fill the screen (run 4).
                    RaddBottomBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        BottomNavTab(
                            label = item.label,
                            icon = item.icon,
                            selectedIcon = item.selectedIcon,
                            selected = selected,
                            onClick = {
                                // Samsung RTL retest (Issue 8): a tab tap
                                // must never be gated on the composition-time
                                // route snapshot — after failed playback
                                // cycles (rapid push/pop sequences through
                                // the player, with the main thread busy on
                                // resolve retries) the snapshot can lag the
                                // back stack, and a stale read turns every
                                // tap on that tab into a silent no-op until
                                // something else recomposes the bar. Read
                                // the controller's live destination at tap
                                // time instead; launchSingleTop keeps a
                                // same-tab tap a harmless no-op at the
                                // controller level.
                                if (navController.currentDestination?.route != item.route) {
                                    navController.navigate(item.navigateRoute) {
                                        popUpTo(Screen.Home.route) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                        )
                    }
                }
                }
            }
        },
    ) { innerPadding ->
        // P0-3 (audit B-5/C-5): no slide anywhere. Tab switches fade
        // through (180 ms in / 90 ms out, no direction — tabs are
        // lateral); pushes use the container transform (fade + settle
        // scale, the poster morphing through the shared-element keys);
        // the player fades through. Reduced-motion users get a pure
        // 30 ms alpha crossfade, never movement.
        val reduceMotion = rememberReduceMotion()
        // Round 6 motion infra: the shared-transition scope lives here —
        // every poster→hero sharedElement() in the graph animates inside
        // this layout. The scope is captured once and handed to the four
        // destinations that use shared elements (Home, Search, My List,
        // Detail); the settled render is unchanged, so no golden moves.
        // The NavHost body keeps its indentation — only this wrap is new.
        SharedTransitionLayout {
            val sharedTransitionScope = this
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(bottom = if (showBottomBar) innerPadding.calculateBottomPadding() else 0.dp),
            enterTransition = {
                if (reduceMotion) {
                    fadeIn(animationSpec = tween(RaddMotion.FadeWhenReduced))
                } else if (targetState.destination.route in tabRoutes) {
                    // P0-3: landing on a tab — the incoming leg of the
                    // fade-through, no direction.
                    fadeIn(animationSpec = tween(TabFadeInMs, easing = RaddMotion.EmphasizedDecelerate))
                } else if (targetState.destination.route in playerRoutes) {
                    // P0-3: the player fades through, never slides.
                    fadeIn(animationSpec = tween(PlayerFadeMs, easing = RaddMotion.EmphasizedDecelerate))
                } else {
                    // P0-3: push — container transform. The poster morphs
                    // into the Detail hero through the shared-element keys
                    // (Motion.kt posterSharedElement); the screen itself
                    // fades in with a slight settle-scale instead of
                    // sliding — a slide would fight the morph.
                    fadeIn(animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedDecelerate)) +
                        scaleIn(
                            initialScale = PushSettleScale,
                            animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedDecelerate),
                        )
                }
            },
            exitTransition = {
                if (reduceMotion) {
                    fadeOut(animationSpec = tween(RaddMotion.FadeWhenReduced))
                } else if (initialState.destination.route in tabRoutes) {
                    // P0-3: leaving a tab — the outgoing leg of the
                    // fade-through, a quick fade out.
                    fadeOut(animationSpec = tween(TabFadeOutMs))
                } else if (initialState.destination.route in playerRoutes) {
                    // P0-3: leaving the player — fade-through out.
                    fadeOut(animationSpec = tween(PlayerFadeMs, easing = RaddMotion.EmphasizedAccelerate))
                } else {
                    // P0-3: leaving a pushed screen — it fades out under
                    // the entering screen; the scale lives on the enter
                    // side, not here, so the two never fight.
                    fadeOut(animationSpec = tween(RaddMotion.DurationDefault))
                }
            },
            popEnterTransition = {
                if (reduceMotion) {
                    fadeIn(animationSpec = tween(RaddMotion.FadeWhenReduced))
                } else if (targetState.destination.route in tabRoutes) {
                    // P0-3: back onto a tab — fade-through in.
                    fadeIn(animationSpec = tween(TabFadeInMs, easing = RaddMotion.EmphasizedDecelerate))
                } else if (targetState.destination.route in playerRoutes) {
                    // P0-3: back to the player — fade-through in.
                    fadeIn(animationSpec = tween(PlayerFadeMs, easing = RaddMotion.EmphasizedDecelerate))
                } else {
                    // P0-3: back to a pushed screen (e.g. Detail from the
                    // player) — fade in; the container scale lives on the
                    // pop-exit side.
                    fadeIn(animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedDecelerate))
                }
            },
            popExitTransition = {
                if (reduceMotion) {
                    fadeOut(animationSpec = tween(RaddMotion.FadeWhenReduced))
                } else if (initialState.destination.route in tabRoutes) {
                    // P0-3: popping a tab root — the outgoing leg of the
                    // fade-through.
                    fadeOut(animationSpec = tween(TabFadeOutMs))
                } else if (initialState.destination.route in playerRoutes) {
                    // P0-3: popping the player — fade-through out.
                    fadeOut(animationSpec = tween(PlayerFadeMs, easing = RaddMotion.EmphasizedAccelerate))
                } else {
                    // P0-3: popping a pushed screen — the container
                    // transform in reverse: fade out while settling down
                    // to 96%. Predictive back plays this leg against the
                    // gesture (navigation-compose 2.8 drives the pop
                    // transitions from the back preview when the system
                    // offers it — the app-level opt-in is the manifest's
                    // android:enableOnBackInvokedCallback, still needed
                    // while targetSdk stays 35; Android 16 enables it by
                    // default for targetSdk 36).
                    fadeOut(animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedAccelerate)) +
                        scaleOut(
                            targetScale = PushSettleScale,
                            animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedAccelerate),
                        )
                }
            },
        ) {
            composable(Screen.Setup.route) {
                SetupScreen(
                    onConnected = { askRegion ->
                        // A fresh install that has just connected has never been asked what it
                        // watches, and the region screen is the one thing MainActivity's launch
                        // gate already promises to serve — it must be served HERE, on this first
                        // launch, not held back for the second. An install that already answered
                        // goes straight to Home, exactly as before. Setup leaves the stack either
                        // way, so pressing back from what follows does not bounce into it.
                        val next = if (askRegion) Screen.Region.route else Screen.Home.route
                        navController.navigate(next) {
                            popUpTo(Screen.Setup.route) { inclusive = true }
                        }
                    },
                )
            }

            composable(Screen.Region.route) {
                RegionSelectionScreen(
                    onContinue = {
                        // Two entrances, one button. Opened from Settings the
                        // viewer came from a screen that is still behind this
                        // one, so popping just this screen returns them to it
                        // — popping back to Home destroyed the Settings entry
                        // and left the viewer with no way back to the row they
                        // just used (Session 114: the row "disappeared"
                        // because its screen did). On a first install the
                        // region screen IS the whole stack, so there is
                        // nothing to pop and Home is pushed instead, with
                        // this screen leaving for good.
                        if (!navController.popBackStack()) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Region.route) { inclusive = true }
                            }
                        }
                    },
                )
            }

            composable(Screen.Home.route) {
                HomeScreen(
                    // P1-2: the header glyph deep-links into a focused
                    // search field — keyboard up, field focused on entry.
                    // The tab tap keeps the plain route (unfocused).
                    onSearchClick = { navController.navigate(Screen.Search.createRoute(focusOnEntry = true)) },
                    // Audit #17: the search bar's submit carries the typed query
                    // into Search, so the viewer never retypes it there.
                    onSearchQuery = { query ->
                        navController.navigate(Screen.Search.createRoute(query))
                    },
                    onPostClick = { post -> navController.navigateToPost(post) },
                    // 2-button detail (Nov 2026): the hero's Watch Now
                    // opens the title on its two buttons — autoplay on
                    // detail open is OFF, so the viewer presses Play.
                    onWatchNowClick = { post ->
                        navController.navigateToPost(post)
                    },
                    // Home fix (Nov 2026): direct play — the rail tile's
                    // play button opens Detail with autoplay, so the tap
                    // starts the title instead of landing on the two buttons.
                    onPlayClick = { post ->
                        navController.navigateToPost(post, autoplay = true)
                    },
                    // REA #4: the "Play Something" shuffle — the Home row
                    // header's pick, opened on its two buttons.
                    onPlaySomethingClick = { post ->
                        navController.navigateToPost(post)
                    },
                    onBrowseType = { type, sort -> navController.navigate(Screen.Browse.createRoute(type, sort)) },
                    // A facet chip on Home lands on Search with the filter
                    // already applied — the same tab, not a pushed copy.
                    onFacetSelected = { genre, industry, language ->
                        navController.navigate(Screen.Search.createRoute(genre = genre, industry = industry, language = language))
                    },
                    onSetupServerClick = { navController.navigate(Screen.Setup.route) },
                    // P0-2/P0-4: the Live TV rail opens Live TV and the header
                    // avatar opens the Profile hub (was: rail no-op, avatar → Settings).
                    onLiveTvClick = { navController.navigate(Screen.LiveTv.route) },
                    onProfileClick = { navController.navigate(Screen.Profile.route) },
                    // S4(c): the fatal error's "Open Settings" action.
                    onOpenSettingsClick = { navController.navigate(Screen.Settings.PLAIN_ROUTE) },
                    heroMax = theme.heroMax,
                    heroAutoplay = theme.heroAutoplay && autoplayPreviews,
                    heroIntervalMs = theme.heroIntervalS * 1_000L,
                    // MX #7 — Kids Lock: the banner's Exit raises the PIN
                    // dialog below; the mode itself rides the ViewModels.
                    kidsMode = kidsMode,
                    onExitKidsMode = { exitPinRequested = true },
                    // Round 6 motion infra: the shared-transition scopes —
                    // Home's posters animate into Detail's hero on this key.
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                )
            }

            composable(
                route = Screen.Browse.route,
                arguments = listOf(
                    navArgument(Screen.Browse.ARG_TYPE) {
                        type = NavType.StringType
                        defaultValue = "all"
                    },
                    // H10: the sort the rail was ordered by — "" means no
                    // sort, the old behaviour; "trending" is the one sort
                    // Browse reproduces.
                    navArgument(Screen.Browse.ARG_SORT) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
                deepLinks = listOf(
                    androidx.navigation.navDeepLink { uriPattern = Brand.deepLinkUri("browse/{type}") },
                ),
            ) {
                BrowseScreen(
                    onBackClick = { navController.popBackStack() },
                    onPostClick = { post -> navController.navigateToPost(post) },
                )
            }

            composable(
                route = Screen.Search.route,
                arguments = listOf(
                    // An actor tap lands here with the name already filled
                    // in; the plain tab navigates with no argument, so the
                    // default keeps every existing search link working.
                    navArgument(Screen.Search.ARG_QUERY) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    // A facet chip on Home lands here with the filter
                    // already applied; the plain tab navigates with no
                    // argument, so the default keeps it unfiltered.
                    navArgument(Screen.Search.ARG_GENRE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument(Screen.Search.ARG_INDUSTRY) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    // UI-fix P1-H5: a language chip on Home lands here with
                    // the language already applied.
                    navArgument(Screen.Search.ARG_LANGUAGE) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    // P1-2: the Home header glyph lands here with the field
                    // focused; the plain tab navigates with no argument, so
                    // the default keeps it unfocused.
                    navArgument(Screen.Search.ARG_FOCUS) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { backStackEntry ->
                SearchScreen(
                    initialQuery = backStackEntry.arguments?.getString(Screen.Search.ARG_QUERY)?.takeIf { it.isNotBlank() },
                    initialGenre = backStackEntry.arguments?.getString(Screen.Search.ARG_GENRE)?.takeIf { it.isNotBlank() },
                    initialIndustry = backStackEntry.arguments?.getString(Screen.Search.ARG_INDUSTRY)?.takeIf { it.isNotBlank() },
                    initialLanguage = backStackEntry.arguments?.getString(Screen.Search.ARG_LANGUAGE)?.takeIf { it.isNotBlank() },
                    focusOnEntry = backStackEntry.arguments?.getString(Screen.Search.ARG_FOCUS) == Screen.Search.FOCUS_ON_ENTRY,
                    onPostClick = { post -> navController.navigateToPost(post) },
                    onBrowse = { type -> navController.navigate(Screen.Browse.createRoute(type)) },
                    // Audit Search #3: the error state offers "Open Settings".
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    // Round 6 motion infra: the shared-transition scopes —
                    // Search's posters animate into Detail's hero on this key.
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                    onPlayDirectUrl = { url, title ->
                        // The address is staged, never routed: the player
                        // opens the opaque key and reads the address from
                        // the store (see PlayerSessionStore).
                        val key = PlayerSessionStore.Instance.stageDirectUrl(url)
                        navController.navigate(Screen.Player.createRoute(key, title)) {
                            // A double-tap on Play must not stack two identical
                            // players: one Back would then land on the same
                            // player again and look like Back doing nothing
                            // (Session 114).
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Screen.MyList.route) {
                MyListScreen(
                    onPostClick = { post -> navController.navigateToPost(post) },
                    // Round 6 motion infra: the shared-transition scopes —
                    // My List's posters animate into Detail's hero on this key.
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                    onOpenWatchHistory = { navController.navigate(Screen.WatchHistory.route) },
                    // L4 (round 2): the brief's "Browse" goes to Home, not
                    // Search — Home is the catalog's front door, and the
                    // empty state's way out should land on it. Same
                    // pop-up block as the bottom-nav Home tab (NAV-4): no
                    // second Home stacked over the list.
                    onBrowseClick = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }

            composable(Screen.LiveTv.route) {
                LiveTvScreen(
                    onPlayChannel = { streamId, title ->
                        // The same player route a movie uses, addressed by
                        // the server's opaque stream handle — the player
                        // itself is untouched by Live TV.
                        navController.navigate(Screen.Player.createRoute(streamId, title))
                    },
                )
            }

            composable(
                route = Screen.Settings.route,
                arguments = listOf(
                    // Settings review (Nov 2026, D2): Help's Contact row
                    // lands here with scrollTo=contact, so the contact card
                    // is already in view. The plain tab navigates with no
                    // argument, so the default keeps it at the top.
                    navArgument(Screen.Settings.ARG_SCROLL_TO) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { backStackEntry ->
                // MX #7 — Kids Lock: Settings in kids mode opens only behind
                // the PIN, and the gate renders over an EMPTY screen — the
                // Settings content itself never composes behind the dialog,
                // so nothing (account, downloads, VIP) leaks through the dim.
                if (kidsMode && !adultUnlocked) {
                    KidsPinGateDialog(
                        title = "Enter PIN",
                        subtitle = "Settings is locked in Kids Mode.",
                        viewModel = kidsLockViewModel,
                        verify = { pin -> kidsLockViewModel.verifyPin(pin) },
                        onSuccess = { adultUnlocked = true },
                        onDismiss = { navController.popBackStack() },
                    )
                } else {
                    SettingsScreen(
                    features = features,
                    // P0-1: Settings is a push destination from the Profile
                    // hub now, not a tab — it needs a way back.
                    onBackClick = { navController.popBackStack() },
                    onOpenDownloads = { navController.navigate(Screen.Downloads.route) },
                    onOpenVipUpgrade = { navController.navigate(Screen.VipUpgrade.route) },
                    onOpenRegionChoice = { navController.navigate(Screen.Region.route) },
                    onOpenDashboard = { navController.navigate(Screen.Dashboard.route) },
                    onOpenProfile = { navController.navigate(Screen.Profile.route) },
                    onOpenHelp = { navController.navigate(Screen.Help.route) },
                    onOpenLanguages = { navController.navigate(Screen.Languages.route) },
                    onOpenWatchHistory = { navController.navigate(Screen.WatchHistory.route) },
                    onOpenResolveTest = { navController.navigate(Screen.ResolveTest.route) },
                    onOpenPressTest = { navController.navigate(Screen.PressTest.route) },
                    onOpenDiagnostics = {
                        if (DiagnosticsBus.isEnabled()) {
                            navController.navigate(Screen.Diagnostics.route)
                        }
                    },
                    showDiagnostics = DiagnosticsBus.isEnabled(),
                    scrollToContact = backStackEntry.arguments?.getString(Screen.Settings.ARG_SCROLL_TO) == Screen.Settings.SCROLL_TO_CONTACT,
                    )
                }
            }

            // Debug-only field-test harness (plan §8): the entry exists only
            // in DEBUG builds, so the screen is unreachable in release.
            if (BuildConfig.DEBUG) {
                composable(Screen.ResolveTest.route) {
                    ResolveTestScreen(
                        onBackClick = { navController.popBackStack() },
                        onOpenPressTest = { navController.navigate(Screen.PressTest.route) },
                    )
                }
                composable(Screen.PressTest.route) {
                    PressTestScreen(
                        onBackClick = { navController.popBackStack() },
                    )
                }
            }

            // APP-13 (review B-2): the diagnostics destination is registered
            // through the Diagnostics interface — the NoOp implementation
            // (release) registers nothing, the debug implementation registers
            // the screen. The navigation itself is gated on isEnabled() at
            // the Settings entry above.
            DiagnosticsBus.addDestination(this) { navController.popBackStack() }

            composable(Screen.Help.route) {
                HelpScreen(
                    onBackClick = { navController.popBackStack() },
                    // Settings review (Nov 2026, D2): the Contact row sends
                    // the viewer to the contact card in Settings with it
                    // already in view — not to the top of the screen.
                    onContactClick = { navController.navigate(Screen.Settings.createRoute(Screen.Settings.SCROLL_TO_CONTACT)) },
                )
            }

            composable(Screen.Languages.route) {
                LanguagesScreen(onBackClick = { navController.popBackStack() })
            }

            composable(Screen.WatchHistory.route) {
                WatchHistoryScreen(
                    onBackClick = { navController.popBackStack() },
                    onPostClick = { postId -> navController.navigate(Screen.Detail.createRoute(postId)) },
                )
            }

            composable(Screen.VipUpgrade.route) {
                VipUpgradeScreen(
                    onBackClick = { navController.popBackStack() },
                )
            }

            // Session 123: the account screens — the dashboard that answers
            // "where do I stand?" and the profile. The dashboard is a push
            // destination (Settings, Profile and VIP link here); the profile
            // is the hub tab since P0-1, so it shows no back arrow when it
            // is reached as a tab and keeps it when pushed from Settings
            // or Dashboard.
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onBackClick = { navController.popBackStack() },
                    onOpenProfile = { navController.navigate(Screen.Profile.route) },
                    onOpenVipUpgrade = { navController.navigate(Screen.VipUpgrade.route) },
                )
            }

            composable(Screen.Profile.route) {
                // P0-1: a tab tap pops to Home first, so a Profile reached
                // as a tab has Home underneath it; pushed from Settings or
                // Dashboard it has that screen underneath. The hub shows
                // no back arrow as a tab root.
                val isTab = navController.previousBackStackEntry?.destination?.route == Screen.Home.route
                ProfileScreen(
                    onBackClick = { navController.popBackStack() },
                    onOpenDashboard = { navController.navigate(Screen.Dashboard.route) },
                    onOpenVipUpgrade = { navController.navigate(Screen.VipUpgrade.route) },
                    onOpenDownloads = {
                        // The hub's Downloads row switches to the Downloads
                        // tab — the same block as a bottom-bar tap, so the
                        // tab is selected and no second Downloads stacks.
                        navController.navigate(Screen.Downloads.route) {
                            popUpTo(Screen.Home.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenWatchHistory = { navController.navigate(Screen.WatchHistory.route) },
                    onOpenSettings = { navController.navigate(Screen.Settings.PLAIN_ROUTE) },
                    onOpenHelp = { navController.navigate(Screen.Help.route) },
                    // Kids Lock lives in Settings (its card is in the
                    // Playback group); the hub row opens Settings, where
                    // the PIN flow already is.
                    onOpenKidsLock = { navController.navigate(Screen.Settings.PLAIN_ROUTE) },
                    isTab = isTab,
                    kidsMode = kidsMode,
                )
            }

            composable(Screen.Downloads.route) {
                DownloadsScreen(
                    onBackClick = { navController.popBackStack() },
                    onPlay = { item ->
                        item.streamHandle?.let {
                            navController.navigate(Screen.Player.createRoute(it, item.title)) {
                                launchSingleTop = true
                            }
                        }
                    },
                    onPlayLocal = { uri, title ->
                        navController.navigate(Screen.PlayerLocal.createRoute(uri, title)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenLocalFiles = { navController.navigate(Screen.LocalFiles.route) },
                    // Phase 4 (Downloads #4/#14): a gated keep offers the
                    // VIP upgrade; the empty state's action goes to Search.
                    onOpenVipUpgrade = { navController.navigate(Screen.VipUpgrade.route) },
                    onBrowseClick = { navController.navigate(Screen.Search.PLAIN_ROUTE) },
                )
            }

            composable(Screen.LocalFiles.route) {
                LocalFilesScreen(
                    onBackClick = { navController.popBackStack() },
                    onPlayLocal = { uri, title ->
                        navController.navigate(Screen.PlayerLocal.createRoute(uri, title)) {
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(
                route = Screen.Detail.route,
                arguments = listOf(
                    navArgument(Screen.Detail.ARG_POST_ID) { type = NavType.IntType },
                    // Phase 7 (M1): the v2 work id — a default of -1 keeps every
                    // existing detail link (deep links included) working.
                    navArgument(Screen.Detail.ARG_WORK_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    // Session 121 (P-26): the hero's Watch Now opens the title and starts
                    // it — an optional flag, so every existing detail link still works.
                    navArgument(Screen.Detail.ARG_AUTOPLAY) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
                deepLinks = listOf(
                    androidx.navigation.navDeepLink { uriPattern = Brand.deepLinkUri("detail/{postId}") },
                ),
            ) { backStackEntry ->
                DetailScreen(
                    // Home fix (Nov 2026): direct play — the rail tile's
                    // play button opens Detail with autoplay, so the tap
                    // starts the title once the layout has loaded. Every
                    // other route leaves it false (the hero's Watch Now
                    // and the shuffle land on the two buttons).
                    autoplay = backStackEntry.arguments?.getBoolean(Screen.Detail.ARG_AUTOPLAY) ?: false,
                    onBackClick = { navController.popBackStack() },
                    onPlayStream = { streamId, title ->
                        navController.navigate(Screen.Player.createRoute(streamId, title)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenProviderPage = { streamId, title ->
                        navController.navigate(Screen.Embed.createRoute(streamId, title)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenDownloads = { navController.navigate(Screen.Downloads.route) },
                    // 2-button detail (Nov 2026): play a file already on
                    // this device ("✓ Downloaded" on the Download button).
                    onPlayLocal = { uri, title ->
                        navController.navigate(Screen.PlayerLocal.createRoute(uri, title)) {
                            launchSingleTop = true
                        }
                    },
                    // Detail redesign (Nov 2026): a season row opens the
                    // season's own sub-page. The work id rides along for
                    // a v2 row, exactly as on this route.
                    onOpenSeason = { season ->
                        val postId = backStackEntry.arguments?.getInt(Screen.Detail.ARG_POST_ID) ?: 0
                        val workId = backStackEntry.arguments?.getLong(Screen.Detail.ARG_WORK_ID) ?: -1L
                        val route = if (workId != -1L) {
                            Screen.DetailSeason.createRouteForWork(workId, season)
                        } else {
                            Screen.DetailSeason.createRoute(postId, season, workId)
                        }
                        navController.navigate(route)
                    },
                    onActorClick = { actor ->
                        navController.navigate(Screen.Actor.createRoute(actor))
                    },
                    onPostClick = { similar -> navController.navigateToPost(similar) },
                    onOpenLanguages = { navController.navigate(Screen.Languages.route) },
                    // Round 6 motion infra: the shared-transition scopes —
                    // the hero carries the same poster key as its source.
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this,
                )
            }

            /**
             * P2 cast (Nov 2026): one actor's page — photo + bio (TMDb)
             * and their movies & TV on this server. The ViewModel reads
             * the name from the nav argument itself, so the screen takes
             * no name parameter. Search stays the fallback behind the
             * screen's own "See all in Search" button.
             */
            composable(
                route = Screen.Actor.route,
                arguments = listOf(
                    navArgument(Screen.Actor.ARG_NAME) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) {
                ActorScreen(
                    onBackClick = { navController.popBackStack() },
                    onPostClick = { post -> navController.navigateToPost(post) },
                    onOpenSearch = { query ->
                        navController.navigate(Screen.Search.createRoute(query))
                    },
                )
            }

            /**
             * Detail redesign (Nov 2026): one season's episodes, as plain
             * numbered tiles. The page shares Detail's ViewModel through
             * the parent back-stack entry — same layout, same progress,
             * same Kids Lock filtering, no refetch. A season the layout
             * does not carry reads as "not available" on the page itself.
             */
            composable(
                route = Screen.DetailSeason.route,
                arguments = listOf(
                    navArgument(Screen.DetailSeason.ARG_POST_ID) { type = NavType.IntType },
                    navArgument(Screen.DetailSeason.ARG_SEASON) { type = NavType.IntType },
                    navArgument(Screen.DetailSeason.ARG_WORK_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(Screen.Detail.route)
                }
                val seasonNumber = backStackEntry.arguments?.getInt(Screen.DetailSeason.ARG_SEASON) ?: 1
                SeasonScreen(
                    seasonNumber = seasonNumber,
                    onBackClick = { navController.popBackStack() },
                    onPlayStream = { streamId, title ->
                        navController.navigate(Screen.Player.createRoute(streamId, title)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenProviderPage = { streamId, title ->
                        navController.navigate(Screen.Embed.createRoute(streamId, title)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenDownloads = { navController.navigate(Screen.Downloads.route) },
                    // 2-button detail (Nov 2026): play a file already on
                    // this device ("✓ Downloaded" on the Download button).
                    onPlayLocal = { uri, title ->
                        navController.navigate(Screen.PlayerLocal.createRoute(uri, title)) {
                            launchSingleTop = true
                        }
                    },
                    // 2-button detail (Nov 2026): jump to another season
                    // of this title, from its chips. The post/work ids
                    // ride this entry's own arguments.
                    onOpenSeason = { season ->
                        val postId = backStackEntry.arguments?.getInt(Screen.DetailSeason.ARG_POST_ID) ?: 0
                        val workId = backStackEntry.arguments?.getLong(Screen.DetailSeason.ARG_WORK_ID) ?: -1L
                        val route = if (workId != -1L) {
                            Screen.DetailSeason.createRouteForWork(workId, season)
                        } else {
                            Screen.DetailSeason.createRoute(postId, season, workId)
                        }
                        navController.navigate(route)
                    },
                    viewModel = hiltViewModel(parentEntry),
                )
            }

            composable(
                route = Screen.Player.route,
                arguments = listOf(
                    navArgument(Screen.Player.ARG_STREAM_ID) { type = NavType.StringType },
                    navArgument(Screen.Player.ARG_TITLE) { type = NavType.StringType },
                ),
                deepLinks = listOf(
                    // radd://stream/<handle>/<title> — a shared link opens the
                    // player for one handle, with the same args the in-app
                    // route carries, so the decode below is identical.
                    androidx.navigation.navDeepLink { uriPattern = Brand.deepLinkUri("stream/{streamId}/{title}") },
                ),
            ) { backStackEntry ->
                val encodedHandle = backStackEntry.arguments?.getString(Screen.Player.ARG_STREAM_ID) ?: ""
                val encodedTitle = backStackEntry.arguments?.getString(Screen.Player.ARG_TITLE) ?: ""
                val streamId = Screen.Player.decodeStreamId(encodedHandle)
                val title = Screen.Player.decodeTitle(encodedTitle)

                PlayerScreen(
                    streamId = streamId,
                    title = title,
                    onBackClick = { navController.popBackStack() },
                    onPlayNextEpisode = { _, nextPostId, season, episode ->
                        if (nextPostId != null && nextPostId > 0) {
                            navController.navigate(Screen.Detail.createRoute(nextPostId)) {
                                launchSingleTop = true
                                popUpTo(Screen.Player.route) { inclusive = true }
                            }
                        }
                    },
                    // REA #1: a post-play preview tile was tapped — open that
                    // title's Detail, leaving the player behind exactly as
                    // the next-episode advance does.
                    onOpenPost = { postId ->
                        navController.navigate(Screen.Detail.createRoute(postId)) {
                            launchSingleTop = true
                            popUpTo(Screen.Player.route) { inclusive = true }
                        }
                    },
                    // REA #4: the give-up card's "Play Something" — the
                    // shuffle's pick, opened on its two buttons and leaving
                    // the dead player behind, exactly as a post-play tile
                    // does. Back from that Detail never drops the viewer
                    // back into the failed player.
                    onPlaySomethingClick = { post ->
                        val route = if (post.workId != null) {
                            Screen.Detail.createRouteForWork(post.workId)
                        } else {
                            Screen.Detail.createRoute(post.id)
                        }
                        navController.navigate(route) {
                            launchSingleTop = true
                            popUpTo(Screen.Player.route) { inclusive = true }
                        }
                    },
                    // SC §3: an actor tapped on the pause strip opens their page,
                    // the same route the detail screen's cast row takes.
                    onActorClick = { actor ->
                        navController.navigate(Screen.Actor.createRoute(actor)) {
                            launchSingleTop = true
                        }
                    },
                    // NAV-4: the expired-link card's way home. It must clear
                    // the dead player (and the detail under it) instead of
                    // stacking a second Home over them: without the pop-up,
                    // Back from that Home dropped the viewer straight back
                    // into the failed player, which reads as Home not
                    // working at all. Same block as the bottom-nav Home tab.
                    onHomeClick = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }

            /**
             * The provider's own page, for a link the server says is a page and not a file. The
             * route carries the OPAQUE HANDLE only — see [Screen.Embed] for why an address never
             * belongs in a back stack, and `EmbedWebViewScreen` for what this screen does not do.
             */
            composable(
                route = Screen.Embed.route,
                arguments = listOf(
                    navArgument(Screen.Embed.ARG_STREAM_ID) { type = NavType.StringType },
                    navArgument(Screen.Embed.ARG_TITLE) { type = NavType.StringType },
                ),
            ) {
                EmbedWebViewScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Screen.PlayerLocal.route,
                arguments = listOf(
                    navArgument(Screen.PlayerLocal.ARG_URI) { type = NavType.StringType },
                    navArgument(Screen.PlayerLocal.ARG_TITLE) { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val localUri = Screen.PlayerLocal.decode(
                    backStackEntry.arguments?.getString(Screen.PlayerLocal.ARG_URI).orEmpty(),
                )
                val title = Screen.PlayerLocal.decode(
                    backStackEntry.arguments?.getString(Screen.PlayerLocal.ARG_TITLE).orEmpty(),
                )
                PlayerScreen(
                    streamId = "",
                    title = title,
                    localUri = localUri,
                    onBackClick = { navController.popBackStack() },
                )
            }

            // NAV-5: the catch-all for a deep link that parsed to nothing.
            composable(route = Screen.DeadLink.route) {
                DeadLinkScreen(
                    // Same as the player card's way home (NAV-4): clear the
                    // dead-link screen instead of stacking a second Home
                    // over it, or Back from Home returns to the dead link.
                    onHomeClick = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
        }

        // MX #7 — Kids Lock: the exit PIN, raised by the Home banner. It
        // sits above the NavHost, so it opens wherever the banner is, and
        // dismissing it leaves kids mode exactly where it was.
        if (exitPinRequested) {
            KidsPinGateDialog(
                title = "Enter PIN",
                subtitle = "Kids Mode is on — enter the PIN to exit.",
                viewModel = kidsLockViewModel,
                verify = { pin -> kidsLockViewModel.exitKidsMode(pin) },
                onSuccess = { exitPinRequested = false },
                onDismiss = { exitPinRequested = false },
            )
        }
    }
}

/**
 * MX #7 — Kids Lock: one PIN gate for both doors that need it — opening
 * Settings in kids mode and exiting kids mode from the Home banner. The
 * dialog owns the error line and the lockout countdown (from the VM's
 * remaining milliseconds); the caller only supplies the check ([verify])
 * and what a right PIN unlocks ([onSuccess]). A wrong PIN leaves the
 * dialog open with its message; the throttle lives in the ViewModel, so
 * five wrong tries lock the pad for 30 seconds.
 */
@Composable
private fun KidsPinGateDialog(
    title: String,
    subtitle: String,
    viewModel: KidsLockViewModel,
    verify: suspend (String) -> KidsPinOutcome,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    PinPadDialog(
        title = title,
        subtitle = subtitle,
        error = error,
        lockedOutSeconds = (viewModel.lockoutRemainingMs() / 1_000).toInt(),
        onPinComplete = { pin ->
            scope.launch {
                when (verify(pin)) {
                    KidsPinOutcome.Success -> onSuccess()
                    KidsPinOutcome.WrongPin -> error = "Wrong PIN — try again"
                    KidsPinOutcome.InvalidFormat -> error = "Enter all 4 digits"
                    KidsPinOutcome.Mismatch -> error = "Wrong PIN — try again"
                    // LockedOut clears the error — the dialog's own
                    // countdown line (from lockedOutSeconds above) says it.
                    KidsPinOutcome.LockedOut -> error = null
                }
            }
        },
        onDismiss = onDismiss,
    )
}
