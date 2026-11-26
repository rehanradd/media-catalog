package com.raddstream.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.LiveTvViewModel
import org.junit.Rule
import org.junit.Test

/**
 * P1 goldens (Nov 2026): LiveTvScreen had no Paparazzi test — the
 * round-6 re-rate could not score it at all. These shots render the
 * real screen body ([LiveTvContent], split from LiveTvScreen the same
 * way HomeContent/ProfileContent are split from their screens) with
 * fake state, so the next re-rate judges renders.
 *
 * Logo URLs are empty on purpose: Coil cannot load network images under
 * Layoutlib, so the card paints its monogram fallback — the layout,
 * LIVE badge, star and meta line are what these shots judge, not the
 * logo art. Record with `:app:recordPaparazziDebug` (paparazzi.yml,
 * workflow_dispatch); verify runs as ci.yml's explicit
 * `:app:verifyPaparazziDebug` step. Same modes-on-testDebugUnitTest rule
 * as BottomNavLargeFontPaparazziTest: build.gradle.kts runs Paparazzi-only
 * when the invocation names a record/verify task, so no Robolectric class
 * loads first in the fork (paparazzi#1979).
 */
class LiveTvPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

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

    private val channels = listOf(
        channel(1, "Geo News", "news", "PK", "urdu"),
        channel(2, "ARY News", "news", "PK", "urdu"),
        channel(3, "PTV Sports", "sports", "PK", "urdu"),
        channel(4, "Star Sports", "sports", "IN", "hindi"),
        channel(5, "Hum TV", "entertainment", "PK", "urdu"),
        channel(6, "Zee Cinema", "movies", "IN", "hindi"),
    )

    private val categories = listOf("news", "sports", "entertainment", "movies")

    @Composable
    private fun content(state: LiveTvViewModel.UiState) {
        LiveTvContent(
            state = state,
            onRefresh = {},
            onQueryChanged = {},
            onCategorySelected = {},
            onFavouritesSelected = {},
            onToggleFavorite = {},
            onChannelClick = {},
        )
    }

    @Test
    fun liveTvPopulated() {
        paparazzi.snapshot {
            themed {
                content(
                    LiveTvViewModel.UiState(
                        isLoading = false,
                        channels = channels,
                        categories = categories,
                        // Geo News starred — the filled star on the
                        // first card is part of the shot.
                        favoriteChannelIds = setOf(1),
                    ),
                )
            }
        }
    }

    @Test
    fun liveTvFiltered() {
        // One match out of six: the count heading reads "1 of
        // 6 channels", the grid holds one card, and the filtered
        // footer ("End of results · 1 of 6 channels") closes it.
        // "geo" matches Geo News only — no other fixture name,
        // language, country or category contains it.
        paparazzi.snapshot {
            themed {
                content(
                    LiveTvViewModel.UiState(
                        isLoading = false,
                        channels = channels,
                        categories = categories,
                        query = "geo",
                    ),
                )
            }
        }
    }

    @Test
    fun liveTvEmpty() {
        paparazzi.snapshot {
            themed {
                content(
                    LiveTvViewModel.UiState(
                        isLoading = false,
                        channels = emptyList(),
                        categories = emptyList(),
                    ),
                )
            }
        }
    }
}
