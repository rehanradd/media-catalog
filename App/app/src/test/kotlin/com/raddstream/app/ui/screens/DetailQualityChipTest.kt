package com.raddstream.app.ui.screens

import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityOption
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Detail review (Nov 2026, P2): a series shows the work-level quality
 * choice as one chip — the Auto band the ladder would play, or the
 * download-only state when no rung plays here. The Auto band itself
 * may not play (a locked top rung); then the first rung that does
 * play names the quality.
 */
class DetailQualityChipTest {

    private fun option(band: String, playsHere: Boolean) = QualityOption(
        band = band,
        title = band,
        streamId = "h-$band",
        playUrl = "http://127.0.0.1:8080/api/v1/stream?id=h-$band",
        sizeBytes = null,
        backupHandles = emptyList(),
        playsHere = playsHere,
        resumable = false,
        copies = 1,
        isProviderPage = false,
        audioLanguages = emptyList(),
    )

    private fun ladder(autoBand: String?, vararg options: QualityOption) = LadderForScope(
        options = options.toList(),
        autoBand = autoBand,
        downloadOnlyBands = emptyList(),
    )

    @Test
    fun autoBandThatPlaysNamesTheQuality() {
        assertEquals(
            "Quality: Auto (1080p)",
            qualityChipLabel(
                ladder(
                    "1080p",
                    option("2160p", playsHere = false),
                    option("1080p", playsHere = true),
                    option("720p", playsHere = true),
                ),
            ),
        )
    }

    @Test
    fun nothingPlayingMeansDownloadOnly() {
        assertEquals(
            "Quality: Download only",
            qualityChipLabel(
                ladder(
                    "1080p",
                    option("2160p", playsHere = false),
                    option("1080p", playsHere = false),
                ),
            ),
        )
    }

    @Test
    fun lockedAutoBandFallsBackToFirstPlayingRung() {
        assertEquals(
            "Quality: Auto (1080p)",
            qualityChipLabel(
                ladder(
                    "2160p",
                    option("2160p", playsHere = false),
                    option("1080p", playsHere = true),
                ),
            ),
        )
    }

    @Test
    fun emptyLadderMeansDownloadOnly() {
        assertEquals(
            "Quality: Download only",
            qualityChipLabel(LadderForScope.EMPTY),
        )
    }
}
