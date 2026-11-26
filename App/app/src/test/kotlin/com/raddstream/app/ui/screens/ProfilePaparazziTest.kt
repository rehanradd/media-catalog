package com.raddstream.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.AccountOverviewUiState
import org.junit.Rule
import org.junit.Test

/**
 * P1 goldens (Nov 2026): the Profile hub ("You") had no Paparazzi test —
 * the round-6 re-rate scored it 75 from code only, low confidence, and
 * excluded it from the mean. These shots render the real ProfileContent
 * with fake state (the Home pattern) so the next re-rate judges renders.
 *
 * The fixtures use fixed epoch dates (14 Nov 2023 created, 3 Dec 2025
 * last opened) so the membership lines never drift with the clock, and
 * `isTab = true` — the hub as shipped, a tab root with no back arrow.
 * Record with `:app:recordPaparazziDebug` (paparazzi.yml,
 * workflow_dispatch); verify runs as ci.yml's explicit
 * `:app:verifyPaparazziDebug` step. Same modes-on-testDebugUnitTest rule
 * as BottomNavLargeFontPaparazziTest: build.gradle.kts runs Paparazzi-only
 * when the invocation names a record/verify task, so no Robolectric class
 * loads first in the fork (paparazzi#1979).
 */
class ProfilePaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    /** Dark-first, like the shipped app: RaddStreamTheme defaults to the brand scheme. */
    @Composable
    private fun themed(content: @Composable () -> Unit) {
        RaddStreamTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    // Fixed dates, never "now": 14 Nov 2023 created, 3 Dec 2025 last opened.
    private val signedInVip = AccountOverviewUiState(
        signedIn = true,
        isVip = true,
        paywallVisible = true,
        createdAt = 1_700_000_000.0,
        lastSeenAt = 1_764_800_000.0,
    )

    @Test
    fun profileSignedIn() {
        paparazzi.snapshot {
            themed {
                ProfileContent(
                    state = signedInVip,
                    onBackClick = {},
                    onOpenDashboard = {},
                    onSignOut = {},
                    isTab = true,
                )
            }
        }
    }

    @Test
    fun profileSignedOut() {
        // The empty state: no account on this device yet — the "no name,
        // no phone" explainer instead of the membership card, and no
        // dashboard / sign-out doors. The hub rows still show (the VIP
        // row keys off the quality gate, not the sign-in — the gate
        // defaults on, so it is here too).
        paparazzi.snapshot {
            themed {
                ProfileContent(
                    state = AccountOverviewUiState(),
                    onBackClick = {},
                    onOpenDashboard = {},
                    onSignOut = {},
                    isTab = true,
                )
            }
        }
    }

    @Test
    fun profilePremiumGateOff() {
        // Free launch: the quality gate is OFF, so every viewer is
        // premium — the membership line says "Premium member", the VIP
        // row is gone (nothing to sell), and the dashboard + sign-out
        // doors stay.
        paparazzi.snapshot {
            themed {
                ProfileContent(
                    state = signedInVip.copy(isVip = false, paywallVisible = false),
                    onBackClick = {},
                    onOpenDashboard = {},
                    onSignOut = {},
                    isTab = true,
                )
            }
        }
    }
}
