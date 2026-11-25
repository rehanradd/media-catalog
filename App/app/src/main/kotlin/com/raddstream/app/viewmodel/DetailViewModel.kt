package com.raddstream.app.viewmodel

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.UserRegion
import com.raddstream.app.data.cache.CacheCoordinator
import com.raddstream.app.data.cache.ConnectivityProbe
import com.raddstream.app.data.cache.KeepIdentity
import com.raddstream.app.data.cache.NetworkClass
import com.raddstream.app.data.downloads.DeviceDownloadResult
import com.raddstream.app.data.downloads.DeviceDownloadState
import com.raddstream.app.data.downloads.DeviceDownloadsRepository
import com.raddstream.app.data.local.SyncedProgressDao
import com.raddstream.app.data.local.WatchHistoryDao
import com.raddstream.app.data.local.WatchHistoryEntity
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.friendlyNetworkError
import com.raddstream.app.network.models.LinkState
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import com.raddstream.app.network.models.parseCastNames
import com.raddstream.app.network.streamRedirectUrl
import com.raddstream.app.network.withVipPass
import com.raddstream.app.player.AdoptRequest
import com.raddstream.app.player.CacheAffordance
import com.raddstream.app.player.CacheEngineHolder
import com.raddstream.app.player.CacheSessionKey
import com.raddstream.app.player.CastAvailabilityTracker
import com.raddstream.app.player.DataSaver
import com.raddstream.app.player.KeepResult
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.LadderRung
import com.raddstream.app.player.PlayerSessionStore
import com.raddstream.app.player.PreviewRequest
import com.raddstream.app.player.QualityLadder
import com.raddstream.app.player.QualityOption
import com.raddstream.app.player.SubtitleTrackInfo
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.repository.FavoritesRepository
import com.raddstream.app.repository.FollowedSeriesRepository
import com.raddstream.app.repository.TitleFeedbackRepository
import com.raddstream.app.repository.V2DetailResult
import com.raddstream.app.repository.V2PlayResult
import com.raddstream.app.sync.SyncAccountRepository
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class DetailUiState(
    val post: PostDetail? = null,
    /**
     * F9 (Batch B): the list row this detail was opened from, when the
     * detail call itself failed. The error screen names the title and shows
     * its poster from this row instead of a bare "Couldn't load this
     * title" — the row is the server's own, minutes old, and it is never a
     * substitute for the detail: no Play, no prints, only the name and the
     * way back. Null when the row was never in this process's cache (a
     * deep link, or a cold start straight to detail).
     */
    val fallbackPost: Post? = null,
    /**
     * Feel-instant S4: the title this screen was opened from, as a detail
     * — painted the moment the tap lands, before any network call. The
     * header (title, poster, year, type) is real content from the list
     * row the viewer just saw; only the body (links, prints, episodes)
     * skeletons until the fetch lands. Cleared when the real detail
     * arrives; kept when the fetch fails, so the error branch still has
     * its header through [fallbackPost].
     */
    val optimisticDetail: PostDetail? = null,
    val layout: ReleaseLayout? = null,
    /**
     * Detail redesign (Nov 2026): Cast lives on this page now, not in the
     * player — the icon shows only when a receiver is actually found AND
     * the server's chromecast switch is on, the same two facts the player
     * gated its button on. Most viewers (no TV nearby) never see it at
     * all, which is the whole point of the move. Pure via
     * [castAvailableFor] so a test can pin the gate without a ViewModel.
     */
    val castAvailable: Boolean = false,
    val isLoading: Boolean = true,
    val isFavorite: Boolean = false,
    /**
     * E2 slice 2: the "Notify me" bell. True when this work's handle is on
     * the follow list — the daily worker then watches it for a newer
     * season/episode. False for a title with no handle: the bell is hidden
     * there, because a follow with nothing to name the work by could never
     * be checked.
     */
    val isFollowed: Boolean = false,
    /**
     * REA #2 (Netflix, Nov 2026): this title's thumbs vote, 1 (liked),
     * -1 (disliked), 0 (no opinion) — the Detail row's Like/Dislike
     * state, observed from [TitleFeedbackRepository]. A vote feeds the
     * "For you" ranker on the next Home load; it never changes this page.
     */
    val titleThumb: Int = 0,
    /**
     * REA #2: the "97% Match" for this title from the "For you" row on
     * screen ([MatchScoreStore]), or null when no row scored it — a
     * Detail opened from Search, or a cold start straight here, paints
     * no %. Read once when the detail lands; a Home recompute while
     * this page is open does not rewrite it.
     */
    val matchPercent: Int? = null,
    val error: String? = null,
    /**
     * MX #7 — Kids Lock: when on, this detail shows only kid-appropriate
     * titles — the ViewModel refuses the rest (see [DetailViewModel]) and
     * the screen renders the blocked branch instead of the title.
     */
    val kidsMode: Boolean = false,
    /**
     * MX #7 — Kids Lock: true when the loaded title is not kid-appropriate.
     * The screen shows "Not available in Kids Mode" with a way back — no
     * title, no poster, no Play, no retry.
     */
    val kidsBlocked: Boolean = false,
    val actionMessage: String? = null,
    /** One row per quality band, as the server collapsed them. Empty until it has any. */
    val ladder: LadderForScope = LadderForScope.EMPTY,
    /**
     * The viewer's regional-dub preference ([UserRegion]), mirrored so the
     * quality card can lead with the viewer's own audio (audit #32).
     * [UserRegion.DEFAULT] until DataStore answers.
     */
    val region: String = UserRegion.DEFAULT,
    /** The ladder is always the whole work's now: the per-episode quality
     * cards left with the episode rows (detail redesign, Nov 2026), and a
     * series picks its picture in the player. The per-episode ladder for
     * in-player switching is resolved on demand instead — see
     * [DetailViewModel.ladderForEpisode] (2-button detail, P1 fix). */
    /** null / [LadderForScope.AUTO] = let the ladder's own pointer choose the rung. */
    val selectedBand: String? = LadderForScope.AUTO,
    /**
     * APP-02 (Phase 4): the selector's chips, as playable options in
     * ladder order — one per verified-active band (locked bands included;
     * dead bands and provider pages omitted). Empty until the ladder
     * lands, and empty when the title has no playable qualities — then
     * the selector hides itself. Published by
     * [DetailViewModel.publishPrimaryActions] from the same scope Play
     * uses, so the chips and the tap can never disagree.
     */
    val qualityOptions: List<QualityOption> = emptyList(),
    /** APP-02: the bands among [qualityOptions] this viewer may not take — greyed with a lock, never hidden. */
    val lockedQualityBands: Set<String> = emptySet(),
    /** The ledger's answer, mirrored so the quality gate and its upsell read one fact. */
    val vip: Boolean = false,
    /** True when this title has 4K/1080p sources that the current viewer cannot play. */
    val premiumSourcesLocked: Boolean = false,
    /**
     * Why this title cannot be played, in the viewer's own words — null
     * when it can. The detail page paints this instead of a bare "not
     * available": no sources at all, only VIP copies, or no working
     * source. Computed in [DetailViewModel.publishViewerContent] from
     * the ungated layout and the gated one, so it follows entitlement
     * changes without a reload.
     */
    val deadEndReason: String? = null,
    /**
     * Detail review (Nov 2026, P1): the dead end's Download target — the
     * best-ranked print this viewer may actually keep (see
     * [deadEndKeepPrint]). Null when no print qualifies, so the button
     * disables instead of offering a download that cannot start.
     * Computed in [DetailViewModel.publishViewerContent] beside
     * [deadEndReason], so the two can never disagree.
     */
    val deadEndKeepPrint: RankedPrint? = null,
    /**
     * Handles of downloads already on this device (READY rows only),
     * so an episode row can say it is downloaded without asking the
     * store on every frame. A handle is the stream id a print or rung
     * keeps by — the same string [DeviceDownload.handle] carries.
     */
    val downloadedHandles: Set<String> = emptySet(),
    /**
     * Detail review (Nov 2026, P1): handles of downloads moving on this
     * device right now (QUEUED or DOWNLOADING rows), so an episode row
     * can say "Downloading" while its keep runs — a PAUSED row is not
     * moving, so it stays unmarked. Same handle shape as
     * [downloadedHandles], collected in the same pass.
     */
    val downloadingHandles: Set<String> = emptySet(),
    /** The latest scope this device stopped in, if any — the Resume button. */
    val resume: ResumePoint? = null,
    /** True when the Resume button has a print to play; false shows plain Play. */
    val resumePlayable: Boolean = false,
    /**
     * True once the resume lookup has answered — including "never watched".
     * Session 123 (DE-1): `resume` being null is ambiguous (not loaded yet,
     * or never watched), and autoplay must not decide until it knows which.
     * False until [loadResume] settles, on every path out of it.
     */
    val resumeLoaded: Boolean = false,
    /** Per-episode progress, keyed by (season, episode) — the marks on episode rows. */
    val episodeProgress: Map<Pair<Int, Int>, EpisodeProgress> = emptyMap(),
    /**
     * What this viewer can actually watch of this title, as a pill.
     * "VIP" for a VIP viewer or a title whose better copies need VIP,
     * "Free · up to X" for a free viewer with nothing locked, null when
     * the title has no prints at all — then "no playable releases" says
     * it instead — and null when the gate is off, because everything is
     * included and there is nothing to flag.
     */
    val includedPill: String? = null,
    /**
     * 2-button detail (Nov 2026): the open quality sheet, or null when
     * closed. DOWNLOAD still opens one (choosing a file to save needs a
     * choice); APP-02 (Phase 4) added the selector chips above the
     * buttons and a BROWSE sheet that only picks a band, so the PLAY
     * sheet is no longer opened from this page. The two buttons never
     * ask twice.
     */
    val qualitySheet: QualitySheetState? = null,
    /**
     * 2-button detail (Nov 2026): true between a Play tap and the player
     * opening (or a plain failure message). The second tap is ignored
     * while it is true, and the button reads "Starting…".
     */
    val playStarting: Boolean = false,
    /**
     * 2-button detail (Nov 2026): the local file for each downloaded
     * handle (READY rows with a localUri), so a "✓ Downloaded" button
     * can play the saved file instead of starting a new download.
     */
    val downloadedLocalUris: Map<String, String> = emptyMap(),
    /**
     * 2-button detail (Nov 2026): the two buttons, computed in
     * [DetailViewModel.publishPrimaryActions] from the same state the
     * decision tree reads — so the label, the enabled state and what
     * the tap does can never disagree.
     */
    val playLabel: String = "Play",
    val playEnabled: Boolean = false,
    /** The plain line under Play: why it is greyed, or where it will play. */
    val playHint: String? = null,
    /**
     * Audit Detail #1 (Nov 2026): the Video row's value — the picked
     * band, or what Auto reaches. Null when this title has no playable
     * qualities to choose from, so the row hides. Computed in
     * [DetailViewModel.publishPrimaryActions] from the same scope Play
     * reads, so the row and the tap can never disagree.
     */
    val videoQualityText: String? = null,
    val downloadLabel: String = "Download",
    val downloadEnabled: Boolean = false,
    /** The plain line under Download: why it is greyed, or what it will save. */
    val downloadHint: String? = null,
    /**
     * 2-button detail (Nov 2026): the local file the Download button
     * plays when it reads "✓ Downloaded" — null when nothing for this
     * title is on the device yet.
     */
    val downloadedUri: String? = null,
    /**
     * 2-button detail (Nov 2026): the snackbar's action button — the
     * label on it, and what it does. Null = no action. The download
     * snackbar's "See downloads" / "Free space" both land on the
     * Downloads screen; nothing ever auto-navigates away from detail.
     */
    val snackbarActionLabel: String? = null,
    val snackbarAction: SnackbarAction? = null,
    /**
     * The viewer's own Wi-Fi-only downloads switch, mirrored so the keep
     * confirm can say "Wi-Fi only" only when it is actually on. On by
     * default, like the switch itself — a viewer who turned it off must
     * never read a line that claims the download waits for Wi-Fi.
     */
    val wifiOnlyDownloads: Boolean = true,
    val isReportingIssue: Boolean = false,
    /** F18: related titles from GET /api/v1/related, excluding the active work. */
    val similarTitles: List<Post> = emptyList(),
    val similarLoading: Boolean = false,
)

/**
 * A download about to start. The size comes from the one metadata ask
 * that also decides which download system owns the copy, so the start
 * never guesses it; null means the server did not name a size, and the
 * download proceeds without one. 2-button detail (Nov 2026): the sheet
 * row is the confirmation, so nothing waits on this any more — keep()
 * hands it straight to the starter.
 *
 * Public because it is part of the public [DetailUiState] shape: a
 * public state class cannot expose an internal type, and neither can
 * its own `target` — so [KeepTarget] is public too.
 */

data class PendingKeep(
    val handle: String,
    val printLabel: String,
    val title: String,
    val sizeBytes: Long?,
    val resumable: Boolean,
    val target: KeepTarget,
)

/** One episode inside a season download: its best keepable copy. */
data class SeasonKeepItem(
    val handle: String,
    val printLabel: String,
    val title: String,
    val sizeBytes: Long?,
)

/**
 * A whole season waiting for the viewer's Confirm. The size is the sum
 * of the episode sizes the server actually named — null when none of
 * them named one, so the dialog says "Size unknown" instead of
 * inventing a total. [lockedCount] is how many episodes of the season
 * this viewer may not keep (VIP-only copies); the dialog names them so
 * a season that downloads 8 of 10 episodes never reads as 10 of 10.
 */
data class PendingSeasonKeep(
    val seasonNumber: Int,
    val items: List<SeasonKeepItem>,
    val lockedCount: Int,
    val totalSizeBytes: Long?,
)

/**
 * Why a title cannot be played, in the viewer's own words — null when
 * it can. The answers, in order: the catalog holds nothing for
 * this title at all; it holds only VIP versions; it holds versions but none
 * this viewer may take; or it holds versions that cannot play, with a
 * download still on the table when at least one of them is keepable.
 * Pure so a test can pin every branch without a ViewModel.
 */
internal fun deadEndReasonFor(
    hasReleases: Boolean,
    gatedHasPrints: Boolean,
    gatedPlayable: Boolean,
    gatedKeepable: Boolean,
    premiumLocked: Boolean,
): String? = when {
    !hasReleases -> "No versions for this title yet."
    !gatedHasPrints && premiumLocked -> "Only VIP versions exist for this title."
    !gatedHasPrints -> "No versions available for this title right now."
    !gatedPlayable && gatedKeepable -> "No working versions right now — download is available."
    !gatedPlayable -> "No working versions right now."
    else -> null
}

/**
 * Detail review (Nov 2026, P1): the dead end's Download target — the
 * best-ranked print this viewer may actually keep. The gate must allow
 * its resolution (the same 3-arg check [seasonKeepPlan] applies) and it
 * must carry a keepable link (a real file, not a provider page — the
 * same [RankedLink.keepable] the dead-end reason counts). The
 * best-ranked print is not the answer: on a gated title it can be a 4K
 * copy this viewer may not keep, while a 720p copy below it keeps fine.
 * Null when no print qualifies, so the button disables. Pure so
 * DetailDeadEndReasonTest pins it.
 */
internal fun deadEndKeepPrint(
    prints: List<RankedPrint>,
    premiumAccess: Boolean,
    freeQualityCap: String,
): RankedPrint? = prints
    .filter { VipQualityGate.allowsResolution(it.resolution, premiumAccess, freeQualityCap) }
    .filter { print -> print.links.any { it.keepable } }
    .maxByOrNull { it.rankKey }

/** The season-download plan: what a season keep would take, before Confirm. */
data class SeasonKeepPlan(
    val items: List<SeasonKeepItem>,
    val lockedCount: Int,
    val totalSizeBytes: Long?,
)

/**
 * Plan a whole-season download: one best keepable copy per episode.
 * An episode whose best copy is VIP-locked for this viewer counts as
 * locked, never as downloadable; a packed-only episode is skipped (its
 * file is the season pack, kept from its own banner); an episode with
 * no keepable copy at all is skipped silently — the dialog's count is
 * the episodes that will actually download. Pure so a test can pin the
 * selection without a repository.
 */
internal fun seasonKeepPlan(
    season: SeasonGroup,
    premiumAccess: Boolean,
    freeQualityCap: String,
): SeasonKeepPlan {
    var locked = 0
    val items = season.episodes.mapNotNull { episode ->
        if (episode.onlyPacked) return@mapNotNull null
        val best = episode.prints.maxByOrNull { it.rankKey } ?: return@mapNotNull null
        // The same gate keepPrint applies: a locked copy is never a
        // download, even when it is the episode's only print.
        if (!VipQualityGate.allowsResolution(best.resolution, premiumAccess, freeQualityCap)) {
            locked++
            return@mapNotNull null
        }
        val link = best.links.firstOrNull { !it.streamId.isBlank() && !it.isPageLink }
            ?: return@mapNotNull null
        SeasonKeepItem(
            handle = link.streamId,
            printLabel = best.qualityLabel.orEmpty(),
            title = episode.title ?: "Episode ${episode.numberWithinSeason}",
            sizeBytes = best.sizeBytes,
        )
    }
    val sizes = items.mapNotNull { it.sizeBytes?.takeIf { size -> size > 0 } }
    return SeasonKeepPlan(
        items = items,
        lockedCount = locked,
        totalSizeBytes = sizes.sum().takeIf { sizes.isNotEmpty() },
    )
}

/** The size line of the keep-confirm dialog: a real size, or an honest unknown. */
fun keepSizeLabel(sizeBytes: Long?): String =
    sizeBytes?.takeIf { it > 0 }?.let { CacheAffordance.formatBytes(it) } ?: "Size unknown"

/**
 * The season-download confirm's one line: how many episodes, their total
 * size, and the Wi-Fi-only note only when the viewer's own switch is on.
 * "12 episodes · 4.20 GB · Wi-Fi only"; a total no episode named says so
 * instead of inventing one. 2-button detail (Nov 2026): the dialog is
 * gone, but this line still names the whole-season row in the Download
 * sheet — same words, same honesty.
 */
fun seasonKeepConfirmLine(pending: PendingSeasonKeep, wifiOnly: Boolean): String {
    val count = "${pending.items.size} episode${if (pending.items.size == 1) "" else "s"}"
    val parts = listOf(count, keepSizeLabel(pending.totalSizeBytes)) +
        if (wifiOnly) listOf("Wi-Fi only") else emptyList()
    return parts.joinToString(" · ")
}

/**
 * What a keep request needs is a file. A page is not one: DownloadManager would fetch HTML and
 * the Downloads row would then claim a film had been kept. The screen offers the page instead,
 * and says why it is not in the kept list.
 */
private const val PAGE_NOT_A_FILE =
    "This source is a page on the provider's site, not a file — open it instead of keeping it."

/** 2-button detail (Nov 2026): what the snackbar's action button does. */
enum class SnackbarAction {
    /** Open the Downloads screen — for "See downloads" and "Free space" alike. */
    SEE_DOWNLOADS,
}

sealed class DetailNavigationEvent {
    /** Navigate by OPAQUE HANDLE only — a URL in a route would live in the back stack. */
    data class PlayStream(val streamId: String, val title: String) : DetailNavigationEvent()

    /** The one hand-off this app makes, and it carries OUR OWN address. */
    data class OpenExternal(val streamId: String, val playUrl: String) : DetailNavigationEvent()

    /**
     * The server says this link is a page, not a file (`ProviderPageLinks`), so our player has
     * nothing to load. The provider's own page is shown, in the foreground, with its own controls
     * and its own conditions. It carries the OPAQUE HANDLE for the reason [PlayStream] does: the
     * screen rebuilds our redirector from the server the viewer connected to, and no upstream
     * address ever enters a back stack.
     */
    data class OpenProviderPage(val streamId: String, val title: String) : DetailNavigationEvent()

    /**
     * 2-button detail (Nov 2026): play a file already on this device
     * ("✓ Downloaded" on the Download button). The local Uri travels
     * the route — it is our own file, never an upstream address.
     */
    data class PlayLocal(val uri: String, val title: String) : DetailNavigationEvent()

    /**
     * 2-button detail (Nov 2026): open the Downloads screen from a
     * snackbar action ("See downloads" / "Free space"). Downloads
     * never auto-navigate away from detail — only this tap does.
     */
    data object OpenDownloads : DetailNavigationEvent()

    /** A legacy device must grant storage before a public download can start. */
    data object DownloadNeedsPermission : DetailNavigationEvent()
}

/**
 * Feel-instant S4: one list row as the detail it will become — the header
 * fields only (title, poster, year, type, and the artwork, genres, rating
 * and handle the header paints). Everything the fetch still owes stays
 * absent: no releases, no synopsis, no cast, no runtime. Nothing here is
 * invented — every field is the row's own, and an empty release list is
 * the honest "not loaded yet", never "nothing to play". Pure so a test
 * can pin it without a ViewModel.
 */
internal fun optimisticDetailFromPost(post: Post): PostDetail = PostDetail(
    id = post.id,
    title = post.title,
    cleanTitle = post.cleanTitle,
    displayTitleWire = post.displayTitleWire,
    year = post.year,
    contentType = post.contentType,
    seasons = post.seasons,
    posterUrl = post.posterUrl,
    backdropUrls = post.backdropUrls,
    imdbRating = post.imdbRating,
    imdbId = post.imdbId,
    tmdbId = post.tmdbId,
    genres = post.genres,
    isAnimated = post.isAnimated,
    industry = post.industry,
    country = post.country,
    language = post.language,
    workRef = post.workRef,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val catalogRepository: CatalogRepository,
    private val favoritesRepository: FavoritesRepository,
    private val followedSeriesRepository: FollowedSeriesRepository,
    private val titleFeedbackRepository: TitleFeedbackRepository,
    private val deviceDownloads: DeviceDownloadsRepository,
    private val apiProvider: ApiProvider,
    private val syncAccountRepository: SyncAccountRepository,
    private val settingsRepository: ServerSettingsRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val syncedProgressDao: SyncedProgressDao,
    private val connectivityProbe: ConnectivityProbe,
) : ViewModel() {

    private val postId: Int = (savedStateHandle[Screen.Detail.ARG_POST_ID] as? String)?.toIntOrNull()
        ?: (savedStateHandle[Screen.Detail.ARG_POST_ID] as? Int)
        ?: -1

    /**
     * Phase 7 (M1): the v2 work id, when this screen was opened from a v2 list.
     * -1 means "not a v2 row" — the v1 path below. A nav argument arrives as a
     * Long from the typed argument, or as a String from a deep link; both read.
     */
    private val workId: Long = (savedStateHandle[Screen.Detail.ARG_WORK_ID] as? Long)
        ?: (savedStateHandle[Screen.Detail.ARG_WORK_ID] as? String)?.toLongOrNull()
        ?: (savedStateHandle[Screen.Detail.ARG_WORK_ID] as? Int)?.toLong()
        ?: -1L

    /** The v1 post id the favorite/resume/adoption calls may use — 0 until the v2 bridge resolves. */
    private var resolvedPostId: Int = postId

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState = _uiState.asStateFlow()

    // Detail redesign (Nov 2026) — Cast: the server's chromecast switch
    // and the network's discoverable receivers, as the ViewModel last saw
    // them. The same two facts the player gated its button on, minus the
    // lock (this page has no player lock to honour — the service still
    // enforces its own gate at transfer time).
    private var castFeatureEnabled: Boolean = true
    private var castDevicesAvailable: Boolean = false
    private val castAvailabilityTracker = CastAvailabilityTracker(appContext)

    private fun recomputeCastAvailable() {
        _uiState.update {
            it.copy(castAvailable = castAvailableFor(castDevicesAvailable, castFeatureEnabled))
        }
    }

    private fun observeCastAvailable() {
        viewModelScope.launch {
            settingsRepository.castFeatureEnabled.collect { enabled ->
                castFeatureEnabled = enabled
                recomputeCastAvailable()
            }
        }
        viewModelScope.launch {
            castAvailabilityTracker.devicesAvailable.collect { available ->
                castDevicesAvailable = available
                recomputeCastAvailable()
            }
        }
    }

    private val _navEvents = MutableSharedFlow<DetailNavigationEvent>()
    val navEvents: SharedFlow<DetailNavigationEvent> = _navEvents.asSharedFlow()

    init {
        // B5: opening any title is the first browse — the first-run Pick 3
        // card waits for it, so a viewer who came from Search or Similar
        // unlocks it too, not only a Home tap. Fire-and-forget, same as the
        // other one-time flags; the write is idempotent.
        viewModelScope.launch {
            runCatching { settingsRepository.setFirstBrowseDone(true) }
        }
        loadDetail()
        observeFavorite()
        observeEntitlement()
        observeDownloads()
        observeCastAvailable()
    }

    /**
     * Which episodes are already on this device: READY downloads only,
     * by their handle. A row still downloading is not downloaded yet —
     * the check appears the moment the bytes land, not while they move.
     * The moving ones ride along as [DetailUiState.downloadingHandles],
     * so the row can say "Downloading" while the keep runs (Detail
     * review, Nov 2026, P1).
     */
    private fun observeDownloads() {
        viewModelScope.launch {
            deviceDownloads.downloads.collect { rows ->
                _uiState.update {
                    it.copy(
                        downloadedHandles = rows
                            .filter { row -> row.state == DeviceDownloadState.READY && row.handle.isNotBlank() }
                            .map { it.handle }
                            .toSet(),
                        downloadingHandles = rows
                            .filter { row ->
                                (row.state == DeviceDownloadState.QUEUED ||
                                    row.state == DeviceDownloadState.DOWNLOADING) &&
                                    row.handle.isNotBlank()
                            }
                            .map { it.handle }
                            .toSet(),
                        // 2-button detail (Nov 2026): the local file per
                        // downloaded handle, so "✓ Downloaded" plays it.
                        downloadedLocalUris = rows
                            .filter { row ->
                                row.state == DeviceDownloadState.READY &&
                                    row.handle.isNotBlank() &&
                                    !row.localUri.isNullOrBlank()
                            }
                            .associate { it.handle to it.localUri!! },
                    )
                }
                // The Download button flips to "✓ Downloaded" the moment
                // the file lands — its label is computed, not stored.
                publishPrimaryActions()
            }
        }
    }

    private fun observeEntitlement() {
        viewModelScope.launch {
            syncAccountRepository.entitlement.collect { earned ->
                serverVip = earned?.vip == true
                publishViewerContent()
            }
        }
        viewModelScope.launch {
            settingsRepository.rewardedVipExpiresAt.collect { expiresAt ->
                rewardedExpiresAt = expiresAt
                publishViewerContent()
            }
        }
        viewModelScope.launch {
            settingsRepository.qualityGateEnabled.collect { enabled ->
                qualityGateEnabled = enabled
                publishViewerContent()
            }
        }
        viewModelScope.launch {
            settingsRepository.freeQualityCap.collect { cap ->
                freeQualityCap = cap
                publishViewerContent()
            }
        }
        viewModelScope.launch {
            settingsRepository.dataSaver.collect { enabled ->
                dataSaver = enabled
            }
        }
        viewModelScope.launch {
            settingsRepository.wifiOnlyDownloads.collect { enabled ->
                wifiOnlyDownloads = enabled
                _uiState.update { it.copy(wifiOnlyDownloads = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.userRegion.collect { region ->
                userRegion = region
                _uiState.update { it.copy(region = region) }
                publishViewerContent()
            }
        }
        viewModelScope.launch {
            settingsRepository.downloadsFeatureEnabled.collect { enabled ->
                downloadsEnabled = enabled
            }
        }
        // MX #7 — Kids Lock: the live flag. Turning it on while a detail
        // is open re-checks the title on screen — a non-appropriate one
        // blocks at once instead of waiting for a reload. Turning it off
        // while blocked reloads the title the mode had hidden.
        viewModelScope.launch {
            settingsRepository.kidsModeActive.collect { active ->
                val wasBlocked = _uiState.value.kidsBlocked
                _uiState.update { it.copy(kidsMode = active) }
                if (active) {
                    val current = rawDetail
                    if (current != null && !KidsLock.isKidAppropriate(current)) {
                        blockDetail()
                    }
                } else if (wasBlocked) {
                    loadDetail()
                }
            }
        }
    }

    private var serverVip: Boolean = false
    private var rewardedExpiresAt: Long = 0L

    /**
     * The operator's switch, not this screen's opinion. On is the shipped default — the
     * fail-closed mirror of the server gate (seeded and live ON): until remote config
     * says otherwise, a free viewer is never offered a rung the server will 402.
     * [premiumAccess] is what every gate call below actually passes.
     */
    private var qualityGateEnabled: Boolean = true
    private var freeQualityCap: String = "720p"
    /** Data Saver and Wi-Fi-only downloads — both on by default, both the viewer's own choice. */
    private var dataSaver: Boolean = true
    private var wifiOnlyDownloads: Boolean = true

    /** True while the network is Wi-Fi; anything else is mobile data for these two switches. */
    private val onWifi: Boolean get() = connectivityProbe.current() == NetworkClass.WIFI

    /**
     * The viewer's regional-dub preference; [UserRegion.DEFAULT] until DataStore answers,
     * which already is the shipped default. It only reorders the release selector.
     */
    private var userRegion: String = UserRegion.DEFAULT

    /**
     * The operator's `feature_flags.downloads`, on until the board says otherwise. Obeyed at
     * [keep], the one choke point every keep affordance on this screen funnels through, so
     * switching the feature off stops bytes reaching the device rather than merely hiding a row.
     */
    private var downloadsEnabled: Boolean = true

    /** Who may see the top rungs: a confirmed member holding a live VIP pass, or everyone while the switch is off. */
    private val premiumAccess: Boolean get() =
        VipQualityGate.premiumAccess(
            qualityGateEnabled,
            // Session 153: the entitlement flag alone does not open them —
            // the stream serves a gated band only to a caller carrying the
            // pass, so a `vip=true` without one is a free viewer for quality
            // purposes. Every gate call below reads this one property, so the
            // ladder, the card, the player Auto and the preresolve all agree.
            qualityVipAccess(serverVip, syncAccountRepository.hasLiveVipPass()),
        )

    /** The ungated catalog answer; every gated view is derived from it on entitlement change. */
    private var rawDetail: PostDetail? = null

    /**
     * Rebuild the presentation from the raw detail and the current viewer. Both the print list
     * and the quality ladder go through [VipQualityGate]; a VIP viewer gets the original objects
     * back unchanged, so this costs nothing on the ordinary paid path.
     */
    private fun publishViewerContent() {
        val detail = rawDetail
        if (detail == null) {
            return
        }
        val artwork = detail.backdropList.ifEmpty {
            listOfNotNull(detail.posterUrl?.takeIf(String::isNotBlank))
        }
        val full = ReleaseLayoutBuilder.build(
            detail.contentType,
            detail.releases,
            artwork,
            detail.seasonArtwork,
            region = userRegion,
            episodeGuide = detail.episodeGuide,
        )
        val state = _uiState.value
        // Detail redesign (Nov 2026): the ladder is the whole work's —
        // the per-episode quality cards left with the episode rows, and a
        // series picks its picture in the player.
        val scope = QualityLadder.forNumberedScope(
            detail.optionGroups,
            null,
            null,
        )
        val premiumLocked = qualityGateEnabled && !serverVip && (
            VipQualityGate.hasPlayablePremiumPrints(full, freeQualityCap) ||
                scope.options.any {
                    (VipQualityGate.isPremiumBand(it.band, freeQualityCap) || VipQualityGate.isPremiumLabel(it.title, freeQualityCap)) &&
                        it.band !in scope.downloadOnlyBands
                }
            )
        // Detail review (Nov 2026, P1): what this viewer can watch, as
        // a pill, not a sentence — VIP reads as one word, a free viewer
        // reads the cap. The free cap is the operator's, so the pill
        // names it rather than a hardcoded 720p.
        val hasPrints = full.workPrints.isNotEmpty() ||
            full.seasons.any { it.hasAnyPrint }
        val includedPill = if (!hasPrints) {
            null
        } else if (!qualityGateEnabled) {
            // F4 (user decision 04:42 PKT 20 Nov): with the gate off, free
            // 4K for everyone, so no pill at all — not even for a VIP
            // viewer, whose "VIP" would imply premium content that isn't
            // gated. The pill returns automatically when the gate is
            // switched on.
            null
        } else if (serverVip || premiumLocked) {
            "VIP"
        } else {
            "Free · up to $freeQualityCap"
        }
        // The gated layout is computed once and shared: the state update
        // below and the dead-end reason both read it, so the page and its
        // reason can never disagree about what this viewer may take.
        val gatedLayout = VipQualityGate.gate(full, premiumAccess, freeQualityCap)
        val gatedPrints = gatedLayout.workPrints +
            gatedLayout.seasons.flatMap { season -> season.episodes.flatMap { it.prints } + season.packedPrints }
        val deadEndReason = deadEndReasonFor(
            hasReleases = full.totalPrints > 0,
            gatedHasPrints = gatedPrints.isNotEmpty(),
            // RankedPrint.canStart lives in DetailScreen; here it is the
            // same two facts spelled out (a file that plays, or a page).
            gatedPlayable = gatedPrints.any { it.playableHere || it.opensProviderPage },
            gatedKeepable = gatedPrints.any { print -> print.links.any { it.keepable } },
            premiumLocked = premiumLocked,
        )
        _uiState.update {
            it.copy(
                layout = gatedLayout,
                ladder = VipQualityGate.gate(scope, premiumAccess, freeQualityCap),
                vip = serverVip,
                premiumSourcesLocked = premiumLocked,
                includedPill = includedPill,
                deadEndReason = deadEndReason,
                deadEndKeepPrint = deadEndKeepPrint(gatedPrints, premiumAccess, freeQualityCap),
            )
        }
        // 2-button detail (Nov 2026): the two buttons are computed from
        // the same gated layout — labels, enabled state and the tap
        // decision can never disagree about what this viewer may take.
        publishPrimaryActions()
        loadResume()
    }

    /**
     * The resume point and episode marks for this title, from the history
     * this device wrote. A title never watched has neither, and the screen
     * shows a plain Play button instead of a Resume one.
     */
    private fun loadResume() {
        val workRef = _uiState.value.post?.workRef?.takeIf { it.isNotBlank() }
        if (workRef == null) {
            // No work identity, so no history to look up — the lookup is
            // settled with "never watched", not left hanging.
            _uiState.update { it.copy(resumeLoaded = true) }
            publishPrimaryActions()
            return
        }
        viewModelScope.launch {
            val rows = runCatching { watchHistoryDao.findWork(workRef) }.getOrNull()
            if (rows == null) {
                _uiState.update { it.copy(resumeLoaded = true) }
                publishPrimaryActions()
                return@launch
            }
            val resume = resumePointFor(rows)
            // The button only shows when its print is still on the page:
            // a resume whose episode lost every print is a dead button.
            val playable = _uiState.value.layout?.let { resumePrintFor(it, resume) != null } ?: false
            _uiState.update {
                it.copy(
                    resume = resume,
                    resumePlayable = playable,
                    resumeLoaded = true,
                    episodeProgress = episodeProgressFor(rows),
                )
            }
            // 2-button detail (Nov 2026): the Play label flips to Resume
            // the moment history answers — it is computed, not stored.
            publishPrimaryActions()
        }
    }

    /** Play the Resume button's episode: its best print, not the work's. */
    fun playResume() {
        val layout = _uiState.value.layout ?: return
        val print = resumePrintFor(layout, _uiState.value.resume) ?: return
        playPrint(print)
    }

    // ------------------------------------------------------------------
    // 2-button detail (Nov 2026)
    //
    // The detail page has exactly two buttons — Play and Download.
    // Quality is chosen on the page itself now (APP-02, Phase 4: the
    // selector chips above the buttons, or the browse sheet they open) —
    // the pre-play sheet is gone, so the first tap plays at once
    // (APP-01). The helpers these entry points share live in
    // TwoButtonDetail.kt.
    // ------------------------------------------------------------------

    /**
     * P1 fix: the ladder for one episode, resolved from the detail's own
     * option groups and gated the same way the whole-work ladder is.
     * The detail ladder is built whole-work scope, so for a
     * multi-episode series it is EMPTY and nothing staged from it arms
     * the in-player switch — this is the ladder a series episode
     * actually plays from, and the one [playV2Bundle] stages.
     */
    fun ladderForEpisode(season: Int, episode: Int): LadderForScope {
        val groups = rawDetail?.optionGroups ?: return LadderForScope.EMPTY
        return ladderForEpisodeScope(groups, season, episode, premiumAccess, freeQualityCap)
    }

    /**
     * Audit Detail #1 (Nov 2026): the ladder the Video row chooses
     * from — the next episode's for a series (the same scope Play
     * uses), the whole work's otherwise. A series episode with no
     * ladder falls back to the whole-work ladder, which is EMPTY for a
     * multi-episode series by design — so the row hides there instead
     * of offering another episode's qualities.
     */
    private fun videoScopeLadder(state: DetailUiState = _uiState.value): LadderForScope {
        val layout = state.layout ?: return state.ladder
        if (!layout.isSeriesLike) return state.ladder
        val next = nextUnwatchedEpisode(layout, state.episodeProgress) ?: return state.ladder
        val episodeLadder = ladderForEpisode(next.first, next.second)
        return if (episodeLadder.isEmpty) state.ladder else episodeLadder
    }

    /**
     * The two buttons, computed from the same state the decision tree
     * reads — labels, enabled state and hints can never disagree with
     * what the tap does. Called whenever the inputs change: detail
     * load, entitlement change, resume landing, download landing.
     */
    private fun publishPrimaryActions() {
        val state = _uiState.value
        val layout = state.layout
        val resume = state.resume.takeIf { state.resumePlayable }
        val bestPrint = layout?.let { bestPlayablePrintOf(it, premiumAccess, freeQualityCap) }
        // APP-02: the selector's chips, from the same scope Play uses —
        // and the selection re-validated against them, so a stale or
        // newly-locked band falls back to Auto instead of labelling a
        // button with a quality the tap cannot play.
        val chips = qualityChipsFor(selectorLadder(), premiumAccess, freeQualityCap)
        val resolvedBand = resolveSelectedBand(chips, state.selectedBand)
        val allPrints = layout?.let {
            it.workPrints +
                it.seasons.flatMap { season -> season.episodes.flatMap { e -> e.prints } + season.packedPrints }
        }.orEmpty()
        val providerOnly = bestPrint == null && allPrints.any { it.opensProviderPage }
        val canPlay = resume != null || bestPrint != null

        // --- Play -------------------------------------------------------
        // Audit Detail #1 (Nov 2026): the button no longer bakes a
        // quality into its label — the Video row below it carries the
        // quality now ("Video: 720p" / "Video: Auto · up to 1080p").
        // Resume keeps its episode + time left: that is resume info,
        // not a quality claim.
        var playLabel: String
        var playHint: String? = null
        when {
            resume != null -> {
                playLabel = resume.label
            }
            layout?.isSeriesLike == true -> {
                val next = nextUnwatchedEpisode(layout, state.episodeProgress)
                if (next == null) {
                    playLabel = "Play"
                    playHint = state.deadEndReason ?: "No working link right now. Try again later."
                } else {
                    val (season, episode) = next
                    val episodeLadder = ladderForEpisode(season, episode)
                    // APP-02: the picked band names the episode's Play
                    // button, else the ladder's own Auto pointer — same
                    // precedence as the tap itself (explicit tap > Auto).
                    val band = selectedRungFor(episodeLadder, resolvedBand)?.band
                        ?: episodeLadder.let { it.effectiveAutoBand ?: it.autoBand }
                        ?: episodePlayPrint(layout, season, episode)?.resolution?.wire
                    playLabel = if (band != null) "Play S$season E$episode · $band" else "Play S$season E$episode"
                }
            }
            state.ladder.options.any { isHlsOption(it) && it.playsHere && it.streamId.isNotBlank() } -> {
                // HLS available: the tap starts Auto — the quality
                // changes in-player.
                playLabel = "Play"
                playHint = "Plays instantly, quality changes while playing."
            }
            else -> {
                // APP-02: the picked band names the Play button, else the
                // ladder's own Auto pointer — same precedence as the tap
                // itself. (The HLS branch above still reads "Play · Auto":
                // that tap plays the bundle, not the picked rung.)
                val band = selectedRungFor(state.ladder, resolvedBand)?.band
                    ?: state.ladder.let { it.effectiveAutoBand ?: it.autoBand }
                    ?: bestPrint?.resolution?.wire
                playLabel = if (band != null) "Play · $band" else "Play"
            }
        }
        if (providerOnly) {
            playLabel = "Watch on the provider's page"
            playHint = "This title plays on the provider's own page, with its own player and ads."
        }
        val playEnabled = canPlay || providerOnly
        if (!playEnabled) {
            playHint = state.deadEndReason ?: "No working link right now. Try again later."
        }

        // --- Download ---------------------------------------------------
        // Audit Detail #2 (Nov 2026): the button names its cost before
        // the tap — the band and the size the server named, never
        // invented. A series names its episode too. The band is the
        // viewer's Video-row pick when one is set, else the sheet's
        // recommended band.
        val downloadedUri = downloadedUriForTitle()
        val videoLadder = videoScopeLadder(state)
        val videoQualityText = videoQualityTextFor(videoLadder, state.selectedBand)
        // APP-02: the Download button names the picked band — and its
        // size when the server named one, never invented (audit Detail
        // #2: the blind download). Auto keeps the bare "Download".
        val downloadLabel = if (downloadedUri != null) {
            "✓ Downloaded"
        } else if (resolvedBand != LadderForScope.AUTO) {
            val picked = chips.firstOrNull { it.band == resolvedBand }
            val size = picked?.option?.sizeBytes?.takeIf { it > 0 }
                ?.let { " · ${QualityOption.formatBytes(it)}" }.orEmpty()
            "Download · $resolvedBand$size"
        } else {
            "Download"
        }
        val downloadEnabled = when {
            state.kidsMode -> false
            layout == null -> false
            layout.isSeriesLike -> {
                val next = nextUnwatchedEpisode(layout, state.episodeProgress)
                next != null && (
                    ladderForEpisode(next.first, next.second).options
                        .any { it.streamId.isNotBlank() && !it.isProviderPage } ||
                        episodeHasKeepable(layout, next.first, next.second)
                    )
            }
            else -> state.ladder.options.any { it.streamId.isNotBlank() && !it.isProviderPage } ||
                state.deadEndKeepPrint != null
        }
        val downloadHint = when {
            state.kidsMode -> "Downloads are off in Kids Mode."
            downloadedUri != null -> "Saved on this device — tap to play it."
            layout?.isSeriesLike == true -> "Save the next episode or the whole season."
            !downloadEnabled -> "Nothing to save for this title."
            else -> null
        }

        _uiState.update {
            it.copy(
                playLabel = playLabel,
                playEnabled = playEnabled,
                playHint = playHint,
                videoQualityText = videoQualityText,
                downloadLabel = downloadLabel,
                downloadEnabled = downloadEnabled,
                downloadHint = downloadHint,
                downloadedUri = downloadedUri,
                // APP-02: the selector's chips and the re-validated
                // selection — published beside the labels, from the same
                // inputs, so the chips and the buttons can never disagree.
                qualityOptions = chips.map { chip -> chip.option },
                lockedQualityBands = chips.filter { chip -> chip.isLocked }.map { chip -> chip.band }.toSet(),
                selectedBand = resolvedBand,
            )
        }
    }

    /** True when one episode has a copy this viewer may keep. */
    private fun episodeHasKeepable(layout: ReleaseLayout, season: Int, episode: Int): Boolean =
        layout.seasons.firstOrNull { it.number == season }
            ?.episodes?.firstOrNull { it.numberWithinSeason == episode }
            ?.prints?.any { print -> print.links.any { it.keepable } } ?: false

    /**
     * The local file the Download button plays when it reads "✓
     * Downloaded": a copy of this title already on this device. For a
     * series it is the next episode's copy only — an earlier episode's
     * file must never answer a tap that means "the next one".
     */
    fun downloadedUriForTitle(): String? {
        val state = _uiState.value
        val layout = state.layout ?: return null
        val handles = if (layout.isSeriesLike) {
            val next = nextUnwatchedEpisode(layout, state.episodeProgress) ?: return null
            episodePlayPrint(layout, next.first, next.second)?.links?.map { it.streamId }.orEmpty()
        } else {
            val prints = layout.workPrints +
                layout.seasons.flatMap { season -> season.episodes.flatMap { e -> e.prints } + season.packedPrints }
            prints.flatMap { it.links }.map { it.streamId }
        }
        return handles.firstNotNullOfOrNull { state.downloadedLocalUris[it] }
    }

    /**
     * The Play button's one entry point. The decision tree (§6.2):
     * resume first, then the next unwatched episode for a series, then
     * the ladder for a movie — HLS plays at once on Auto, and any direct
     * file plays at once now (APP-01, Phase 4): the picked rung when the
     * viewer chose one, else the ladder's first-tap rung. The pre-play
     * sheet is gone from this path. Nothing playable leaves the button
     * greyed; this function is only reached while it is enabled.
     */
    fun playPrimary() {
        if (_uiState.value.playStarting) return
        val state = _uiState.value
        // Review RISK-g: resume outranks the band pick on purpose —
        // continue-watching resumes the copy in progress, it does not
        // start a fresh play. The pick steers fresh plays only. The
        // label names the requested band; Data Saver may still cap the
        // actual start rung player-side, where the quality pill shows
        // the real band (PlayerViewModel.armQualityChoices).
        if (state.resume != null && state.resumePlayable) {
            // Audit Detail #1: an explicit Video-row pick plays the
            // resume episode at that quality — the position belongs to
            // the episode, not the print, so resuming at a picked band
            // keeps the same place. No pick (or a pick this episode
            // cannot play) resumes the print as before.
            val resumeLadder = state.resume?.let { ladderForEpisode(it.season, it.episode) }
                ?: LadderForScope.EMPTY
            val picked = state.selectedBand?.takeIf { it != LadderForScope.AUTO }
                ?.let { band ->
                    resumeLadder.options.firstOrNull {
                        it.band == band && it.playsHere && !it.isProviderPage && it.streamId.isNotBlank()
                    }
                }
            _uiState.update { it.copy(playStarting = true) }
            if (picked != null) {
                playLadder(picked, ladder = resumeLadder, season = state.resume?.season, episode = state.resume?.episode)
            } else {
                playResume()
            }
            return
        }
        val layout = state.layout ?: run {
            _uiState.update { it.copy(actionMessage = "No working link right now. Try again later.") }
            return
        }
        if (layout.isSeriesLike) {
            val next = nextUnwatchedEpisode(layout, state.episodeProgress) ?: run {
                _uiState.update { it.copy(actionMessage = "No working link right now. Try again later.") }
                return
            }
            playEpisodeDecision(next.first, next.second)
            return
        }
        val ladder = state.ladder
        val playable = ladder.options.filter { it.playsHere && !it.isProviderPage && it.streamId.isNotBlank() }
        if (playable.isNotEmpty()) {
            // Audit Detail #1: an explicit Video-row pick plays at
            // once — the pick outranks Auto, the same precedence the
            // player gives an explicit tap. Without a pick, the HLS
            // branch below still starts Auto, and the sheet still asks
            // when two or more direct files compete.
            val picked = state.selectedBand?.takeIf { it != LadderForScope.AUTO }
                ?.let { band -> playable.firstOrNull { it.band == band } }
            if (picked != null) {
                _uiState.update { it.copy(playStarting = true) }
                playLadder(picked)
                return
            }
            if (playable.any { isHlsOption(it) }) {
                // HLS available: play directly on Auto, 1 tap; the
                // quality switches in the player, not here.
                _uiState.update { it.copy(playStarting = true) }
                playBest()
                return
            }
            // APP-01: the first tap plays at once — the rung the viewer
            // picked, else the ladder's own first-tap rung. playLadder
            // steps a locked rung down and refuses a blank handle, so no
            // new gate logic is needed here.
            // Invariant (review RISK-c): firstTapOption uses the same
            // playsHere / non-provider / non-blank-handle predicate as
            // `playable` above, so when `playable` is non-empty `first`
            // is non-null. If the predicates ever diverge, the null
            // case falls through to the provider / playBest fallback
            // below instead of playing nothing.
            val first = selectedRungFor(ladder, state.selectedBand) ?: firstTapOption(ladder)
            if (first != null) {
                _uiState.update { it.copy(playStarting = true) }
                playLadder(first)
                return
            }
        }
        // Nothing on the ladder plays here: a provider page still opens,
        // a ladder-less title falls back to its best print, and a title
        // with nothing at all says so.
        val provider = ladder.options.firstOrNull { it.isProviderPage }
        if (provider != null) {
            playLadder(provider)
            return
        }
        _uiState.update { it.copy(playStarting = true) }
        playBest()
    }

    /**
     * Play one episode: its best print, 1 tap, when it has no ladder or
     * an HLS stream — or its first-tap rung through the ladder path when
     * it has two or more direct files (APP-01, Phase 4): the picked rung,
     * else the ladder's first-tap rung, so the in-player switch is armed
     * for the episode actually playing. A series episode with no
     * per-episode ladder at all still plays (the player picks the
     * picture); the in-player switch is armed from the episode ladder
     * inside [playV2Bundle] when one exists, and stays unarmed when it
     * does not — that gap is a known limitation, recorded in the Phase 1
     * checkpoint.
     */
    private fun playEpisodeDecision(season: Int, episode: Int) {
        val ladder = ladderForEpisode(season, episode)
        val playable = ladder.options.filter { it.playsHere && !it.isProviderPage && it.streamId.isNotBlank() }
        if (playable.isEmpty() || playable.any { isHlsOption(it) }) {
            _uiState.update { it.copy(playStarting = true) }
            playEpisode(season, episode)
            return
        }
        // Same invariant as playPrimary (review RISK-c): `first` is
        // non-null whenever `playable` is non-empty; the null case falls
        // through to playEpisode below.
        val first = selectedRungFor(ladder, _uiState.value.selectedBand) ?: firstTapOption(ladder)
        if (first != null) {
            _uiState.update { it.copy(playStarting = true) }
            playLadder(first, ladder = ladder, season = season, episode = episode)
            return
        }
        _uiState.update { it.copy(playStarting = true) }
        playEpisode(season, episode)
    }

    /** Open the quality sheet in one mode, for one ladder and scope. */
    private fun openSheet(
        mode: QualitySheetMode,
        ladder: LadderForScope,
        season: Int?,
        episode: Int?,
        selectedBand: String? = null,
    ) {
        _uiState.update {
            it.copy(
                qualitySheet = QualitySheetState(
                    mode = mode,
                    ladder = ladder,
                    // Review RISK-a: a DOWNLOAD sheet recommends the
                    // viewer's own pick when it is a playable, unlocked
                    // rung on this ladder — the download label already
                    // names the pick, so the sheet must agree with it.
                    // BROWSE keeps the ladder's own recommendation.
                    recommendedBand = if (mode == QualitySheetMode.DOWNLOAD) {
                        downloadRecommendedBand(
                            ladder,
                            _uiState.value.selectedBand,
                            premiumAccess,
                            freeQualityCap,
                        )
                    } else {
                        recommendedBandFor(ladder, mode, premiumAccess, freeQualityCap)
                    },
                    season = season,
                    episode = episode,
                    rows = sheetRowsFor(ladder, mode, premiumAccess, freeQualityCap, selectedBand),
                    title = _uiState.value.post?.let { post -> post.cleanTitle ?: post.title },
                ),
            )
        }
    }

    /**
     * Audit Detail #1 (Nov 2026) + APP-02 (Phase 4): the browse sheet's
     * pick, and a selector chip tapped. AUTO (or a band this scope no
     * longer offers) clears the pick; a locked band answers with the
     * upsell instead of changing the selection — the sheet's own rows
     * already intercept locked taps, so this is the safety net, not the
     * path. The pick is validated against the selector's chips, never
     * trusted blind: a locked or stale band never becomes the selection.
     * The labels republish, so the Video row, the Download button and
     * the next Play tap all read the new pick.
     */
    fun selectQualityBand(band: String) {
        val chips = qualityChipsFor(selectorLadder(), premiumAccess, freeQualityCap)
        _uiState.update { it.copy(qualitySheet = null) }
        if (band == LadderForScope.AUTO) {
            _uiState.update { it.copy(selectedBand = LadderForScope.AUTO) }
            publishPrimaryActions()
            return
        }
        val chip = chips.firstOrNull { it.band == band }
        if (chip == null) {
            _uiState.update { it.copy(selectedBand = LadderForScope.AUTO) }
            publishPrimaryActions()
            return
        }
        if (chip.isLocked) {
            _uiState.update { it.copy(actionMessage = VipQualityGate.lockedMessage(freeQualityCap)) }
            return
        }
        _uiState.update { it.copy(selectedBand = band) }
        publishPrimaryActions()
    }

    /** A sheet row tapped in PLAY mode: close the sheet, start the copy. */
    fun playSheetOption(option: QualityOption) {
        val sheet = _uiState.value.qualitySheet ?: return
        _uiState.update { it.copy(qualitySheet = null) }
        if (option.isProviderPage) {
            playLadder(option, ladder = sheet.ladder, season = sheet.season, episode = sheet.episode)
            return
        }
        _uiState.update { it.copy(playStarting = true) }
        playLadder(option, ladder = sheet.ladder, season = sheet.season, episode = sheet.episode)
    }

    /** A sheet row tapped in DOWNLOAD mode: close the sheet, start the keep. */
    fun downloadSheetOption(option: QualityOption) {
        _uiState.update { it.copy(qualitySheet = null) }
        keepRung(option)
    }

    /** The sheet dismissed without choosing: nothing started. */
    fun dismissQualitySheet() {
        _uiState.update { it.copy(qualitySheet = null) }
    }

    /**
     * APP-02 (Phase 4): the scope the quality selector (and its browse
     * sheet) describe — the next unwatched episode's ladder for a series,
     * the whole work's for a movie. UNGATED on purpose: [VipQualityGate.gate]
     * removes premium bands, and a locked band must stay visible (greyed,
     * never hidden) in the selector, so the chips are built from the ladder
     * before the gate. When the episode ladder is empty, the whole-work
     * ladder — empty for a multi-episode series by design, never another
     * episode's links.
     */
    private fun selectorLadder(): LadderForScope {
        val groups = rawDetail?.optionGroups ?: return LadderForScope.EMPTY
        val layout = _uiState.value.layout
        if (layout?.isSeriesLike == true) {
            val next = nextUnwatchedEpisode(layout, _uiState.value.episodeProgress)
            if (next != null) {
                val episode = QualityLadder.forNumberedScope(groups, next.first, next.second)
                if (!episode.isEmpty) return episode
            }
        }
        return QualityLadder.forNumberedScope(groups, null, null)
    }

    /**
     * APP-02: a selector chip (or browse-sheet row) tapped. A locked band
     * answers with the upsell line and changes nothing — the tap never
     * silently steps down to a free copy the viewer did not choose. Any
     * other band becomes the selection: Play and Download act on it from
     * now on. Nothing starts here.
     */

    /** APP-02: a browse-sheet row tapped — pick the band, close the sheet, start nothing. */
    fun selectSheetQuality(option: QualityOption) {
        _uiState.update { it.copy(qualitySheet = null) }
        selectQualityBand(option.band)
    }

    /** APP-02: open the browse sheet — the same scope as the chips, for picking only. */
    fun openQualityBrowseSheet() {
        val state = _uiState.value
        val layout = state.layout
        var season: Int? = null
        var episode: Int? = null
        if (layout?.isSeriesLike == true) {
            nextUnwatchedEpisode(layout, state.episodeProgress)?.let { (s, e) ->
                season = s
                episode = e
            }
        }
        openSheet(QualitySheetMode.BROWSE, selectorLadder(), season, episode)
    }

    /**
     * A locked (VIP) row tapped in the quality sheet. There is no
     * dedicated VIP upgrade sheet in the app yet, so the tap closes
     * the sheet and the existing upsell line answers instead — the
     * locked row is never hidden, and the tap never silently steps
     * down to a free copy the viewer did not choose.
     */
    fun onLockedQualityRow() {
        _uiState.update {
            it.copy(
                qualitySheet = null,
                actionMessage = VipQualityGate.lockedMessage(freeQualityCap),
            )
        }
    }

    /**
     * The Download button's one entry point. A title already on this
     * device plays its saved file, never a new download. Otherwise the
     * Download sheet decides (§6.3).
     */
    fun onDownloadPrimary() {
        val state = _uiState.value
        if (state.kidsMode) {
            _uiState.update { it.copy(actionMessage = "Downloads are locked in Kids Mode.") }
            return
        }
        val uri = state.downloadedUri
        if (uri != null) {
            viewModelScope.launch {
                _navEvents.emit(
                    DetailNavigationEvent.PlayLocal(
                        uri,
                        state.post?.let { it.cleanTitle ?: it.title } ?: "Stream",
                    ),
                )
            }
            return
        }
        openDownloadSheet()
    }

    /**
     * The Download sheet. A movie: one keepable quality starts at once
     * (1 tap), two or more open the quality rows (2 taps). A series:
     * the next episode and the whole season instead of quality rows —
     * the rows name their own quality, so no second choice is needed.
     */
    fun openDownloadSheet() {
        val state = _uiState.value
        val layout = state.layout ?: run {
            _uiState.update { it.copy(actionMessage = "Nothing to save for this title.") }
            return
        }
        if (layout.isSeriesLike) {
            val next = nextUnwatchedEpisode(layout, state.episodeProgress) ?: run {
                _uiState.update { it.copy(actionMessage = "Nothing to save for this title.") }
                return
            }
            val season = layout.seasons.firstOrNull { it.number == next.first } ?: run {
                _uiState.update { it.copy(actionMessage = "Nothing to save for this title.") }
                return
            }
            val ladder = ladderForEpisode(next.first, next.second)
            val plan = seasonKeepPlan(season, premiumAccess, freeQualityCap)
            // Review RISK-a: the next-episode row saves the viewer's
            // picked band when it is playable and unlocked here, else
            // the ladder's own recommendation — same rule as openSheet.
            val band = downloadRecommendedBand(ladder, _uiState.value.selectedBand, premiumAccess, freeQualityCap)
            val option = band?.let { ladder.optionFor(it) } ?: ladder.autoOption
            val nextRow = option?.let {
                SeriesDownloadRow(
                    kind = SeriesDownloadKind.NEXT_EPISODE,
                    label = "Next episode",
                    detail = "S${next.first} E${next.second} · ${bandDisplayName(it.band)}" +
                        (it.sizeBytes?.takeIf { bytes -> bytes > 0 }?.let { bytes -> " · ${QualityOption.formatBytes(bytes)}" } ?: ""),
                )
            }
            val wholeRow = if (plan.items.isEmpty()) {
                null
            } else {
                SeriesDownloadRow(
                    kind = SeriesDownloadKind.WHOLE_SEASON,
                    label = "Whole season",
                    detail = seasonKeepConfirmLine(
                        PendingSeasonKeep(season.number, plan.items, plan.lockedCount, plan.totalSizeBytes),
                        wifiOnlyDownloads,
                    ),
                )
            }
            if (nextRow == null && wholeRow == null) {
                _uiState.update { it.copy(actionMessage = "Nothing to save for this title.") }
                return
            }
            _uiState.update {
                it.copy(
                    qualitySheet = QualitySheetState(
                        mode = QualitySheetMode.DOWNLOAD,
                        ladder = ladder,
                        recommendedBand = band,
                        season = next.first,
                        episode = next.second,
                        nextEpisodeRow = nextRow,
                        wholeSeasonRow = wholeRow,
                        wholeSeasonPlan = plan,
                        wholeSeasonNumber = next.first,
                        title = state.post?.let { post -> post.cleanTitle ?: post.title },
                    ),
                )
            }
            return
        }
        val ladder = state.ladder
        val keepable = ladder.options.filter { !it.isProviderPage && it.streamId.isNotBlank() }
        if (keepable.isEmpty()) {
            // No ladder, or a ladder with nothing keepable: the dead
            // end's best keepable print, or nothing at all.
            val print = state.deadEndKeepPrint
            if (print != null) {
                keepPrint(print)
            } else {
                _uiState.update { it.copy(actionMessage = "Nothing to save for this title.") }
            }
            return
        }
        if (keepable.size == 1) {
            keepRung(keepable.first())
            return
        }
        openSheet(QualitySheetMode.DOWNLOAD, ladder, null, null)
    }

    /** A series row tapped in the Download sheet: next episode, or the whole season. */
    fun downloadSeriesRow(kind: SeriesDownloadKind) {
        val sheet = _uiState.value.qualitySheet ?: return
        _uiState.update { it.copy(qualitySheet = null) }
        when (kind) {
            SeriesDownloadKind.NEXT_EPISODE -> {
                val option = sheet.recommendedBand?.let { sheet.ladder.optionFor(it) }
                    ?: sheet.ladder.autoOption
                if (option == null) {
                    _uiState.update { it.copy(actionMessage = "Nothing to save for this episode.") }
                    return
                }
                keepRung(option)
            }
            SeriesDownloadKind.WHOLE_SEASON -> {
                val plan = sheet.wholeSeasonPlan
                val number = sheet.wholeSeasonNumber
                if (plan == null || number == null) return
                startSeasonDownload(plan, number)
            }
        }
    }

    /**
     * The episode sheet's row tap: download that episode at once, at
     * the recommended quality — the row IS the confirmation (2 taps
     * total: "Download an episode", then the episode).
     */
    fun downloadEpisode(season: Int, episode: Int) {
        val ladder = ladderForEpisode(season, episode)
        val band = recommendedBandFor(ladder, QualitySheetMode.DOWNLOAD, premiumAccess, freeQualityCap)
        val option = band?.let { ladder.optionFor(it) } ?: ladder.autoOption
        if (option == null) {
            _uiState.update { it.copy(actionMessage = "Nothing to save for this episode.") }
            return
        }
        keepRung(option)
    }

    /**
     * Batch download (Phase 1): the episodes the viewer ticked in the
     * episode sheet, at one quality. The sheet offers only the bands
     * every ticked episode has a copy in — a band missing from one
     * episode is not offered, so the batch never starts half a season.
     */
    fun openBatchDownloadSheet(targets: List<BatchEpisodeTarget>) {
        if (targets.isEmpty()) return
        val ladders = targets.map { ladderForEpisode(it.season, it.episode) }
        val bands = SHEET_BAND_ORDER.filter { band ->
            ladders.all { ladder ->
                ladder.options.any { it.band == band && it.streamId.isNotBlank() && !it.isProviderPage }
            }
        }
        if (bands.isEmpty()) {
            _uiState.update { it.copy(actionMessage = "These episodes have no quality in common to save.") }
            return
        }
        val recommended = if ("720p" in bands) "720p" else bands.first()
        _uiState.update {
            it.copy(
                qualitySheet = QualitySheetState(
                    mode = QualitySheetMode.DOWNLOAD,
                    ladder = LadderForScope.EMPTY,
                    recommendedBand = recommended,
                    batchEpisodes = targets,
                    batchBands = bands,
                    title = _uiState.value.post?.let { post -> post.cleanTitle ?: post.title },
                ),
            )
        }
    }

    /** Start the ticked episodes at one quality, and say how many started. */
    fun startBatchDownload(band: String) {
        val sheet = _uiState.value.qualitySheet ?: return
        val targets = sheet.batchEpisodes ?: return
        _uiState.update { it.copy(qualitySheet = null) }
        if (_uiState.value.kidsMode) {
            _uiState.update { it.copy(actionMessage = "Downloads are locked in Kids Mode.") }
            return
        }
        if (DataSaver.wifiOnlyBlocks(wifiOnlyDownloads, onWifi)) {
            _uiState.update {
                it.copy(actionMessage = "Wi-Fi only is on — connect to Wi-Fi to download this title. You can change it in Settings.")
            }
            return
        }
        viewModelScope.launch {
            var started = 0
            var refused = 0
            var spaceBlocked: FreeSpaceVerdict.NotEnough? = null
            for (target in targets) {
                val ladder = ladderForEpisode(target.season, target.episode)
                val option = ladder.optionFor(band) ?: ladder.autoOption
                if (option == null || isLocked(option) || option.isProviderPage) {
                    refused++
                    continue
                }
                val meta = runCatching { catalogRepository.resolveStream(option.streamId) }.getOrNull()
                val targetKind = if (meta != null && meta.success) keepTargetFor(meta.mimeType) else KeepTarget.DeviceFile
                val title = _uiState.value.post?.let { post -> post.cleanTitle ?: post.title } ?: "Stream"
                if (targetKind == KeepTarget.EngineHls) {
                    if (keepViaEngine(option.streamId, option.title, title, option.sizeBytes, meta?.resumable ?: false, quiet = true)) {
                        started++
                    } else {
                        refused++
                    }
                    continue
                }
                when (val room = deviceFileRoom(option.sizeBytes)) {
                    is FreeSpaceVerdict.NotEnough -> {
                        if (spaceBlocked == null) spaceBlocked = room
                        refused++
                        continue
                    }
                    is FreeSpaceVerdict.Ok -> Unit
                }
                when (deviceDownloads.enqueue(option.streamId, title, option.title)) {
                    is DeviceDownloadResult.Enqueued -> started++
                    is DeviceDownloadResult.NeedsPermission -> {
                        _navEvents.emit(DetailNavigationEvent.DownloadNeedsPermission)
                        return@launch
                    }
                    is DeviceDownloadResult.Refused -> refused++
                }
            }
            val blocked = spaceBlocked
            when {
                started > 0 -> {
                    _uiState.update {
                        it.copy(
                            actionMessage = if (refused > 0) {
                                "$started of ${targets.size} episodes started downloading."
                            } else {
                                "${targets.size} episodes started downloading."
                            },
                            snackbarActionLabel = "See downloads",
                            snackbarAction = SnackbarAction.SEE_DOWNLOADS,
                        )
                    }
                }
                blocked != null -> {
                    _uiState.update {
                        it.copy(
                            actionMessage = notEnoughSpaceMessage(blocked.neededBytes, blocked.freeBytes),
                            snackbarActionLabel = "Free space",
                            snackbarAction = SnackbarAction.SEE_DOWNLOADS,
                        )
                    }
                }
                refused > 0 -> {
                    _uiState.update { it.copy(actionMessage = "These episodes could not start downloading.") }
                }
            }
        }
    }

    /** The season screen's Play Season: the next unwatched episode, 1 tap. */
    fun playSeason(seasonNumber: Int) {
        if (_uiState.value.playStarting) return
        val layout = _uiState.value.layout ?: return
        val next = nextUnwatchedEpisode(layout, _uiState.value.episodeProgress, seasonNumber) ?: run {
            _uiState.update { it.copy(actionMessage = "No working link right now. Try again later.") }
            return
        }
        playEpisodeDecision(next.first, next.second)
    }

    /** The season screen's Play Season label: the next episode's quality, when one is named. */
    fun seasonPlayLabel(seasonNumber: Int): String {
        val layout = _uiState.value.layout ?: return "Play Season"
        val next = nextUnwatchedEpisode(layout, _uiState.value.episodeProgress, seasonNumber)
            ?: return "Play Season"
        val band = ladderForEpisode(next.first, next.second)
            .let { it.effectiveAutoBand ?: it.autoBand }
            ?: episodePlayPrint(layout, next.first, next.second)?.resolution?.wire
        return if (band != null) "Play Season · $band" else "Play Season"
    }

    /**
     * The season screen's "Download an episode" sheet rows: one per
     * episode that has a file, with the size the server named (never
     * invented) and whether the episode is already on this device.
     */
    fun episodeDownloadRows(seasonNumber: Int): List<EpisodeDownloadRow> {
        val state = _uiState.value
        val layout = state.layout ?: return emptyList()
        val season = layout.seasons.firstOrNull { it.number == seasonNumber } ?: return emptyList()
        return season.episodes.mapNotNull { episode ->
            val number = episode.numberWithinSeason
            if (episode.prints.isEmpty()) return@mapNotNull null
            val print = episodePlayPrint(layout, seasonNumber, number)
            val ladder = ladderForEpisode(seasonNumber, number)
            val size = ladder.autoOption?.sizeBytes?.takeIf { it > 0 }
                ?: print?.sizeBytes?.takeIf { it > 0 }
            val downloaded = print?.links?.any { link ->
                state.downloadedHandles.contains(link.streamId)
            } ?: false
            EpisodeDownloadRow(seasonNumber, number, size, downloaded)
        }
    }

    /** A dimmed tile tapped: say so in plain words, instead of doing nothing. */
    fun onUnplayableEpisodeTapped(episode: Int) {
        _uiState.update { it.copy(actionMessage = "Episode $episode has no working copy.") }
    }

    /** [Try again] on the dead end: reload this title. */
    fun tryAgainPlay() = loadDetail()

    /** The snackbar's action button: open the Downloads screen. */
    fun onSnackbarAction() {
        if (_uiState.value.snackbarAction == SnackbarAction.SEE_DOWNLOADS) {
            viewModelScope.launch { _navEvents.emit(DetailNavigationEvent.OpenDownloads) }
        }
    }

    /**
     * The Play guard ends when the detail comes back — a failed
     * playback must never leave the button reading "Starting…". The
     * screen calls this on resume.
     */
    fun clearPlayStarting() {
        _uiState.update { it.copy(playStarting = false) }
    }

    /**
     * The device-file free-space check. The file needs its own size
     * plus a 500 MB floor; when the server named no size, only the
     * floor is checked. A storage read that fails assumes room — the
     * download's own failure is the honest answer then, not a false
     * "not enough space".
     */
    private fun deviceFileRoom(sizeBytes: Long?): FreeSpaceVerdict =
        freeSpaceVerdictFor(externalFreeBytes(), sizeBytes)

    private fun externalFreeBytes(): Long = runCatching {
        val dir = appContext.getExternalFilesDir(null) ?: return Long.MAX_VALUE
        StatFs(dir.path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    private fun observeFavorite() {
        if (resolvedPostId <= 0) return
        viewModelScope.launch {
            favoritesRepository.isFavorite(resolvedPostId).collect { fav ->
                _uiState.update { it.copy(isFavorite = fav) }
            }
        }
    }

    /** E2 slice 2: the bell's state, from the follow list, keyed by the work's handle. */
    private fun observeFollowed(workRef: String?) {
        val ref = workRef?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            followedSeriesRepository.isFollowed(ref).collect { followed ->
                _uiState.update { it.copy(isFollowed = followed) }
            }
        }
    }

    /**
     * E2 slice 2: the "Notify me" bell. Following seeds the last-seen
     * scope from this detail (see [FollowedSeriesRepository.follow]), so
     * the episodes already on the page are never announced as new; the
     * daily worker then watches for anything strictly newer. A title
     * with no handle cannot be followed — the bell is hidden there, so
     * this guard is the second line, not the first.
     */
    fun toggleFollow() {
        val detail = _uiState.value.post ?: return
        if (detail.workRef.isNullOrBlank()) {
            _uiState.update { it.copy(actionMessage = "Could not follow this title right now.") }
            return
        }
        viewModelScope.launch {
            val isNowFollowed = followedSeriesRepository.toggleFollow(detail)
            _uiState.update {
                it.copy(
                    isFollowed = isNowFollowed,
                    actionMessage = if (isNowFollowed) {
                        "You'll be notified about new episodes"
                    } else {
                        "Notifications off for this series"
                    },
                )
            }
        }
    }

    /** REA #2: the thumbs vote for this work's handle, like [observeFollowed]. */
    private fun observeTitleFeedback(workRef: String?) {
        val ref = workRef?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            titleFeedbackRepository.thumbFor(ref).collect { thumb ->
                _uiState.update { it.copy(titleThumb = thumb ?: 0) }
            }
        }
    }

    /**
     * REA #2: tap a thumb to vote; tap the same thumb again to clear it.
     * The state paints the returned vote at once, and the Flow above
     * keeps it in sync afterwards. A title with no handle cannot be
     * voted on — the row is hidden there, so this guard is the second
     * line, not the first.
     */
    fun setTitleThumb(thumb: Int) {
        val ref = _uiState.value.post?.workRef?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val next = titleFeedbackRepository.toggleThumb(ref, thumb)
            _uiState.update { it.copy(titleThumb = next) }
        }
    }

    /**
     * Appetize FAILED 2 (2026-11-12) — the detail fetch, retried.
     *
     * The first load failed and a manual Retry always loaded fully, so the
     * load retries itself: up to [DETAIL_LOAD_MAX_ATTEMPTS] attempts for a
     * transient error ([isTransientDetailError]), and one retry of a null
     * detail ([DETAIL_LOAD_NULL_RETRIES]) — the v2 path swallows HTTP/IO
     * failures into null inside the repository, so a null is the only shape
     * a 429/503 takes there. A cancelled load is not a failed load: the
     * [CancellationException] is rethrown so the coroutine ends cancelled
     * instead of painting an error over a screen the viewer already left.
     */
    private suspend fun fetchDetailWithRetry(): PostDetail? {
        var nullRetries = 0
        var attempt = 0
        while (true) {
            attempt++
            try {
                // Phase 7 (M1): a v2 row opens through the v2 detail route —
                // the work id is the only key it has. The repository bridges
                // the work_ref back to a v1 post for cast + season art, and
                // the returned detail carries that post id (0 when the bridge
                // found no post: the title still loads, but nothing may write
                // a Room row under id 0).
                val detail = if (workId >= 0) {
                    // Gap A2 follow-up: the bridge warm below used to carry no
                    // audio word, so the warmed auto tier could be a different
                    // audio than the ladder Play presses. Name the preferred
                    // audio here too, exactly as the v1 path does.
                    when (val res = catalogRepository.fetchWorkDetailV2(workId, audio = detailWarmAudio(userRegion))) {
                        is V2DetailResult.Found -> res.detail
                        V2DetailResult.Unavailable -> null
                        is V2DetailResult.TransientFailure -> throw res.error
                    }
                } else {
                    // Audit #1: warm the auto tier on detail open and name
                    // the preferred audio, so the Play press answers from
                    // the resolved cache and the ladder leads with the
                    // viewer's language.
                    catalogRepository.getPostDetail(
                        postId,
                        warm = true,
                        audio = detailWarmAudio(userRegion),
                    )
                }
                if (detail != null) return detail
                if (nullRetries < DETAIL_LOAD_NULL_RETRIES) {
                    nullRetries++
                    delay(detailLoadBackoffMs(attempt))
                    continue
                }
                return null
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                if (attempt >= DETAIL_LOAD_MAX_ATTEMPTS || !isTransientDetailError(ex)) throw ex
                delay(detailLoadBackoffMs(attempt))
            }
        }
    }

    /**
     * MX #7 — Kids Lock: refuse a title. Everything that names it leaves
     * the state — post, optimistic header, fallback row, similar titles,
     * layout — so the screen's blocked branch has nothing to leak through.
     * The error stays null: a blocked title is not a failed load, and the
     * screen must not offer a retry that can only block again.
     */
    private fun blockDetail() {
        _uiState.update {
            it.copy(
                post = null,
                optimisticDetail = null,
                fallbackPost = null,
                similarTitles = emptyList(),
                layout = null,
                isLoading = false,
                error = null,
                kidsBlocked = true,
            )
        }
    }

    fun loadDetail() {
        // 2-button detail (Nov 2026): a new title (or a [Try again]) ends
        // any in-flight Play guard and closes the sheet — a stale
        // "Starting…" or a sheet for the old title must never survive
        // the reload.
        // Review RISK-f: selectedBand is intentionally NOT reset here.
        // A reload is the same title, so the pick stands; a new title
        // gets a new ViewModel (hiltViewModel per destination), and
        // publishPrimaryActions re-validates the band against the new
        // ladder on every publish anyway.
        _uiState.update { it.copy(playStarting = false, qualitySheet = null) }
        if (postId < 0 && workId < 0) {
            _uiState.update { it.copy(isLoading = false, error = "This title could not be opened.") }
            return
        }
        viewModelScope.launch {
            // MX #7 — Kids Lock: read the mode up front, from the store, not
            // the state — the collector above may not have delivered yet, and
            // the optimistic header below must not flash a title the mode
            // forbids while the fetch is in flight.
            val kidsOn = settingsRepository.kidsModeActive.first()
            _uiState.update { it.copy(kidsMode = kidsOn) }
            // Feel-instant S4: paint the header at once, from the list row
            // this process already holds — the tap answers within a frame,
            // and the warm + fetch below only ever fill the body. A row the
            // cache never held (a deep link, a cold start straight to
            // detail) paints the skeleton exactly as before.
            val optimistic = catalogRepository.cachedPost(postId, workId)
                ?.let(::optimisticDetailFromPost)
                ?.takeIf { !kidsOn || KidsLock.isKidAppropriate(it) }
            _uiState.update {
                it.copy(
                    isLoading = true, error = null, fallbackPost = null,
                    optimisticDetail = optimistic,
                    // MX #7: a reload after the mode turned off clears the
                    // block — the title is loading again, not still refused.
                    kidsBlocked = false,
                )
            }
            try {
                // Session 153: warm the connection once before the first detail
                // call — a cold pool can fail the first tap before any byte
                // leaves the device. Bounded and silent: a slow warm must never
                // hold the detail back, it only ever helps it. Session 155:
                // the warm sits inside the try now, so a warm that ever throws
                // (it is runCatching-guarded and should not) lands on the same
                // error path as the detail call instead of escaping the
                // coroutine and leaving the screen loading with no error.
                withTimeoutOrNull(3_000) { catalogRepository.warmConnectionIfNeeded() }
                val detail = fetchDetailWithRetry()
                if (detail != null) {
                    // MX #7 — Kids Lock: the fetch is the backstop — a title
                    // that fails the rule never reaches the screen, however
                    // it was opened (deep link, stale row, cold start).
                    if (kidsOn && !KidsLock.isKidAppropriate(detail)) {
                        blockDetail()
                        return@launch
                    }
                    // Wide art only: a portrait poster cropped into a 16:9 still looks broken,
                    // so it is used just when the catalog has no backdrop at all.
                    val artwork = detail.backdropList.ifEmpty {
                        listOfNotNull(detail.posterUrl?.takeIf(String::isNotBlank))
                    }
                    val layout = ReleaseLayoutBuilder.build(
                        detail.contentType,
                        detail.releases,
                        artwork,
                        detail.seasonArtwork,
                        region = userRegion,
                        episodeGuide = detail.episodeGuide,
                    )
                    rawDetail = detail
                    _uiState.update {
                        it.copy(
                            post = detail,
                            isLoading = false,
                            // The real detail is on screen now; the
                            // optimistic header has done its job.
                            optimisticDetail = null,
                            // REA #2: the Match % for this title from the
                            // "For you" row on screen, or null when no row
                            // scored it.
                            matchPercent = MatchScoreStore.get(detail.id),
                        )
                    }
                    // The catalog answer is ungated; what the viewer may SEE is decided here,
                    // from the entitlement the repository already holds.
                    publishViewerContent()
                    // E2 slice 2: the bell follows the handle, not the post id — a
                    // v2 detail whose bridge found no v1 post still has a handle
                    // worth following, so this is gated on the handle alone.
                    observeFollowed(detail.workRef)
                    // REA #2: the thumbs follow the same handle, for the same reason.
                    observeTitleFeedback(detail.workRef)
                    // Gap A2: pre-resolve the top link now, while the viewer
                    // is still reading the page, so Play does not pay the
                    // `playV2` round trip first.
                    preresolveTopLink()
                    // Live fill (detail open): the detail is on screen; if
                    // it still shows blanks, ask the server to fill just
                    // this title in the background and patch the fields in
                    // place when it answers. Never blocks the screen, never
                    // fires twice for one load — the staleness guard in
                    // maybeLiveFill drops an answer for a detail the viewer
                    // already left. After the Kids Lock gate above, so a
                    // blocked title never asks for a fill.
                    maybeLiveFill(detail)
                    // A row saved before this app had a handle becomes syncable the moment the
                    // catalog finally names its work. Nothing here fabricates a handle: an absent
                    // key is a no-op, and a present one is the server's own. On the v2 path the
                    // bridge's post id is the only id these calls may use — a 0 must never
                    // reach Room or the sync account.
                    if (detail.id > 0) {
                        if (workId >= 0) {
                            resolvedPostId = detail.id
                            observeFavorite()
                        }
                        syncAccountRepository.adoptWorkRef(detail.id, detail.workRef)
                        loadSimilarTitles(detail.id, detail)
                    } else if (postId > 0) {
                        loadSimilarTitles(postId, detail)
                    }
                } else {
                    // F9 (Batch B): the server answered "no such title" — the
                    // list row still names it, when this process has it.
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "This title is not available on the connected server.",
                            fallbackPost = catalogRepository.cachedPost(postId, workId),
                        )
                    }
                }
            } catch (ex: CancellationException) {
                // A cancelled load is not a failed load: rethrow so the
                // coroutine ends cancelled instead of painting an error
                // over a screen the viewer already left.
                throw ex
            } catch (ex: Exception) {
                // F9 (Batch B): the call failed — the list row still names
                // the title, when this process has it.
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = friendlyNetworkError(
                            ex,
                            "We couldn't load this title right now. Please check your connection and try again.",
                        ),
                        fallbackPost = catalogRepository.cachedPost(postId, workId),
                    )
                }
            }
        }
    }

    private var similarJob: Job? = null

    private fun loadSimilarTitles(seedPostId: Int, current: PostDetail) {
        if (seedPostId <= 0) return
        similarJob?.cancel()
        similarJob = viewModelScope.launch {
            _uiState.update { it.copy(similarLoading = true) }
            val items = try {
                val resp = catalogRepository.getRelated(workId = seedPostId, page = 1, limit = 18)
                // MX #7 — Kids Lock: similar titles pass the same rule —
                // a related row is still a row on the screen.
                val filtered = filterSimilarTitles(resp.results, current)
                if (_uiState.value.kidsMode) filtered.filter { KidsLock.isKidAppropriate(it) } else filtered
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.update { it.copy(similarTitles = items, similarLoading = false) }
        }
    }

    fun toggleFavorite() {
        val currentPost = _uiState.value.post ?: return
        // Phase 7 (M1): a v2 detail whose work_ref bridge found no v1 post
        // carries id 0 — toggling it would write a junk Room row under 0.
        // The title is still viewable; only saving is refused, honestly.
        if (currentPost.id <= 0) {
            _uiState.update { it.copy(actionMessage = "Could not save this title right now.") }
            return
        }
        viewModelScope.launch {
            val isNowFav = favoritesRepository.toggleFavorite(currentPost)
            _uiState.update {
                it.copy(
                    isFavorite = isNowFav,
                    actionMessage = if (isNowFav) "Added to My List" else "Removed from My List",
                )
            }
        }
    }

    /**
     * Phase 7 (D1): "Start over" — forget where viewing stopped for the scope
     * the Resume button would play. The server row goes first (the
     * `removeProgress` endpoint the app defined but never called), then the
     * local rows; the resume state clears either way, so a refused server
     * delete can only bring the row back on the next sync, never by keeping
     * a button the viewer just removed.
     */
    fun removeProgress() {
        val detail = _uiState.value.post ?: return
        val ref = detail.workRef?.takeIf { it.isNotBlank() } ?: return
        val resume = _uiState.value.resume
        val season = resume?.season ?: 0
        val episode = resume?.episode ?: 0
        viewModelScope.launch {
            runCatching { syncAccountRepository.removeProgress(workRef = ref, season = season, episode = episode) }
            runCatching { watchHistoryDao.remove(ref, season, episode) }
            runCatching { syncedProgressDao.remove(ref, season, episode) }
            _uiState.update {
                it.copy(
                    resume = null,
                    resumePlayable = false,
                    actionMessage = "Removed from Continue Watching",
                )
            }
        }
    }

    fun clearActionMessage() {
        // 2-button detail (Nov 2026): the snackbar going away also ends
        // the "Starting…" guard and drops its action — a new Play tap
        // after a failure must work, and a stale action must not linger.
        _uiState.update {
            it.copy(
                actionMessage = null,
                playStarting = false,
                snackbarActionLabel = null,
                snackbarAction = null,
            )
        }
    }

    /** A rung this app cannot present is downloaded, and the row says so — no hidden failure. */
    fun keepRung(option: QualityOption) {
        // MX #7 — Kids Lock: downloads are a locked surface — a kid taps
        // nothing that starts a file landing on the device.
        if (_uiState.value.kidsMode) {
            _uiState.update { it.copy(actionMessage = "Downloads are locked in Kids Mode.") }
            return
        }
        if (isLocked(option)) {
            _uiState.update { it.copy(actionMessage = VipQualityGate.lockedMessage(freeQualityCap)) }
            return
        }
        if (option.isProviderPage) {
            _uiState.update { it.copy(actionMessage = PAGE_NOT_A_FILE) }
            return
        }
        _uiState.update { it.copy(selectedBand = option.band) }
        keep(
            handle = option.streamId,
            printLabel = option.title,
        )
    }

    /**
     * The tier a rung requires: VIP when the operator's gate reserves its band,
     * Standard otherwise. A property of the stream and the operator's policy,
     * never of the viewer — a VIP viewer reads "VIP" on the 1080p row they can
     * play, and a free viewer never sees that row at all (the gate removed it
     * and the hero's included pill says where it went). With the gate off, every rung is
     * Standard because nothing requires VIP.
     */
    fun tierLabelFor(option: QualityOption): String =
        if (qualityGateEnabled &&
            (VipQualityGate.isPremiumBand(option.band, freeQualityCap) ||
                VipQualityGate.isPremiumLabel(option.title, freeQualityCap))
        ) "VIP" else "Standard"

    /** A premium rung the current viewer may not take, decided from the same gate the UI uses. */
    internal fun isLocked(option: QualityOption): Boolean =
        !VipQualityGate.allowsBand(option.band, premiumAccess, freeQualityCap) ||
            (!premiumAccess && VipQualityGate.isPremiumLabel(option.title, freeQualityCap))

    /**
     * Keep one rung for offline playback, by OUR opaque handle.
     *
     * Which system owns the copy depends on what it is: a single file goes to the
     * device download (a public file in Movies/RaddStream); a segmented playlist
     * goes to the keep engine, which fetches the segments and plays them back
     * offline in-app. A page is neither and is refused before either is asked.
     *
     * Every branch answers out loud: an accepted download routes to the Downloads
     * screen where its row is already moving; a legacy device learns it must grant
     * storage first, because a button that quietly does nothing is worse than one
     * that says no. The address handed to either system is our own redirector —
     * the server verified the handle, and the upstream location never enters this app.
     */
    private fun keep(handle: String, printLabel: String) {
        lastDownload = handle to printLabel
        if (!downloadsEnabled) {
            lastDownload = null
            _uiState.update { it.copy(actionMessage = "Keeping files for offline is switched off for this server.") }
            return
        }
        if (handle.isBlank()) {
            _uiState.update { it.copy(actionMessage = "Nothing to download on this rung yet.") }
            return
        }
        // Session 123: the Wi-Fi-only gate, at the tap — the same gate
        // `keepPrint` already honours. The dialog must not offer a download
        // the switch forbids, and the viewer must hear why now, not after
        // confirming a size for a file that will never start.
        if (DataSaver.wifiOnlyBlocks(wifiOnlyDownloads, onWifi)) {
            _uiState.update {
                it.copy(actionMessage = "Wi-Fi only is on — connect to Wi-Fi to download this title. You can change it in Settings.")
            }
            return
        }
        val workTitle = _uiState.value.post?.let { it.cleanTitle ?: it.title } ?: "Stream"
        viewModelScope.launch {
            // One metadata ask decides which download system owns this copy. A failed
            // ask falls through to the device path, which refuses with the same words it
            // always used — no new failure shape for a server that cannot answer.
            // 2-button detail (Nov 2026): the sheet row IS the confirmation —
            // there is no second dialog, so the download starts here.
            val meta = runCatching { catalogRepository.resolveStream(handle) }.getOrNull()
            val target = if (meta != null && meta.success) {
                keepTargetFor(meta.mimeType)
            } else {
                KeepTarget.DeviceFile
            }
            startKeep(
                PendingKeep(
                    handle = handle,
                    printLabel = printLabel,
                    title = workTitle,
                    sizeBytes = meta?.fileSizeBytes?.takeIf { it > 0 },
                    resumable = meta?.resumable ?: false,
                    target = target,
                ),
            )
        }
    }

    /**
     * 2-button detail (Nov 2026): start the download a sheet row chose.
     * The device-file path checks free space first (the engine path
     * already checks its own storage): the size plus a 500 MB floor must
     * fit, or the viewer hears the real numbers and gets a "Free space"
     * button to the Downloads screen instead of a download that dies
     * halfway. Nothing auto-navigates away from detail — the snackbar
     * offers "See downloads" instead.
     */
    private fun startKeep(pending: PendingKeep) {
        // Session 123: the gate again, at start time — the Wi-Fi state can
        // change while the metadata ask is in flight, and the start is the
        // moment the bytes would actually begin.
        if (DataSaver.wifiOnlyBlocks(wifiOnlyDownloads, onWifi)) {
            _uiState.update {
                it.copy(actionMessage = "Wi-Fi only is on — connect to Wi-Fi to download this title. You can change it in Settings.")
            }
            return
        }
        viewModelScope.launch {
            if (pending.target == KeepTarget.EngineHls) {
                keepViaEngine(pending.handle, pending.printLabel, pending.title, pending.sizeBytes, pending.resumable)
                return@launch
            }
            when (val room = deviceFileRoom(pending.sizeBytes)) {
                is FreeSpaceVerdict.NotEnough -> {
                    lastDownload = null
                    _uiState.update {
                        it.copy(
                            actionMessage = notEnoughSpaceMessage(room.neededBytes, room.freeBytes),
                            snackbarActionLabel = "Free space",
                            snackbarAction = SnackbarAction.SEE_DOWNLOADS,
                        )
                    }
                    return@launch
                }
                is FreeSpaceVerdict.Ok -> Unit
            }
            when (val outcome = deviceDownloads.enqueue(pending.handle, pending.title, pending.printLabel)) {
                is DeviceDownloadResult.Enqueued -> {
                    lastDownload = null
                    val size = pending.sizeBytes?.takeIf { it > 0 }?.let { " · ${QualityOption.formatBytes(it)}" } ?: ""
                    _uiState.update {
                        it.copy(
                            actionMessage = "Downloading ${pending.printLabel}$size",
                            snackbarActionLabel = "See downloads",
                            snackbarAction = SnackbarAction.SEE_DOWNLOADS,
                        )
                    }
                }
                is DeviceDownloadResult.NeedsPermission ->
                    _navEvents.emit(DetailNavigationEvent.DownloadNeedsPermission)
                is DeviceDownloadResult.Refused -> {
                    lastDownload = null
                    _uiState.update { it.copy(actionMessage = outcome.message) }
                }
            }
        }
    }

    /**
     * 2-button detail (Nov 2026): open the Download sheet for one
     * season — one row, "Whole season", with the episode count and the
     * total size in front of the viewer. The row IS the confirmation;
     * [startSeasonDownload] starts it.
     */
    fun openDownloadSeasonSheet(season: SeasonGroup) {
        // MX #7 — Kids Lock: downloads are a locked surface.
        if (_uiState.value.kidsMode) {
            _uiState.update { it.copy(actionMessage = "Downloads are locked in Kids Mode.") }
            return
        }
        val plan = seasonKeepPlan(season, premiumAccess, freeQualityCap)
        if (plan.items.isEmpty()) {
            _uiState.update { it.copy(actionMessage = "Nothing to download in this season yet.") }
            return
        }
        val row = SeriesDownloadRow(
            kind = SeriesDownloadKind.WHOLE_SEASON,
            label = "Whole season",
            detail = seasonKeepConfirmLine(
                PendingSeasonKeep(season.number, plan.items, plan.lockedCount, plan.totalSizeBytes),
                wifiOnlyDownloads,
            ),
        )
        _uiState.update {
            it.copy(
                qualitySheet = QualitySheetState(
                    mode = QualitySheetMode.DOWNLOAD,
                    ladder = LadderForScope.EMPTY,
                    recommendedBand = null,
                    season = season.number,
                    wholeSeasonRow = row,
                    wholeSeasonPlan = plan,
                    wholeSeasonNumber = season.number,
                    title = _uiState.value.post?.let { post -> post.cleanTitle ?: post.title },
                ),
            )
        }
    }

    /**
     * 2-button detail (Nov 2026): start a whole-season download. Each
     * episode goes through the same keep routing a single download would
     * (engine for HLS, device file otherwise), decided per handle from
     * the one metadata ask — so a season of mixed files and playlists
     * still lands in the right system. The Wi-Fi gate is checked at the
     * moment bytes would actually start. One refusal never stops the
     * rest: a refused episode is counted, and the message at the end
     * says how many of the season actually started. The snackbar offers
     * "See downloads" instead of navigating away.
     */
    fun startSeasonDownload(plan: SeasonKeepPlan, seasonNumber: Int) {
        if (DataSaver.wifiOnlyBlocks(wifiOnlyDownloads, onWifi)) {
            _uiState.update {
                it.copy(actionMessage = "Wi-Fi only is on — connect to Wi-Fi to download this title. You can change it in Settings.")
            }
            return
        }
        viewModelScope.launch {
            var started = 0
            var refused = 0
            var spaceBlocked: FreeSpaceVerdict.NotEnough? = null
            for (item in plan.items) {
                // The same one-ask decision keep() makes per copy:
                // the metadata answer decides which system owns it.
                val meta = runCatching { catalogRepository.resolveStream(item.handle) }.getOrNull()
                val target = if (meta != null && meta.success) keepTargetFor(meta.mimeType) else KeepTarget.DeviceFile
                if (target == KeepTarget.EngineHls) {
                    if (keepViaEngine(item.handle, item.printLabel, item.title, item.sizeBytes, meta?.resumable ?: false, quiet = true)) {
                        started++
                    } else {
                        refused++
                    }
                    continue
                }
                when (val room = deviceFileRoom(item.sizeBytes)) {
                    is FreeSpaceVerdict.NotEnough -> {
                        if (spaceBlocked == null) spaceBlocked = room
                        refused++
                        continue
                    }
                    is FreeSpaceVerdict.Ok -> Unit
                }
                when (deviceDownloads.enqueue(item.handle, item.title, item.printLabel)) {
                    is DeviceDownloadResult.Enqueued -> started++
                    is DeviceDownloadResult.NeedsPermission -> {
                        _navEvents.emit(DetailNavigationEvent.DownloadNeedsPermission)
                        return@launch
                    }
                    is DeviceDownloadResult.Refused -> refused++
                }
            }
            val blocked = spaceBlocked
            when {
                started > 0 -> {
                    _uiState.update {
                        it.copy(
                            actionMessage = if (refused > 0) {
                                "Season $seasonNumber: $started of ${plan.items.size} episodes started downloading."
                            } else {
                                "Season $seasonNumber started downloading."
                            },
                            snackbarActionLabel = "See downloads",
                            snackbarAction = SnackbarAction.SEE_DOWNLOADS,
                        )
                    }
                }
                blocked != null -> {
                    _uiState.update {
                        it.copy(
                            actionMessage = notEnoughSpaceMessage(blocked.neededBytes, blocked.freeBytes),
                            snackbarActionLabel = "Free space",
                            snackbarAction = SnackbarAction.SEE_DOWNLOADS,
                        )
                    }
                }
                refused > 0 -> {
                    _uiState.update { it.copy(actionMessage = "Season $seasonNumber could not start downloading.") }
                }
            }
        }
    }

    /**
     * Keep one HLS title through the in-app keep engine: it fetches the playlist's
     * segments from our redirector and plays them back offline. Adopt pins the row,
     * so the sweeper never ages out a file the Downloads list calls kept.
     *
     * 2-button detail (Nov 2026): returns true when a download actually
     * started (the old caller counted a failed engine keep as started).
     * [quiet] is for the season/batch loops, which report one total at
     * the end instead of one message per episode. The engine path
     * already checks its own storage; the device-file path's free-space
     * check lives in [startKeep] / [startSeasonDownload].
     */
    private suspend fun keepViaEngine(
        handle: String,
        printLabel: String,
        title: String,
        sizeBytes: Long?,
        resumable: Boolean,
        quiet: Boolean = false,
    ): Boolean {
        val engine = CacheEngineHolder.engine
        if (engine == null) {
            lastDownload = null
            if (!quiet) _uiState.update { it.copy(actionMessage = "Download engine not wired into this build yet") }
            return false
        }
        val baseUrl = runCatching { apiProvider.currentBaseUrl() }.getOrNull()
        if (baseUrl == null) {
            lastDownload = null
            if (!quiet) _uiState.update { it.copy(actionMessage = "Connect to a catalog server before downloading.") }
            return false
        }
        val url = withVipPass(
            streamRedirectUrl(baseUrl, handle),
            // Session 123: the keep fetches bytes through the same redirector
            // the player uses, so the same pass rides it — a VIP keep of a
            // premium print downloads instead of 402ing.
            syncAccountRepository.currentVipPass(),
        )
        val request = AdoptRequest(
            key = CacheSessionKey(renditionKey = handle, url = url),
            url = url,
            fetchedBytesSoFar = 0L,
            isByteCursorKnown = false,
            positionMs = 0L,
            bufferedMs = 0L,
            durationMs = 0L,
            expectedTotalBytes = sizeBytes?.takeIf { it > 0 },
            resumable = resumable,
            printLabel = printLabel,
            title = title,
            sharesCacheStorage = false,
            // The post id rides into the task row so the Downloads
            // auto-delete sweep can match this keep by identity, never by
            // title string. The loaded post's id first, the detail's own
            // id as fallback; 0 means unknown, and an unknown keep is
            // never auto-deleted.
            postId = _uiState.value.post?.id?.takeIf { it > 0 }
                ?: resolvedPostId.takeIf { it > 0 } ?: 0,
        )
        val result = try {
            engine.adopt(request)
        } catch (t: Throwable) {
            KeepResult.Failed(t.message ?: "engine error")
        }
        when (result) {
            is KeepResult.Stored, is KeepResult.OnlyPlayableInApp -> {
                lastDownload = null
                if (!quiet) {
                    val size = sizeBytes?.takeIf { it > 0 }?.let { " · ${QualityOption.formatBytes(it)}" } ?: ""
                    _uiState.update {
                        it.copy(
                            actionMessage = "Downloading $printLabel$size",
                            snackbarActionLabel = "See downloads",
                            snackbarAction = SnackbarAction.SEE_DOWNLOADS,
                        )
                    }
                }
                return true
            }
            is KeepResult.StorageFull -> {
                lastDownload = null
                if (!quiet) {
                    _uiState.update {
                        it.copy(
                            actionMessage = "Not enough space to keep this title — needs " +
                                CacheAffordance.formatBytes(result.requiredBytes) + ", " +
                                CacheAffordance.formatBytes(result.availableBytes) + " free.",
                            snackbarActionLabel = "Free space",
                            snackbarAction = SnackbarAction.SEE_DOWNLOADS,
                        )
                    }
                }
                return false
            }
            is KeepResult.Failed -> {
                lastDownload = null
                if (!quiet) _uiState.update { it.copy(actionMessage = result.message) }
                return false
            }
            is KeepResult.Interrupted -> {
                lastDownload = null
                if (!quiet) _uiState.update { it.copy(actionMessage = "Download paused — it will resume on its own.") }
                return false
            }
            KeepResult.EngineNotWired -> {
                lastDownload = null
                if (!quiet) _uiState.update { it.copy(actionMessage = "Download engine not wired into this build yet") }
                return false
            }
        }
    }

    /** Re-run the request that stopped for a storage grant, once the viewer allows it. */
    fun retryLastDownload() {
        lastDownload?.let { (handle, label) -> keep(handle, label) }
    }

    private var lastDownload: Pair<String, String>? = null

    /**
     * Gap A2 — the pre-resolved top link: the `playV2` bundle for the best
     * print, resolved when the detail opened, so Play reuses it instead of
     * calling the server again. A failed pre-resolve leaves all three null
     * and Play falls back to its own call — a warm-up that fails is silent,
     * never an error the viewer sees.
     */
    private var preresolvedReleaseId: Int? = null
    private var preresolvedResult: V2PlayResult? = null
    private var preresolvedAtMs: Long? = null
    private var preresolveJob: Job? = null
    private var liveFillJob: Job? = null

    /**
     * Live fill (detail open): ask the server to fill just this title's
     * missing metadata, in the background, and patch the fields in place
     * when it answers. Fires once per load, only when the detail shows a
     * blank displayable field, and only after the Kids Lock gate — the
     * call site sits past it. A v2 row names its work id; a v1 row its
     * post id (a v2 detail whose bridge found no v1 post still has its
     * work id). The staleness guard drops an answer for a detail the
     * viewer already left: only the exact instance this load showed may
     * be patched, by reference. A failure is silent — the repository
     * answers null and the detail keeps the blanks it already shows.
     */
    private fun maybeLiveFill(detail: PostDetail) {
        if (!detail.hasMissingDisplayFields()) return
        // The v2 path names the work; the v1 path names the post. A v2
        // detail whose bridge found no v1 post still has its work id.
        // Exactly one id is sent — the server refuses a body with both.
        val work = workId.takeIf { it >= 0 }
        val post = if (work == null) {
            postId.takeIf { it > 0 } ?: detail.id.takeIf { it > 0 }
        } else {
            null
        }
        if (work == null && post == null) return
        liveFillJob?.cancel()
        liveFillJob = viewModelScope.launch {
            val filled = catalogRepository.enrichWorkDetail(workId = work, postId = post)
                ?: return@launch
            if (filled.isEmpty()) return@launch
            val current = _uiState.value.post ?: return@launch
            if (current !== detail) return@launch
            val patched = current.withLiveFill(filled)
            if (patched == current) return@launch
            rawDetail = patched
            _uiState.update { it.copy(post = patched) }
        }
    }

    /**
     * Pre-resolve better (2026-11-13) — the byte seed's linger gate and the
     * key of the seed this detail started. The gate waits out
     * [PREVIEW_SEED_LINGER_MS] after the bundle lands before the seed may
     * run, so a viewer who leaves at once never starts a download; the key
     * is what [onCleared] cancels in the engine when the viewer does leave
     * after the seed started — the download lives in the engine's scope,
     * not this ViewModel's, so cancelling the ViewModel's jobs alone would
     * never stop it.
     */
    private val previewSeedGate = PreviewSeedGate(viewModelScope)
    private var seededPreviewKey: CacheSessionKey? = null

    /**
     * Pre-resolve better (2026-11-13) — leaving the detail stops everything
     * this detail started: the in-flight pre-resolve, a seed still waiting
     * out its linger, and — through the engine — a seed already downloading
     * for a title the viewer abandoned. The engine cancels only an
     * opportunistic auto-preview row; a row the viewer promoted to a keep
     * is never touched. `viewModelScope` is cancelled on clear anyway; the
     * explicit cancels here are the record that the seed's engine side is
     * owned by this screen too.
     */
    override fun onCleared() {
        preresolveJob?.cancel()
        previewSeedGate.cancel()
        seededPreviewKey?.let { key ->
            seededPreviewKey = null
            CacheEngineHolder.engine?.cancelPreview(key)
        }
        super.onCleared()
    }

    /**
     * Gap A2: resolve the best print's `playV2` bundle while the viewer is
     * still on the detail page. Same ceiling ([PRERESOLVE_TIMEOUT_MS]) and
     * same quality preference as the Play-press call, same print Play would
     * pick, and a locked print is never warmed — warming a rung the viewer
     * may not play would spend the server's answer on nothing.
     *
     * On Wi-Fi the winner's first bytes are also pre-buffered through the
     * engine's preview seed (the same bounded, silent seed the player uses
     * for the next episode): the budget there decides whether the bytes are
     * affordable, and a preview that fails is silent — Play still works, it
     * just waits on the first bytes again. Mobile data never pre-buffers.
     */
    private fun preresolveTopLink() {
        preresolveJob?.cancel()
        previewSeedGate.cancel()
        preresolvedReleaseId = null
        preresolvedResult = null
        preresolvedAtMs = null
        val best = bestPlayablePrintOf(_uiState.value.layout, premiumAccess, freeQualityCap) ?: return
        // Salaar (2026-11-13): the warm is chosen VIP-aware — the best print
        // the caller may actually play (Salaar: 720p for a free viewer, never
        // the 2160p print the server refuses to bundle). The step-down below
        // stays as defence in depth: the warm print is already free, so it
        // passes through unchanged.
        val warm = playStartPrint(_uiState.value.layout, best, premiumAccess, freeQualityCap) ?: return
        preresolveJob = viewModelScope.launch {
            val result = runCatching {
                withTimeout(PRERESOLVE_TIMEOUT_MS) {
                    catalogRepository.playV2(
                        warm.releaseId,
                        prefer = DataSaver.playPrefer(dataSaver, onWifi),
                        // Salaar (2026-11-12): a v2 detail's best print is a
                        // cat id — warming the wrong title's bundle would
                        // cache it for Play to reuse, so the universe rides
                        // along here exactly as it does at Play-press time.
                        legacyReleaseId = workId < 0,
                    )
                }
            }.getOrNull() ?: return@launch
            if (result.bundle.renditions.isEmpty()) return@launch
            preresolvedReleaseId = warm.releaseId
            preresolvedResult = result
            preresolvedAtMs = System.currentTimeMillis()
            // Pre-resolve better (2026-11-13): the bundle itself is ready
            // immediately — Play reuses it at once. The byte seed is not
            // immediate any more: the gate waits out the linger and
            // re-checks this bundle is still current and the device is
            // still on Wi-Fi before a single byte moves.
            if (onWifi) {
                previewSeedGate.schedule(
                    stillWanted = {
                        shouldSeedAfterLinger(
                            preresolvedReleaseId,
                            preresolvedAtMs,
                            warm.releaseId,
                            onWifi,
                            System.currentTimeMillis(),
                        )
                    },
                    seed = { seedPreviewTopLink(warm, result) },
                )
            }
        }
    }

    /** Gap A2: the Wi-Fi pre-buffer for the pre-resolved winner. Silent by contract. */
    private suspend fun seedPreviewTopLink(best: RankedPrint, result: V2PlayResult) {
        val winner = result.bundle.renditions.firstOrNull() ?: return
        val playUrl = winner.playUrl?.takeIf { it.isNotBlank() } ?: return
        val handle = winner.streamId?.takeIf { it.isNotBlank() } ?: return
        val engine = CacheEngineHolder.engine ?: return
        val url = withVipPass(playUrl, runCatching { syncAccountRepository.currentVipPass() }.getOrNull())
        // Pre-resolve better (2026-11-13): remember the seed's key BEFORE
        // the engine call, so onCleared can cancel this exact preview even
        // if the seed is still in flight. The seed is a first seed — the
        // engine caps its first fill at the small window, and the player
        // extends it to the full ahead window when Play starts.
        val key = CacheSessionKey(renditionKey = handle, url = url)
        seededPreviewKey = key
        runCatching {
            engine.seedPreview(
                PreviewRequest(
                    key = key,
                    url = url,
                    expectedTotalBytes = best.sizeBytes?.takeIf { it > 0L },
                    resumable = winner.resumable,
                    printLabel = winner.label ?: best.rawTitle ?: "Top link",
                    firstSeed = true,
                ),
            )
        }
    }

    // Review RISK-h: last external provider-page open, uptime millis —
    // the debounce for the double-open race (see playLadder).
    private var lastProviderOpenMs: Long = 0

    /**
     * Play one rung of the ladder. The rung's runner-ups and the ladder itself are staged
     * for the player first, so a CDN that refuses mid-play costs a silent hop instead of a
     * dialog, and a viewer who chose Auto can be moved along these same rungs by the
     * measured throughput — while the navigation route still carries one opaque handle only.
     */
    /**
     * Play one rung of a ladder. [ladder] is the ladder the rung came
     * from — the whole-work ladder for a movie, the episode's own ladder
     * for a series (2-button detail, P1 fix) — and [season]/[episode]
     * are the scope the rung belongs to, so the resume point and the
     * in-player switch are armed for the episode actually playing, not
     * for the whole work.
     */
    fun playLadder(
        option: QualityOption,
        external: Boolean = false,
        ladder: LadderForScope = _uiState.value.ladder,
        season: Int? = null,
        episode: Int? = null,
    ) {
        // Salaar (2026-11-12): a locked rung steps down to the best free rung
        // before any lock is shown — the lock is only the answer when no free
        // rung can play. A tap on 4K that would 402 at press time starts the
        // best free picture instead of burning the player's whole start
        // budget on copies that cannot play.
        val effective = if (isLocked(option)) {
            // Review RISK-d: the step-down still plays the best free
            // rung instead of blocking (Salaar, 2026-11-12), but it is
            // no longer silent — the viewer is told which rung actually
            // plays, so a premium tap never quietly becomes a lower
            // picture. Only the no-free-rung case shows the lock line.
            val stepped = stepDownRung(ladder, option, premiumAccess, freeQualityCap)
                ?: run {
                    _uiState.update { it.copy(actionMessage = VipQualityGate.lockedMessage(freeQualityCap)) }
                    return
                }
            _uiState.update {
                it.copy(actionMessage = "Selected quality is premium - playing ${bandDisplayName(stepped.band)} instead.")
            }
            stepped
        } else {
            option
        }
        val title = _uiState.value.post?.let { it.cleanTitle ?: it.title } ?: "Stream"
        if (effective.streamId.isBlank()) {
            _uiState.update { it.copy(actionMessage = "Nothing playable on this rung yet.") }
            return
        }
        // A rung that is a page is not staged and not played: nothing starts, so there is no
        // resume point to write and no position for Auto to hop to. Staging it would put an HTML
        // page in the failover list the player walks when a CDN refuses.
        // Review RISK-h: the provider page opens externally, and this
        // path clears playStarting (Detail stays usable), so the tap
        // guard cannot debounce it — the open itself is debounced
        // instead: a second open within 2 s of the first is the same
        // tap, not intent.
        if (effective.isProviderPage) {
            val now = SystemClock.uptimeMillis()
            _uiState.update { it.copy(selectedBand = effective.band, playStarting = false) }
            if (now - lastProviderOpenMs < 2_000) return
            lastProviderOpenMs = now
            viewModelScope.launch {
                _navEvents.emit(DetailNavigationEvent.OpenProviderPage(effective.streamId, title))
            }
            return
        }
        PlayerSessionStore.Instance.stageFailover(effective.streamId, effective.backupHandles)
        stageAutoLadder(effective, ladder)
        stageSyncScope(
            streamId = effective.streamId,
            // 2-button detail (Nov 2026, P1 fix): the scope is the
            // episode the rung belongs to — a series episode's resume
            // point belongs to the episode, not the whole work.
            season = season,
            episode = episode,
            // Every rung of this scope's ladder plus each rung's runner-ups: Auto moves
            // playback onto another address without coming back through this screen, and a
            // resume point belongs to the episode, not to whichever address served it.
            backupHandles = ladder.options.flatMap { listOf(it.streamId) + it.backupHandles },
        )
        _uiState.update { it.copy(selectedBand = effective.band) }
        viewModelScope.launch {
            if (external && effective.playUrl != null) {
                _navEvents.emit(DetailNavigationEvent.OpenExternal(effective.streamId, effective.playUrl))
            } else if (effective.playsHere) {
                _navEvents.emit(DetailNavigationEvent.PlayStream(effective.streamId, title))
            } else {
                // Not a failure and not a silent no-op: the rung can be downloaded, and
                // saying so is the honest answer to a tap on a picture we cannot stream here.
                keep(effective.streamId, effective.title)
            }
        }
    }

    /**
     * Hand the player the rungs Auto may move along, in the CURRENT scope only: a ladder is
     * per season/episode, and offering another episode's copies would be a lie about what
     * the viewer is watching. Bands and handles only — no addresses, nothing displayable.
     *
     * Auto is armed exactly when the tapped row IS the ladder's own auto pointer. A
     * hand-picked band is a decision, and a decision outranks any throughput estimate: the
     * director then reports the rung and never moves it.
     */
    private fun stageAutoLadder(option: QualityOption, ladder: LadderForScope = _uiState.value.ladder) {
        val auto = isAutoRung(ladder, option)
        val rungs = ladder.options
            .filter { it.playsHere && it.streamId.isNotBlank() && it.band.isNotBlank() }
            .map {
                LadderRung(
                    band = it.band,
                    streamId = it.streamId,
                    backupHandles = it.backupHandles,
                    label = it.title,
                    // 2-button detail (Nov 2026): the in-player quality
                    // sheet shows the size when the server named one, and
                    // the format in plain words — never invented, never
                    // guessed. Null size means the row omits it.
                    sizeBytes = it.sizeBytes,
                    isHls = isHlsOption(it),
                )
            }
        PlayerSessionStore.Instance.stageAutoLadder(
            streamId = option.streamId,
            rungs = rungs,
            startBand = option.band,
            auto = auto,
        )
    }

    /**
     * Which work a launched rung is, in the only form the sync account accepts: the handle the
     * server minted for this post. Nothing is staged when the catalog named no handle, so a
     * deep-linked or un-named playback simply syncs nothing instead of guessing at an
     * identifier — the player reads these back from [PlayerSessionStore].
     *
     * A rung's silent runner-ups are staged under the same scope, because a CDN that refuses
     * moves playback onto one of them without anybody choosing a new work: the resume point
     * belongs to the episode, not to the address that happened to serve it.
     */
    private fun stageSyncScope(
        streamId: String,
        season: Int?,
        episode: Int?,
        backupHandles: List<String> = emptyList(),
    ): PlayerSessionStore.SyncScope? {
        val workRef = _uiState.value.post?.workRef?.takeIf { it.isNotBlank() } ?: return null
        val post = _uiState.value.post
        val scope = PlayerSessionStore.SyncScope(
            workRef = workRef,
            season = season ?: 0,
            episode = episode ?: 0,
            postId = post?.id ?: 0,
            workType = post?.contentType,
        )
        PlayerSessionStore.Instance.stageWorkRef(streamId = streamId, scope = scope)
        backupHandles.forEach { PlayerSessionStore.Instance.stageWorkRef(it, scope) }
        // D-35: stage the display metadata beside the scope, so the player can show
        // the poster behind its loading state instead of a black void. The poster is
        // the detail's own posterUrl; the cast is parsed the same way the detail
        // screen parses it (parseCastNames, cap 12).
        val meta = PlayerSessionStore.TitleMeta(
            posterUrl = post?.posterUrl,
            year = post?.year,
            castNames = parseCastNames(post?.castNames),
        )
        PlayerSessionStore.Instance.stageTitleMeta(streamId = streamId, meta = meta)
        backupHandles.forEach { PlayerSessionStore.Instance.stageTitleMeta(it, meta) }
        return scope
    }

    /** Play the best link this print offers. The server already ranked them. */
    fun playPrint(print: RankedPrint, external: Boolean = false) {
        // Salaar (2026-11-12): a locked best print steps down to the best free
        // print BEFORE any lock is shown — the lock is only the answer when no
        // free print can play. The old shape returned the lock here even while a
        // 720p copy of the same title sat playable.
        val start = playStartPrint(_uiState.value.layout, print, premiumAccess, freeQualityCap)
        if (start == null) {
            _uiState.update { it.copy(actionMessage = VipQualityGate.lockedMessage(freeQualityCap)) }
            return
        }
        if (start.releaseId != print.releaseId) {
            playPrint(start, external)
            return
        }
        val link = print.links.firstOrNull { it.playableHere } ?:
            print.links.firstOrNull { it.isProviderPage } ?:
            print.links.firstOrNull { it.keepable }
        if (link == null) {
            _uiState.update { it.copy(actionMessage = "Nothing playable on this print yet.") }
            return
        }
        // The server's own bundle first (plan §5 step 13): its winner is ranked from
        // the server's probes, and only it carries the print's coverage. Any failure
        // — a 403 while nginx still blocks the route, a timeout, a dead socket — falls
        // back to the client-side link above, so Play never gets slower or stricter
        // than it was. The ceiling is [PLAY_V2_TIMEOUT_MS]: the old 2 s timed out
        // a healthy call under load (the server alone pays 0.25-0.37 s of CPU per
        // request before the bundle reads), and the fallback is a cold single link
        // — strictly worse than the ranked bundle with its runner-ups.
        viewModelScope.launch {
            // Gap A2: the detail-open pre-resolve, when it is fresh and for
            // this very print — Play then starts on the bundle that is
            // already here instead of paying for the same call again.
            val cached = preresolvedResult.takeIf {
                isPreresolveFresh(preresolvedReleaseId, preresolvedAtMs, print.releaseId, System.currentTimeMillis())
            }
            if (cached != null && playV2Bundle(cached, print, external)) return@launch
            val v2 = runCatching {
                withTimeout(PLAY_V2_TIMEOUT_MS) {
                    catalogRepository.playV2(
                        print.releaseId,
                        prefer = DataSaver.playPrefer(dataSaver, onWifi),
                        // Salaar (2026-11-12): the id's universe is the
                        // detail's — a v2 detail's print id is a cat id, and
                        // sending it as legacy presses another title.
                        legacyReleaseId = workId < 0,
                    )
                }
            }.getOrNull()
            val playedV2 = v2 != null && v2.bundle.renditions.isNotEmpty() &&
                playV2Bundle(v2, print, external)
            if (!playedV2) {
                // Salaar (2026-11-12): a premium print the server would not
                // bundle (the mint refuses it for a free caller) must step
                // down to the best free print instead of pressing this
                // print's own link — that link 402s at press time, and the
                // player then burns its whole watchdog budget on copies that
                // cannot play. The trigger is server truth, not the local
                // gate flag: the bundle came back empty for a premium print,
                // and the mint exempts a confirmed VIP, so an empty bundle
                // for a caller without confirmed VIP IS the gate refusing it
                // — whatever the flag said when this detail loaded. A VIP's
                // empty bundle is a transient or dead print, and the
                // fallback link is the right second chance for them.
                if (!serverVip &&
                    VipQualityGate.isPremiumResolution(print.resolution, freeQualityCap)
                ) {
                    val lower = stepDownPrint(
                        _uiState.value.layout, print, premiumAccess, freeQualityCap,
                    )
                    if (lower != null) {
                        playPrint(lower, external)
                        return@launch
                    }
                }
                // Gap A2 follow-up: the fallback used to launch with no
                // runner-ups, so a stall on this print could only retry the
                // same address. The print's own sibling copies ride along
                // now, and the player hops between them on a stall.
                playLink(link, external, print.season, print.episodeFrom, siblingBackupHandles(print, link))
            }
        }
    }

    /**
     * Session 121 (P-26): play the best print of the loaded title — the hero's Watch
     * Now. The "best" is the same print the screen's own Play button offers
     * (highest `rankKey` across every print of the title), so the hero and the
     * details page never disagree about what plays. A title with no print, a
     * locked print, or a print with nothing playable degrades gracefully: the
     * viewer stays on the details page with the reason in front of them, because
     * `playPrint` says so instead of navigating anywhere.
     */
    fun playBest() {
        // Salaar (2026-11-13): Auto is the best print the caller may play —
        // a free viewer gets the best free print (720p on Salaar), never the
        // 2160p tier the server refuses to bundle. `playPrint` still steps
        // down as defence in depth.
        val best = bestPlayablePrintOf(_uiState.value.layout, premiumAccess, freeQualityCap)
        if (best == null) {
            _uiState.update { it.copy(actionMessage = "Nothing playable on this title yet.") }
            return
        }
        playPrint(best)
    }

    /**
     * Detail redesign (Nov 2026): a number tile on the season sub-page.
     * Plays the episode's best print that can actually start here — the
     * same rule the hero's Play button applies to the whole work. The
     * print's own season / episode ride along, so the resume point
     * belongs to the episode, not the address that served it. A tile with
     * nothing playable is dimmed and never calls this; a call that still
     * finds nothing says so instead of navigating anywhere.
     */
    fun playEpisode(season: Int, episode: Int) {
        val print = episodePlayPrint(_uiState.value.layout, season, episode)
        if (print == null) {
            _uiState.update { it.copy(actionMessage = "Nothing playable on this episode yet.") }
            return
        }
        playPrint(print)
    }

    /**
     * Play the server's own bundle for this print (plan §5 step 13). The winner is
     * the server's best copy — ranked from its probes, not our formula — so it is
     * the handle that plays. Its runner-ups stage as the silent failover and its
     * coverage stages as the print's chapters, both under the winner's handle, so
     * the player reads them back without the route carrying anything new.
     * Returns false when the bundle cannot be played after all, so the caller falls
     * back to the client-side link instead of leaving the viewer with nothing.
     */
    private fun playV2Bundle(result: V2PlayResult, print: RankedPrint, external: Boolean): Boolean {
        val winner = result.bundle.renditions.firstOrNull() ?: return false
        val streamId = winner.streamId?.takeIf { it.isNotBlank() } ?: return false
        val playUrl = winner.playUrl?.takeIf { it.isNotBlank() } ?: return false
        PlayerSessionStore.Instance.stageFailover(
            streamId,
            result.bundle.renditions.drop(1).mapNotNull { it.streamId },
        )
        PlayerSessionStore.Instance.stageCoverage(streamId, result.coverage)
        // Gap A3: the winner's subtitle tracks ride the handle the same way
        // its coverage does — the player stamps them onto the rendition
        // when it opens the handle. A track with no URL can never be
        // fetched, so it is dropped here rather than staged to fail on.
        PlayerSessionStore.Instance.stageTracks(
            streamId,
            winner.tracks.mapNotNull { track ->
                val url = track.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SubtitleTrackInfo(
                    language = track.language,
                    kind = track.kind,
                    url = url,
                    format = track.format,
                    isDefault = track.isDefault,
                    forced = track.forced,
                )
            },
        )
        // MX #4: the winner's Skip Intro / Skip Recap windows ride the
        // handle the same way its coverage does — the player stamps them
        // onto the bundle when it opens the handle. An empty list stages
        // nothing: no segments, no button.
        PlayerSessionStore.Instance.stageSegments(streamId, result.segments)
        val link = RankedLink(
            streamId = streamId,
            linkId = winner.linkId?.toInt(),
            // A stream candidate is, by the server's own verdict, playable and not
            // dead — the dead ones never reach this list. Anything else would be
            // the client re-judging the server's ranking.
            health = LinkState.ACTIVE,
            ephemeral = winner.ephemeral,
            resumable = winner.resumable,
            playUrl = playUrl,
            label = winner.label,
            // kind is not served on a rendition: the playability alone decides
            // whether this is a page, and a stream candidate never is one.
            kind = null,
            playability = winner.playability,
            score = winner.linkScore,
        )
        // Session 153: stage the gated ladder's rungs for the winner's band
        // before playing, so a 402 on this print steps down to the next rung
        // instead of giving up. Only `playLadder` staged one before, so the
        // Play-button path had no step-down plan at all — a gated handle
        // that reached the player burned the whole start budget. The winner's
        // band is the print's resolution band; a rung with no band match
        // stages nothing, and the player falls back to its own failover.
        // 2-button detail (Nov 2026, P1 fix): the whole-work ladder is
        // EMPTY for a multi-episode series, so staging from it armed no
        // switch at all. Resolve the ladder for this print's own episode
        // instead — a pack (one file for the whole season) keeps the old
        // behaviour, because another episode's copies would be the wrong
        // content at a different quality.
        val scopeLadder = if (print.season != null && print.episodeFrom != null && !print.isPacked) {
            ladderForEpisode(print.season, print.episodeFrom)
        } else {
            _uiState.value.ladder
        }
        rungForBand(scopeLadder, print.resolution.wire)?.let { stageAutoLadder(it, scopeLadder) }
        playLink(link, external, print.season, print.episodeFrom)
        return true
    }

    /**
     * Nothing is resolved here: the server hands us an opaque handle plus OUR OWN redirector
     * for it, and the 302 happens at playback time. A link without either is not a link.
     *
     * [season] / [episode] are the scope this row belongs to, when the caller knows it. They
     * decide which resume point the player looks up, so an unknown scope is passed on as the
     * card's own rather than as a guess of 0/0.
     */
    fun playLink(link: RankedLink, external: Boolean = false, season: Int? = null, episode: Int? = null, backupHandles: List<String> = emptyList()) {
        val title = _uiState.value.post?.let { it.cleanTitle ?: it.title } ?: "Stream"
        val playUrl = link.playUrl
        if (link.streamId.isBlank() || playUrl.isNullOrBlank()) {
            _uiState.update { it.copy(actionMessage = "This option is download-only.") }
            return
        }
        // Nothing plays here, so nothing is staged: no resume scope, no Continue-watching row,
        // no position lookup waiting on bytes that will never arrive. The provider's page opens
        // instead, and the viewer leaves this screen having watched nothing on our side.
        if (link.isProviderPage) {
            viewModelScope.launch {
                _navEvents.emit(DetailNavigationEvent.OpenProviderPage(link.streamId, title))
            }
            return
        }
        // Gap A2 follow-up: this is the fallback path (the server bundle failed
        // or timed out), and it used to launch a bare single-rendition bundle —
        // the 20 s watchdog could only retry the same slow address and then give
        // up, while sibling copies of the print sat unused. Stage the
        // caller-supplied runner-ups so a stall hops within the rung instead.
        // Only staged when non-empty: the v2-bundle caller already staged its
        // own runner-ups before reaching here, and an empty list would wipe
        // them (`stageFailover` replaces).
        if (backupHandles.isNotEmpty()) {
            PlayerSessionStore.Instance.stageFailover(link.streamId, backupHandles)
        }
        val scope = stageSyncScope(
            link.streamId,
            season,
            episode,
        )
        rememberWatchStart(scope)
        viewModelScope.launch {
            if (external) _navEvents.emit(DetailNavigationEvent.OpenExternal(link.streamId, playUrl))
            else _navEvents.emit(DetailNavigationEvent.PlayStream(link.streamId, title))
        }
    }

    /**
     * Put this scope at the top of Continue watching the moment playback is launched. The row
     * carries the catalog title and art so the rail never has to guess what a handle means;
     * [PlayerViewModel] owns the position from here on. A work the server could not name has
     * no handle, so there is no row to write — the player's own in-memory resume still works.
     */
    private fun rememberWatchStart(scope: PlayerSessionStore.SyncScope?) {
        val bound = scope ?: return
        val post = _uiState.value.post ?: return
        val title = post.cleanTitle?.takeIf { it.isNotBlank() } ?: post.title
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            watchHistoryDao.insertIfAbsent(
                WatchHistoryEntity(
                    workRef = bound.workRef,
                    postId = post.id,
                    title = title,
                    posterUrl = post.posterUrl?.takeIf { it.isNotBlank() },
                    seasonNumber = bound.season,
                    episodeNumber = bound.episode,
                    positionMs = 0L,
                    durationMs = 0L,
                    updatedAt = now,
                    imdbRating = post.imdbRating,
                ),
            )
            // A row that already existed keeps its position but still moves to the top and
            // picks up art the detail screen may have learned since it was first written.
            watchHistoryDao.touch(
                workRef = bound.workRef,
                season = bound.season,
                episode = bound.episode,
                postId = post.id,
                title = title,
                posterUrl = post.posterUrl?.takeIf { it.isNotBlank() },
                imdbRating = post.imdbRating,
                updatedAt = now,
            )
        }
    }

    fun keepPrint(print: RankedPrint) {
        // MX #7 — Kids Lock: downloads are a locked surface.
        if (_uiState.value.kidsMode) {
            _uiState.update { it.copy(actionMessage = "Downloads are locked in Kids Mode.") }
            return
        }
        if (!VipQualityGate.allowsResolution(print.resolution, premiumAccess)) {
            _uiState.update { it.copy(actionMessage = VipQualityGate.lockedMessage(freeQualityCap)) }
            return
        }
        // Wi-Fi-only is a gate, not a suggestion: starting a hundreds-of-megabytes
        // download on mobile data by accident is the expensive mistake it exists
        // to prevent. The message says where the switch lives, so the viewer is
        // never left guessing why the tap did nothing.
        if (DataSaver.wifiOnlyBlocks(wifiOnlyDownloads, onWifi)) {
            _uiState.update {
                it.copy(actionMessage = "Wi-Fi only is on — connect to Wi-Fi to download this title. You can change it in Settings.")
            }
            return
        }
        val link = print.links.firstOrNull { !it.streamId.isBlank() && !it.isPageLink }
        if (link == null) {
            _uiState.update {
                it.copy(
                    actionMessage = if (print.links.any { row -> row.isPageLink }) PAGE_NOT_A_FILE
                    else "Nothing to download on this print yet.",
                )
            }
            return
        }
        keep(
            handle = link.streamId,
            printLabel = print.qualityLabel.orEmpty(),
        )
    }

    /**
     * Submits a genuine broken-stream or load failure report to the server
     * (never exposes raw upstream URLs or host names). Uses `reportLink`
     * when a stream handle is loaded and `submitContact` with valid server
     * categories (`"bug"` on detail load failure, `"content"` on stream issue).
     */
    fun reportBrokenStream(note: String = "") {
        val current = _uiState.value
        if (current.isReportingIssue) return
        _uiState.update { it.copy(isReportingIssue = true) }
        viewModelScope.launch {
            val title = current.post?.cleanTitle?.takeIf(String::isNotBlank)
                ?: current.post?.title
                ?: current.fallbackPost?.cleanTitle?.takeIf(String::isNotBlank)
                ?: current.fallbackPost?.title
                ?: "Title"
            val idTag = when {
                workId > 0L -> "work=$workId"
                resolvedPostId > 0 -> "post=$resolvedPostId"
                else -> "unknown"
            }
            val layout = current.layout
            val sampleHandle = layout?.workPrints?.firstOrNull()?.links?.firstOrNull()?.streamId
                ?: layout?.seasons?.firstOrNull()?.episodes?.firstOrNull()?.prints?.firstOrNull()?.links?.firstOrNull()?.streamId
            val cleanNote = note.trim()
            val linkReported = if (!sampleHandle.isNullOrBlank()) {
                catalogRepository.reportLink(
                    handle = sampleHandle,
                    event = "dead_manual",
                    error = cleanNote.ifBlank { "user_reported_broken" },
                )
            } else {
                false
            }
            val bodyParts = buildList {
                add("Title: $title ($idTag)")
                current.error?.let { add("Load error: $it") }
                if (cleanNote.isNotBlank()) add("Viewer note: $cleanNote")
                else add("Reported broken stream from detail screen.")
            }
            val contactSaved = catalogRepository.submitContact(
                category = if (current.error != null) "bug" else "content",
                subject = "Broken stream report: $title",
                body = bodyParts.joinToString("\n"),
                accountHint = null,
            )
            _uiState.update {
                it.copy(
                    isReportingIssue = false,
                    actionMessage = if (contactSaved || linkReported) {
                        "Thanks — we've sent your report."
                    } else {
                        "Couldn't send the report right now. Please try again."
                    },
                )
            }
        }
    }
}

/** Which download system owns a copy. */
enum class KeepTarget { DeviceFile, EngineHls }

/**
 * Which download system owns a copy, from its resolved mime alone. A segmented
 * playlist goes to the keep engine, which fetches segments; everything else stays
 * a device file. The rule is the engine's own [KeepIdentity.linkKindFor], so the
 * detail screen and the engine can never disagree about what HLS is.
 */
internal fun keepTargetFor(mimeType: String?): KeepTarget =
    if (KeepIdentity.linkKindFor(mimeType, null) == CacheCoordinator.KIND_HLS) {
        KeepTarget.EngineHls
    } else {
        KeepTarget.DeviceFile
    }

/**
 * One scope's progress, as an episode row shows it. `fraction` is null when
 * the file never reported a duration — the row paints no bar rather than
 * inventing one. `watched` is true only when the viewer ran to the end, not
 * when they merely opened it.
 */
data class EpisodeProgress(
    val fraction: Float?,
    val watched: Boolean,
    val remainingLabel: String?,
)

/** The Resume button's target: the latest scope this device stopped in. */
data class ResumePoint(
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
) {
    /** "Resume S1:E3 · 23 min left", or "Resume · 23 min left" for a whole work. */
    val label: String
        get() = "Resume" +
            (ContinueWatchingTile.scopeLabel(season, episode)?.let { " $it" } ?: "") +
            (ContinueWatchingTile.progressLabel(positionMs, durationMs)?.let { " · $it" } ?: "")
}

/**
 * The latest resumable row, newest first — a finished row is not a resume.
 * `rows` arrives newest first from the DAO; a caller passing them in any
 * other order gets the first resumable row of the list it gave.
 */
fun resumePointFor(rows: List<WatchHistoryEntity>): ResumePoint? {
    val row = rows.firstOrNull { it.resumable } ?: return null
    return ResumePoint(row.seasonNumber, row.episodeNumber, row.positionMs, row.durationMs)
}

/** One entry per watched scope, keyed by (season, episode). */
fun episodeProgressFor(rows: List<WatchHistoryEntity>): Map<Pair<Int, Int>, EpisodeProgress> =
    rows.associate { row ->
        val watched = row.durationMs > 0L &&
            row.positionMs >= row.durationMs - WatchHistoryEntity.END_GUARD_MS
        (row.seasonNumber to row.episodeNumber) to EpisodeProgress(
            fraction = ContinueWatchingTile.progressFraction(row.positionMs, row.durationMs),
            watched = watched,
            // A watched episode has nothing left to promise: "Less than a minute left"
            // under a full bar is a lie about a finished episode.
            remainingLabel = if (watched) null
                else ContinueWatchingTile.progressLabel(row.positionMs, row.durationMs),
        )
    }

/** The print the Resume button would play, or null when it has none. */
fun resumePrintFor(layout: ReleaseLayout, resume: ResumePoint?): RankedPrint? {
    if (resume == null) return null
    return if (resume.season > 0 && resume.episode > 0) {
        layout.seasons
            .firstOrNull { it.number == resume.season }
            ?.episodes?.firstOrNull { it.numberWithinSeason == resume.episode }
            ?.bestPrint
    } else {
        layout.workPrints.maxByOrNull { it.rankKey }
    }
}

/**
 * Detail redesign (Nov 2026): the Cast icon's gate — a receiver is
 * discoverable on this network AND the server's chromecast switch is on.
 * Either fact alone shows nothing: a switch with no TV is a dead button,
 * and a TV with the switch off is a promise the server will refuse.
 * Pure so a test can pin the gate without a ViewModel.
 */
internal fun castAvailableFor(devicesAvailable: Boolean, featureEnabled: Boolean): Boolean =
    devicesAvailable && featureEnabled

/**
 * Detail redesign (Nov 2026): the print a season-page tile plays — the
 * episode's best print that can actually start here (plays on this
 * device, or opens the provider's own page), highest rank first. The
 * layout is already the gated one, so a VIP-locked copy never wins for
 * a free viewer. Null when the episode has no such print: the tile
 * shows dimmed and never calls play. Pure so a test can pin it without
 * a ViewModel.
 */
internal fun episodePlayPrint(layout: ReleaseLayout?, season: Int, episode: Int): RankedPrint? =
    layout?.seasons
        ?.firstOrNull { it.number == season }
        ?.episodes?.firstOrNull { it.numberWithinSeason == episode }
        ?.prints?.filter { it.playableHere || it.opensProviderPage }
        ?.maxByOrNull { it.rankKey }

/** "2 h 10 m", "1 h 40 m", "96 min" — null when the server sent no runtime. */
fun formatRuntimeMinutes(minutes: Int?): String? {
    if (minutes == null || minutes <= 0) return null
    return if (minutes < 60) "$minutes min"
    else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) "$hours h" else "$hours h $rest m"
    }
}

/**
 * Filters related titles so the currently viewed work (matched by id, workRef,
 * or normalized title+year) and duplicate works never repeat in the Similar rail.
 */
internal fun filterSimilarTitles(candidates: List<Post>, current: PostDetail): List<Post> {
    val selfTitle = current.cleanTitle?.takeIf { it.isNotBlank() } ?: current.title
    // workKey prefers the work ref, so a candidate without one never matches
    // a current title that has one — the title+year key must be tracked too,
    // or "Jawan!" would show as similar to "Jawan". Both keys go in the seen
    // set, for the current title and for every kept candidate.
    val selfKey = UserWords.workKey(current.workRef, selfTitle, current.year)
    val selfTitleKey = UserWords.workKey(null, selfTitle, current.year)
    val seen = mutableSetOf(selfKey, selfTitleKey)
    val out = ArrayList<Post>(candidates.size)
    for (post in candidates) {
        if (current.id > 0 && post.id == current.id) continue
        if (!current.workRef.isNullOrBlank() && post.workRef == current.workRef) continue
        val key = UserWords.workKey(post.workRef, post.displayTitle, post.year)
        val titleKey = UserWords.workKey(null, post.displayTitle, post.year)
        if (key in seen || titleKey in seen) continue
        seen.add(key)
        seen.add(titleKey)
        out.add(post)
    }
    return out
}
