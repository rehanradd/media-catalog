package com.raddstream.app.data.cache

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MEGA's client-side encryption, as proven live by the Phase C mega lane
 * (`Fast_Fetch/checkpoints/20261120-phase-c-mega.md`): a mega file URL carries
 * its 32-byte key in the `#` fragment, the download URL serves AES-CTR
 * ciphertext, and the decrypted bytes are a real container (EBML magic
 * `1a45dfa3` for the probed MKV). Pure `javax.crypto` — no new dependency —
 * and pure JVM, so every function here is unit-tested without Robolectric.
 *
 * The key NEVER leaves this device: it arrives in the per-press stream
 * descriptor, lives in memory only, and is never written to disk, Room,
 * DataStore, or a log.
 */
object MegaCrypto {

    /** The unmerged link key: AES key, CTR IV, and MAC key (unused for playback). */
    data class MergedKey(val aesKey: ByteArray, val iv: ByteArray, val macKey: ByteArray)

    /** EBML (`1a45dfa3`), MP4 (`ftyp` at offset 4), WebM/AVI (`RIFF`). */
    private val MAGIC_CHECKS: List<(ByteArray) -> Boolean> = listOf(
        { b -> b.size >= 4 && b[0] == 0x1a.toByte() && b[1] == 0x45.toByte() && b[2] == 0xdf.toByte() && b[3] == 0xa3.toByte() },
        { b -> b.size >= 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() },
        { b -> b.size >= 4 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() },
    )

    /**
     * Decode a 43-char URL-safe base64 link key (no padding) to its 32 bytes.
     * Null when the string is not a key at all — a malformed descriptor must
     * fail before any media byte is fetched, never mid-play.
     */
    fun decodeLinkKey(base64Url: String?): ByteArray? {
        if (base64Url.isNullOrBlank()) return null
        val padded = base64Url.trim().let { it + "=".repeat((4 - it.length % 4) % 4) }
        return try {
            val bytes = Base64.getUrlDecoder().decode(padded)
            if (bytes.size == 32) bytes else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Unmerge the 32-byte link key (Lane F, verified with openssl):
     * AES key = K[0:16] XOR K[16:32]; CTR IV = K[16:24]; MAC key = K[24:32].
     */
    fun unmergeKey(linkKey: ByteArray): MergedKey {
        require(linkKey.size == 32) { "a mega link key is 32 bytes" }
        val aesKey = ByteArray(16) { i -> (linkKey[i].toInt() xor linkKey[i + 16].toInt()).toByte() }
        val iv = linkKey.copyOfRange(16, 24)
        val macKey = linkKey.copyOfRange(24, 32)
        return MergedKey(aesKey, iv, macKey)
    }

    /** CTR counter for a block: IV (8 bytes) || 64-bit big-endian block index. */
    fun counterFor(iv: ByteArray, blockIndex: Long): ByteArray {
        require(iv.size == 8) { "a mega CTR IV is 8 bytes" }
        val counter = ByteArray(16)
        iv.copyInto(counter, 0)
        for (i in 0 until 8) {
            counter[8 + i] = ((blockIndex ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        return counter
    }

    /**
     * AES-128-CTR decrypt. `ciphertext` must start on a 16-byte block boundary
     * of the file and `counter` must be that block's counter (see [counterFor]):
     * CTR is a stream cipher, so any byte range decrypts once its block's
     * counter is known — that is what makes seeking work.
     */
    fun decryptCtr(ciphertext: ByteArray, aesKey: ByteArray, counter: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(counter))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Decrypt the `at` attributes blob (URL-safe base64, AES-128-CBC, zero IV,
     * zero padding) and return the plaintext when it starts with `MEGA`
     * followed by JSON — the key check that runs before any media byte is
     * fetched. Null on a bad key, a bad blob, or a non-MEGA plaintext: a wrong
     * key must fail fast here, never play garbage.
     */
    fun decryptAttrs(attrsBase64Url: String?, aesKey: ByteArray): String? {
        if (attrsBase64Url.isNullOrBlank()) return null
        val padded = attrsBase64Url.trim().let { it + "=".repeat((4 - it.length % 4) % 4) }
        val ciphertext = try {
            Base64.getUrlDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (ciphertext.isEmpty() || ciphertext.size % 16 != 0) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
            val plain = cipher.doFinal(ciphertext)
            // Zero padding: the JSON ends where the zeros start.
            val end = plain.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: plain.size
            val text = String(plain, 0, end, Charsets.UTF_8)
            if (text.startsWith("MEGA{")) text else null
        } catch (_: Exception) {
            null
        }
    }

    /** True when the decrypted head looks like a media container, not random bytes. */
    fun isContainerMagic(plaintextHead: ByteArray): Boolean =
        MAGIC_CHECKS.any { check -> runCatching { check(plaintextHead) }.getOrDefault(false) }
}
