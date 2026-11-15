package com.raddstream.app.ui.screens

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.viewmodel.RankedPrint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Detail redesign (Nov 2026): one consolidated "Audio & Subtitles" line
 * under the synopsis, built from the prints' own data. Every branch of
 * [audioSubtitleLine] is pinned here.
 */
class DetailAudioLineTest {

    private fun print(
        releaseId: Int,
        languages: List<String>,
        subtitlesAvailable: Boolean = false,
        subtitleLangs: List<String> = emptyList(),
    ) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "copy",
        descriptor = ReleaseLabelParsing.describe(releaseTitle = "A Movie", quality = "720p")
            .copy(subtitlesAvailable = subtitlesAvailable, subtitleLangs = subtitleLangs),
        languages = languages,
        sizeBytes = null,
        coverage = CoverageType.WHOLE_WORK,
        season = null,
        episodeFrom = null,
        episodeTo = null,
        links = emptyList(),
    )

    @Test
    fun audioAndSubtitlesJoinWithAMiddleDot() {
        assertEquals(
            "Audio: Hindi, English · Subtitles: English",
            audioSubtitleLine(
                listOf(print(1, listOf("Hindi", "English"), subtitlesAvailable = true, subtitleLangs = listOf("English"))),
            ),
        )
    }

    @Test
    fun audioOnlyShowsAudio() {
        assertEquals("Audio: Hindi", audioSubtitleLine(listOf(print(1, listOf("Hindi")))))
    }

    @Test
    fun subtitlesOnlyShowsSubtitles() {
        assertEquals(
            "Subtitles: English",
            audioSubtitleLine(
                listOf(print(1, emptyList(), subtitlesAvailable = true, subtitleLangs = listOf("English"))),
            ),
        )
    }

    @Test
    fun neitherMeansNoLine() {
        assertNull(audioSubtitleLine(listOf(print(1, emptyList()))))
        assertNull(audioSubtitleLine(emptyList()))
    }

    @Test
    fun languagesDedupeAcrossPrintsInFirstSeenOrder() {
        assertEquals(
            "Audio: Hindi, English, Urdu",
            audioSubtitleLine(
                listOf(
                    print(1, listOf("Hindi", "English")),
                    print(2, listOf("English", "Urdu")),
                ),
            ),
        )
    }

    @Test
    fun subtitleFlagWithoutLanguagesShowsNoSubtitles() {
        // A print that claims subtitles but names none contributes
        // nothing — the line never reads "Subtitles: " with an empty list.
        assertEquals(
            "Audio: Hindi",
            audioSubtitleLine(
                listOf(print(1, listOf("Hindi"), subtitlesAvailable = true, subtitleLangs = emptyList())),
            ),
        )
    }
}
