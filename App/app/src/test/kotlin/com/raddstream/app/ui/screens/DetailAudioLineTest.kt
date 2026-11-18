package com.raddstream.app.ui.screens

import com.raddstream.app.network.models.CoverageType
import com.raddstream.app.network.models.ReleaseLabelParsing
import com.raddstream.app.viewmodel.RankedPrint
import com.raddstream.app.viewmodel.channelLayoutLabel
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
        channelLayout: String? = null,
    ) = RankedPrint(
        releaseId = releaseId,
        rawTitle = "copy",
        descriptor = ReleaseLabelParsing.describe(releaseTitle = "A Movie", quality = "720p")
            .copy(
                subtitlesAvailable = subtitlesAvailable,
                subtitleLangs = subtitleLangs,
                channelLayout = channelLayout ?: "unknown",
            ),
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

    // Detail review (Nov 2026, P1): the first real surround layout
    // across the prints appends after the audio languages — "5.1" or
    // "7.1" only, never "unknown".

    @Test
    fun surroundLayoutAppendsAfterAudioLanguages() {
        assertEquals(
            "Audio: Hindi, English · 5.1",
            audioSubtitleLine(
                listOf(print(1, listOf("Hindi", "English"), channelLayout = "5.1")),
            ),
        )
    }

    @Test
    fun sevenOneLayoutIsKept() {
        assertEquals(
            "Audio: Hindi · 7.1",
            audioSubtitleLine(listOf(print(1, listOf("Hindi"), channelLayout = "7.1"))),
        )
    }

    @Test
    fun stereoAndUnknownLayoutsAreHidden() {
        assertEquals(
            "Audio: Hindi",
            audioSubtitleLine(listOf(print(1, listOf("Hindi"), channelLayout = "2.0"))),
        )
        assertEquals(
            "Audio: Hindi",
            audioSubtitleLine(listOf(print(1, listOf("Hindi"), channelLayout = "unknown"))),
        )
    }

    @Test
    fun firstSurroundLayoutAcrossPrintsWins() {
        assertEquals(
            "Audio: Hindi · 5.1",
            audioSubtitleLine(
                listOf(
                    print(1, listOf("Hindi")),
                    print(2, listOf("Hindi"), channelLayout = "5.1"),
                ),
            ),
        )
        assertEquals(
            "Audio: Hindi · 7.1",
            audioSubtitleLine(
                listOf(
                    print(1, listOf("Hindi"), channelLayout = "7.1"),
                    print(2, listOf("Hindi"), channelLayout = "5.1"),
                ),
            ),
        )
    }

    @Test
    fun layoutIsNotShownOnSubtitlesOnlyLine() {
        // A surround layout is an audio fact — it never rides on a
        // subtitles-only line.
        assertEquals(
            "Subtitles: English",
            audioSubtitleLine(
                listOf(
                    print(
                        1,
                        emptyList(),
                        subtitlesAvailable = true,
                        subtitleLangs = listOf("English"),
                        channelLayout = "5.1",
                    ),
                ),
            ),
        )
    }

    @Test
    fun channelLayoutLabelKeepsSurroundOnly() {
        assertEquals("5.1", channelLayoutLabel("5.1"))
        assertEquals("7.1", channelLayoutLabel("7.1"))
        assertEquals("5.1", channelLayoutLabel(" 5.1 "))
        assertNull(channelLayoutLabel("2.0"))
        assertNull(channelLayoutLabel("unknown"))
        assertNull(channelLayoutLabel(null))
        assertNull(channelLayoutLabel(""))
    }

    @Test
    fun sevenOneIsParsedAsSevenOneNotFiveOne() {
        // The old parser mapped every 7.1 print to "5.1" — a 7.1 print
        // was mislabelled and the audio line could never say 7.1.
        assertEquals(
            "7.1",
            ReleaseLabelParsing.describe(releaseTitle = "A Movie 7.1", quality = "1080p").channelLayout,
        )
        assertEquals(
            "5.1",
            ReleaseLabelParsing.describe(releaseTitle = "A Movie 5.1", quality = "1080p").channelLayout,
        )
        assertEquals(
            "2.0",
            ReleaseLabelParsing.describe(releaseTitle = "A Movie Stereo", quality = "1080p").channelLayout,
        )
    }

    // "Unknown" labels (Nov 2026): the server's language lists carry the
    // literal words "Unknown" and "N/A" where a print's language was never
    // recorded — a missing value, not a language — so the line drops them
    // instead of printing "Audio: Unknown".

    @Test
    fun unknownAudioLanguagesAreDropped() {
        assertEquals(
            "Audio: Hindi",
            audioSubtitleLine(
                listOf(
                    print(1, listOf("Unknown", "Hindi", "N/A")),
                    print(2, listOf("unknown")),
                ),
            ),
        )
    }

    @Test
    fun unknownSubtitleLanguagesAreDropped() {
        assertEquals(
            "Subtitles: English",
            audioSubtitleLine(
                listOf(
                    print(1, emptyList(), subtitlesAvailable = true, subtitleLangs = listOf("Unknown", "English", "N/A")),
                ),
            ),
        )
    }

    @Test
    fun allUnknownLanguagesMeansNoLine() {
        assertNull(
            audioSubtitleLine(
                listOf(
                    print(1, listOf("Unknown"), subtitlesAvailable = true, subtitleLangs = listOf("N/A")),
                ),
            ),
        )
    }
}
