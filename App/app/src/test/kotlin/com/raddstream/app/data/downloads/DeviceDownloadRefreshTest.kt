package com.raddstream.app.data.downloads

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.local.AppDatabase
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.RaddStreamApi
import com.raddstream.app.network.models.StreamResponse
import com.raddstream.app.sync.SyncAccountRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Phase 4 (Downloads #5 + APP-05 §6): a 404/410 retry re-resolves the
 * handle before re-queueing — it never re-GETs the identical dead URL —
 * and a stored raw failure string never reaches the row.
 *
 * Runs on the JVM under Robolectric because this project has no emulator
 * image. Requires an x86_64 runner (robolectric/robolectric#9166).
 */
@RunWith(RobolectricTestRunner::class)
class DeviceDownloadRefreshTest {

    private class FakeApiProvider(
        settingsRepository: ServerSettingsRepository,
        var resolveAnswer: StreamResponse,
    ) : ApiProvider(settingsRepository) {
        var resolveCalls = 0

        override suspend fun current(): RaddStreamApi {
            @Suppress("UNCHECKED_CAST")
            return Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(RaddStreamApi::class.java),
            ) { _, method, args ->
                val continuation = args!!.last() as Continuation<Any?>
                when (method.name) {
                    "resolveStream" -> {
                        resolveCalls++
                        continuation.resume(resolveAnswer)
                    }
                    else -> continuation.resumeWithException(
                        UnsupportedOperationException("unused in this test: ${method.name}"),
                    )
                }
                COROUTINE_SUSPENDED
            } as RaddStreamApi
        }

        override suspend fun currentBaseUrl(): String = "https://catalog.example"
    }

    private lateinit var db: AppDatabase
    private lateinit var provider: FakeApiProvider
    private lateinit var repo: DeviceDownloadsRepository
    private lateinit var app: Application

    private fun goodMeta(handle: String) = StreamResponse(
        success = true,
        streamHandle = handle,
        kind = "direct",
        mimeType = "video/mp4",
        fileSizeBytes = 1_000_000_000L,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        app = ApplicationProvider.getApplicationContext()
        val settings = ServerSettingsRepository(context)
        provider = FakeApiProvider(settings, goodMeta("handle-1"))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // A signed-out account: currentVipPass answers null without any
        // network, so the re-resolve rebuilds the plain redirector URL.
        val syncRepo = SyncAccountRepository(
            provider,
            settings,
            db.favoriteDao(),
            db.unavailableWorkDao(),
            db.syncedProgressDao(),
            CoroutineScope(Dispatchers.Unconfined),
        )
        repo = DeviceDownloadsRepository(context, provider, syncRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Seed one entry through the real enqueue path; returns its id. */
    private fun seed(handle: String): String = runBlocking {
        val result = repo.enqueue(handle, "Echoes of Silence", "1080p")
        assertTrue(result is DeviceDownloadResult.Enqueued)
        (result as DeviceDownloadResult.Enqueued).download.id
    }

    /** Consume every service-start intent the seeding left behind. */
    private fun drainStartedServices() {
        while (shadowOf(app).nextStartedService != null) {
            // Loop until the shadow queue is empty.
        }
    }

    @Test
    fun `resume on link_expired re-resolves and restarts from zero`() = runBlocking {
        val id = seed("handle-1")
        repo.markFailed(id, 450_000_000L, 1_000_000_000L, "HTTP 404", errorKind = "link_expired")
        val callsBefore = provider.resolveCalls
        drainStartedServices()

        repo.requestResume(id)

        val entry = repo.entity(id)!!
        assertEquals(DeviceDownloadState.QUEUED, entry.state)
        assertEquals("https://catalog.example/api/v1/download?id=handle-1", entry.url)
        assertEquals(0L, entry.bytesDownloaded)
        assertNull(entry.errorKind)
        assertEquals(callsBefore + 1, provider.resolveCalls)
        // The service was asked to start the re-queued download.
        val started = shadowOf(app).nextStartedService
        assertEquals("com.raddstream.app.downloads.START", started?.action)
        assertEquals(id, started?.getStringExtra("download_id"))
    }

    @Test
    fun `resume on link_expired with a failed resolve stays failed and does not start the service`() =
        runBlocking {
            val id = seed("handle-1")
            repo.markFailed(id, 450_000_000L, 1_000_000_000L, "HTTP 404", errorKind = "link_expired")
            provider.resolveAnswer = StreamResponse(success = false)
            val callsBefore = provider.resolveCalls
            drainStartedServices()

            repo.requestResume(id)

            val entry = repo.entity(id)!!
            assertEquals(DeviceDownloadState.FAILED, entry.state)
            assertEquals("link_expired", entry.errorKind)
            assertEquals("https://catalog.example/api/v1/download?id=handle-1", entry.url)
            assertEquals(callsBefore + 1, provider.resolveCalls)
            assertNull(shadowOf(app).nextStartedService)
        }

    @Test
    fun `resume on a plain paused entry does not re-resolve`() = runBlocking {
        val id = seed("handle-1")
        repo.markPaused(id, 300_000_000L)
        val callsBefore = provider.resolveCalls

        repo.requestResume(id)

        val entry = repo.entity(id)!!
        assertEquals(DeviceDownloadState.QUEUED, entry.state)
        assertEquals(callsBefore, provider.resolveCalls)
        assertEquals("https://catalog.example/api/v1/download?id=handle-1", entry.url)
        // A plain resume keeps its bytes — only a re-resolve restarts.
        assertEquals(300_000_000L, entry.bytesDownloaded)
    }

    @Test
    fun `resume on transfer_failed does not re-resolve`() = runBlocking {
        val id = seed("handle-1")
        repo.markFailed(id, 100_000_000L, 1_000_000_000L, "HTTP 500", errorKind = "transfer_failed")
        val callsBefore = provider.resolveCalls

        repo.requestResume(id)

        val entry = repo.entity(id)!!
        assertEquals(DeviceDownloadState.QUEUED, entry.state)
        assertEquals(callsBefore, provider.resolveCalls)
        assertEquals(100_000_000L, entry.bytesDownloaded)
    }

    @Test
    fun `mega branch attempts the proxy and stays failed when it cannot start`() = runBlocking {
        val id = seed("handle-1")
        repo.markFailed(id, 450_000_000L, 1_000_000_000L, "HTTP 404", errorKind = "link_expired")
        // A decrypting copy with no node id: the proxy refuses immediately
        // (NotMega), with no network — the entry must not be re-queued
        // against a URL the proxy never minted.
        provider.resolveAnswer = goodMeta("handle-1").copy(
            decrypt = "aes-ctr",
            decryptNodeId = null,
            decryptKey = null,
        )
        val callsBefore = provider.resolveCalls

        repo.requestResume(id)

        val entry = repo.entity(id)!!
        assertEquals(DeviceDownloadState.FAILED, entry.state)
        assertEquals("link_expired", entry.errorKind)
        assertEquals(callsBefore + 1, provider.resolveCalls)
        assertEquals("https://catalog.example/api/v1/download?id=handle-1", entry.url)
    }

    @Test
    fun `enqueue starts at attempt 1 and each resume counts one more`() = runBlocking {
        // D12: a retry is a counted try — the row names its attempt when it fails.
        val id = seed("handle-1")
        assertEquals(1, repo.entity(id)!!.attempts)
        assertEquals(1, repo.downloads.value.first { it.id == id }.attempts)
        repo.markPaused(id, 300_000_000L)
        repo.requestResume(id)
        assertEquals(2, repo.entity(id)!!.attempts)
        assertEquals(2, repo.downloads.value.first { it.id == id }.attempts)
        // A re-resolved retry counts too.
        repo.markFailed(id, 0L, 1_000_000_000L, "HTTP 404", errorKind = "link_expired")
        repo.requestResume(id)
        assertEquals(3, repo.entity(id)!!.attempts)
        assertEquals(DeviceDownloadState.QUEUED, repo.entity(id)!!.state)
    }

    @Test
    fun `stored raw http text never reaches the row`() = runBlocking {
        val id = seed("handle-1")
        repo.markFailed(id, 100_000_000L, 1_000_000_000L, "HTTP 404", errorKind = "link_expired")
        assertEquals(
            "This copy is no longer available. Retry to get a fresh link.",
            repo.downloads.value.first { it.id == id }.failureReason,
        )
        repo.markFailed(
            id, 100_000_000L, 1_000_000_000L,
            "Unexpected content type: text/html",
            errorKind = "incomplete_file",
        )
        assertEquals(
            "The download stopped before it finished.",
            repo.downloads.value.first { it.id == id }.failureReason,
        )
        // An unclassified failure renders nothing — the generic line
        // stands alone rather than leaking the stored string.
        repo.markFailed(id, 100_000_000L, 1_000_000_000L, "HTTP 500")
        assertNull(repo.downloads.value.first { it.id == id }.failureReason)
        // A PARTIAL row keeps its constructed parts-count sentence: it is
        // viewer words, never an exception message.
        repo.markPartial(id, 750_000_000L, 1_000_000_000L, "3 of 4 parts did not finish.")
        assertEquals(
            "3 of 4 parts did not finish.",
            repo.downloads.value.first { it.id == id }.failureReason,
        )
    }
}
