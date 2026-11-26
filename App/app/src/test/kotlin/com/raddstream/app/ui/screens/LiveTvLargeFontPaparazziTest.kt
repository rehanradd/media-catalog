package com.raddstream.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.LiveTvViewModel
import org.junit.Rule
import org.junit.Test

/**
 * P1 goldens (Nov 2026): the Live TV grid at 1.3x font scale on a small
 * phone — the configuration that truncated the bottom-nav "Settings"
 * label (APP-09). The count heading, the filter pill, the category
 * chips and the card name + two-line meta must not clip, overlap or
 * push the grid off the screen when the font grows. ONE Paparazzi rule
 * per class: a second @get:Rule in the same class crashes every test,
 * so the 1.3x shot lives here and the 1.0x shots live in
 * LiveTvPaparazziTest. Record with `:app:recordPaparazziDebug`
 * (paparazzi.yml, workflow_dispatch); verify runs as ci.yml's explicit
 * `:app:verifyPaparazziDebug` step.
 */
class LiveTvLargeFontPaparazziTest {

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

    private fun channel(
        id: Int,
        name: String,
        category: String,
        country: String,
        language: String,
    ) = LiveTvChannel(
        id = id,
        name = name,
        category = category,
        country = country,
        language = language,
        logoUrl = "",
        streamId = "live-$id",
    )

    @Test
    fun liveTvPopulatedLargeFont() {
        paparazzi.snapshot {
            themed {
                LiveTvContent(
                    state = LiveTvViewModel.UiState(
                        isLoading = false,
                        channels = listOf(
                            channel(1, "Geo News", "news", "PK", "urdu"),
                            channel(2, "ARY News", "news", "PK", "urdu"),
                            channel(3, "PTV Sports", "sports", "PK", "urdu"),
                            channel(4, "Star Sports", "sports", "IN", "hindi"),
                            channel(5, "Hum TV", "entertainment", "PK", "urdu"),
                            channel(6, "Zee Cinema", "movies", "IN", "hindi"),
                        ),
                        categories = listOf("news", "sports", "entertainment", "movies"),
                        favoriteChannelIds = setOf(1),
                    ),
                    onRefresh = {},
                    onQueryChanged = {},
                    onCategorySelected = {},
                    onFavouritesSelected = {},
                    onToggleFavorite = {},
                    onChannelClick = {},
                )
            }
        }
    }
}
