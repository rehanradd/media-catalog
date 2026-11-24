package com.raddstream.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test

/**
 * APP-09 (mobile-tester run 4, 00:26 PKT 24 Nov): the bottom-nav
 * "Settings" label truncated to "Settin..." at 1.3x font scale on a small
 * phone. This shot renders the real bar (RaddBottomBar + BottomNavTab) at
 * 360dp wide with fontScale 1.3 — the configuration that truncated — so a
 * regression shows up as a changed golden. Icons are the material set the
 * touch-target test uses, not the brand vectors: the label layout is what
 * this shot judges, and the brand vectors are proven only under
 * Robolectric. Record with `:app:recordPaparazziDebug` (paparazzi.yml,
 * workflow_dispatch); verify runs as ci.yml's explicit
 * `:app:verifyPaparazziDebug` step. Both are modes on testDebugUnitTest
 * in Paparazzi 2.x (not separate tasks): build.gradle.kts runs
 * Paparazzi-only when the invocation names a record/verify task, and
 * excludes the Paparazzi classes from the plain suite run, so no
 * Robolectric class loads first in the fork (paparazzi#1979).
 */
class BottomNavLargeFontPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1280,
            screenWidth = 720,
            // Paparazzi's density is com.android.resources.Density (the
            // enum), not Compose's Density — XHIGH is 320dpi, so 720px
            // renders 360dp wide, the width that truncated (CI 45316305076:
            // Density(2f, 2f) did not resolve, and the missing dp import
            // hid beside it; CI 45321135048: the enum spells it XHIGH,
            // not XHDPI).
            density = Density.XHIGH,
            xdpi = 320,
            ydpi = 320,
            fontScale = 1.3f,
        ),
    )

    private val items: List<Pair<String, ImageVector>> = listOf(
        "Home" to Icons.Default.Home,
        "Search" to Icons.Default.Search,
        "Live TV" to Icons.Default.Tv,
        "My List" to Icons.Default.Favorite,
        "Settings" to Icons.Default.Settings,
    )

    @Test
    fun bottomNavLargeFont() {
        paparazzi.snapshot {
            RaddStreamTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RaddBottomBar(modifier = Modifier.windowInsetsPadding(WindowInsets(bottom = 0.dp))) {
                        items.forEach { (label, icon) ->
                            BottomNavTab(
                                label = label,
                                icon = icon,
                                selected = label == "Home",
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }
    }
}
