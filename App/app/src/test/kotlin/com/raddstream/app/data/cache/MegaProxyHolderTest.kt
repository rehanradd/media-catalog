package com.raddstream.app.data.cache

import com.raddstream.app.network.models.StreamResponse
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The holder end to end over a fake MEGA: a descriptor in, a loopback URL
 * out, and the bytes at that URL are the clear container — no network, no
 * Robolectric. The mint and the ciphertext fetch are both injected, so the
 * only real code under test is the holder's own wiring: field names, key
 * check, magic check, and the error mapping the UI reads.
 */
class MegaProxyHolderTest {

    private val linkKey = ByteArray(32) { i -> (i * 7 + 3).toByte() }
    private val merged = MegaCrypto.unmergeKey(linkKey)
    private val keyText = Base64.getUrlEncoder().withoutPadding().encodeToString(linkKey)
    private val fileSize = 100_000
    private val plain = ByteArray(fileSize) { index -> (index % 251).toByte() }.also {
        it[0] = 0x1a; it[1] = 0x45; it[2] = 0xdf.toByte(); it[3] = 0xa3.toByte()
    }
    private val cipher = encryptCtr(plain)
    private val attrs = encryptAttrs("""MEGA{"n":"test.mkv"}""")

    @After
    fun tearDown() {
        MegaProxyHolder.stop()
    }

    private fun descriptor(
        decrypt: String? = "aes-ctr",
        nodeId: String? = "78QnEaBK",
        key: String? = keyText,
        size: Long? = fileSize.toLong(),
    ) = StreamResponse(
        success = true,
        streamHandle = "s.mega",
        kind = "url",
        mimeType = "video/x-matroska",
        fileSizeBytes = size,
        resumable = true,
        decrypt = decrypt,
        decryptNodeId = nodeId,
        decryptKey = key,
    )

    private fun okMint() = MegaApi.MintResult.Ok(
        MegaApi.MegaMint(
            gUrl = "https://gfs.example.invalid/file",
            attrsBase64Url = attrs,
            size = fileSize.toLong(),
        ),
    )

    private fun fetcher() = MegaDecryptProxy.MegaCiphertextFetcher { start, end ->
        cipher.copyOfRange(start.toInt(), (end + 1).toInt())
    }

    @Test
    fun startServesClearBytesAtTheLoopbackUrl() {
        val url = MegaProxyHolder.start(
            descriptor(),
            mintOverride = { okMint() },
            fetcherOverride = { fetcher() },
        )
        assertTrue("a decrypting descriptor must start", url != null)
        assertTrue(url!!.startsWith("http://127.0.0.1:"))
        assertNull(MegaProxyHolder.lastError)
        val head = get(url, range = "bytes=0-15")
        assertEquals(206, head.status)
        assertArrayEquals(plain.copyOfRange(0, 16), head.body)
        // A seek mid-file returns the right plaintext, not the head again.
        val mid = get(url, range = "bytes=50000-50099")
        assertEquals(206, mid.status)
        assertArrayEquals(plain.copyOfRange(50000, 50100), mid.body)
    }

    @Test
    fun startReusesTheProxyForTheSameFile() {
        val first = MegaProxyHolder.start(
            descriptor(),
            mintOverride = { okMint() },
            fetcherOverride = { fetcher() },
        )
        val second = MegaProxyHolder.start(
            descriptor(),
            mintOverride = { throw AssertionError("a reuse must not re-mint") },
            fetcherOverride = { throw AssertionError("a reuse must not re-fetch") },
        )
        assertEquals(first, second)
    }

    @Test
    fun aPlainDescriptorIsNotMega() {
        assertNull(MegaProxyHolder.start(descriptor(decrypt = null)))
        assertEquals(MegaStartError.NotMega, MegaProxyHolder.lastError)
        assertNull(MegaProxyHolder.start(descriptor(decrypt = "none")))
        assertEquals(MegaStartError.NotMega, MegaProxyHolder.lastError)
    }

    @Test
    fun aBadKeyFailsFast() {
        val wrong = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x11 })
        assertNull(
            MegaProxyHolder.start(
                descriptor(key = wrong),
                mintOverride = { okMint() },
                fetcherOverride = { fetcher() },
            ),
        )
        assertEquals(MegaStartError.BadKey, MegaProxyHolder.lastError)
    }

    @Test
    fun quotaMapsToQuotaAndDeadMapsToDead() {
        assertNull(
            MegaProxyHolder.start(
                descriptor(),
                mintOverride = { MegaApi.MintResult.Quota },
                fetcherOverride = { fetcher() },
            ),
        )
        assertEquals(MegaStartError.Quota, MegaProxyHolder.lastError)
        assertNull(
            MegaProxyHolder.start(
                descriptor(),
                mintOverride = { MegaApi.MintResult.Dead(-9) },
                fetcherOverride = { fetcher() },
            ),
        )
        assertEquals(MegaStartError.Dead, MegaProxyHolder.lastError)
        assertNull(
            MegaProxyHolder.start(
                descriptor(),
                mintOverride = { MegaApi.MintResult.Transport("offline") },
                fetcherOverride = { fetcher() },
            ),
        )
        assertEquals(MegaStartError.Network, MegaProxyHolder.lastError)
    }

    @Test
    fun stopDropsTheProxy() {
        val url = MegaProxyHolder.start(
            descriptor(),
            mintOverride = { okMint() },
            fetcherOverride = { fetcher() },
        )
        assertTrue(url != null)
        MegaProxyHolder.stop()
        // A second start after a stop mints again: the old proxy is gone.
        var minted = 0
        val again = MegaProxyHolder.start(
            descriptor(),
            mintOverride = { minted++; okMint() },
            fetcherOverride = { fetcher() },
        )
        assertTrue(again != null)
        assertEquals(1, minted)
    }

    private fun encryptCtr(plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(MegaCrypto.counterFor(merged.iv, 0L)))
        return c.doFinal(plaintext)
    }

    private fun encryptAttrs(json: String): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val pad = (16 - bytes.size % 16) % 16
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(ByteArray(16)))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(c.doFinal(bytes + ByteArray(pad)))
    }

    private class Answer(val status: Int, val body: ByteArray)

    private fun get(url: String, range: String? = null): Answer {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            range?.let { connection.setRequestProperty("Range", it) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return Answer(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}
