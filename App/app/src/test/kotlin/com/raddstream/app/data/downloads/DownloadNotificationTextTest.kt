package com.raddstream.app.data.downloads

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 4 (Downloads #5/#6): the foreground notification carries the same
 * percent + bytes + speed + ETA as the row — never percent alone — and a
 * download failure maps to its stable kind, never a raw HTTP code.
 * Pure functions, so no Robolectric runner is needed.
 */
class DownloadNotificationTextTest {

    @Test
    fun downloadingTextReadsPercentBytesSpeedEta() {
        assertEquals(
            "Downloading 45% · 450 MB / 1.0 GB · 12.0 MB/s · about 1 min left",
            DownloadService.downloadNotificationText(450_000_000L, 1_000_000_000L, 12_000_000L, 60L),
        )
    }

    @Test
    fun downloadingTextReadsBytesWhileCalculating() {
        assertEquals(
            "Downloading 45% · 450 MB / 1.0 GB · calculating…",
            DownloadService.downloadNotificationText(450_000_000L, 1_000_000_000L, 0L, null),
        )
    }

    @Test
    fun downloadingTextBeforeTheFirstByteIsJustStarting() {
        assertEquals(
            "Downloading — starting…",
            DownloadService.downloadNotificationText(0L, 0L, 0L, null),
        )
    }

    @Test
    fun downloadingTextWithUnknownTotalShowsBytesAlone() {
        assertEquals(
            "Downloading 450 MB · calculating…",
            DownloadService.downloadNotificationText(450_000_000L, 0L, 0L, null),
        )
    }

    @Test
    fun httpFailuresMapToStableKinds() {
        assertEquals(
            DeviceDownloadErrorKind.LINK_EXPIRED,
            DownloadService.downloadFailureKind(DownloadHttpException(404)),
        )
        assertEquals(
            DeviceDownloadErrorKind.LINK_EXPIRED,
            DownloadService.downloadFailureKind(DownloadHttpException(410)),
        )
        assertEquals(
            DeviceDownloadErrorKind.HOST_REFUSED,
            DownloadService.downloadFailureKind(DownloadHttpException(403)),
        )
        assertEquals(
            DeviceDownloadErrorKind.RATE_LIMITED,
            DownloadService.downloadFailureKind(DownloadHttpException(429)),
        )
        assertEquals(
            DeviceDownloadErrorKind.TRANSFER_FAILED,
            DownloadService.downloadFailureKind(DownloadHttpException(500)),
        )
    }

    @Test
    fun nonHttpFailuresAreTransferFailures() {
        assertEquals(
            DeviceDownloadErrorKind.TRANSFER_FAILED,
            DownloadService.downloadFailureKind(IOException("socket closed")),
        )
    }
}
