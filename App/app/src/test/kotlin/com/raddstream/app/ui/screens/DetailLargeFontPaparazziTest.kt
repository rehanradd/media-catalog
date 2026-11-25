package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.EpisodeGroup
import com.raddstream.app.viewmodel.EpisodeProgress
import com.raddstream.app.viewmodel.RankedLink
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.ResumePoint
import com.raddstream.app.viewmodel.SeasonGroup
import org.junit.Rule
import org.junit.Test

/**
 * DT-5 / S2 (round 2, Nov 2026): the Detail page at 1.3x font scale —
 * the configuration that truncated the bottom-nav "Settings" label
 * (BottomNavLargeFontPaparazziTest). The hero meta line (year ·
 * duration · quality), the Play / Download buttons and the episode
 * row are what this shot judges: a 1.3x title, meta or button label
 * must wrap or ellipsize cleanly, never clip or overlap. The episode
 * row is shot on its own too — it lives below the fold of the full
 * page, so the full-page shot never reaches it. Record with
 * `:app:recordPaparazziDebug` (paparazzi.yml, workflow_dispatch);
 * verify runs as ci.yml's explicit `:app:verifyPaparazziDebug` step.
 */
class DetailLargeFontPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1280,
            screenWidth = 720,
            // Paparazzi's density is com.android.resources.Density (the
            // enum), not Compose's Density — XHIGH is 320dpi, so 720px
            // renders 360dp wide, the narrow width that truncates.
            density = Density.XHIGH,
            xdpi = 320,
            ydpi = 320,
            fontScale = 1.3f,
        ),
    )

    private fun fakePrint(releaseId: Int, releaseTitle: String, quality: String?, season: Int? = null) =
        RankedPrint(
            releaseId = releaseId,
            rawTitle = "$quality copy",
            descriptor = ReleaseLabelParsing.describe(releaseTitle = releaseTitle, quality = quality),
            languages = listOf("Hindi"),
            sizeBytes = 1_400_000_000L,
            coverage = CoverageType.WHOLE_WORK,
            season = season,
            episodeFrom = null,
            episodeTo = null,
            // One healthy, directly playable link: a print with no links renders
            // as "Not available to stream yet", which is the download-only
            // state — the shot must judge the Play state real viewers see.
            links = listOf(
                RankedLink(
                    streamId = "h-$releaseId",
                    linkId = releaseId,
                    health = LinkState.ACTIVE,
                    ephemeral = false,
                    resumable = true,
                    playUrl = "http://example.invalid/play/$releaseId",
                    label = "Fast direct",
                    kind = "direct",
                    playability = Playability.STREAM,
                    score = 100,
                ),
            ),
        )

    private val seriesDetail = PostDetail(
        id = 2,
        title = "Money Heist",
        cleanTitle = "Money Heist",
        year = 2017,
        contentType = "series",
        posterUrl = null,
        backdropUrls = null,
        imdbRating = "8.2",
        genres = "Crime, Drama",
        synopsis = "A criminal mastermind known as The Professor plans the biggest heist in recorded history.",
        castNames = "Úrsula Corberó, Álvaro Morte, Pedro Alonso",
        industry = "Hollywood",
        language = "English",
        runtimeMinutes = 45,
    )

    private val seriesSeasons = listOf(
        SeasonGroup(
            number = 1,
            episodes = listOf(
                EpisodeGroup(
                    season = 1,
                    numberWithinSeason = 1,
                    absoluteNumber = 1,
                    title = "Part 1",
                    prints = listOf(
                        fakePrint(201, "Money Heist S01E01 1080p WEB-DL", "1080p", season = 1),
                        fakePrint(202, "Money Heist S01E01 720p WEB-DL", "720p", season = 1),
                    ),
                ),
                EpisodeGroup(
                    season = 1,
                    numberWithinSeason = 2,
                    absoluteNumber = 2,
                    title = "Part 2",
                    prints = listOf(
                        fakePrint(203, "Money Heist S01E02 1080p WEB-DL", "1080p", season = 1),
                    ),
                ),
            ),
            packedPrints = emptyList(),
        ),
        SeasonGroup(
            number = 2,
            episodes = listOf(
                EpisodeGroup(
                    season = 2,
                    numberWithinSeason = 1,
                    absoluteNumber = 3,
                    title = "Part 3",
                    prints = listOf(
                        fakePrint(205, "Money Heist S02E01 1080p WEB-DL", "1080p", season = 2),
                    ),
                ),
            ),
            packedPrints = emptyList(),
        ),
    )

    @Test
    fun detailLargeFont() {
        paparazzi.snapshot {
            RaddStreamTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DetailContent(
                        post = seriesDetail,
                        seasons = seriesSeasons,
                        workPrints = emptyList(),
                        capabilities = listOf("Hindi audio"),
                        isSeries = true,
                        resume = ResumePoint(season = 1, episode = 2, positionMs = 1_200_000L, durationMs = 3_000_000L),
                        includedPill = "Free · up to 720p",
                        onOpenSeason = {},
                        // Resume names the episode and the time left; the
                        // quality lives in the Video row below it, never
                        // in the button.
                        playLabel = "Resume S1:E2 · 30 min left",
                        playEnabled = true,
                        videoQualityText = "Auto · up to 720p",
                        downloadLabel = "Download S1:E2 · 720p · 1.3 GB",
                        downloadEnabled = true,
                        episodeProgress = mapOf(
                            (1 to 1) to EpisodeProgress(fraction = 1f, watched = true, remainingLabel = null),
                        ),
                        episodeRowSeason = 1,
                        nextEpisode = 1 to 2,
                        similarTitles = emptyList(),
                        reloadError = null,
                        onRetryReload = {},
                        isFavorite = true,
                        onToggleFavorite = {},
                        onShare = {},
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
    }

    @Test
    fun detailEpisodeRowLargeFont() {
        paparazzi.snapshot {
            RaddStreamTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        EpisodeRowSection(
                            seasons = seriesSeasons,
                            selectedSeason = 1,
                            episodeProgress = mapOf(
                                (1 to 1) to EpisodeProgress(fraction = 1f, watched = true, remainingLabel = null),
                            ),
                            nextEpisode = 1 to 2,
                            onSelectSeason = {},
                            onPlayEpisode = { _, _ -> },
                            onDownloadEpisode = { _, _ -> },
                        )
                    }
                }
            }
        }
    }
}
