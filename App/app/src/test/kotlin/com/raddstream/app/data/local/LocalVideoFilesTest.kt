package com.raddstream.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVideoFilesTest {

    @Test
    fun titleStripsExtensionAndReadsUnderscoresAsSpaces() {
        assertEquals("my movie", localVideoTitle("my_movie.mp4"))
        assertEquals("clip", localVideoTitle("clip"))
        assertEquals("my.movie", localVideoTitle("my.movie.mp4"))
    }

    @Test
    fun titleFallsBackWhenTheNameCarriesNothing() {
        assertEquals(LOCAL_VIDEO_UNTITLED, localVideoTitle(null))
        assertEquals(LOCAL_VIDEO_UNTITLED, localVideoTitle("   "))
        assertEquals(LOCAL_VIDEO_UNTITLED, localVideoTitle("12345.mp4"))
        assertEquals(LOCAL_VIDEO_UNTITLED, localVideoTitle(".mp4"))
    }

    // Video-audit #35 (27 Nov 2026): "River of No Return (2026) Episode 15
    // kisskh" — the downloader baked the source site's name into the file
    // name, and the file name is the displayed title. The tag is cut at
    // display time only; the file itself is never renamed.

    @Test
    fun titleStripsATrailingSourceSiteTag() {
        assertEquals(
            "River of No Return (2026) Episode 15",
            localVideoTitle("River of No Return (2026) Episode 15 kisskh.mp4"),
        )
        assertEquals(
            "River of No Return (2026) Episode 15",
            localVideoTitle("River_of_No_Return_(2026)_Episode_15_kisskh.mp4"),
        )
        assertEquals(
            "Dune Part Two (2024)",
            localVideoTitle("Dune Part Two (2024)-kisskh.mkv"),
        )
        assertEquals(
            "Show (2023) S01E01",
            localVideoTitle("Show (2023) S01E01 [vegamovies].mp4"),
        )
    }

    @Test
    fun titleKeepsARealWordThatHappensToBeASiteName() {
        // "The Castle" has no year and no episode marker, so its last word
        // is the title, not a tag — a conservative miss beats a wrong cut.
        assertEquals("The Castle", localVideoTitle("The Castle.mp4"))
        assertEquals("Archive", localVideoTitle("Archive.mp4"))
        // An unknown trailing word is not a site slug: kept.
        assertEquals("My Movie xyzabc", localVideoTitle("My Movie xyzabc.mp4"))
    }

    @Test
    fun stripSourceTagNeedsAYearOrAnEpisodeToCut() {
        assertEquals("Castle (2009) S01E01", stripSourceTag("Castle (2009) S01E01 castle"))
        assertEquals("Movie (2024)", stripSourceTag("Movie (2024) kisskh"))
        assertEquals("The Castle", stripSourceTag("The Castle"))
        assertEquals("Movie 480p kisskh", stripSourceTag("Movie 480p kisskh"))
        assertEquals("Dune Part Two (2024)", stripSourceTag("Dune Part Two (2024)"))
    }

    @Test
    fun distinguisherFindsTheEpisodeNumber() {
        // Video-audit #40: the episode is the difference between two
        // same-named files, and it sits past the title's truncation.
        assertEquals("Episode 15", localVideoDistinguisher("River of No Return (2026) Episode 15 kisskh"))
        assertEquals("Episode 23", localVideoDistinguisher("Parizaad Ep 23"))
        assertEquals("Episode 3", localVideoDistinguisher("Show Ep. 3"))
        assertEquals("Episode 15", localVideoDistinguisher("River of No Return E15"))
        assertEquals("S1 E7", localVideoDistinguisher("Show S01E07"))
    }

    @Test
    fun distinguisherCombinesEpisodeAndQuality() {
        // The same episode in two qualities must still differ.
        assertEquals("Episode 15 · 480p", localVideoDistinguisher("River of No Return (2026) Episode 15 480p"))
        assertEquals("Episode 8 · 720p", localVideoDistinguisher("The Remarried Empress Episode 8 720p"))
        assertEquals("S1 E7 · 1080p", localVideoDistinguisher("Show S01E07 1080p"))
    }

    @Test
    fun distinguisherFallsBackToPartOrQuality() {
        assertEquals("Part 2", localVideoDistinguisher("Movie Part 2"))
        assertEquals("720p", localVideoDistinguisher("Movie 720p"))
        assertEquals("4K", localVideoDistinguisher("Movie 4k"))
        assertEquals("Part 2 · 720p", localVideoDistinguisher("Movie Part 2 720p"))
    }

    @Test
    fun distinguisherIsNullWhenNothingDistinguishes() {
        assertNull(localVideoDistinguisher("my movie"))
        assertNull(localVideoDistinguisher("Local video"))
        assertNull(localVideoDistinguisher("River of No Return (2026)"))
    }

    @Test
    fun folderNamesMoviesAndDownloadsFromEitherPathShape() {
        assertEquals("Movies", localVideoFolder("Movies/RaddStream/", null))
        assertEquals("Downloads", localVideoFolder("Download/", null))
        assertEquals("Movies", localVideoFolder(null, "/storage/emulated/0/Movies/clip.mp4"))
        assertEquals("Downloads", localVideoFolder(null, "/storage/emulated/0/Download/clip.mp4"))
        assertEquals("On this phone", localVideoFolder("Pictures/", null))
        assertEquals("On this phone", localVideoFolder(null, null))
    }

    @Test
    fun mimeOrExtensionDecidesTheContainer() {
        assertTrue(isLocalVideoFile("video/mp4", "clip.mp4"))
        assertTrue(isLocalVideoFile("video/x-matroska", "clip.mkv"))
        assertTrue(isLocalVideoFile(null, "clip.mkv"))
        assertTrue(isLocalVideoFile("video/mp2t", "clip.mp4"))
        assertFalse(isLocalVideoFile("text/html", "clip.jpg"))
        assertFalse(isLocalVideoFile(null, null))
        assertFalse(isLocalVideoFile(null, "clip"))
    }

    @Test
    fun rowMapsToAFileWithTitleAndFolder() {
        val file = localVideoFromRow(
            uri = "content://media/external/video/media/42",
            displayName = "my_movie.mp4",
            durationMs = 125_000L,
            sizeBytes = 245_000_000L,
            relativePath = "Movies/RaddStream/",
            dataPath = null,
            mimeType = "video/mp4",
            dateAddedMs = 1_700_000_000_000L,
        )
        assertEquals("content://media/external/video/media/42", file?.uri)
        assertEquals("my movie", file?.title)
        assertEquals("Movies", file?.folder)
        assertEquals(125_000L, file?.durationMs)
        assertEquals(1_700_000_000_000L, file?.dateAddedMs)
    }

    @Test
    fun nonVideoRowMapsToNull() {
        assertNull(
            localVideoFromRow(
                uri = "content://media/external/video/media/43",
                displayName = "photo.jpg",
                durationMs = 0L,
                sizeBytes = 100L,
                relativePath = "Movies/",
                dataPath = null,
                mimeType = "image/jpeg",
                dateAddedMs = 0L,
            ),
        )
    }
}
