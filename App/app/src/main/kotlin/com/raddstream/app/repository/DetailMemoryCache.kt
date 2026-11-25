package com.raddstream.app.repository

import com.raddstream.app.network.models.PostDetail

/**
 * App-speed (Nov 2026): the parsed-detail memory cache — the fast layer
 * under the S1 OkHttp disk cache.
 *
 * What it is for: every Detail open builds a new ViewModel, and every one
 * re-fetches the detail — a disk read plus a Gson parse of a 441 KB v2
 * body (or 55–89 KB v1) even inside the HTTP stale window, then the
 * release-layout build on top. A repeat open of a title this process
 * already parsed (back from the player, a Similar-titles hop, a re-tap)
 * should answer from memory instead: zero network calls, zero parse.
 *
 * What it is not: a source of truth. Entries live at most
 * [DETAIL_MEMORY_CACHE_TTL_MS] (10 minutes — a title edited on the server
 * shows within 10 minutes on a repeat open), at most
 * [DETAIL_MEMORY_CACHE_MAX_ENTRIES] titles (a parsed detail with a full
 * release ladder is a few hundred KB; 16 entries is a few MB, bounded),
 * and only in this process — a process death clears it. Failures and
 * nulls are never stored, so a failed fetch cannot poison a later open.
 *
 * The key names the server too: a viewer who switches catalog servers
 * must never be served the old server's detail for the same id. The
 * audio is part of the key because the server orders the tiers by the
 * preferred audio, so the same title in two languages can answer
 * differently.
 *
 * Threading: the repository is a `@Singleton` read from many scopes, so
 * every access is synchronized on the map itself. The map is
 * access-ordered, so the eldest entry is the least-recently-used one.
 */
class DetailMemoryCache(
    private val maxEntries: Int = DETAIL_MEMORY_CACHE_MAX_ENTRIES,
    private val ttlMs: Long = DETAIL_MEMORY_CACHE_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val detail: PostDetail, val cachedAtMs: Long)

    private val entries = object : LinkedHashMap<DetailCacheKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<DetailCacheKey, Entry>,
        ): Boolean = size > maxEntries
    }

    /** The cached detail for [key], or null when absent or stale. A stale entry is dropped on read. */
    @Synchronized
    fun get(key: DetailCacheKey): PostDetail? {
        val entry = entries[key] ?: return null
        if (nowMs() - entry.cachedAtMs > ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.detail
    }

    /** Store [detail] under [key], evicting the least-recently-used entry past the cap. */
    @Synchronized
    fun put(key: DetailCacheKey, detail: PostDetail) {
        entries[key] = Entry(detail, nowMs())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}

/** One parsed detail's identity in the memory cache. */
sealed interface DetailCacheKey {
    /** A v1 post detail: `GET /api/v1/posts/{id}`. */
    data class V1(val postId: Int, val server: String, val audio: String?) : DetailCacheKey

    /** A v2 work detail: `GET /api/v2/works/{id}`. */
    data class V2(val workId: Long, val server: String, val audio: String?) : DetailCacheKey
}

/** The most parsed details one process keeps in memory. */
const val DETAIL_MEMORY_CACHE_MAX_ENTRIES: Int = 16

/** A cached detail stays fresh for 10 minutes. */
const val DETAIL_MEMORY_CACHE_TTL_MS: Long = 10L * 60L * 1000L
