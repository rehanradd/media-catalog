package com.raddstream.app.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.raddstream.app.brand.Brand

// ═════════════════════════════════════════════════════════════════════════════
//  RADD STREAM THEME — "CURRENT & UNDERTOW"                           v2.0
//  Full M3 role mapping, deliberate dynamic-color policy, motion tokens,
//  and the artwork-driven accent hook (see ArtworkAccent.kt for extraction).
// ═════════════════════════════════════════════════════════════════════════════

// ── DARK (the flagship scheme — "undertow") ─────────────────────────────────
// Every role pairing used for text was WCAG-checked in Color.kt; the scheme
// only re-homes those same verified paints into M3 roles.
private val RaddDarkScheme = darkColorScheme(
    // Primary = the current. Note onPrimary is DARK INK, not white: current500
    // is a high-luminance brand color and white-on-it measures 2.2:1 (fail).
    primary = RaddCurrent500,
    onPrimary = RaddDeepCurrentInk,
    primaryContainer = RaddCurrentContainerDark,
    onPrimaryContainer = RaddOnCurrentContainer,

    secondary = RaddCoral400,
    onSecondary = RaddDeepCurrentInk, // coral is icon-first; ink labels stay ≥4.5
    secondaryContainer = RaddCoralContainerDark,
    onSecondaryContainer = RaddOnCoralContainer,

    tertiary = RaddTideIndigo,
    onTertiary = RaddInk950,
    tertiaryContainer = RaddTideContainerDark,
    onTertiaryContainer = RaddOnTideContainer,

    error = RaddDanger,
    onError = RaddInk950,
    errorContainer = RaddHealthDeadPill,
    onErrorContainer = RaddHealthDead,

    background = RaddInk950,
    onBackground = RaddPaperOnInk,
    surface = RaddInk900,
    onSurface = RaddPaperOnInk,
    surfaceVariant = RaddInk800,
    onSurfaceVariant = RaddMistOnInk,
    outline = RaddInk600,
    outlineVariant = RaddInk700,
    scrim = RaddScrim,

    // Tone-based container tiers (M3): low→high elevation without shadows.
    surfaceDim = RaddInkVoid,
    surfaceBright = RaddInk750,
    surfaceContainerLowest = RaddInkVoid,
    surfaceContainerLow = RaddInk900,
    surfaceContainer = RaddInk850,          // recommended default card fill
    surfaceContainerHigh = RaddInk800,      // chips, text fields, nav pills
    surfaceContainerHighest = RaddInk750,   // dialogs, FAB-on-ink
)

// ── LIGHT ("daybreak") — same water at noon ─────────────────────────────────
// P2-10 DECISION (26 Nov 2026): light mode is COMPLETE, not dark-only.
// Evidence counted at the decision (main 79527b28):
//  * The scheme itself was already complete: every M3 role mapped here,
//    22 [colors.light] keys in brand.properties, WCAG pairs verified
//    inline (16.1:1 text, 5.8:1 primary), features.light_scheme=true,
//    operator panel mode "light"/"system" resolved by resolveThemeDark
//    and wired in MainActivity (status-bar icons flip with the theme).
//  * The legacy aliases that used to pin screens dark had ZERO usages
//    anywhere in the app (main + test) — that migration had already
//    finished, so the audit's "several screens hard-paint via legacy
//    aliases" premise was stale. The alias block is deleted (Color.kt).
//  * Direct Radd* paints outside ui/theme: 118 lines, but all except
//    Settings were correctly scheme-independent — player chrome over
//    video (PlayerOnVideo / RaddHud* / RaddPlayer*, dark in both modes,
//    as on YouTube/Netflix players) and badges/placeholders over
//    artwork (documented dark-pill treatment, Color.kt §12). Home,
//    Search, My List, Downloads, Detail, Live TV and Browse use
//    colorScheme roles only (0 direct Radd* usages) and adapt for free.
//  * The ONE screen that broke in light was Settings: helper #A3ABA8
//    measured 1.75:1 on the light card fill, chip label #B3B3B3 1.57:1,
//    the card default fill was a fixed dark #1A1A1A slab, and the
//    Appearance header coral icon was 2.38:1 (below the 3:1 graphics
//    bar). All four are scheme roles now (onSurfaceVariant /
//    surfaceContainerHighest / primary); the fixed paints are deleted.
//  * The app stays dark-first (scheme.default_dark=true): light is
//    opt-in via the operator panel or the system setting, never the
//    default. No user-facing theme picker was added — mode selection
//    stays operator-controlled; that is a product decision for later,
//    not a rendering gap.
private val RaddLightScheme = lightColorScheme(
    primary = RaddCurrent700,               // 5.8:1 on paper; white label 6.2:1
    onPrimary = RaddLightOnPrimary,          // brand colors.light.on_primary
    primaryContainer = RaddCurrentContainerLight,
    onPrimaryContainer = RaddOnCurrentContainerLight,

    secondary = RaddCoral400,               // ink label on coral: 6.7:1
    onSecondary = RaddInkOnPaper,
    secondaryContainer = RaddCoralContainerDark,
    onSecondaryContainer = RaddOnCoralContainer,

    tertiary = RaddTideContainerDark,       // dark chip persists in light (badges rule)
    onTertiary = RaddOnTideContainer,       // 11.1:1
    tertiaryContainer = RaddTideContainerDark,
    onTertiaryContainer = RaddOnTideContainer,

    error = RaddDangerLight,                // 6.1:1 on paper
    onError = RaddLightOnPrimary,            // white label on the brand error red
    errorContainer = RaddDangerContainerLight,
    onErrorContainer = RaddOnDangerContainerLight,

    background = RaddPaperDay,
    onBackground = RaddInkOnPaper,          // 16.1:1
    surface = RaddPaperSurface,
    onSurface = RaddInkOnPaper,
    surfaceVariant = RaddPaperContainerHigh,
    onSurfaceVariant = RaddMistOnPaper,     // 5.4:1 vs its variant tier
    outline = RaddOutlineOnPaper,
    outlineVariant = RaddOutlineVarOnPaper,

    surfaceDim = RaddPaperContainerLow,
    surfaceBright = RaddLightSurfaceBright,
    surfaceContainerLowest = RaddLightSurfaceBright,
    surfaceContainerLow = RaddPaperContainerLow,
    surfaceContainer = RaddPaperContainer,
    surfaceContainerHigh = RaddPaperContainerHigh,
    surfaceContainerHighest = RaddPaperContainerHighest,
)

// ── SHAPES — "river stone" radii ────────────────────────────────────────────
// Geometry language: nothing is a hard rectangle and nothing is a circle.
// Posters share the medium radius so grid and hero read as one pebble family.
val RaddShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),   // posters, chips
    large = RoundedCornerShape(16.dp),    // cards, sheets top
    extraLarge = RoundedCornerShape(28.dp), // dialogs, hero container
)

// ── MOTION — timing & easing tokens (import once, use everywhere) ───────────
// M3 conventions: state changes 100–200ms, entrance/exit 250–400ms, brand/
// hero moves up to 600ms. Easing is asymmetric: exit accelerates, entrance
// decelerates on the "emphasized" curve so content settles like water.
@Immutable
object RaddMotion {
    /** Toggle/ripple/color crossfade. */
    const val DurationQuick = 120
    /** Chip selection, icon state, tab indicator. */
    const val DurationDefault = 250
    /** Screen-level entrance, sheet expand, poster→detail share. */
    const val DurationEmphasized = 400
    /**
     * Hero/fanfare only — first frame of DetailScreen, brand splash mark.
     * Session 154 (T-2): capped at 500ms — the skill never lets a
     * transition exceed about 500ms (was 600ms). Currently unused; the cap
     * keeps the token honest for the day it is used.
     */
    const val DurationSlow = 500

    /** Paired tween for standard property animation. */
    fun <T> quick(millis: Int = DurationQuick) = tween<T>(millis, easing = Standard)
    fun <T> default(millis: Int = DurationDefault) = tween<T>(millis, easing = EmphasizedDecelerate)
    fun <T> emphasized(millis: Int = DurationEmphasized) = tween<T>(millis, easing = EmphasizedDecelerate)

    /** General purpose in/out. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Entrance: fast start, long graceful settle. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Exit: gentle lean-in, brisk finish. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Full-duration brand moves only; expressive overshoot in the middle. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Linear-ish for continuous things (shimmer sweep). */
    val Linearish = CubicBezierEasing(0f, 0f, 1f, 1f)

    // ── EXPRESSIVE SPRINGS (M3 Expressive motion tokens) ────────────────
    // Spring physics, not fixed durations, for component motion — what moves
    // inside a screen. Token values are the M3 Expressive tokens: spatial
    // springs may overshoot (damping < 1) so a move settles with a little
    // life; effects springs (fades, colour) are critically damped (1.0) so
    // they never overshoot. Screen enter/exit transitions keep the legacy
    // tween durations above — the skill keeps those for transitions.
    // S7 (2027 Home fix) — WIRED 25 Nov 2026 (BOM bump): MotionScheme is
    // PUBLIC in material3 1.5.0 (Compose BOM 2026.11.01) and
    // RaddStreamTheme sets motionScheme = MotionScheme.expressive(), so M3
    // components use the expressive springs natively. The RaddMotion specs
    // below carry the same token values directly (0.6/800 fast, 0.8/380
    // default, 1.0/1600 effects) for call sites that animate outside a
    // MaterialTheme scope — same springs, same pixels, no double source.
    /** Fast move: small shape/bounds changes. */
    fun <T> fastSpatial() = spring<T>(dampingRatio = 0.6f, stiffness = 800f)

    /** Default move: rails arriving, cards settling, pager pages. */
    fun <T> defaultSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)

    /** Slow move: hero glow, large containers. */
    fun <T> slowSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 200f)

    /** Fast fade/colour: HUD showing, chip state. */
    fun <T> fastEffects() = spring<T>(dampingRatio = 1.0f, stiffness = 3800f)

    /** Default fade/colour: press scale, HUD hiding, selection. */
    fun <T> defaultEffects() = spring<T>(dampingRatio = 1.0f, stiffness = 1600f)

    /** Slow fade/colour: glow crossfades, large fades. */
    fun <T> slowEffects() = spring<T>(dampingRatio = 1.0f, stiffness = 800f)

    /** Respect Reduce-Motion: screens wrap durations with this. */
    const val FadeWhenReduced = 30 // ms, pure alpha crossfade, no movement
}

// ── SPACING GRID (4dp base; values are dp ints, not a theme type) ───────────
object RaddSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp   // standard page gutter
    val xl = 24.dp
    val xxl = 32.dp
    val gutter = 16.dp
    val railGap = 12.dp     // between poster cards in a row/grid (S6: shipped rails use 12dp)
    val sectionGap = 24.dp  // between home rails
    val thumbBarClearance = 24.dp // content padding under edge-to-edge nav
}

// ── DYNAMIC COLOR POLICY — decided, not defaulted ───────────────────────────
// Brand-locked (dynamicColor = false) is ON BY DEFAULT, and it is deliberate:
//  1. This app speaks a COLOR LANGUAGE: jade=stream/watch/healthy, indigo=
//     download, amber=print quality, red-family=dead/deprecated. Material You
//     would repaint `primary` from the wallpaper and silently break "jade
//     means playable" — the semantic scale in Color.kt survives dynamic mode
//     but users can no longer trust the *scheme* roles to mean anything.
//  2. The dark scheme's neutrals are tuned to artwork (teal-shifted near-black
//     behind posters); a wallpaper-derived neutral palette destroys the scrim
//     contrast math (Color.kt §10) that keeps overlaid badges AA.
//  3. Award-grade brand apps (the owner's brief) pin their identity and give
//     dynamism *around* it (wallpaper-tinted launcher icon via <monochrome>,
//     artwork-derived accents via rememberArtworkAccent) rather than *inside*
//     it. The artwork accent hook already delivers personalization per-title,
//     which is more meaningful than per-wallpaper.
// Escape hatch honored anyway: callers may pass dynamicColor = true on
// Android 12+ (Settings toggle can expose it; it only remaps the generic M3
// roles — every semantic Radd* token stays constant).
@Composable
fun RaddStreamTheme(
    // Both defaults come from brand.properties ([scheme]); a re-skin can flip
    // the app to light-first or allow Material You without touching any screen.
    darkTheme: Boolean = Brand.schemeDefaultDark,
    dynamicColor: Boolean = Brand.schemeDynamicColorDefault,
    // The operator's accent from the admin panel (null = brand default). It
    // repaints the `primary` roles only: the semantic Radd* tokens (jade =
    // playable, the link-health scale) stay constant, so an accent can never
    // silently re-meaning a colour the app uses as a signal.
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> RaddDarkScheme
        else -> RaddLightScheme
    }
    val colorScheme = if (accent == null) {
        baseScheme
    } else {
        baseScheme.copy(
            primary = accent,
            primaryContainer = mixColors(accent, baseScheme.primaryContainer),
            // A light accent needs dark ink on it, a dark accent white: the
            // same luminance rule the brand's own on-primary pair follows.
            onPrimary = accentOnPrimary(accent),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = RaddTypography,
        shapes = RaddShapes,
        // BOM bump 2026-11-25: the M3 Expressive motion contract — M3
        // components animate on these springs; RaddMotion carries the
        // same token values for custom animations.
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** Halfway between two colours, channel by channel — the accent's container. */
private fun mixColors(first: Color, second: Color): Color = Color(
    red = (first.red + second.red) / 2f,
    green = (first.green + second.green) / 2f,
    blue = (first.blue + second.blue) / 2f,
    alpha = (first.alpha + second.alpha) / 2f,
)
