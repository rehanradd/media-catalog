package com.raddstream.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.raddstream.app.data.downloads.DeviceDownload
import com.raddstream.app.data.downloads.DeviceDownloadState
import com.raddstream.app.ui.screens.DeviceDownloadRow
import com.raddstream.app.ui.screens.DownloadsEmptyPreview
import com.raddstream.app.ui.screens.KeptItemRow
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.KeepState
import com.raddstream.app.viewmodel.KeptItem
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4 (Downloads): the first Downloads goldens — until this branch
 * there was no Downloads Paparazzi case and no downloads PNG among the
 * committed goldens. Each case renders the real row the app ships, never
 * a hand-written stand-in, fed with the same fixtures the mockup tests
 * pin in words.
 *
 * The first run records goldens (`:app:recordPaparazziDebug`); later runs
 * verify against them (`:app:verifyPaparazziDebug`, also inside
 * `:app:testDebugUnitTest`).
 */
class DownloadsPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    /** Dark-first, like the shipped app: RaddStreamTheme defaults to the brand scheme. */
    @Composable
    private fun themed(content: @Composable () -> Unit) {
        RaddStreamTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    private fun deviceFile(
        id: String,
        title: String,
        printLabel: String,
        state: DeviceDownloadState,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        bytesPerSec: Long = 0L,
        etaSeconds: Long? = null,
        failureReason: String? = null,
    ) = DeviceDownload(
        id = id,
        title = title,
        printLabel = printLabel,
        state = state,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        localUri = null,
        mimeType = "video/mp4",
        fileName = "file-$id.mp4",
        bytesPerSec = bytesPerSec,
        etaSeconds = etaSeconds,
        failureReason = failureReason,
    )

    private fun keep(
        id: String,
        title: String,
        printLabel: String,
        state: KeepState,
        bytesDownloaded: Long = 0L,
        bytesTotal: Long = 0L,
        failureReason: String? = null,
    ) = KeptItem(
        id = id,
        title = title,
        printLabel = printLabel,
        bytesDownloaded = bytesDownloaded,
        bytesTotal = bytesTotal,
        state = state,
        expiresAtMillis = null,
        failureReason = failureReason,
    )

    @Test
    fun downloadsDownloadingDeviceRowWithBytes() {
        paparazzi.snapshot {
            themed {
                Column(modifier = Modifier.padding(16.dp)) {
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
        }
    }

    @Test
    fun downloadsFailedDeviceRowLinkExpired() {
        paparazzi.snapshot {
            themed {
                Column(modifier = Modifier.padding(16.dp)) {
                    DeviceDownloadRow(
                        item = deviceFile(
                            id = "d6",
                            title = "Fall",
                            printLabel = "1080p · High bitrate",
                            state = DeviceDownloadState.FAILED,
                            bytesDownloaded = 100_000_000L,
                            bytesTotal = 1_000_000_000L,
                            failureReason = "This copy is no longer available. Retry to get a fresh link.",
                        ),
                        onPlay = {},
                        onPause = {},
                        onResume = {},
                        onRemove = {},
                    )
                }
            }
        }
    }

    @Test
    fun downloadsGatedKeepRow() {
        paparazzi.snapshot {
            themed {
                Column(modifier = Modifier.padding(16.dp)) {
                    KeptItemRow(
                        item = keep(
                            id = "10",
                            title = "Salaar",
                            printLabel = "1080p · High bitrate",
                            state = KeepState.GATED,
                        ),
                        onPlay = {},
                        onCancel = {},
                        onRetry = {},
                        onUpgrade = {},
                    )
                }
            }
        }
    }

    @Test
    fun downloadsPausedDeviceRowWithBytesSaved() {
        paparazzi.snapshot {
            themed {
                Column(modifier = Modifier.padding(16.dp)) {
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
        }
    }

    @Test
    fun downloadsQueuedDeviceRow() {
        paparazzi.snapshot {
            themed {
                Column(modifier = Modifier.padding(16.dp)) {
                    DeviceDownloadRow(
                        item = deviceFile(
                            id = "d1q",
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
        }
    }

    @Test
    fun downloadsFailedKeepRowWithRemove() {
        paparazzi.snapshot {
            themed {
                Column(modifier = Modifier.padding(16.dp)) {
                    KeptItemRow(
                        item = keep(
                            id = "15",
                            title = "Fall",
                            printLabel = "1080p · High bitrate",
                            state = KeepState.FAILED,
                            bytesDownloaded = 100_000_000L,
                            bytesTotal = 1_000_000_000L,
                            failureReason = "The source stopped responding.",
                        ),
                        onPlay = {},
                        onCancel = {},
                        onRetry = {},
                    )
                }
            }
        }
    }

    @Test
    fun downloadsEmptyPreview() {
        paparazzi.snapshot {
            themed {
                Column(modifier = Modifier.padding(16.dp)) {
                    DownloadsEmptyPreview()
                }
            }
        }
    }
}
