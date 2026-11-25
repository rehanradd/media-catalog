package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.data.downloads.DeviceDownload
import com.raddstream.app.data.downloads.DeviceDownloadState
import com.raddstream.app.ui.components.DownloadsSkeleton
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
        failureReason: String? = null,
        attempts: Int = 0,
        stalled: Boolean = false,
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
        failureReason = failureReason,
        attempts = attempts,
        stalled = stalled,
    )

    private fun keep(
        id: String,
        title: String,
        printLabel: String,
        state: KeepState,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        streamHandle: String? = null,
        bytesPerSec: Long = 0L,
        expiresAtMillis: Long? = null,
        failureReason: String? = null,
        attempt: Int = 0,
        stalled: Boolean = false,
    ) = KeptItem(
        id = id,
        title = title,
        printLabel = printLabel,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        state = state,
        expiresAtMillis = expiresAtMillis,
        streamHandle = streamHandle,
        bytesPerSec = bytesPerSec,
        failureReason = failureReason,
        attempt = attempt,
        stalled = stalled,
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
        // Real percent + real bytes + real speed + ETA, from the engine's own numbers.
        // Phase 4 (Downloads #1): the device row carries its bytes, like the keep row.
        compose.onNodeWithText("45% · 450 MB / 1.0 GB · 12.0 MB/s · 1 min left").assertIsDisplayed()
        compose.onNode(hasContentDescription("Pause Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Play Echoes of Silence")).assertDoesNotExist()
        // D21: the quality is a badge now — "1080p" never truncates — and
        // the size is its own node, so it never truncates either (the
        // joined "High bitrate · 1.0 GB" string cut the size to "·…" on
        // rows with action buttons; the remainder flexes instead).
        compose.onNodeWithText("1080p").assertIsDisplayed()
        compose.onNodeWithText("High bitrate").assertIsDisplayed()
        compose.onNodeWithText("1.0 GB").assertIsDisplayed()
    }

    @Test
    fun downloadingDeviceRowShowsBytesWhileCalculating() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1c",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.DOWNLOADING,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        // No live rate yet, so "calculating", never a guessed number — but
        // the bytes are already there (Phase 4, Downloads #1).
        compose.onNodeWithText("45% · 450 MB / 1.0 GB · calculating…").assertIsDisplayed()
    }

    @Test
    fun startingDeviceRowShowsJustStarting() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1s",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.DOWNLOADING,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNodeWithText("Starting…").assertIsDisplayed()
    }

    @Test
    fun downloadingDeviceRowWithUnknownTotalShowsBytesAlone() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1u",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.DOWNLOADING,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 0L,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        // No percent without a total — the bytes-so-far stand alone, never
        // "X / unknown size" (Phase 4, Downloads #1).
        compose.onNodeWithText("450 MB · calculating…").assertIsDisplayed()
    }

    @Test
    fun pausedDeviceRowShowsBytesSaved() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d3b",
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
        // Phase 4 (Downloads #2): a paused row keeps its progress in words
        // as well as in the dimmed bar.
        compose.onNodeWithText("Paused — resume when you are ready").assertIsDisplayed()
        compose.onNodeWithText("300 MB of 1.3 GB saved · 23%").assertIsDisplayed()
    }

    @Test
    fun failedDeviceRowShowsBytesSaved() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d6b",
                        title = "Fall",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.FAILED,
                        bytesDownloaded = 100_000_000L,
                        bytesTotal = 1_000_000_000L,
                        failureReason = "The download stopped before it finished.",
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNodeWithText("Download failed — resume, or cancel and try again").assertIsDisplayed()
        compose.onNodeWithText("100 MB of 1.0 GB saved · 10%").assertIsDisplayed()
        compose.onNodeWithText("The download stopped before it finished.").assertIsDisplayed()
    }

    @Test
    fun queuedDeviceRowSaysItIsWaitingNotDownloading() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1q",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.QUEUED,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        // Phase 4 (Downloads #7): waiting, not downloading at 0%.
        compose.onNodeWithText("Queued").assertIsDisplayed()
        compose.onAllNodes(hasText("0%")).assertCountEquals(0)
        compose.onAllNodes(hasText("Downloading")).assertCountEquals(0)
    }

    @Test
    fun queuedDeviceRowNamesItsPlaceInLine() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1q2",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.QUEUED,
                    ),
                    queuePosition = 2,
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNodeWithText("Queued · #2 in queue").assertIsDisplayed()
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
        // D15: a finished row says so in words, with the jade check beside it.
        compose.onNodeWithText("Downloaded ✓ — plays here or in any video player").assertIsDisplayed()
        compose.onNodeWithText("High bitrate").assertIsDisplayed()
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
        // Percent + bytes + speed/ETA: no live rate yet, so "calculating",
        // never a guessed number — the same rule as the device row.
        compose.onNodeWithText("45% · 450 MB / 1.0 GB · calculating…").assertIsDisplayed()
        compose.onNode(hasContentDescription("Pause Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Play Echoes of Silence")).assertDoesNotExist()
    }

    @Test
    fun downloadingKeepRowShowsSpeedAndEtaWhenTheEngineReportsARate() {
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
                        bytesPerSec = 12_000_000L,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("45% · 450 MB / 1.0 GB · 12.0 MB/s · 45 sec left").assertIsDisplayed()
    }

    @Test
    fun gatedKeepRowOffersUpgradeAndDismissNotRetry() {
        // Phase 4 (Downloads #4): Retry cannot succeed — the gate will
        // refuse again. The row offers the upgrade, or dismiss.
        var upgraded = false
        var dismissed = false
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "10",
                        title = "Salaar",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.GATED,
                    ),
                    onPlay = {},
                    onCancel = { dismissed = true },
                    onRetry = {},
                    onUpgrade = { upgraded = true },
                )
            }
        }
        compose.onNodeWithText("Locked to VIP — this quality needs a VIP plan").assertIsDisplayed()
        compose.onNodeWithText("View VIP options").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNode(hasContentDescription("Dismiss Salaar")).assertIsDisplayed()
        compose.onNodeWithText("View VIP options").performClick()
        assertTrue(upgraded)
        compose.onNode(hasContentDescription("Dismiss Salaar")).performClick()
        assertTrue(dismissed)
        compose.onNode(hasContentDescription("Play Salaar")).assertDoesNotExist()
    }

    @Test
    fun failedKeepRowShowsRemove() {
        // Phase 4 (Downloads #3): a dead keep can be removed, not only
        // retried forever.
        var cancelled = false
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "15r",
                        title = "Fall",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.FAILED,
                        failureReason = "The source stopped responding.",
                    ),
                    onPlay = {},
                    onCancel = { cancelled = true },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Fall")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Fall")).performClick()
        assertTrue(cancelled)
    }

    @Test
    fun noSpaceKeepRowShowsRemove() {
        var cancelled = false
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "12r",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.NO_SPACE,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                    ),
                    onPlay = {},
                    onCancel = { cancelled = true },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Not enough space to finish — free room and retry").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).performClick()
        assertTrue(cancelled)
    }

    @Test
    fun pausedKeepRowShowsBytesSaved() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "11b",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.PAUSED,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        // Phase 4 (Downloads #2): a paused keep keeps its progress too.
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithText("450 MB of 1.0 GB saved · 45%").assertIsDisplayed()
    }

    @Test
    fun pausedKeepRowShowsResumeAndCancel() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "11",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.PAUSED,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Play Echoes of Silence")).assertDoesNotExist()
    }

    @Test
    fun noSpaceKeepRowShowsItsReasonBesideTheStalledBar() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "12",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.NO_SPACE,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Not enough space to finish — free room and retry").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun queuedKeepRowSaysItIsWaiting() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "13",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.QUEUED,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Queued for download").assertIsDisplayed()
    }

    @Test
    fun expiringKeepRowShowsTheCountdown() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "14",
                        title = "Severance",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.EXPIRING,
                        bytesDownloaded = 1_300_000_000L,
                        bytesTotal = 1_300_000_000L,
                        streamHandle = "handle-14",
                        // 42 minutes plus a half-minute buffer, so the
                        // composition-time read cannot slip to 41.
                        expiresAtMillis = System.currentTimeMillis() + 42 * 60_000L + 30_000L,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Kept — expires in 42 min").assertIsDisplayed()
    }

    @Test
    fun failedKeepRowShowsItsStoredReason() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "15",
                        title = "Fall",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.FAILED,
                        failureReason = "The source stopped responding.",
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Download failed — retry to resume").assertIsDisplayed()
        compose.onNodeWithText("The source stopped responding.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun failedDeviceRowShowsItsStoredReason() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d6",
                        title = "Fall",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.FAILED,
                        bytesDownloaded = 100_000_000L,
                        bytesTotal = 1_000_000_000L,
                        // Phase 4 (Downloads #5): the fixture is the mapped
                        // sentence now — the raw stored reason is never
                        // painted (see DeviceDownloadRefreshTest).
                        failureReason = "The download stopped before it finished.",
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNodeWithText("Download failed — resume, or cancel and try again").assertIsDisplayed()
        compose.onNodeWithText("The download stopped before it finished.").assertIsDisplayed()
    }

    @Test
    fun retryButtonMeetsThe48dpFloor() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "16",
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
        // The My List floor pattern: measure the node, not the modifier.
        val height = with(compose.density) {
            compose.onNodeWithText("Retry").fetchSemanticsNode().boundsInRoot.height.toDp()
        }
        assertTrue("Retry measures $height, expected at least the 48dp floor", height >= 48.dp)
    }

    @Test
    fun deviceProgressLineReadsPercentBytesSpeedEta() {
        // Phase 4 (Downloads #1): the device line mirrors the keep line.
        val moving = deviceFile(
            id = "d7",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = DeviceDownloadState.DOWNLOADING,
            bytesDownloaded = 450_000_000L,
            bytesTotal = 1_000_000_000L,
            bytesPerSec = 12_000_000L,
            etaSeconds = 60L,
        )
        assertEquals("45% · 450 MB / 1.0 GB · 12.0 MB/s · 1 min left", deviceProgressLine(moving))
        val calculating = deviceFile(
            id = "d8",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = DeviceDownloadState.DOWNLOADING,
            bytesDownloaded = 450_000_000L,
            bytesTotal = 1_000_000_000L,
        )
        assertEquals("45% · 450 MB / 1.0 GB · calculating…", deviceProgressLine(calculating))
        // D11: no movement past the stall window says so — "calculating…"
        // is only the first seconds, never minutes of no movement.
        val stalled = deviceFile(
            id = "d11",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = DeviceDownloadState.DOWNLOADING,
            bytesDownloaded = 450_000_000L,
            bytesTotal = 1_000_000_000L,
            stalled = true,
        )
        assertEquals("45% · 450 MB / 1.0 GB · Stalled — check connection", deviceProgressLine(stalled))
        val starting = deviceFile(
            id = "d9",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = DeviceDownloadState.DOWNLOADING,
        )
        assertEquals("Starting…", deviceProgressLine(starting))
        val unknownTotal = deviceFile(
            id = "d10",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = DeviceDownloadState.DOWNLOADING,
            bytesDownloaded = 450_000_000L,
            bytesTotal = 0L,
        )
        assertEquals("450 MB · calculating…", deviceProgressLine(unknownTotal))
    }

    @Test
    fun bytesSavedLineReadsSavedShare() {
        assertEquals("300 MB of 1.3 GB saved · 23%", bytesSavedLine(300_000_000L, 1_300_000_000L, 23))
        assertEquals("450 MB saved", bytesSavedLine(450_000_000L, 0L, null))
        assertEquals(null, bytesSavedLine(0L, 1_000_000_000L, 0))
    }

    @Test
    fun emptyPreviewShowsSampleRow() {
        // Phase 4 (Downloads #14): the empty screen previews the
        // populated state.
        compose.setContent {
            RaddStreamTheme { DownloadsEmptyPreview() }
        }
        compose.onNodeWithText("Your downloads will look like this:").assertIsDisplayed()
        compose.onNodeWithText("Example title").assertIsDisplayed()
        // D11: the sample row shows a download that is MOVING — the old
        // "calculating…" was the stale copy this round replaces.
        compose.onNodeWithText("45% · 540 MB / 1.2 GB · 12.0 MB/s · 1 min left").assertIsDisplayed()
    }

    @Test
    fun keepProgressLineReadsPercentBytesSpeedEta() {
        val moving = keep(
            id = "7",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = KeepState.DOWNLOADING,
            bytesDownloaded = 450_000_000L,
            bytesTotal = 1_000_000_000L,
            bytesPerSec = 12_000_000L,
        )
        assertEquals("45% · 450 MB / 1.0 GB · 12.0 MB/s · 45 sec left", keepProgressLine(moving))
        val calculating = keep(
            id = "8",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = KeepState.DOWNLOADING,
            bytesDownloaded = 450_000_000L,
            bytesTotal = 1_000_000_000L,
        )
        assertEquals("45% · 450 MB / 1.0 GB · calculating…", keepProgressLine(calculating))
        // D11: the keep side follows the same stall rule as the device side.
        val stalled = keep(
            id = "8s",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = KeepState.DOWNLOADING,
            bytesDownloaded = 450_000_000L,
            bytesTotal = 1_000_000_000L,
            stalled = true,
        )
        assertEquals("45% · 450 MB / 1.0 GB · Stalled — check connection", keepProgressLine(stalled))
    }

    @Test
    fun remainingFormatsInHoursMinutesAndUnderAMinute() {
        assertEquals("2 h 14 m", formatRemaining(2 * 3_600_000L + 14 * 60_000L))
        assertEquals("42 min", formatRemaining(42 * 60_000L))
        assertEquals("less than a minute", formatRemaining(30_000L))
        // Dev-cleanup (Nov 2026): an unknown countdown is "less than a
        // minute" too — the old "soon" read as "Kept — expires in soon".
        assertEquals("less than a minute", formatRemaining(null))
    }

    @Test
    fun keepProgressLineBeforeTheFirstByteIsJustStarting() {
        // Dev-cleanup (Nov 2026): while nothing has arrived and the total
        // is unknown there are no byte counts to show — the old
        // "0 KB / unknown size · calculating…" read as a broken counter.
        val starting = keep(
            id = "9",
            title = "Echoes of Silence",
            printLabel = "1080p · High bitrate",
            state = KeepState.DOWNLOADING,
            bytesDownloaded = 0L,
            bytesTotal = 0L,
        )
        assertEquals("Starting…", keepProgressLine(starting))
    }

    @Test
    fun downloadsSkeletonRendersRowsWhileLoading() {
        compose.setContent {
            RaddStreamTheme { DownloadsSkeleton() }
        }
        compose.onNodeWithTag("downloadsSkeleton").assertIsDisplayed()
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
        compose.onNode(hasContentDescription("How to free space")).performClick()
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
        compose.onNodeWithText("HOW TO FREE SPACE").assertDoesNotExist()
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

    @Test
    fun qualityBadgeNeverTruncatesMidWord() {
        // D21: the quality is a badge that never truncates; the rest of the
        // label shares one short line with the size — no "1080p…" anywhere.
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1b",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.DOWNLOADING,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNodeWithText("1080p").assertIsDisplayed()
        compose.onNodeWithText("High bitrate").assertIsDisplayed()
        compose.onNodeWithText("1.0 GB").assertIsDisplayed()
        compose.onAllNodes(hasText("1080p…")).assertCountEquals(0)
    }

    @Test
    fun stalledDeviceRowSaysStalledNotCalculating() {
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1st",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.DOWNLOADING,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                        stalled = true,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNodeWithText("45% · 450 MB / 1.0 GB · Stalled — check connection").assertIsDisplayed()
    }

    @Test
    fun stalledKeepRowSaysStalledNotCalculating() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "7st",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.DOWNLOADING,
                        bytesDownloaded = 450_000_000L,
                        bytesTotal = 1_000_000_000L,
                        stalled = true,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("45% · 450 MB / 1.0 GB · Stalled — check connection").assertIsDisplayed()
    }

    @Test
    fun failedDeviceRowNamesItsAttempt() {
        // D12: a failed row names its attempt — a retry is a counted try,
        // not a mystery.
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d6a",
                        title = "Fall",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.FAILED,
                        bytesDownloaded = 100_000_000L,
                        bytesTotal = 1_000_000_000L,
                        failureReason = "The download stopped before it finished.",
                        attempts = 2,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNodeWithText("Attempt 2").assertIsDisplayed()
    }

    @Test
    fun failedKeepRowNamesItsAttempt() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "15a",
                        title = "Fall",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.FAILED,
                        failureReason = "The source stopped responding.",
                        attempt = 3,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Attempt 3 of 6").assertIsDisplayed()
    }

    @Test
    fun queuedDeviceRowOffersNoPause() {
        // D19: a queued row is not downloading yet — offering it Pause is
        // a contradiction. Only Cancel.
        compose.setContent {
            RaddStreamTheme {
                DeviceDownloadRow(
                    item = deviceFile(
                        id = "d1qp",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = DeviceDownloadState.QUEUED,
                    ),
                    onPlay = {},
                    onPause = {},
                    onResume = {},
                    onRemove = {},
                )
            }
        }
        compose.onNode(hasContentDescription("Pause Echoes of Silence")).assertDoesNotExist()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).assertIsDisplayed()
    }

    @Test
    fun queuedKeepRowOffersNoPause() {
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "13p",
                        title = "Echoes of Silence",
                        printLabel = "1080p · High bitrate",
                        state = KeepState.QUEUED,
                    ),
                    onPlay = {},
                    onCancel = {},
                    onRetry = {},
                )
            }
        }
        compose.onNode(hasContentDescription("Pause Echoes of Silence")).assertDoesNotExist()
        compose.onNode(hasContentDescription("Cancel Echoes of Silence")).assertIsDisplayed()
    }

    @Test
    fun finishedKeepRowSaysDownloaded() {
        // D15: a finished keep says so in words, with the jade check beside it.
        compose.setContent {
            RaddStreamTheme {
                KeptItemRow(
                    item = keep(
                        id = "9d",
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
        compose.onNodeWithText("Downloaded ✓ — available offline").assertIsDisplayed()
    }
}
