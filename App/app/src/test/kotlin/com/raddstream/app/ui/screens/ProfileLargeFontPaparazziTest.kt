package com.raddstream.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.AccountOverviewUiState
import org.junit.Rule
import org.junit.Test

/**
 * P1 goldens (Nov 2026): the Profile hub at 1.3x font scale on a small
 * phone — the configuration that truncated the bottom-nav "Settings"
 * label (APP-09). The membership line (headlineSmall bold), the two
 * date lines and the six hub rows all carry text that must not clip,
 * overlap or push the sign-out button off the screen when the font
 * grows. ONE Paparazzi rule per class: a second @get:Rule in the same
 * class crashes every test, so the 1.3x shot lives here and the 1.0x
 * shots live in ProfilePaparazziTest. Record with
 * `:app:recordPaparazziDebug` (paparazzi.yml, workflow_dispatch);
 * verify runs as ci.yml's explicit `:app:verifyPaparazziDebug` step.
 */
class ProfileLargeFontPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1280,
            screenWidth = 720,
            density = Density.XHIGH,
            xdpi = 320,
            ydpi = 320,
            fontScale = 1.3f,
        ),
    )

    /** Dark-first, like the shipped app: RaddStreamTheme defaults to the brand scheme. */
    @Composable
    private fun themed(content: @Composable () -> Unit) {
        RaddStreamTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    @Test
    fun profileSignedInLargeFont() {
        paparazzi.snapshot {
            themed {
                ProfileContent(
                    state = AccountOverviewUiState(
                        signedIn = true,
                        isVip = true,
                        paywallVisible = true,
                        createdAt = 1_700_000_000.0,
                        lastSeenAt = 1_764_800_000.0,
                    ),
                    onBackClick = {},
                    onOpenDashboard = {},
                    onSignOut = {},
                    isTab = true,
                )
            }
        }
    }
}
