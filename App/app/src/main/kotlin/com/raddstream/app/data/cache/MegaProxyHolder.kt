package com.raddstream.app.data.cache

import com.raddstream.app.network.models.StreamResponse
import okhttp3.OkHttpClient

/**
 * Owns the one [MegaDecryptProxy] of the current play session.
 *
 * A copy whose bytes are ciphertext never reaches the player as ciphertext:
 * the app mints the download URL itself ([MegaApi]), decrypts on the fly
 * through the proxy, and hands ExoPlayer a loopback URL that serves clear
 * bytes. The link key lives only in the proxy's memory while the session
 * runs; [stop] closes the proxy and drops it. Nothing here writes the key
 * to disk, to a log, or to any persisted store.
 *
 * Call [start] from the play path when the stream descriptor carries
 * `decrypt == "aes-ctr"`; call [stop] when playback ends, the player is
 * released, or the user leaves the screen. Starting a different file stops
 * the old proxy first.
 *
 * The [mintOverride] / [fetcherOverride] parameters exist only for tests:
 * production passes neither, and the proxy then mints and fetches for real.
 */
object MegaProxyHolder {

    /** Why the last [start] failed, for the UI to explain. Null after a success. */
    @Volatile
    var lastError: MegaStartError? = null
        private set

    private val lock = Any()
    private var proxy: MegaDecryptProxy? = null
    private var activeNodeId: String? = null
    private var httpClient: OkHttpClient? = null

    /**
     * Starts (or reuses) the decrypting proxy for [descriptor] and returns
     * the loopback URL the player should play, or null when the copy cannot
     * be started — see [lastError] for why. A descriptor without decrypt
     * fields returns null with [MegaStartError.NotMega].
     */
    fun start(
        descriptor: StreamResponse,
        mintOverride: ((String) -> MegaApi.MintResult)? = null,
        fetcherOverride: ((String) -> MegaDecryptProxy.MegaCiphertextFetcher)? = null,
    ): String? {
        val nodeId = descriptor.decryptNodeId
        val keyText = descriptor.decryptKey
        if (descriptor.decrypt?.lowercase() != "aes-ctr" || nodeId.isNullOrBlank() || keyText.isNullOrBlank()) {
            lastError = MegaStartError.NotMega
            return null
        }
        synchronized(lock) {
            if (proxy != null && activeNodeId == nodeId) {
                lastError = null
                return proxy?.uriFor().toString()
            }
            stopLocked()
            val linkKey = MegaCrypto.decodeLinkKey(keyText)
                ?: run { lastError = MegaStartError.BadKey; return null }
            val merged = try {
                MegaCrypto.unmergeKey(linkKey)
            } catch (_: IllegalArgumentException) {
                lastError = MegaStartError.BadKey
                return null
            }
            val size = descriptor.fileSizeBytes ?: 0L
            if (size <= 0L) {
                lastError = MegaStartError.BadKey
                return null
            }
            val client = httpClient ?: MegaDecryptProxy.defaultOkHttpClient().also { httpClient = it }
            val mint = try {
                mintOverride?.invoke(nodeId) ?: MegaApi().mint(nodeId)
            } catch (_: Exception) {
                lastError = MegaStartError.Network
                return null
            }
            when (mint) {
                is MegaApi.MintResult.Quota -> {
                    lastError = MegaStartError.Quota
                    return null
                }
                is MegaApi.MintResult.Dead -> {
                    lastError = MegaStartError.Dead
                    return null
                }
                is MegaApi.MintResult.Transport -> {
                    lastError = MegaStartError.Network
                    return null
                }
                is MegaApi.MintResult.Ok -> {
                    val candidate = MegaDecryptProxy(
                        gUrl = mint.mint.gUrl,
                        key = merged,
                        size = size,
                        contentType = descriptor.mimeType ?: "application/octet-stream",
                        fetcher = fetcherOverride?.invoke(mint.mint.gUrl)
                            ?: MegaDecryptProxy.okHttpFetcher(mint.mint.gUrl, client),
                    )
                    if (!candidate.verify(mint.mint.attrsBase64Url)) {
                        candidate.close()
                        lastError = MegaStartError.BadKey
                        return null
                    }
                    val head = try {
                        candidate.readPlain(0L, 16)
                    } catch (_: Exception) {
                        candidate.close()
                        lastError = MegaStartError.Network
                        return null
                    }
                    if (!MegaCrypto.isContainerMagic(head)) {
                        candidate.close()
                        lastError = MegaStartError.BadKey
                        return null
                    }
                    proxy = candidate
                    activeNodeId = nodeId
                    lastError = null
                    return candidate.uriFor().toString()
                }
            }
        }
    }

    /** Stops the proxy and drops the key. Safe to call when nothing is running. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        try {
            proxy?.close()
        } catch (_: Exception) {
            // Best effort: the socket is loopback-only and the key is memory-only.
        }
        proxy = null
        activeNodeId = null
    }
}

/** Why a decrypting copy could not be started. The UI maps each to plain words. */
enum class MegaStartError {
    /** The descriptor is not a decrypting copy at all. */
    NotMega,
    /** The key does not open this file (wrong key or corrupt attrs). */
    BadKey,
    /** MEGA's free quota for this connection is used up (~5 GB / 6 h). */
    Quota,
    /** The file is gone from MEGA (deleted, or the uploader's account closed). */
    Dead,
    /** No answer from MEGA right now (offline, or MEGA unreachable). */
    Network,
}
