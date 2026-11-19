package com.raddstream.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Local Library's pure rules: the folder a row came from, the name the
 * row paints, the all-folders query shape, and the grouping that puts
 * folders first — newest folder on top, newest video first inside it.
 */
class LocalLibraryTest {

    private fun video(
        uri: String,
        folderPath: String,
        dateAddedMs: Long,
        title: String = "clip",
    ) = LocalVideoFile(
        uri = uri,
        title = title,
        durationMs = 0L,
        sizeBytes = 0L,
        folder = "On this phone",
        dateAddedMs = dateAddedMs,
        folderPath = folderPath,
    )

    // ── folder path ─────────────────────────────────────────────────────

    @Test
    fun folderPathIsTheRelativePathWithoutItsTrailingSlash() {
        assertEquals("Movies/RaddStream", localVideoFolderPath("Movies/RaddStream/", null))
        assertEquals("Download", localVideoFolderPath("Download/", null))
        assertEquals("DCIM/Camera", localVideoFolderPath("DCIM/Camera/", null))
    }

    @Test
    fun folderPathIsTheParentOfAFilesystemPath() {
        assertEquals(
            "/storage/emulated/0/Movies",
            localVideoFolderPath(null, "/storage/emulated/0/Movies/clip.mp4"),
        )
        assertEquals(
            "/storage/1234-5678/Download",
            localVideoFolderPath(null, "/storage/1234-5678/Download/clip.mp4"),
        )
    }

    @Test
    fun folderPathIsEmptyWhenTheProviderStoredNoPath() {
        assertEquals("", localVideoFolderPath(null, null))
        assertEquals("", localVideoFolderPath("   ", null))
        assertEquals("", localVideoFolderPath(null, ""))
    }

    @Test
    fun relativePathWinsOverAFilesystemPath() {
        assertEquals(
            "Movies",
            localVideoFolderPath("Movies/", "/storage/emulated/0/Download/clip.mp4"),
        )
    }

    // ── display name ────────────────────────────────────────────────────

    @Test
    fun displayNameIsTheLastSegment() {
        assertEquals("RaddStream", localFolderDisplayName("Movies/RaddStream"))
        assertEquals("Camera", localFolderDisplayName("DCIM/Camera"))
        assertEquals("Movies", localFolderDisplayName("/storage/emulated/0/Movies"))
    }

    @Test
    fun displayNameFallsBackInsteadOfGoingBlank() {
        assertEquals("On this phone", localFolderDisplayName(""))
        assertEquals("On this phone", localFolderDisplayName("   "))
    }

    // ── query shape ─────────────────────────────────────────────────────

    @Test
    fun selectionHasNoFolderClause() {
        val (selection, args) = localLibrarySelection()
        assertFalse(selection.contains("relative_path"))
        assertFalse(selection.contains("_data"))
        assertFalse(selection.contains("LIKE"))
        assertTrue(selection.contains("mime_type IN (?, ?, ?)"))
        assertEquals("video/mp4", args[0])
        assertEquals("video/x-matroska", args[1])
        assertEquals("video/webm", args[2])
        assertEquals("%.mp4", args[3])
        assertEquals("%.mkv", args[4])
        assertEquals("%.webm", args[5])
    }

    // ── grouping ────────────────────────────────────────────────────────

    @Test
    fun groupsByFullPathNotByLeafName() {
        val folders = groupVideosIntoFolders(
            listOf(
                video("content://media/1", "Movies/Action", 100L),
                video("content://media/2", "Download/Action", 200L),
            ),
        )
        assertEquals(2, folders.size)
        assertEquals("Action", folders[0].displayName)
        assertEquals("Action", folders[1].displayName)
        assertEquals("Download/Action", folders[0].path)
        assertEquals("Movies/Action", folders[1].path)
    }

    @Test
    fun foldersSortByNewestVideoFirst() {
        val folders = groupVideosIntoFolders(
            listOf(
                video("content://media/1", "Movies", 100L),
                video("content://media/2", "Download", 300L),
                video("content://media/3", "DCIM/Camera", 200L),
            ),
        )
        assertEquals(listOf("Download", "DCIM/Camera", "Movies"), folders.map { it.path })
    }

    @Test
    fun videosInsideAFolderAreNewestFirst() {
        val folders = groupVideosIntoFolders(
            listOf(
                video("content://media/1", "Movies", 100L, title = "old"),
                video("content://media/2", "Movies", 300L, title = "new"),
                video("content://media/3", "Movies", 200L, title = "mid"),
            ),
        )
        assertEquals(1, folders.size)
        assertEquals(listOf("new", "mid", "old"), folders[0].videos.map { it.title })
        assertEquals("content://media/2", folders[0].newestVideoUri)
        assertEquals(300L, folders[0].newestAddedMs)
        assertEquals(3, folders[0].videoCount)
    }

    @Test
    fun sameDisplayNameGetsAParentHint() {
        val folders = groupVideosIntoFolders(
            listOf(
                video("content://media/1", "Movies/Action", 100L),
                video("content://media/2", "Download/Action", 200L),
            ),
        )
        assertEquals("Download", folders[0].parentHint)
        assertEquals("Movies", folders[1].parentHint)
    }

    @Test
    fun uniqueDisplayNameGetsNoHint() {
        val folders = groupVideosIntoFolders(
            listOf(
                video("content://media/1", "Movies", 100L),
                video("content://media/2", "Download", 200L),
            ),
        )
        assertNull(folders[0].parentHint)
        assertNull(folders[1].parentHint)
    }

    @Test
    fun emptyInputGroupsToNothing() {
        assertTrue(groupVideosIntoFolders(emptyList()).isEmpty())
    }

    @Test
    fun aPathlessVideoLandsUnderOnThisPhone() {
        val folders = groupVideosIntoFolders(
            listOf(video("content://media/1", "", 100L)),
        )
        assertEquals(1, folders.size)
        assertEquals("", folders[0].path)
        assertEquals("On this phone", folders[0].displayName)
    }

    @Test
    fun folderOrderTiesBreakByName() {
        val folders = groupVideosIntoFolders(
            listOf(
                video("content://media/1", "Movies", 100L),
                video("content://media/2", "Download", 100L),
            ),
        )
        assertEquals(listOf("Download", "Movies"), folders.map { it.path })
    }
}
