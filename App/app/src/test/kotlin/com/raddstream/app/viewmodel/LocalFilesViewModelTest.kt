package com.raddstream.app.viewmodel

import android.graphics.Bitmap
import com.raddstream.app.data.local.LocalVideoFile
import com.raddstream.app.data.local.LocalVideosDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Local Library's ViewModel states, driven through a fake data source:
 * denied permission shows the honest state, granted permission loads
 * folders newest first, opening a folder and going back, refresh keeping
 * (or dropping) the open folder, a failed load showing the retry state,
 * and one thumbnail request per folder from its newest video.
 *
 * Robolectric (not plain JVM): the thumbnail test needs a real Bitmap,
 * which the JVM stub cannot create.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocalFilesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeLocalVideosDataSource : LocalVideosDataSource {
        var videos: List<LocalVideoFile> = emptyList()
        var failLoad: Boolean = false
        var thumbnailFailures: Set<String> = emptySet()
        var thumbnailRequests: List<String> = emptyList()

        override suspend fun loadVideos(): List<LocalVideoFile> {
            if (failLoad) throw IllegalStateException("boom")
            return videos
        }

        override suspend fun loadThumbnail(videoUri: String): Bitmap? {
            thumbnailRequests += videoUri
            if (videoUri in thumbnailFailures) throw IllegalStateException("no thumb")
            return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }
    }

    private fun video(uri: String, folderPath: String, dateAddedMs: Long) =
        LocalVideoFile(
            uri = uri,
            title = "clip",
            durationMs = 0L,
            sizeBytes = 0L,
            folder = "On this phone",
            dateAddedMs = dateAddedMs,
            folderPath = folderPath,
        )

    private lateinit var fake: FakeLocalVideosDataSource
    private lateinit var viewModel: LocalFilesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fake = FakeLocalVideosDataSource()
        viewModel = LocalFilesViewModel(fake)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `permission denied shows the honest state`() = runTest(testDispatcher) {
        viewModel.onPermissionResult(false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.needsPermission)
        assertTrue(state.folders.isEmpty())
    }

    @Test
    fun `permission granted loads folders newest first`() = runTest(testDispatcher) {
        fake.videos = listOf(
            video("content://media/1", "Movies", 100L),
            video("content://media/2", "Download", 300L),
            video("content://media/3", "Movies", 200L),
        )
        viewModel.onPermissionResult(true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.needsPermission)
        assertEquals(listOf("Download", "Movies"), state.folders.map { it.path })
        assertEquals(2, state.folders[1].videoCount)
        assertEquals("content://media/3", state.folders[1].newestVideoUri)
    }

    @Test
    fun `selecting a folder and going back`() = runTest(testDispatcher) {
        fake.videos = listOf(video("content://media/1", "Movies", 100L))
        viewModel.onPermissionResult(true)
        advanceUntilIdle()

        viewModel.selectFolder("Movies")
        assertEquals("Movies", viewModel.uiState.value.selectedFolderPath)
        assertEquals("Movies", viewModel.uiState.value.selectedFolder?.displayName)
        assertTrue(viewModel.onBackPressed())
        assertNull(viewModel.uiState.value.selectedFolderPath)
        assertFalse(viewModel.onBackPressed())
    }

    @Test
    fun `selecting an unknown folder is ignored`() = runTest(testDispatcher) {
        fake.videos = listOf(video("content://media/1", "Movies", 100L))
        viewModel.onPermissionResult(true)
        advanceUntilIdle()

        viewModel.selectFolder("Nope")
        assertNull(viewModel.uiState.value.selectedFolderPath)
    }

    @Test
    fun `refresh keeps the open folder when it still exists`() = runTest(testDispatcher) {
        fake.videos = listOf(video("content://media/1", "Movies", 100L))
        viewModel.onPermissionResult(true)
        advanceUntilIdle()
        viewModel.selectFolder("Movies")

        fake.videos = listOf(
            video("content://media/1", "Movies", 100L),
            video("content://media/2", "Movies", 200L),
        )
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("Movies", viewModel.uiState.value.selectedFolderPath)
    }

    @Test
    fun `refresh drops a selection whose folder is gone`() = runTest(testDispatcher) {
        fake.videos = listOf(video("content://media/1", "Movies", 100L))
        viewModel.onPermissionResult(true)
        advanceUntilIdle()
        viewModel.selectFolder("Movies")

        fake.videos = listOf(video("content://media/2", "Download", 200L))
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedFolderPath)
    }

    @Test
    fun `a failed load shows the retry state`() = runTest(testDispatcher) {
        fake.failLoad = true
        viewModel.onPermissionResult(true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.loadFailed)
        assertTrue(state.folders.isEmpty())
    }

    @Test
    fun `one thumbnail per folder from its newest video`() = runTest(testDispatcher) {
        fake.videos = listOf(
            video("content://media/1", "Movies", 100L),
            video("content://media/2", "Download", 300L),
            video("content://media/3", "Movies", 200L),
        )
        viewModel.onPermissionResult(true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            listOf("content://media/2", "content://media/3"),
            fake.thumbnailRequests.sorted(),
        )
        assertEquals(setOf("Download", "Movies"), state.thumbnails.keys)
    }

    @Test
    fun `a failed thumbnail leaves that folder on the dark tile`() = runTest(testDispatcher) {
        fake.videos = listOf(
            video("content://media/1", "Movies", 100L),
            video("content://media/2", "Download", 300L),
        )
        fake.thumbnailFailures = setOf("content://media/2")
        viewModel.onPermissionResult(true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(setOf("Movies"), state.thumbnails.keys)
        assertEquals(2, state.folders.size)
    }
}
