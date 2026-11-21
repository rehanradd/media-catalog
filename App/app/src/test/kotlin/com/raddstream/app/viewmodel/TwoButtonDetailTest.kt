package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.LadderAuto
import com.raddstream.app.network.models.LadderLink
import com.raddstream.app.network.models.LadderTier
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.OptionGroup
import com.raddstream.app.network.models.Playability
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import com.raddstream.app.player.StreamCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2-button detail (Nov 2026): the pure decision layer behind the Play /
 * Download redesign — sheet rows, the recommended row, the per-episode
 * ladder (the P1 fix), the next episode to watch, and the free-space
 * gate. Pure, so every branch pins here without a ViewModel. The
 * screen only paints what these functions decide, and the ViewModel
 * only calls them — so a row this file pins cannot be re-decided
 * somewhere else on the page.
 */
class TwoButtonDetailTest {

    private fun option(
        band: String,
        title: String = band,
        streamId: String = "h-$band",
        sizeBytes: Long? = null,
        copies: Int = 1,
        playsHere: Boolean = true,
        isProviderPage: Boolean = false,
        streamCategory: StreamCategory? = null,
    ) = QualityOption(
        band = band,
        title = title,
        streamId = streamId,
        playUrl = "http://example.invalid/play/$band",
        sizeBytes = sizeBytes,
        backupHandles = emptyList(),
        playsHere = playsHere,
        resumable = true,
        copies = copies,
        isProviderPage = isProviderPage,
        streamCategory = streamCategory,
    )

    private fun ladder(autoBand: String? = "720p") = LadderForScope(
        options = listOf(
            option("1080p", "1080p Full HD", sizeBytes = 1_073_741_824L, copies = 3),
            option("720p", "720p HD"),
            option(
                "480p",
                "480p",
                sizeBytes = 800_000_000L,
                streamCategory = StreamCategory(StreamCategory.Kind.INSTANT_STREAM, "M3U8"),
            ),
        ),
        autoBand = autoBand,
        downloadOnlyBands = emptyList(),
    )

    @Test
    fun bandDisplayNameIsNumberPlusPlainWord() {
        assertEquals("4K Ultra HD", bandDisplayName("2160p"))
        assertEquals("1080p Full HD", bandDisplayName("1080p"))
        assertEquals("720p HD", bandDisplayName("720p"))
        assertEquals("480p", bandDisplayName("480p"))
        assertEquals("SD", bandDisplayName("sd"))
        assertEquals("weird", bandDisplayName("weird"))
    }

    @Test
    fun bestForLineIsPlainAndNullWhenUnknown() {
        assertEquals("Sharp on big screens", bestForLine("1080p"))
        assertEquals("Best for phones", bestForLine("720p"))
        assertEquals("Good on slow data", bestForLine("480p"))
        assertNull(bestForLine("weird"))
    }

    @Test
    fun playSheetLeadsWithAutoAsTheOnlyRecommendedRow() {
        val rows = sheetRowsFor(ladder(), QualitySheetMode.PLAY, premiumAccess = false, freeQualityCap = "720p")
        assertEquals("auto", rows.first().band)
        assertTrue(rows.first().isAuto)
        assertTrue(rows.first().isRecommended)
        // Exactly one Recommended row per sheet — and in PLAY it is the
        // Auto row, never 720p.
        assertEquals(1, rows.count { it.isRecommended })
        assertFalse(rows.first { it.band == "720p" }.isRecommended)
        // Best first after Auto: 1080p, 720p, 480p.
        assertEquals(listOf("auto", "1080p", "720p", "480p"), rows.map { it.band })
    }

    @Test
    fun playSheetShowsLockedRowsGreyedNeverHidden() {
        val rows = sheetRowsFor(ladder(), QualitySheetMode.PLAY, premiumAccess = false, freeQualityCap = "720p")
        val locked = rows.first { it.band == "1080p" }
        assertTrue(locked.isLocked)
        assertEquals("1080p Full HD", locked.title)
        // The free rows stay open.
        assertFalse(rows.first { it.band == "720p" }.isLocked)
        assertFalse(rows.first { it.band == "480p" }.isLocked)
        // A VIP viewer locks nothing.
        val vip = sheetRowsFor(ladder(), QualitySheetMode.PLAY, premiumAccess = true, freeQualityCap = "720p")
        assertFalse(vip.any { it.isLocked })
    }

    @Test
    fun playSheetShowsDataPerHourAndCopiesAsSafetyNet() {
        val rows = sheetRowsFor(ladder(), QualitySheetMode.PLAY, premiumAccess = false, freeQualityCap = "720p")
        assertEquals("≈ 3 GB/hour", rows.first { it.band == "1080p" }.dataPerHour)
        assertEquals("≈ 1.5 GB/hour", rows.first { it.band == "720p" }.dataPerHour)
        assertEquals("≈ 700 MB/hour", rows.first { it.band == "480p" }.dataPerHour)
        assertEquals("· 2 more copies if this one fails", rows.first { it.band == "1080p" }.copiesNote)
        assertNull(rows.first { it.band == "720p" }.copiesNote)
        // PLAY never shows a size: the size is what a download costs,
        // not what an hour of watching costs.
        assertTrue(rows.all { it.sizeLine == null })
    }

    @Test
    fun downloadSheetHasNoAutoRowAndRecommends720p() {
        val rows = sheetRowsFor(ladder(), QualitySheetMode.DOWNLOAD, premiumAccess = false, freeQualityCap = "720p")
        assertFalse(rows.any { it.isAuto })
        assertEquals(1, rows.count { it.isRecommended })
        assertTrue(rows.first { it.band == "720p" }.isRecommended)
        assertEquals(listOf("1080p", "720p", "480p"), rows.map { it.band })
    }

    @Test
    fun downloadSheetShowsSizeWhenKnownAndNeverInventsOne() {
        val rows = sheetRowsFor(ladder(), QualitySheetMode.DOWNLOAD, premiumAccess = false, freeQualityCap = "720p")
        assertEquals("1.0 GB", rows.first { it.band == "1080p" }.sizeLine)
        // 720p's server never named a size: the line is omitted, never guessed.
        assertNull(rows.first { it.band == "720p" }.sizeLine)
        // No data/hour on a download row.
        assertTrue(rows.all { it.dataPerHour == null })
    }

    @Test
    fun downloadSheetHlsRowReadsSaveForOfflineNeverASize() {
        val rows = sheetRowsFor(ladder(), QualitySheetMode.DOWNLOAD, premiumAccess = false, freeQualityCap = "720p")
        val hls = rows.first { it.band == "480p" }
        assertTrue(hls.isHls)
        assertEquals("Save for offline", hls.downloadNote)
        // Even though the server named a size, a playlist stream never shows one.
        assertNull(hls.sizeLine)
    }

    @Test
    fun deadBandIsGreyedAndNotTappable() {
        val dead = ladder().copy(
            options = ladder().options + option("sd", "SD", streamId = ""),
        )
        val rows = sheetRowsFor(dead, QualitySheetMode.PLAY, premiumAccess = false, freeQualityCap = "720p")
        val sd = rows.first { it.band == "sd" }
        assertTrue(sd.isDead)
        assertFalse(sd.isRecommended)
    }

    @Test
    fun recommendedBandFallsBackWhen720pIsMissing() {
        val no720 = LadderForScope(
            options = listOf(
                option("1080p", "1080p Full HD"),
                option("480p", "480p"),
            ),
            autoBand = "480p",
            downloadOnlyBands = emptyList(),
        )
        // PLAY always recommends Auto; DOWNLOAD falls to the best free band.
        assertEquals("auto", recommendedBandFor(no720, QualitySheetMode.PLAY, premiumAccess = false, freeQualityCap = "720p"))
        assertEquals("480p", recommendedBandFor(no720, QualitySheetMode.DOWNLOAD, premiumAccess = false, freeQualityCap = "720p"))
        assertNull(recommendedBandFor(LadderForScope.EMPTY, QualitySheetMode.DOWNLOAD, premiumAccess = false, freeQualityCap = "720p"))
    }

    // ── P1: the per-episode ladder ──

    private fun episodeGroup(season: String, episode: String, band: String) = OptionGroup(
        season = season,
        episode = episode,
        tiers = listOf(
            LadderTier(
                band = band,
                playable = true,
                copies = 1,
                link = LadderLink(
                    streamId = "h-$season-$episode-$band",
                    playUrl = "http://example.invalid/play/$band",
                    kind = "direct",
                    playable = true,
                    fileSizeBytes = 800_000_000L,
                    mimeType = "video/mp4",
                ),
            ),
        ),
        auto = LadderAuto(band = band),
    )

    @Test
    fun perEpisodeLadderFindsItsOwnEpisodeInAMultiEpisodeSeries() {
        // P1 (report §2): the whole-work lookup returns EMPTY for a
        // multi-episode series, so the detail ladder staged nothing and
        // the player offered no quality switch. The per-episode lookup
        // finds the episode's own ladder instead.
        val groups = listOf(
            episodeGroup("S1", "E1", "720p"),
            episodeGroup("S1", "E2", "480p"),
        )
        val e2 = ladderForEpisodeScope(groups, season = 1, episode = 2, premiumAccess = false, freeQualityCap = "720p")
        assertFalse(e2.isEmpty)
        assertEquals("480p", e2.autoBand)
        assertEquals(listOf("480p"), e2.options.map { it.band })
        // An episode with no group is honestly empty, never another
        // episode's links.
        assertTrue(ladderForEpisodeScope(groups, season = 1, episode = 9, premiumAccess = false, freeQualityCap = "720p").isEmpty)
    }

    @Test
    fun wholeWorkLookupIsEmptyForAMultiEpisodeSeries() {
        // The bug shape, pinned so it cannot come back: the detail
        // screen's whole-work scope is EMPTY once a series has more
        // than one group, which is why the per-episode lookup exists.
        val groups = listOf(
            episodeGroup("S1", "E1", "720p"),
            episodeGroup("S1", "E2", "480p"),
        )
        assertTrue(
            com.raddstream.app.player.QualityLadder.forNumberedScope(groups, null, null).isEmpty,
        )
        // A movie has one group, so the whole-work lookup still works.
        val movieGroup = OptionGroup(
            season = null,
            episode = null,
            tiers = episodeGroup("", "", "720p").tiers,
            auto = LadderAuto(band = "720p"),
        )
        assertEquals(
            listOf("720p"),
            com.raddstream.app.player.QualityLadder.forNumberedScope(
                listOf(movieGroup),
                null,
                null,
            ).options.map { it.band },
        )
    }

    // ── next unwatched episode ──

    private fun print(releaseId: Int, playable: Boolean) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "copy $releaseId",
        descriptor = ReleaseLabelParsing.describe("Title 720p WEB-DL", "720p"),
        languages = listOf("Hindi"),
        sizeBytes = 800_000_000L,
        coverage = CoverageType.WHOLE_WORK,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = if (playable) {
            listOf(
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
            )
        } else {
            emptyList()
        },
    )

    private fun layout(): ReleaseLayout {
        val s1 = SeasonGroup(
            number = 1,
            episodes = listOf(
                EpisodeGroup(season = 1, numberWithinSeason = 1, absoluteNumber = 1, title = "Pilot", prints = listOf(print(1, true))),
                EpisodeGroup(season = 1, numberWithinSeason = 2, absoluteNumber = 2, title = "Second", prints = listOf(print(2, true))),
                EpisodeGroup(season = 1, numberWithinSeason = 3, absoluteNumber = 3, title = "Third", prints = listOf(print(3, false))),
            ),
            packedPrints = emptyList(),
        )
        val s2 = SeasonGroup(
            number = 2,
            episodes = listOf(
                EpisodeGroup(season = 2, numberWithinSeason = 1, absoluteNumber = 4, title = "Return", prints = listOf(print(4, true))),
            ),
            packedPrints = emptyList(),
        )
        return ReleaseLayout(
            isSeriesLike = true,
            seasons = listOf(s1, s2),
            workPrints = emptyList(),
            capabilities = emptyList(),
            officialPrintCount = 4,
            camPrintCount = 0,
            totalPrints = 4,
        )
    }

    @Test
    fun nextUnwatchedSkipsWatchedAndUnplayable() {
        val progress = mapOf(
            (1 to 1) to EpisodeProgress(fraction = 1f, watched = true, remainingLabel = null),
        )
        assertEquals(1 to 2, nextUnwatchedEpisode(layout(), progress))
        // Episode 3 has no playable print, so it is never next.
        // Every playable episode watched: re-watch from the start.
        // (S2 E1 is playable too, so it is marked watched as well —
        // otherwise it, not the re-watch, is next.)
        val allWatched = mapOf(
            (1 to 1) to EpisodeProgress(fraction = 1f, watched = true, remainingLabel = null),
            (1 to 2) to EpisodeProgress(fraction = 1f, watched = true, remainingLabel = null),
            (2 to 1) to EpisodeProgress(fraction = 1f, watched = true, remainingLabel = null),
        )
        assertEquals(1 to 1, nextUnwatchedEpisode(layout(), allWatched))
        // One season only: the season screen's Play Season.
        assertEquals(2 to 1, nextUnwatchedEpisode(layout(), emptyMap(), season = 2))
    }

    @Test
    fun nextUnwatchedIsNullWhenNothingCanPlay() {
        assertNull(nextUnwatchedEpisode(null, emptyMap()))
        val empty = layout().copy(
            seasons = listOf(
                SeasonGroup(
                    number = 1,
                    episodes = listOf(
                        EpisodeGroup(season = 1, numberWithinSeason = 1, absoluteNumber = 1, title = "Pilot", prints = listOf(print(1, false))),
                    ),
                    packedPrints = emptyList(),
                ),
            ),
        )
        assertNull(nextUnwatchedEpisode(empty, emptyMap()))
    }

    // ── free-space gate ──

    @Test
    fun freeSpaceNeedsSizePlusFloor() {
        val oneGb = 1_073_741_824L
        // 2 GB free covers a 1 GB file plus the 500 MB floor.
        assertEquals(FreeSpaceVerdict.Ok, freeSpaceVerdictFor(2_147_483_648L, oneGb))
        // 1 GB free does not: the file needs 1 GB + 500 MB.
        assertEquals(
            FreeSpaceVerdict.NotEnough(oneGb, oneGb),
            freeSpaceVerdictFor(oneGb, oneGb),
        )
    }

    @Test
    fun freeSpaceWithUnknownSizeNeedsOnlyTheFloor() {
        assertEquals(FreeSpaceVerdict.Ok, freeSpaceVerdictFor(600_000_000L, null))
        assertEquals(
            FreeSpaceVerdict.NotEnough(DOWNLOAD_SPACE_FLOOR_BYTES, 400_000_000L),
            freeSpaceVerdictFor(400_000_000L, null),
        )
    }

    @Test
    fun notEnoughSpaceMessageNamesRealNumbers() {
        assertEquals(
            "Not enough space. This needs 1.0 GB, you have 800 MB free.",
            notEnoughSpaceMessage(1_073_741_824L, 838_860_800L),
        )
    }
}
