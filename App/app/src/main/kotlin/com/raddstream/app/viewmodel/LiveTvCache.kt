package com.raddstream.app.viewmodel

import com.google.gson.annotations.SerializedName
import com.raddstream.app.network.buildRaddGson
import com.raddstream.app.network.models.LiveTvChannel

/**
 * App-design audit (2026-11-12, issue #13): the Live TV tab must show
 * something when the server is down — the last good channel list, saved
 * as plain JSON in SharedPreferences. Pure encode/decode, so the cache
 * round-trips in a unit test; the ViewModel only decides when to read it.
 *
 * Live TV review (Nov 2026, D5): the saved list carries its saved-at
 * timestamp, so the cached notice can say how old the list is instead of
 * warning without informing. Caches written before the timestamp existed
 * decode with [DecodedCache.savedAtMillis] null and read as "saved
 * earlier" — never a guessed date.
 */
object LiveTvCache {

    private val gson = buildRaddGson()

    private data class CachedList(
        val channels: List<LiveTvChannel> = emptyList(),
        val categories: List<String> = emptyList(),
        @SerializedName("saved_at")
        val savedAtMillis: Long? = null,
    )

    /**
     * The decoded cache: the channels, the categories, and when the list
     * was saved (epoch millis), or null for a cache written before the
     * timestamp was stored.
     */
    data class DecodedCache(
        val channels: List<LiveTvChannel>,
        val categories: List<String>,
        val savedAtMillis: Long?,
    )

    fun encode(
        channels: List<LiveTvChannel>,
        categories: List<String>,
        savedAtMillis: Long = System.currentTimeMillis(),
    ): String =
        gson.toJson(CachedList(channels, categories, savedAtMillis))

    fun decode(json: String?): DecodedCache? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val cached = gson.fromJson(json, CachedList::class.java)
                ?: return null
            if (cached.channels.isEmpty()) {
                null
            } else {
                DecodedCache(cached.channels, cached.categories, cached.savedAtMillis)
            }
        }.getOrNull()
    }
}
