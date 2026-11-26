package com.raddstream.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * RaddTokens — the one token file is the law (design benchmark §14/§15, rule 1).
 *
 * Colour, type, spacing, radius, shadow, motion — every named value a screen
 * needs lives here, and screens read tokens, never literals. What already had
 * a home stays there and is aliased, not duplicated: [RaddSpacing] owns the
 * 4dp grid, [RaddMotion] owns the shipped durations and easings, [RaddShapes]
 * owns the river-stone radii, [RaddTypography] owns the type scale, and
 * Color.kt owns every paint. This file is the single entry point that gathers
 * them into the benchmark's spec, and the place the two gaps in those systems
 * are filled: the 20dp and 48dp steps the grid was missing, and the benchmark
 * durations (200/320ms) beside the shipped ones (250/600ms).
 *
 * Nothing here invents a paint or a size: every colour is a named Color.kt
 * value, every duration is the benchmark §14 number, every radius is the
 * §14 spec. A new screen that needs a value not listed here adds the token
 * here first — a literal in a screen is a token this file forgot.
 */
object RaddTokens {

    /** Spacing — the 8pt grid (benchmark §14: 4, 8, 12, 16, 20, 24, 32, 48). */
    object Space {
        val Xs: Dp = RaddSpacing.xs
        /** Half-step 6dp: the hero-stack gaps (D7) — below the 8dp grid step. */
        val Xxs: Dp = 6.dp
        /** Half-step 2dp: hairline gaps and thin dividers. */
        val Micro: Dp = 2.dp
        val Sm: Dp = RaddSpacing.sm
        val Md: Dp = RaddSpacing.md
        val Lg: Dp = RaddSpacing.lg
        val Xl: Dp = RaddSpacing.xl
        val Xxl: Dp = RaddSpacing.xxl
        /** The 20dp step the grid skipped: card inner padding, rail insets. */
        val Step20: Dp = 20.dp
        /** The 48dp step the grid skipped: section breaks, sheet top gaps. */
        val Step48: Dp = 48.dp
        /** Standard page gutter. */
        val Gutter: Dp = RaddSpacing.gutter
        /** The 34dp clearance the brief requires above the bottom bar (L5). */
        val BottomClearance: Dp = 34.dp
        /** Between poster cards in a row or grid. */
        val RailGap: Dp = RaddSpacing.railGap
        /** Between home rails. */
        val SectionGap: Dp = RaddSpacing.sectionGap
    }

    /** Radii — one radius language (benchmark §14). */
    object Radius {
        /**
         * Posters: 16 (brief's 16-20dp card radius, round 2 - was 8dp.
         * The 10dp/12dp the plan called unresolved was the token (8dp)
         * against the Material medium shape (12dp); the brief decides
         * it: 16dp, the same radius as [Card].)
         */
        val Poster: Dp = 16.dp
        /** Thumbnails: 6-8. */
        val Thumb: Dp = 8.dp
        /** Chips: fully round — one chip geometry, the same pill
         * RaddChip ships (P2-2, B-14: the 4dp token was the outlier
         * next to pills; a Chip shape built from it is a pill now). */
        val Chip: Dp = 999.dp
        /** Inputs and buttons: 8-12. */
        val Input: Dp = 12.dp
        /** Cards: 12-16. */
        val Card: Dp = 16.dp
        /** Sheet tops: 20 (M3 large increased). */
        val Sheet: Dp = 20.dp
        /** Pills: fully round. */
        val Pill: Dp = 999.dp
    }

    /** Shapes built from the radius tokens, for the call sites that take one. */
    object Shape {
        val Poster: RoundedCornerShape = RoundedCornerShape(Radius.Poster)
        val Thumb: RoundedCornerShape = RoundedCornerShape(Radius.Thumb)
        val Chip: RoundedCornerShape = RoundedCornerShape(Radius.Chip)
        val Input: RoundedCornerShape = RoundedCornerShape(Radius.Input)
        val Card: RoundedCornerShape = RoundedCornerShape(Radius.Card)
        val Sheet: RoundedCornerShape = RoundedCornerShape(Radius.Sheet)
        val Pill: RoundedCornerShape = RoundedCornerShape(Radius.Pill)
    }

    /**
     * Motion — press feedback only (benchmark §14). Session 154 (T-1): the
     * duration/easing/spring duplicates lived here unused and their values
     * did not match the skill — the live system is [RaddMotion] (Theme.kt).
     * Only [PressScale] is used, by [pressScale].
     */
    object Motion {
        /** Press scale: 0.97-0.98, under 100ms, with haptic confirmation. */
        const val PressScale: Float = 0.97f
    }

    /** Touch — 48x48dp minimum target, 8pt minimum gap (benchmark §14/§15). */
    object Touch {
        val MinTarget: Dp = 48.dp
        val MinGap: Dp = 8.dp

        /**
         * Bottom-nav active-indicator pill, 64×32dp (S6/H3, Nov 2026 —
         * the brief's 64×32 spring pill; was 56dp). M3 Expressive's spec
         * pill on a 64dp bar. The pill is VISUAL only: the tab itself is
         * the touch target at 64dp, still above the 48dp floor, so the
         * pill no longer needs to clear the floor on its own. Robo runs
         * 4–6 measured the 32dp pill under the floor, but run 6 proved
         * the measured node is the tab, not the pill — and the pill
         * carries no click action. A Robo re-run on a 3-button-nav device
         * is still the verification owed for this change (see
         * BottomNavTouchTargetTest).
         */
        val NavIndicatorWidth: Dp = 64.dp
        val NavIndicatorHeight: Dp = 32.dp

        /**
         * Bottom-nav tab height, 64dp (density, Nov 2026 — M3 Expressive
         * ContainerHeight; was 80dp). The bar sizes to this content
         * height PLUS the system nav-bars inset — RaddBottomBar applies
         * the inset inside its Row exactly as NavigationBar did — so the
         * tabs keep their full 64dp even on a 3-button-nav device (Robo
         * run 6 lesson: never force the bar to an exact total height).
         */
        val NavTabHeight: Dp = 64.dp
    }

    /**
     * Elevation — the benchmark's shadows translated to Compose elevation.
     * Dialog 24, card 8, sheet 16: depth on purpose, never nested glass, and
     * a solid fallback for low-end devices (the scheme's surface tiers carry
     * the same lift without blur).
     */
    object Elevation {
        val Card: Dp = 8.dp
        val Sheet: Dp = 16.dp
        val Dialog: Dp = 24.dp
    }

    /**
     * Semantic colours — one colour, one meaning (benchmark §15 rule 4).
     * Every alias is a named Color.kt value; nothing here holds a hex.
     */
    object Color {
        /** The single action colour: primary actions, active states, progress. */
        val Action = RaddCurrent500
        /** The player's meaning accent: selected rung, live speed, buffering. */
        val PlayerAccent = AccentOrange
        /** Favourites. */
        val Favourite = RaddCoral400
        /** Download and offline. */
        val Download = RaddTideIndigo
        /** Ratings and print badges. */
        val Signal = RaddSignalGold
        /** Healthy, playable, "that worked". */
        val Success = RaddSuccess
        /** Dead, failed, refused. */
        val Danger = RaddDanger
        /** Slow, expiring, "check this". */
        val Warning = RaddWarning
        /** Canvas and raised ink, darkest first. */
        val InkVoid = RaddInkVoid
        val Ink950 = RaddInk950
        val Ink900 = RaddInk900
        val Ink850 = RaddInk850
        val Ink800 = RaddInk800
        val Ink750 = RaddInk750
        /** Primary text on ink. */
        val TextPrimary = RaddPaperOnInk
        /** Secondary text on ink. */
        val TextSecondary = RaddMistOnInk
        /** Muted text on ink. */
        val TextMuted = RaddWhisperOnInk
    }

    /**
     * Type — the benchmark §14 scale, read off the shipped typography so the
     * scale stays single-sourced in Type.kt. Display for heroes, headline for
     * screen titles and section heads, title for card titles, body for prose.
     */
    object Type {
        val Display: TextStyle = RaddTypography.displayLarge
        val Headline: TextStyle = RaddTypography.headlineMedium
        val Title: TextStyle = RaddTypography.titleMedium
        val Body: TextStyle = RaddTypography.bodyMedium
        val BodySmall: TextStyle = RaddTypography.bodySmall
        val Caption: TextStyle = RaddTypography.labelSmall
    }
}
