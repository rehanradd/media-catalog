package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raddstream.app.data.downloads.DeviceDownload
import com.raddstream.app.data.downloads.DeviceDownloadState
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.KeepState
import com.raddstream.app.viewmodel.KeptItem
import com.raddstream.app.viewmodel.StorageInfo
import com.raddstream.app.viewmodel.freeUpSpaceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Stitch Downloads mockup, applied 2026-11-15: a storage card with a
 * jade used-share bar and a FREE UP SPACE link, rows as a thumb + clean
 * title + "quality · size" + a jade progress bar with real percent/speed,
 * icon actions on the right (play when finished, pause while downloading,
 * × to remove), and the quiet line "Downloads play here, even after the
 * app closes." No bottom nav — the screen is a sub-screen, and its
 * Scaffold carries no bottomBar.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class DownloadsMockupTest {

    @get:Rule
    val compose = createComposeRule()

    private fun deviceFile(
        id: String,
        title: String,
        printLabel: String,
        state: DeviceDownloadState,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        localUri: String? = null,
        bytesPerSec: Long = 0L,
        etaSeconds: Long? = null,
    ) = DeviceDownload(
        id = id,
        title = title,
        printLabel = printLabel,
        state = state,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        localUri = localUri,
        mimeType = "video/mp4",
        fileName = "file-$id.mp4",
        bytesPerSec = bytesPerSec,
        etaSeconds = etaSeconds,
    )

    private fun keep(
        id: String,
        title: String,
        printLabel: String,
        state: KeepState,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        streamHandle: String? = null,
    ) = KeptItem(
        id = id,
        title = title,
        printLabel = printLabel,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        state = state,
        expiresAtMillis = null,
        streamHandle = streamHandle,
    )

    @Test
    fun downloadingDeviceRowShowsPercentSpeedAndPause() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.DOWNLOADING,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                        bytesPerSec = 12_000_000L,
                        etaSeconds = 60L,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        // Real percent + real speed + ETA, from the engine's own numbers.
        compose.onNodeWithText("45% · 12.0 MB/s · about 1 min left").assertIsDisplayed()
        compose.onNode(hasContentDescription("Pause Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Play Echoes of Silence")).assertDoesNotExist()
        // Quality + size, one meta line.
        compose.onNodeWithText("1080p · High bitrate").assertIsDisplayed()
        compose.onNodeWithText("1.0 GB").assertIsDisplayed()
    }

    @Test
    fun finishedDeviceRowShowsPlayAndRemove() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d2",
                        title = "Severance",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.READY,
                        bytesDownloaded = 1_300_000_000L,
                        bytesTotal = 1_300_000_000L,
                        localUri = "content://media/severance.mp4",
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNode(hasContentDescription("Play Severance")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Delete Severance")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Pause Severance")).assertDoesNotExist()
        compose.onNodeWithText("Saved on device — plays here or in any video player").assertIsDisplayed()
        compose.onNodeWithText("1.3 GB").assertIsDisplayed()
    }

    @Test
    fun pausedDeviceRowShowsResumeAndCancel() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d3",
                        title = "Solaris Drift",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.PAUSED,
                        bytesDownloaded = 300_000_000L,
                        bytesTotal = 1_300_000_000L,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNode(hasContentDescription("Resume Solaris Drift")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Solaris Drift")).assertIsDisplayed()
        compose.onNodeWithText("Paused — resume when you are ready").assertIsDisplayed()
    }

    @Test
    fun downloadingKeepRowShowsProgressAndCancel() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "7",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.DOWNLOADING,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("45% · 450 MB / 1.0 GB").assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Play Echoes of Silence")).assertDoesNotExist()
    }

    @Test
    fun failedKeepRowShowsStatusLineAndRetryText() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "8",
                        title = "Fall",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.FAILED,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Download failed — retry to resume").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun readyKeepRowShowsPlayAndDelete() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "9",
                        title = "Severance",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.READY,
                        bytesDownloaded = 1_300_000_000L,
                        bytesTotal = 1_300_000_000L,
                        streamHandle = "handle-9",
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNode(hasContentDescription("Play Severance")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Delete Severance")).assertIsDisplayed()
    }

    @Test
    fun rowTitleStripsTrailingYearButKeepsRealWords() {
        compose.setContent {
            RaddStreamTheme {
                Column {
                    DeviceDownloadRow(
                        item = deviceFile(
                            id = "d4",
                            title = "Salaar (2023)",
                            printLabel = "1080p · High bitrate",
                            state = DeviceDownloadState.READY,
                            bytesDownloaded = 1_300_000_000L,
                            bytesTotal = 1_300_000_000L,
                            localUri = "content://media/salaar.mp4",
                        ),
                        onPlay = {},
                        onPause = {},
                        onResume = {},
                        onRemove = {},
                    )
                    DeviceDownloadRow(
                        item = deviceFile(
                            id = "d5",
                            title = "Scary Movie",
                            printLabel = "720p",
                            state = DeviceDownloadState.READY,
                            bytesDownloaded = 800_000_000L,
                            bytesTotal = 800_000_000L,
                            localUri = "content://media/scary.mp4",
                        ),
                        onPlay = {},
                        onPause = {},
                        onResume = {},
                        onRemove = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Salaar").assertIsDisplayed()
        compose.onAllNodes(hasText("Salaar (2023)")).assertCountEquals(0)
        // A trailing "Movie" the row cannot prove is a suffix stays put.
        compose.onNodeWithText("Scary Movie").assertIsDisplayed()
    }

    @Test
    fun storageCardShowsUsedShareAndFreeUpSpace() {
        var clicked = false
        compose.setContent {
            RaddStreamTheme {
                StorageCard(
                    storage = StorageInfo(
                        freeBytes = 29_600_000_000L,
                        totalBytes = 32_000_000_000L,
                    ),
                    onFreeUpSpace = { clicked = true },
                )
            }
        }
        compose.onNodeWithText("2.4 GB of 32 GB used").assertIsDisplayed()
        compose.onNode(hasContentDescription("Free up space")).performClick()
        assertTrue(clicked)
    }

    @Test
    fun storageCardWithoutStorageHidesFreeUpSpace() {
        compose.setContent {
            RaddStreamTheme {
                StorageCard(storage = null)
            }
        }
        compose.onNodeWithText("Storage information unavailable.").assertIsDisplayed()
        compose.onNodeWithText("FREE UP SPACE").assertDoesNotExist()
    }

    @Test
    fun freeUpSpaceMessageNamesRealBytes() {
        assertEquals(
            "Downloads here use 1.3 GB. Remove a finished title with its × to free space.",
            freeUpSpaceMessage(1_300_000_000L),
        )
        assertEquals(
            "Nothing downloaded here yet — there is nothing to free up.",
            freeUpSpaceMessage(0L),
        )
    }

    @Test
    fun quietLineIsTheMockupLine() {
        compose.setContent {
            RaddStreamTheme {
                NoticeCard(DOWNLOADS_QUIET_LINE)
            }
        }
        compose.onNodeWithText("Downloads play here, even after the app closes.").assertIsDisplayed()
    }
}
