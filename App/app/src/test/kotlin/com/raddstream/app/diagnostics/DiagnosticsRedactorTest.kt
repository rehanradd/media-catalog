package com.raddstream.app.diagnostics

import com.raddstream.app.diagnostics.debug.DiagnosticsRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redaction rules (APP-13, review B-1). Every value below is an
 * obviously-fake placeholder — never a real credential.
 */
class DiagnosticsRedactorTest {

    @Test
    fun `token-bearing URL drops in full`() {
        val url = "https://api.example.com/play?token=fake-token-value-123&quality=1080p"
        assertEquals(DiagnosticsRedactor.REDACTED, DiagnosticsRedactor.redactUrl(url))
    }

    @Test
    fun `signed R2 URL drops in full`() {
        val url = "https://pub-fake123.r2.dev/file.mkv?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=fake-credential-456&X-Amz-Signature=fake-signature-789"
        assertEquals(DiagnosticsRedactor.REDACTED, DiagnosticsRedactor.redactUrl(url))
    }

    @Test
    fun `media-pattern URL drops in full even without a query`() {
        assertEquals(
            DiagnosticsRedactor.REDACTED,
            DiagnosticsRedactor.redactUrl("https://abc123.r2.cloudflarestorage.com/bucket/file.mkv"),
        )
        assertEquals(
            DiagnosticsRedactor.REDACTED,
            DiagnosticsRedactor.redactUrl("https://video-downloads.googleusercontent.com/file.mp4"),
        )
        assertEquals(
            DiagnosticsRedactor.REDACTED,
            DiagnosticsRedactor.redactUrl("https://pub-fake123.r2.dev/file.mkv"),
        )
    }

    @Test
    fun `clean URL passes through unchanged`() {
        val url = "https://catalog.example.com/api/v1/posts?page=2"
        assertEquals(url, DiagnosticsRedactor.redactUrl(url))
    }

    @Test
    fun `authorization header is redacted`() {
        assertEquals(
            DiagnosticsRedactor.REDACTED,
            DiagnosticsRedactor.redactHeader("Authorization", "Bearer fake-sync-key-123"),
        )
        assertEquals(
            DiagnosticsRedactor.REDACTED,
            DiagnosticsRedactor.redactHeader("X-Api-Key", "fake-api-key-456"),
        )
        assertEquals(
            "application/json",
            DiagnosticsRedactor.redactHeader("Content-Type", "application/json"),
        )
    }

    @Test
    fun `bearer value in free text is scrubbed`() {
        val text = "call failed: Authorization: Bearer fake-sync-key-123"
        val out = DiagnosticsRedactor.redactText(text)
        assertFalse(out.contains("fake-sync-key-123"))
        assertTrue(out.contains("Bearer ${DiagnosticsRedactor.REDACTED}"))
    }

    @Test
    fun `media URL in free text is dropped`() {
        val text = "error loading https://abc123.r2.cloudflarestorage.com/bucket/file.mkv?X-Amz-Signature=fake-sig-999"
        val out = DiagnosticsRedactor.redactText(text)
        assertFalse(out.contains("fake-sig-999"))
        assertFalse(out.contains("r2.cloudflarestorage.com"))
        assertTrue(out.contains(DiagnosticsRedactor.REDACTED))
    }

    @Test
    fun `key-value credential fragment in free text is scrubbed`() {
        val out = DiagnosticsRedactor.redactText("failed with sig=fake-sig-value-321 after retry")
        assertFalse(out.contains("fake-sig-value-321"))
        assertTrue(out.contains("sig=${DiagnosticsRedactor.REDACTED}"))
    }

    @Test
    fun `long token-shaped value after equals is scrubbed`() {
        val value = "a".repeat(40)
        val out = DiagnosticsRedactor.redactText("session=$value")
        assertFalse(out.contains(value))
    }

    @Test
    fun `hashId is a stable 64-hex SHA-256, never the input`() {
        val first = DiagnosticsRedactor.hashId("fake-account-1")
        val second = DiagnosticsRedactor.hashId("fake-account-1")
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(first.contains("fake-account-1"))
        assertTrue(DiagnosticsRedactor.hashId("fake-account-2") != first)
    }

    @Test
    fun `truncate caps at 200 chars`() {
        val long = "x".repeat(250)
        assertEquals(200, DiagnosticsRedactor.truncate(long).length)
        assertEquals("short", DiagnosticsRedactor.truncate("short"))
    }

    @Test
    fun `isSafe accepts the redacted marker and rejects a live bearer`() {
        assertTrue(DiagnosticsRedactor.isSafe("Bearer ${DiagnosticsRedactor.REDACTED}"))
        assertFalse(DiagnosticsRedactor.isSafe("Bearer fake-sync-key-123"))
        assertTrue(DiagnosticsRedactor.isSafe("plain text, no credential"))
    }
}
