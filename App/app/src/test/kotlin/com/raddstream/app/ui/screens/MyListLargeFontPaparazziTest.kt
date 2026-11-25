package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ListSort
import com.raddstream.app.viewmodel.MyListUiState
import org.junit.Rule
import org.junit.Test

/**
 * L5 (round 2): My List at 1.3x font scale on a small phone — the
 * configuration that truncated the bottom-nav "Settings" label (APP-09).
 * The count line (titleMedium + tabular numerals), the four filter chips
 * and the sort button all carry text that must not clip, overlap or push
 * the grid off the screen when the font grows. Two saves, one
 * half-watched, one downloaded (badge + "downloaded" in the spoken
 * sentence) — the shot judges the whole loaded header at large font.
 * Record with `:app:recordPaparazziDebug` (paparazzi.yml,
 * workflow_dispatch); verify runs as ci.yml's explicit
 * `:app:verifyPaparazziDebug` step. Same modes-on-testDebugUnitTest rule
 * as BottomNavLargeFontPaparazziTest: build.gradle.kts runs Paparazzi-only
 * when the invocation names a record/verify task, so no Robolectric class
 * loads first in the fork (paparazzi#1979).
 */
class MyListLargeFontPaparazziTest {

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

    private fun post(id: Int, title: String, contentType: String = "movie") = Post(
        id = id,
        title = title,
        cleanTitle = title,
        year = 2023,
        contentType = contentType,
        posterUrl = null,
    )

    @Test
    fun myListPopulatedLargeFont() {
        paparazzi.snapshot {
            RaddStreamTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) {
                        MyListLoadedContent(
                            state = MyListUiState(
                                favorites = listOf(
                                    post(1, "Jawan"),
                                    post(2, "Salaar: Part 1 – Ceasefire"),
                                ),
                                sort = ListSort.RECENT,
                                popular = listOf(post(3, "McLintock!")),
                                progressByPostId = mapOf(1 to 0.5f),
                                progressLabelByPostId = mapOf(1 to "S2:E5 · 23 min left"),
                                downloadedPostIds = setOf(2),
                            ),
                            onPostClick = {},
                            onRemove = {},
                            onSort = {},
                            isFavorite = { false },
                            onToggleFavorite = {},
                        )
                    }
                }
            }
        }
    }
}
