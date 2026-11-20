package com.raddstream.app.data.cache

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MEGA crypto, proven against itself the way the Phase C lane proved it
 * against openssl: a fixed key, a known plaintext, and the counter
 * construction the proxy's seeking depends on. No network, no Robolectric —
 * pure `javax.crypto`, so this runs on plain JVM.
 *
 * The lane's live proof this mirrors: the 32-byte link key unmerges to an
 * AES key (first 16 XOR second 16) + an 8-byte CTR IV, and the first
 * ciphertext bytes decrypt to EBML magic `1a45dfa3` + "matroska".
 */
class MegaCryptoTest {

    // A fixed 32-byte link key, so the vectors below are deterministic.
    private val linkKey = ByteArray(32) { i -> (i * 7 + 3).toByte() }
    private val merged = MegaCrypto.unmergeKey(linkKey)

    @Test
    fun unmergeKeyXorsTheHalves() {
        val expectedAes = ByteArray(16) { i -> (linkKey[i].toInt() xor linkKey[i + 16].toInt()).toByte() }
        assertEquals(expectedAes.toList(), merged.aesKey.toList())
        assertEquals(linkKey.copyOfRange(16, 24).toList(), merged.iv.toList())
        assertEquals(linkKey.copyOfRange(24, 32).toList(), merged.macKey.toList())
    }

    @Test
    fun decodeLinkKeyAccepts43CharUrlSafeNoPadding() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(linkKey)
        assertEquals(43, encoded.length)
        assertEquals(linkKey.toList(), MegaCrypto.decodeLinkKey(encoded)?.toList())
    }

    @Test
    fun decodeLinkKeyRejectsGarbage() {
        assertNull(MegaCrypto.decodeLinkKey(null))
        assertNull(MegaCrypto.decodeLinkKey(""))
        assertNull(MegaCrypto.decodeLinkKey("not-a-key"))
        assertNull(MegaCrypto.decodeLinkKey(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))))
    }

    @Test
    fun counterForIsIvPlusBigEndianBlockIndex() {
        val counter = MegaCrypto.counterFor(merged.iv, 0x0102030405060708L)
        assertEquals(merged.iv.toList(), counter.copyOfRange(0, 8).toList())
        assertEquals(listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08).map { it.toByte() }, counter.copyOfRange(8, 16).toList())
        val zero = MegaCrypto.counterFor(merged.iv, 0L)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0).map { it.toByte() }, zero.copyOfRange(8, 16).toList())
    }

    @Test
    fun decryptCtrInvertsPlatformCtr() {
        val plain = ByteArray(1000) { i -> (i % 251).toByte() }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(MegaCrypto.counterFor(merged.iv, 0L)))
        val ciphertext = cipher.doFinal(plain)
        val back = MegaCrypto.decryptCtr(ciphertext, merged.aesKey, MegaCrypto.counterFor(merged.iv, 0L))
        assertEquals(plain.toList(), back.toList())
    }

    @Test
    fun decryptCtrSeeksToAMiddleBlock() {
        // Encrypt 64 blocks; decrypt ONLY block 37 with its own counter.
        val plain = ByteArray(64 * 16) { i -> (i % 251).toByte() }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(MegaCrypto.counterFor(merged.iv, 0L)))
        val ciphertext = cipher.doFinal(plain)
        val block = 37L
        val start = (block * 16).toInt()
        val decrypted = MegaCrypto.decryptCtr(
            ciphertext.copyOfRange(start, start + 16),
            merged.aesKey,
            MegaCrypto.counterFor(merged.iv, block),
        )
        assertEquals(plain.copyOfRange(start, start + 16).toList(), decrypted.toList())
    }

    @Test
    fun decryptCtrWithAWrongKeyIsGarbageNotPlaintext() {
        val plain = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(60) { 0x41 }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(MegaCrypto.counterFor(merged.iv, 0L)))
        val ciphertext = cipher.doFinal(plain)
        val wrongKey = ByteArray(16) { 0x55 }
        val back = MegaCrypto.decryptCtr(ciphertext, wrongKey, MegaCrypto.counterFor(merged.iv, 0L))
        assertFalse("a wrong key must never reproduce the plaintext", back.contentEquals(plain))
        assertFalse("a wrong key must never reproduce the container magic", MegaCrypto.isContainerMagic(back))
    }

    @Test
    fun decryptAttrsAcceptsMegaAttrsAndRejectsOthers() {
        val attrsJson = """MEGA{"n":"Scarlet (2025).mkv"}"""
        val padded = attrsJson.toByteArray(Charsets.UTF_8).let { bytes ->
            val pad = (16 - bytes.size % 16) % 16
            bytes + ByteArray(pad)
        }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(ByteArray(16)))
        val ciphertext = cipher.doFinal(padded)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)
        assertEquals(attrsJson, MegaCrypto.decryptAttrs(encoded, merged.aesKey))
        // A wrong key decrypts to noise, not "MEGA{" — fail, never play.
        assertNull(MegaCrypto.decryptAttrs(encoded, ByteArray(16) { 0x55 }))
        assertNull(MegaCrypto.decryptAttrs("not-base64!!", merged.aesKey))
        assertNull(MegaCrypto.decryptAttrs(null, merged.aesKey))
    }

    @Test
    fun isContainerMagicRecognisesEbmlMp4AndRiff() {
        assertTrue(MegaCrypto.isContainerMagic(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0x42, 0x82.toByte())))
        assertTrue(MegaCrypto.isContainerMagic(byteArrayOf(0, 0, 0, 0, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())))
        assertTrue(MegaCrypto.isContainerMagic(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())))
        assertFalse(MegaCrypto.isContainerMagic(ByteArray(64) { (it * 13).toByte() }))
        assertFalse(MegaCrypto.isContainerMagic(ByteArray(0)))
    }

    @Test
    fun decryptCtrHandlesTheLaneProvenFirstBytes() {
        // The lane's proof, reduced to its essence: ciphertext that decrypts
        // to EBML magic + "matroska" is a real MKV under encryption, not noise.
        val plain = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0x42, 0x82.toByte(), 0x84.toByte(), 'm'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(), 's'.code.toByte(), 'k'.code.toByte(), 'a'.code.toByte(), 0x00) +
            ByteArray(48) { (it % 251).toByte() }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(MegaCrypto.counterFor(merged.iv, 0L)))
        val ciphertext = cipher.doFinal(plain)
        val back = MegaCrypto.decryptCtr(ciphertext, merged.aesKey, MegaCrypto.counterFor(merged.iv, 0L))
        assertEquals(plain.toList(), back.toList())
        assertTrue(MegaCrypto.isContainerMagic(back))
        assertNotNull(MegaCrypto.decryptAttrs(encryptAttrsForTest("""MEGA{"n":"x.mkv"}"""), merged.aesKey))
    }

    private fun encryptAttrsForTest(json: String): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val pad = (16 - bytes.size % 16) % 16
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(merged.aesKey, "AES"), IvParameterSpec(ByteArray(16)))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(bytes + ByteArray(pad)))
    }
}
