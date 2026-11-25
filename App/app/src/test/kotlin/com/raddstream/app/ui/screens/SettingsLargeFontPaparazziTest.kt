package com.raddstream.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.raddstream.app.data.RemoteFeatures
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.SettingsUiState
import org.junit.Rule
import org.junit.Test

/**
 * Settings round 2 (2027): the whole screen at 1.3x font scale — the
 * configuration that truncated the bottom-nav label (APP-09) — so a
 * regression in the new 16sp/14sp/12sp hierarchy shows up as a changed
 * golden. Same DeviceConfig as BottomNavLargeFontPaparazziTest:
 * 360dp wide, fontScale 1.3. The Kids Lock card arrives as an empty slot
 * (it needs a real ViewModel; see PaparazziPreviewTest.settingsScreen).
 * Record with `:app:recordPaparazziDebug`; verify runs as ci.yml's explicit
 * `:app:verifyPaparazziDebug` step.
 */
class SettingsLargeFontPaparazziTest {

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

    @Test
    fun settingsLargeFont() {
        paparazzi.snapshot {
            RaddStreamTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalInspectionMode provides true) {
                        SettingsContent(
                            state = SettingsUiState(),
                            features = RemoteFeatures(),
                            actions = SettingsActions(),
                            searchQuery = "",
                            onSearchQueryChange = {},
                            kidsLockCard = {},
                            profileLinkDialog = {},
                            onOpenDownloads = {},
                            onOpenVipUpgrade = {},
                            onOpenRegionChoice = {},
                            onOpenDashboard = {},
                            onOpenProfile = {},
                            onOpenHelp = {},
                            onOpenLanguages = {},
                            onOpenWatchHistory = {},
                            onOpenResolveTest = {},
                            onOpenPressTest = {},
                            onOpenDiagnostics = {},
                            showDiagnostics = false,
                            scrollToContact = false,
                        )
                    }
                }
            }
        }
    }
}
