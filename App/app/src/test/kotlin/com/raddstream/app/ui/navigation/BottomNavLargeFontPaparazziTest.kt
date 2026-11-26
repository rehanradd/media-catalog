package com.raddstream.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.raddstream.app.R
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Rule
import org.junit.Test

/**
 * APP-09 (mobile-tester run 4, 00:26 PKT 24 Nov): the bottom-nav
 * "Settings" label truncated to "Settin..." at 1.3x font scale on a small
 * phone. This shot renders the real bar (RaddBottomBar + BottomNavTab) at
 * 360dp wide with fontScale 1.3 — the configuration that truncated — so a
 * regression shows up as a changed golden. Record with
 * `:app:recordPaparazziDebug` (paparazzi.yml, workflow_dispatch); verify
 * runs as ci.yml's explicit `:app:verifyPaparazziDebug` step. Both are
 * modes on testDebugUnitTest in Paparazzi 2.x (not separate tasks):
 * build.gradle.kts runs Paparazzi-only when the invocation names a
 * record/verify task, and excludes the Paparazzi classes from the plain
 * suite run, so no Robolectric class loads first in the fork
 * (paparazzi#1979).
 *
 * P1 goldens (Nov 2026): the items come from [PanelTabs.DEFAULT], not a
 * hand-written list. The old list still named the retired 5-tab set
 * (Home/Search/Live TV/My List/Settings) — the golden proved a bar the
 * app no longer ships, and the new 4-tab set (Home · Search · Downloads ·
 * Profile) was code-proven only. The id -> label/icon mapping below is
 * the same one NavGraph's bottomNavItems uses; only the SET comes from
 * the lock, so a future set change re-renders this golden instead of
 * silently testing a stale bar. The icons are the house stroke vectors
 * the shipped bar wears, not the Material set the touch-target test
 * uses: the label layout is what this shot judges, and the brand
 * vectors are proven only under Robolectric — but the golden now shows
 * the real bar's glyphs at 1.3x, so a glyph that crowds its label at
 * large font shows up here too.
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

    // The tab set is PanelTabs.DEFAULT — the id -> (label, icon,
    // selectedIcon) mapping is NavGraph's bottomNavItems mapping, kept
    // beside the test so the golden renders exactly what the app ships.
    // Downloads and Profile have no filled tab variants, so they wear
    // their house action glyphs in both states (same as NavGraph).
    private fun tabItem(id: String): Triple<String, ImageVector, ImageVector> = when (id) {
        "search" -> Triple(
            "Search",
            ImageVector.vectorResource(R.drawable.ic_tab_search),
            ImageVector.vectorResource(R.drawable.ic_tab_search_filled),
        )
        "downloads" -> Triple(
            "Downloads",
            ImageVector.vectorResource(R.drawable.ic_action_download),
            ImageVector.vectorResource(R.drawable.ic_action_download),
        )
        "profile" -> Triple(
            "Profile",
            ImageVector.vectorResource(R.drawable.ic_action_user),
            ImageVector.vectorResource(R.drawable.ic_action_user),
        )
        else -> Triple(
            "Home",
            ImageVector.vectorResource(R.drawable.ic_tab_home),
            ImageVector.vectorResource(R.drawable.ic_tab_home_filled),
        )
    }

    @Test
    fun bottomNavLargeFont() {
        val items = PanelTabs.DEFAULT.map(::tabItem)
        paparazzi.snapshot {
            RaddStreamTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RaddBottomBar(modifier = Modifier.windowInsetsPadding(WindowInsets(bottom = 0.dp))) {
                        items.forEach { (label, icon, selectedIcon) ->
                            BottomNavTab(
                                label = label,
                                icon = icon,
                                selectedIcon = selectedIcon,
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
