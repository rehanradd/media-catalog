package com.raddstream.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.network.models.LiveTvChannelsResponse
import com.raddstream.app.player.PlayerSessionStore
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.ui.screens.categoryLabel
import com.raddstream.app.ui.screens.countryLabel
import com.raddstream.app.ui.screens.languageLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The Live TV tab's state: one server fetch, then client-side filtering.
 *
 * The served list is 759 channels (measured 2026-11-16 — the "17
 * channels" the old comment claimed is years stale), a 167 KB body, so
 * the category chips and the name search filter the rows already on the
 * device instead of a network round-trip per tap. The endpoint's own
 * `?category=` filter stays available for a day the list grows large
 * enough to page.
 *
 * A channel is played by its signed `stream_id` handle through the existing
 * player — this ViewModel never holds, shows or routes an upstream URL.
 *
 * App-design audit #13: the last good list is cached on this device, so a
 * down server shows yesterday's channels (labelled as cached) instead of
 * a dead tab. The tab itself is never hidden — the backend fix for the
 * 503 is a server job; the app's job is to fail gracefully.
 */
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: CatalogRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val channels: List<LiveTvChannel> = emptyList(),
        val categories: List<String> = emptyList(),
        val selectedCategory: String? = null,
        val query: String = "",
        val error: String? = null,
        /** True when [channels] are the saved list, not a fresh fetch. */
        val showingCached: Boolean = false,
        /** When the saved list in [channels] was stored (epoch millis), or null when fresh or unknown. */
        val cachedAtMillis: Long? = null,
    ) {
        /** What the grid shows: the chosen category, then the search across name + language + country + category. */
        val visibleChannels: List<LiveTvChannel>
            get() {
                val byCategory = if (selectedCategory == null) {
                    channels
                } else {
                    channels.filter { it.category == selectedCategory }
                }
                val needle = query.trim().lowercase()
                return if (needle.isEmpty()) {
                    byCategory
                } else {
                    byCategory.filter { liveTvChannelMatches(it, needle) }
                }
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        // Live TV review D1: stale-while-revalidate — paint the saved list
        // at once, then refresh behind it. The skeleton only runs when
        // there is nothing at all to show; the 45 s budget and the one
        // silent retry stay untouched.
        loadCache()?.let { cached ->
            _uiState.update {
                it.copy(
                    channels = cached.channels,
                    categories = cached.categories,
                    showingCached = true,
                    cachedAtMillis = cached.savedAtMillis,
                )
            }
        }
        refresh()
    }

    private var refreshJob: Job? = null

    fun refresh() {
        // Live TV review D9: one fetch at a time — a double-tap Retry must
        // not spawn a second fetch whose late failure could overwrite
        // fresh channels with the cached list + error.
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = fetchChannelsWithRetry()
                saveCache(response.channels, response.categories)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        channels = response.channels,
                        categories = response.categories,
                        error = null,
                        showingCached = false,
                        cachedAtMillis = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Audit #13/#14: plain words, never a raw code — and the
                // last good list stays on screen when there is one.
                val cached = loadCache()
                if (cached != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            channels = cached.channels,
                            categories = cached.categories,
                            error = LIVE_TV_TROUBLE,
                            showingCached = true,
                            cachedAtMillis = cached.savedAtMillis,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, error = LIVE_TV_TROUBLE)
                    }
                }
            }
        }
    }

    /**
     * Screenshot-fix (2026-11-16): the channel list is a 167 KB body and
     * the phone that photographed an empty Live TV tab was on a 2-25 KB/s
     * connection — one stalled read must not be the whole story. One
     * silent retry for a transport failure (a read timeout is an
     * [IOException]) before the error/cache path in [refresh] below; a
     * second failure is the error, not a third attempt. Cancellation
     * aborts at once, never retries.
     */
    private suspend fun fetchChannelsWithRetry(): LiveTvChannelsResponse {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return repository.getLiveTvChannels()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (attempt >= LIVE_TV_FETCH_MAX_ATTEMPTS) throw error
                delay(LIVE_TV_FETCH_RETRY_DELAY_MS)
            }
        }
    }

    private fun saveCache(channels: List<LiveTvChannel>, categories: List<String>) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CACHED_LIST, LiveTvCache.encode(channels, categories))
                .apply()
        }
    }

    private fun loadCache(): LiveTvCache.DecodedCache? = runCatching {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_LIST, null)
        LiveTvCache.decode(json)
    }.getOrNull()

    companion object {
        const val LIVE_TV_TROUBLE =
            "Live TV is having trouble right now. Check your connection or try again."
        private const val PREFS_NAME = "live_tv_cache"
        private const val KEY_CACHED_LIST = "cached_list"
        /** One silent retry of a failed channel fetch — see [fetchChannelsWithRetry]. */
        private const val LIVE_TV_FETCH_MAX_ATTEMPTS = 2
        private const val LIVE_TV_FETCH_RETRY_DELAY_MS = 1_000L
    }

    fun onCategorySelected(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    private var warmJob: Job? = null
    private var lastWarmedHandle: String? = null

    /**
     * Batch C — warm one channel's link in the background, on tap.
     *
     * The tap navigates straight to the player with a cold handle, so the
     * player pays the server's live resolve (~1.6 s for a live channel,
     * measured 2026-11-14) inside "Finding your video". This ask is
     * metadata only (`GET /api/v1/stream?redirect=0`): on a cache miss the
     * server live-resolves the link and banks the answer, so the player's
     * redirector 302s from cache instead. It runs in parallel with the
     * navigation — it never gates it — and a warm that fails is silent:
     * Play still works, it just waits on the first bytes again. Same shape
     * as DetailViewModel.warmScopeLink, and the same guard
     * ([shouldWarmHandle]). No byte seed here — HLS is a live playlist,
     * not a file — and no warm store: the player never calls
     * `resolveStream` at start (it follows the redirector's 302), so the
     * banked server answer is the whole mechanism.
     */
    fun warmChannel(streamId: String) {
        if (!shouldWarmHandle(streamId, lastWarmedHandle)) return
        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            val warmed = runCatching {
                withTimeout(30_000) { repository.resolveStream(streamId) }
            }.isSuccess
            if (warmed) lastWarmedHandle = streamId
        }
    }

    /**
     * Live TV is HLS, always: every channel in the registry serves an
     * `.m3u8` playlist (759/759), and the player opens the bare handle
     * through our extensionless redirector — which carries no `.m3u8` for
     * the reader to infer from. Staging the manifest MIME here is what lets
     * the player declare HLS before the first frame instead of opening the
     * playlist with the progressive reader (`UnrecognizedInputFormatException`,
     * Robo #4). Same in-memory-only discipline as the warm: a playback
     * launched any other way simply has no entry and builds as before.
     */
    fun stageLiveChannel(streamId: String) {
        PlayerSessionStore.Instance.stageManifestMime(streamId, MimeTypes.APPLICATION_M3U8)
        // The player must not trust Media3 alone for live-ness: a channel
        // whose HLS playlist carries an end tag reads as VOD with the DVR
        // window as its duration (Discover Pakistan: 2:00:12 + seek bar).
        // The tap stages the live flag next to the MIME; the ticker ORs
        // it with `isCurrentMediaItemLive`.
        PlayerSessionStore.Instance.stageLiveChannel(streamId)
    }
}

/**
 * Live TV review D2: the search matches the name, the language (code and
 * label — "ur" and "Urdu" both answer "urdu"), the country (code and
 * label — "PK" and "Pakistan" both answer "pakistan"), and the category
 * ("sports" answers the Sports chip's channels). Pure, so the 759-row
 * fixture pins it in a unit test.
 */
internal fun liveTvChannelMatches(channel: LiveTvChannel, needle: String): Boolean {
    if (needle.isEmpty()) return true
    val haystacks = listOfNotNull(
        channel.name,
        channel.language,
        languageLabel(channel.language),
        channel.country,
        countryLabel(channel.country),
        channel.category,
        categoryLabel(channel.category),
    )
    return haystacks.any { it.lowercase().contains(needle) }
}

/**
 * Live TV review D5: the cached list's age in viewer words — "just now",
 * "5 minutes ago", "3 hours ago", "2 days ago". A future timestamp (clock
 * skew) reads as "just now"; anything older than a week reads as "more
 * than a week ago". Pure, so the boundaries are pinned in a unit test.
 */
internal fun formatCacheAge(savedAtMillis: Long, nowMillis: Long): String {
    val diff = nowMillis - savedAtMillis
    if (diff < 60_000L) return "just now"
    val minutes = diff / 60_000L
    if (minutes < 60) return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
    val hours = minutes / 60
    if (hours < 24) return if (hours == 1L) "1 hour ago" else "$hours hours ago"
    val days = hours / 24
    if (days < 7) return if (days == 1L) "1 day ago" else "$days days ago"
    return "more than a week ago"
}
