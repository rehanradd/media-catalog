package com.raddstream.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
 * APP-09 (mobile-tester run 4, 00:26 PKT 24 Nov): the bottom-nav label
 * shrinks to fit its slot instead of truncating — "Settings" read
 * "Settin..." at 1.3x font scale on a small phone, because five tabs share
 * the width and labelMedium at 1.3x needs more than a fifth of it. The
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    // Density (Nov 2026): the pill is M3 Expressive's
                    // 56×32dp visual — cap width at 56dp while allowing
                    // 2dp breathing room on narrow 320dp screens.
                    .padding(horizontal = 2.dp)
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
                    icon,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .size(24.dp)
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

@Composable
fun RaddStreamNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String,
    features: RemoteFeatures = RemoteFeatures(),
    /** The operator's tab order from the panel (`tabs` tuning key), as the activity last received it; empty means "never received", and the bar falls back to its own five. */
    tabs: List<String> = emptyList(),
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

    // MX #7 — Kids Lock: the session-scoped adult unlock. Settings is the
    // one door that stays reachable in kids mode, and it opens only after
    // this flips true — once per kids session, never persisted, and it
    // resets the moment kids mode turns off, so handing the phone back
    // never hands the Settings open with it.
    var adultUnlocked by remember { mutableStateOf(false) }
    LaunchedEffect(kidsMode) {
        if (!kidsMode) adultUnlocked = false
    }
    // Entering kids mode pops everything above Home: My List, Settings and
    // Downloads cannot linger on the stack for a kid to reach with Back.
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

    // The operator's tab order, resolved against what this build can show: Home is always
    // first, Search only when its feature flag is on, and an empty or all-unknown list falls
    // back to the five tabs this build has always shipped. The routes stay registered even
    // for a tab the operator hid — a back stack that already holds that screen must not name
    // a destination the graph cannot resolve.
    // The five tabs wear the brand's own icons (res/drawable/ic_tab_*.xml,
    // the stroke set from the brand pack — the `?attr/colorControlNormal`
    // tint the pack files carried was stripped on copy-in, because this app
    // has no such attr; the NavigationBarItem tint is the only tint). The
    // vectors are stroke-black; Icon's tint recolours them to the bar's
    // selected/unselected colours, exactly as the Material icons did.
    val bottomNavItems = PanelTabs.resolve(tabs, features.search)
        // MX #7 — Kids Lock: My List and Live TV leave the bar. Their rows
        // are unfiltered — a kid's own saves can hold adult titles, and a
        // live channel carries no rating at all — so they are not reachable
        // in kids mode at all. Home, Search and Settings stay.
        .filterNot { kidsMode && (it == "my_list" || it == "live_tv") }
        .map { id ->
        when (id) {
            "live_tv" -> BottomNavItem("Live TV", Screen.LiveTv.route, ImageVector.vectorResource(R.drawable.ic_tab_live_tv))
            "search" -> BottomNavItem("Search", Screen.Search.route, ImageVector.vectorResource(R.drawable.ic_tab_search), navigateRoute = Screen.Search.PLAIN_ROUTE)
            "my_list" -> BottomNavItem("My List", Screen.MyList.route, ImageVector.vectorResource(R.drawable.ic_tab_my_list))
            "settings" -> BottomNavItem("Settings", Screen.Settings.route, ImageVector.vectorResource(R.drawable.ic_tab_settings), navigateRoute = Screen.Settings.PLAIN_ROUTE)
            else -> BottomNavItem("Home", Screen.Home.route, ImageVector.vectorResource(R.drawable.ic_tab_home))
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
                            .height(1.dp)
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
        // UI-fix P1-X1: screens no longer cut between each other. Slide + fade
        // on the motion tokens (250–320 ms); reduced-motion users get a pure
        // 30 ms alpha crossfade, never movement.
        val reduceMotion = rememberReduceMotion()
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(bottom = if (showBottomBar) innerPadding.calculateBottomPadding() else 0.dp),
            enterTransition = {
                if (reduceMotion) {
                    fadeIn(animationSpec = tween(RaddMotion.FadeWhenReduced))
                } else {
                    slideInHorizontally(
                        initialOffsetX = { it / 4 },
                        animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedDecelerate),
                    ) + fadeIn(animationSpec = tween(RaddMotion.DurationDefault))
                }
            },
            exitTransition = {
                if (reduceMotion) {
                    fadeOut(animationSpec = tween(RaddMotion.FadeWhenReduced))
                } else {
                    slideOutHorizontally(
                        targetOffsetX = { -it / 4 },
                        animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedAccelerate),
                    ) + fadeOut(animationSpec = tween(RaddMotion.DurationDefault))
                }
            },
            popEnterTransition = {
                if (reduceMotion) {
                    fadeIn(animationSpec = tween(RaddMotion.FadeWhenReduced))
                } else {
                    slideInHorizontally(
                        initialOffsetX = { -it / 4 },
                        animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedDecelerate),
                    ) + fadeIn(animationSpec = tween(RaddMotion.DurationDefault))
                }
            },
            popExitTransition = {
                if (reduceMotion) {
                    fadeOut(animationSpec = tween(RaddMotion.FadeWhenReduced))
                } else {
                    slideOutHorizontally(
                        targetOffsetX = { it / 4 },
                        animationSpec = tween(RaddMotion.DurationDefault, easing = RaddMotion.EmphasizedAccelerate),
                    ) + fadeOut(animationSpec = tween(RaddMotion.DurationDefault))
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
                    onSearchClick = { navController.navigate(Screen.Search.PLAIN_ROUTE) },
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
                    onBrowseType = { type -> navController.navigate(Screen.Browse.createRoute(type)) },
                    // A facet chip on Home lands on Search with the filter
                    // already applied — the same tab, not a pushed copy.
                    onFacetSelected = { genre, industry, language ->
                        navController.navigate(Screen.Search.createRoute(genre = genre, industry = industry, language = language))
                    },
                    onSetupServerClick = { navController.navigate(Screen.Setup.route) },
                    heroMax = theme.heroMax,
                    heroAutoplay = theme.heroAutoplay && autoplayPreviews,
                    heroIntervalMs = theme.heroIntervalS * 1_000L,
                    // MX #7 — Kids Lock: the banner's Exit raises the PIN
                    // dialog below; the mode itself rides the ViewModels.
                    kidsMode = kidsMode,
                    onExitKidsMode = { exitPinRequested = true },
                )
            }

            composable(
                route = Screen.Browse.route,
                arguments = listOf(
                    navArgument(Screen.Browse.ARG_TYPE) {
                        type = NavType.StringType
                        defaultValue = "all"
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
                ),
            ) { backStackEntry ->
                SearchScreen(
                    initialQuery = backStackEntry.arguments?.getString(Screen.Search.ARG_QUERY)?.takeIf { it.isNotBlank() },
                    initialGenre = backStackEntry.arguments?.getString(Screen.Search.ARG_GENRE)?.takeIf { it.isNotBlank() },
                    initialIndustry = backStackEntry.arguments?.getString(Screen.Search.ARG_INDUSTRY)?.takeIf { it.isNotBlank() },
                    initialLanguage = backStackEntry.arguments?.getString(Screen.Search.ARG_LANGUAGE)?.takeIf { it.isNotBlank() },
                    onPostClick = { post -> navController.navigateToPost(post) },
                    onBrowse = { type -> navController.navigate(Screen.Browse.createRoute(type)) },
                    // Audit Search #3: the error state offers "Open Settings".
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
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
                    onOpenWatchHistory = { navController.navigate(Screen.WatchHistory.route) },
                    onBrowseClick = { navController.navigate(Screen.Browse.createRoute(null)) },
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
            // "where do I stand?" and the profile. None of them is a tab;
            // Settings, Home and VIP link here.
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    onBackClick = { navController.popBackStack() },
                    onOpenProfile = { navController.navigate(Screen.Profile.route) },
                    onOpenVipUpgrade = { navController.navigate(Screen.VipUpgrade.route) },
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onBackClick = { navController.popBackStack() },
                    onOpenDashboard = { navController.navigate(Screen.Dashboard.route) },
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
                        navController.navigate(Screen.Search.createRoute(actor))
                    },
                    onPostClick = { similar -> navController.navigateToPost(similar) },
                    onOpenLanguages = { navController.navigate(Screen.Languages.route) },
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
                    // SC §3: an actor tapped on the pause strip searches them,
                    // the same route the detail screen's cast chips take.
                    onActorClick = { actor ->
                        navController.navigate(Screen.Search.createRoute(actor)) {
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
