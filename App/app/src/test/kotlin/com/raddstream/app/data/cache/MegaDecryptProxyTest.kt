package com.raddstream.app.data.cache

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The decrypting proxy over a fake MEGA: ciphertext generated in-memory with
 * the platform CTR cipher, served through a real [MegaDecryptProxy] over
 * `127.0.0.1` — no network, no Robolectric. Mirrors [LocalMediaProxyTest]:
 * the whole point is that a `DefaultHttpDataSource` can Range-request it
 * exactly like a CDN, so the tests speak real HTTP.
 */
class MegaDecryptProxyTest {

    private val linkKey = ByteArray(32) { i -> (i * 7 + 3).toByte() }
    private val merged = MegaCrypto.unmergeKey(linkKey)
    private val fileSize = 300_000
    private lateinit var plain: ByteArray
    private lateinit var cipher: ByteArray
    private lateinit var attrs: String
    private lateinit var proxy: MegaDecryptProxy

    @Before
    fun setUp() {
        plain = ByteArray(fileSize) { index -> (index % 251).toByte() }
        // EBML magic at the head, so verify() can recognise a container.
        plain[0] = 0x1a; plain[1] = 0x45; plain[2] = 0xdf.toByte(); plain[3] = 0xa3.toByte()
        plain[4] = 0x42; plain[5] = 0x82.toByte(); plain[6] = 0x84.toByte(); plain[7] = 'm'.code.toByte()
        cipher = encryptCtr(plain)
        attrs = encryptAttrs("""MEGA{"n":"test.mkv"}""")
        proxy = MegaDecryptProxy(
            gUrl = "https://gfs.example.invalid/file",
            key = merged,
            size = fileSize.toLong(),
            fetcher = MegaDecryptProxy.MegaCiphertextFetcher { start, end ->
                cipher.copyOfRange(start.toInt(), (end + 1).toInt())
            },
            windowBytes = 64L * 1024,
        )
    }

    @After
    fun tearDown() {
        proxy.close()
    }

    @Test
    fun verifyAcceptsGoodKeyAndAttrs() {
        assertTrue(proxy.verify(attrs))
    }

    @Test
    fun verifyRejectsBadAttrs() {
        assertFalse("attrs encrypted under another key must fail", proxy.verify(encryptAttrsWithKey("""MEGA{"n":"test.mkv"}""", ByteArray(16) { 0x55 })))
        assertFalse(proxy.verify("not-base64!!"))
        assertFalse(proxy.verify(null))
    }

    @Test
    fun verifyRejectsWrongKey() {
        val wrongKeyProxy = MegaDecryptProxy(
            gUrl = "https://gfs.example.invalid/file",
            key = MegaCrypto.unmergeKey(ByteArray(32) { 0x11 }),
            size = fileSize.toLong(),
            fetcher = MegaDecryptProxy.MegaCiphertextFetcher { start, end ->
                cipher.copyOfRange(start.toInt(), (end + 1).toInt())
            },
            windowBytes = 64L * 1024,
        )
        try {
            assertFalse("a wrong key must fail the key check, never play garbage", wrongKeyProxy.verify(attrs))
        } finally {
            wrongKeyProxy.close()
        }
    }

    @Test
    fun noRangeHeaderServesDecryptedWholeFile() {
        val answer = get()
        assertEquals(200, answer.status)
        assertArrayEquals(plain, answer.body)
        assertEquals("bytes", answer.headers["accept-ranges"])
    }

    @Test
    fun explicitRangeServesExactlyThatDecryptedWindow() {
        val answer = get(range = "bytes=1000-1999")
        assertEquals(206, answer.status)
        assertEquals(1000, answer.body.size)
        assertArrayEquals(plain.copyOfRange(1000, 2000), answer.body)
        assertEquals("bytes 1000-1999/$fileSize", answer.headers["content-range"])
    }

    @Test
    fun seekToANonZeroBlockBoundaryReturnsTheRightPlaintext() {
        // The seek that proves CTR seeking: a range starting mid-file, at a
        // block boundary the proxy never fetched the head for. A cipher that
        // decrypted from block 0 every time would return the wrong bytes.
        val at = 3 * 64 * 1024 + 16
        val answer = get(range = "bytes=$at-${at + 999}")
        assertEquals(206, answer.status)
        assertEquals(1000, answer.body.size)
        assertArrayEquals(plain.copyOfRange(at, at + 1000), answer.body)
    }

    @Test
    fun seekAcrossAWindowBoundaryRefetches() {
        val at = 64 * 1024 - 500
        val answer = get(range = "bytes=$at-${at + 1999}")
        assertEquals(206, answer.status)
        assertEquals(2000, answer.body.size)
        assertArrayEquals(plain.copyOfRange(at, at + 2000), answer.body)
    }

    @Test
    fun openEndedRangeServesToTheLastByte() {
        val from = fileSize - 50_000
        val answer = get(range = "bytes=$from-")
        assertEquals(206, answer.status)
        assertEquals(50_000, answer.body.size)
        assertArrayEquals(plain.copyOfRange(from, fileSize), answer.body)
    }

    @Test
    fun suffixRangeServesTheTail() {
        val answer = get(range = "bytes=-500")
        assertEquals(206, answer.status)
        assertEquals(500, answer.body.size)
        assertArrayEquals(plain.copyOfRange(fileSize - 500, fileSize), answer.body)
    }

    @Test
    fun rangePastTheEndAnswers416() {
        val answer = get(range = "bytes=$fileSize-${fileSize + 100}")
        assertEquals(416, answer.status)
        assertEquals("bytes */$fileSize", answer.headers["content-range"])
        assertEquals(0, answer.body.size)
    }

    @Test
    fun aFailedCiphertextFetchDropsTheConnection() {
        val failing = MegaDecryptProxy(
            gUrl = "https://gfs.example.invalid/file",
            key = merged,
            size = fileSize.toLong(),
            fetcher = MegaDecryptProxy.MegaCiphertextFetcher { _, _ ->
                throw IOException("MEGA 509: quota spent")
            },
        )
        try {
            val connection = URL(failing.baseHttpUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            try {
                connection.inputStream.use { it.readBytes() }
                throw AssertionError("a dead ciphertext fetch must not serve bytes")
            } catch (e: IOException) {
                // The proxy drops the connection; the player treats it as a
                // failed rendition and fails over. Any IOException is the
                // expected shape — except our own AssertionError above.
                assertTrue(e.message?.contains("quota") != true)
            } finally {
                connection.disconnect()
            }
        } finally {
            failing.close()
        }
    }

    @Test
    fun nonGetRequestsAreRefused() {
        val connection = URL(proxy.baseHttpUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }
            assertEquals(405, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    private fun encryptCtr(plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(MegaCrypto.counterFor(merged.iv, 0L)))
        return c.doFinal(plaintext)
    }

    private fun encryptAttrs(json: String): String = encryptAttrsWithKey(json, merged.aesKey)

    private fun encryptAttrsWithKey(json: String, aesKey: ByteArray): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val pad = (16 - bytes.size % 16) % 16
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(c.doFinal(bytes + ByteArray(pad)))
    }

    private class Answer(val status: Int, val body: ByteArray, val headers: Map<String, String>)

    private fun get(range: String? = null): Answer {
        val connection = URL(proxy.baseHttpUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            range?.let { connection.setRequestProperty("Range", it) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { (key, _) -> key.lowercase() }
                .mapValues { (_, values) -> values.joinToString(", ") }
            return Answer(status, body, headers)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
