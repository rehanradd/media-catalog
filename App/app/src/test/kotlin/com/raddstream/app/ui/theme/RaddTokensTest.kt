package com.raddstream.app.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RaddTokensTest — pins the benchmark §14 spec the token file promises, and
 * the Session 123 player-chrome fold that moved the HUD paints into brand
 * roles. Plain JUnit4 on the JVM: tokens are data, not composition.
 *
 * Deliberately no raw paint literals here: BrandDriftTest fails any file
 * outside Color.kt that writes one, and these assertions compare named values
 * against named values instead.
 */
class RaddTokensTest {

    @Test
    fun spacingIsTheEightPointGrid() {
        assertEquals(4.dp, RaddTokens.Space.Xs)
        // S6 (2027 Home fix): the two half-steps — 6dp hero-stack gaps
        // (D7) and 2dp hairlines — live as tokens too, never as literals.
        assertEquals(6.dp, RaddTokens.Space.Xxs)
        assertEquals(2.dp, RaddTokens.Space.Micro)
        assertEquals(8.dp, RaddTokens.Space.Sm)
        assertEquals(12.dp, RaddTokens.Space.Md)
        assertEquals(16.dp, RaddTokens.Space.Lg)
        assertEquals(20.dp, RaddTokens.Space.Step20)
        assertEquals(24.dp, RaddTokens.Space.Xl)
        assertEquals(32.dp, RaddTokens.Space.Xxl)
        assertEquals(48.dp, RaddTokens.Space.Step48)
        // S6: the shipped rails use a 12dp gap, not the 8dp the token
        // used to carry — the token now says what ships.
        assertEquals(12.dp, RaddTokens.Space.RailGap)
    }

    @Test
    fun radiiFollowTheSpec() {
        // Round 2 (Nov 2026): the brief's card radius is 16-20dp, and
        // the Poster token moved 8dp -> 16dp with it - the 8dp the old
        // assertion pinned was the pre-brief value.
        assertEquals(16.dp, RaddTokens.Radius.Poster)
        assertEquals(8.dp, RaddTokens.Radius.Thumb)
        // P2-2 (B-14): chips are pills — the 4dp token was the outlier
        // next to RaddChip's pill default; the token is 999dp now.
        assertEquals(999.dp, RaddTokens.Radius.Chip)
        assertEquals(12.dp, RaddTokens.Radius.Input)
        assertEquals(16.dp, RaddTokens.Radius.Card)
        assertEquals(20.dp, RaddTokens.Radius.Sheet)
        assertEquals(999.dp, RaddTokens.Radius.Pill)
    }

    @Test
    fun motionKeepsOnlyThePressScale() {
        // Session 154 (T-1): the duration/easing/spring duplicates were
        // deleted — they were unused and their values did not match the
        // skill. The live motion system is RaddMotion (Theme.kt); only
        // PressScale survives here.
        assertEquals(0.97f, RaddTokens.Motion.PressScale, 0.001f)
    }

    @Test
    fun navIndicatorKeepsTheExpressivePillSizes() {
        // Density-nav (2026-11-15): the 56x32dp pill and 64dp tab are the
        // M3 Expressive sizes. The Session 154 (N-2) pin (64x48 pill, 80dp
        // tab) is superseded: it treated the pill as the touch target, but
        // the pill is visual only — the TAB is the touch target (64dp,
        // above the 48dp floor, pinned by BottomNavTouchTargetTest). Visual
        // size is not touch size; a 32dp-tall pill inside a 64dp tab is a
        // 64dp target.
        // H3 (2027 Home fix): the pill is 64x32dp now — the brief's 64x32
        // spring pill (was 56dp). The tab stays 64dp.
        assertEquals(64.dp, RaddTokens.Touch.NavIndicatorWidth)
        assertEquals(32.dp, RaddTokens.Touch.NavIndicatorHeight)
        assertEquals(64.dp, RaddTokens.Touch.NavTabHeight)
    }

    @Test
    fun slowDurationStaysInsideTheFiveHundredMillisecondCap() {
        // Session 154 (T-2): no transition may exceed about 500ms.
        assertTrue(RaddMotion.DurationSlow <= 500)
    }

    @Test
    fun touchTargetIsFortyEight() {
        assertEquals(48.dp, RaddTokens.Touch.MinTarget)
        assertEquals(8.dp, RaddTokens.Touch.MinGap)
    }

    @Test
    fun semanticColoursAreTheBrandRoles() {
        assertEquals(RaddCurrent500, RaddTokens.Color.Action)
        assertEquals(AccentOrange, RaddTokens.Color.PlayerAccent)
        assertEquals(RaddCoral400, RaddTokens.Color.Favourite)
        assertEquals(RaddTideIndigo, RaddTokens.Color.Download)
        assertEquals(RaddSignalGold, RaddTokens.Color.Signal)
        assertEquals(RaddSuccess, RaddTokens.Color.Success)
        assertEquals(RaddDanger, RaddTokens.Color.Danger)
        assertEquals(RaddWarning, RaddTokens.Color.Warning)
    }

    @Test
    fun playerChromeFoldKeepsThePlatesOnTheInkRamp() {
        assertEquals(RaddInk900, RaddPlayerStage)
        assertEquals(RaddInk850, RaddPlayerSheet)
        assertEquals(RaddInk800, RaddPlayerDialog)
        assertEquals(RaddSuccess, RaddNarrationCheck)
        assertEquals(RaddInk800.copy(alpha = 0.8f), RaddNarrationPlate)
        assertEquals(RaddInk800.copy(alpha = 0.9f), RaddPlayerSnackbarPlate)
        assertEquals(AccentOrange.copy(alpha = 0.8f), AccentOrangeToast)
    }

    @Test
    fun keepChipStatesAreMeaningColoursAtFortyPercent() {
        assertEquals(RaddSuccess.copy(alpha = 0.4f), RaddKeepStored)
        assertEquals(RaddDanger.copy(alpha = 0.4f), RaddKeepBlocked)
        assertEquals(RaddTideIndigo.copy(alpha = 0.4f), RaddKeepKeeping)
    }

    @Test
    fun typeAliasesAreTheShippedScale() {
        assertEquals(RaddTypography.displayLarge, RaddTokens.Type.Display)
        assertEquals(RaddTypography.headlineMedium, RaddTokens.Type.Headline)
        assertEquals(RaddTypography.titleMedium, RaddTokens.Type.Title)
        assertEquals(RaddTypography.bodyMedium, RaddTokens.Type.Body)
        assertEquals(RaddTypography.bodySmall, RaddTokens.Type.BodySmall)
        assertEquals(RaddTypography.labelSmall, RaddTokens.Type.Caption)
    }
}
