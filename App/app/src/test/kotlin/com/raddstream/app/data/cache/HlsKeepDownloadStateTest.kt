package com.raddstream.app.data.cache

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R3 — the Media3 download state → keep state mapping, kept pure so it is
 * testable without a DownloadManager: a stopped download reads PAUSED (not
 * DOWNLOADING), a removed one reads NOT_STARTED so the drive re-adds it,
 * and only COMPLETED ever means the keep is playable offline.
 */
class HlsKeepDownloadStateTest {

    @Test
    fun `completed is completed`() {
        assertEquals(
            HlsKeepDownloadState.COMPLETED,
            HlsKeepDownloader.mapHlsKeepState(Download.STATE_COMPLETED, Download.STOP_REASON_NONE),
        )
    }

    @Test
    fun `failed is failed`() {
        assertEquals(
            HlsKeepDownloadState.FAILED,
            HlsKeepDownloader.mapHlsKeepState(Download.STATE_FAILED, Download.STOP_REASON_NONE),
        )
    }

    @Test
    fun `queued and restarting are queued`() {
        assertEquals(
            HlsKeepDownloadState.QUEUED,
            HlsKeepDownloader.mapHlsKeepState(Download.STATE_QUEUED, Download.STOP_REASON_NONE),
        )
        assertEquals(
            HlsKeepDownloadState.QUEUED,
            HlsKeepDownloader.mapHlsKeepState(Download.STATE_RESTARTING, Download.STOP_REASON_NONE),
        )
    }

    @Test
    fun `downloading without a stop reason is downloading`() {
        assertEquals(
            HlsKeepDownloadState.DOWNLOADING,
            HlsKeepDownloader.mapHlsKeepState(Download.STATE_DOWNLOADING, Download.STOP_REASON_NONE),
        )
    }

    @Test
    fun `downloading with a stop reason is paused, never downloading`() {
        assertEquals(
            HlsKeepDownloadState.PAUSED,
            HlsKeepDownloader.mapHlsKeepState(
                Download.STATE_DOWNLOADING,
                HlsKeepDownloader.STOP_REASON_USER_PAUSED,
            ),
        )
    }

    @Test
    fun `an unknown state never claims completion`() {
        assertEquals(
            HlsKeepDownloadState.NOT_STARTED,
            HlsKeepDownloader.mapHlsKeepState(999, Download.STOP_REASON_NONE),
        )
    }

    @Test
    fun `a stopped download reads not started, not downloading`() {
        assertEquals(
            HlsKeepDownloadState.NOT_STARTED,
            HlsKeepDownloader.mapHlsKeepState(Download.STATE_STOPPED, Download.STOP_REASON_NONE),
        )
    }
}
