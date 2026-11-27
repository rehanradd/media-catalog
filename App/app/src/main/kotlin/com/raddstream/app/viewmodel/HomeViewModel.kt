package com.raddstream.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.UserRegion
import com.raddstream.app.data.cache.ConnectivityProbe
import com.raddstream.app.data.cache.NetworkClass
import com.raddstream.app.data.local.DownloadsForYouDao
import com.raddstream.app.data.local.DownloadsForYouEntry
import com.raddstream.app.data.local.FavoritePost
import com.raddstream.app.data.local.SyncedProgressDao
import com.raddstream.app.data.local.WatchHistoryDao
import com.raddstream.app.data.local.WatchHistoryEntity
import com.raddstream.app.data.local.WatchHistoryMerge
import com.raddstream.app.network.friendlyNetworkError
import com.raddstream.app.network.models.FacetsResponse
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PanelBroadcast
import com.raddstream.app.network.models.VipRemoteConfig
import com.raddstream.app.network.models.currentCalendarYear
import com.raddstream.app.network.models.displayYearLabel
import com.raddstream.app.network.models.WireText
import com.raddstream.app.ui.components.UserWords
import com.raddstream.app.player.AdCadenceGovernor
import com.raddstream.app.player.AdSurface
import com.raddstream.app.repository.CacheRepository
import com.raddstream.app.repository.CatalogRepository
import com.raddstream.app.repository.FavoritesRepository
import com.raddstream.app.repository.FollowedSeriesRepository
import com.raddstream.app.repository.TitleFeedbackRepository
import com.raddstream.app.sync.DeviceTrial
import com.raddstream.app.sync.SyncAccountRepository
import com.raddstream.app.ui.navigation.BroadcastTargeting
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One horizontal rail on Home. Every rail is loaded independently and reports its
 * own loading/error so one slow facet cannot blank the whole page.
 *
 * HONESTY RULE (owner brief + AGENTS.md policy): a rail is only *rendered* when it
 * has a real backing source. `HomeViewModel` therefore only builds rails the v1 API
 * can actually serve. Continue Watching renders from the device's own watch
 * history (no server needed), and the panel's sections render from the feed.
 * "Because you watch" renders from GET /api/v1/related, seeded from the
 * first Continue Watching post, and the genre/language jump-off renders
 * from GET /api/v1/facets — both are real server answers now, so neither
 * is reserved any more. "Most copies" used to sit in that list; it does
 * not any more, because the sort it needs already exists on the live route
 * (`/api/v1/posts?sort=trending`) — see [initialHomeSections].
 */
/**
 * The rails Home actually renders, in order. Continue Watching counts as a
 * row: the rule is at most 10 rows total, CW pinned under the hero, so the
 * content rails behind it are 9 when CW is showing and 10 when it is not.
 * A panel that publishes 12 sections does not get 12 rails on a phone —
 * the cap is the phone's, and the first rails win.
 */
fun visibleHomeRows(sections: List<HomeSection>, hasContinueWatching: Boolean): List<HomeSection> {
    val contentRows = if (hasContinueWatching) MAX_HOME_ROWS - 1 else MAX_HOME_ROWS
    return sections.take(contentRows)
}

/** At most 10 rows on Home, Continue Watching included. */
const val MAX_HOME_ROWS = 10

/**
 * The Home filter chips (mockup, Nov 2026): one row under the header that
 * filters the content rails in place — no navigation, no new screen. The
 * match is the rail's own identity: its [HomeSection.type] first, then its
 * key, so a panel rail (whose type is null — the panel owns the rows, not
 * the type) still matches by key. A mixed rail ("Recently added",
 * "Most copies", the regional rails) has no single type, so it shows only
 * under ALL — the filter never invents a type for a rail that has none.
 * "Complete series" is series content, so it rides SERIES. The hero,
 * Continue Watching, Pick 3 and the library are not rails and are never
 * filtered — only the content rails below them.
 * Anime and K-pop are the exception (video audit #31, Nov 2026): neither
 * has a rail of its own — anime is a post-level flag, not a rail type —
 * so those two chips narrow a rail to its matching titles instead (see
 * [filteredHomeSections]); a rail with no matching title hides.
 */
enum class HomeFilter(val label: String) {
    All("ALL"),
    Movies("MOVIES"),
    Series("SERIES"),
    Anime("ANIME"),
    Kpop("KPOP"),
    ;

    /** The empty-filter line when this chip hides every rail. */
    val emptyMessage: String
        get() = when (this) {
            All -> "Nothing here yet."
            Movies -> "No movies here yet."
            Series -> "No series here yet."
            Anime -> "No anime here yet."
            Kpop -> "No K-pop here yet."
        }
}

/** Whether this rail survives the active filter chip. Pure, so the chip filter is testable. */
internal fun homeFilterMatches(section: HomeSection, filter: HomeFilter): Boolean {
    if (filter == HomeFilter.All) return true
    val key = section.key
    return when (filter) {
        HomeFilter.Movies -> section.type == "movie" || key == "movie" || key == "panel:movie"
        HomeFilter.Series -> section.type == "series" || section.type == "complete_season" ||
            key == "series" || key == "panel:series"
        HomeFilter.Anime -> section.type == "anime" || key == "anime" || key == "panel:anime"
        HomeFilter.Kpop -> section.type == "kpop" || key == "kpop" || key == "panel:kpop"
        // Unreachable: ALL returns true above. It stays so the `when` is
        // exhaustive without an `else` that would silently swallow a new chip.
        HomeFilter.All -> true
    }
}

/**
 * Video audit #31 (Nov 2026): whether this post is anime, on the
 * server's own definition. Animation is not a `content_type` (the values
 * are only movie/series/complete_season/ongoing_season) — it is the
 * [Post.isAnimated] flag, and the server's `type=anime` answers from
 * `(is_animated = 1 OR industry LIKE '%anime%')` (verified live:
 * 10,739 rows). This is the same fragment, over the fields the list row
 * carries — so the chip and the server can never disagree about what
 * anime is again. A null flag reads as "we do not know", never as
 * animated: only a set flag or an anime industry claims the title.
 */
internal fun isAnimePost(post: Post): Boolean =
    post.isAnimated == true || post.industry?.contains("anime", ignoreCase = true) == true

/**
 * Video audit #31: whether this post is K-pop. K-pop has no server
 * taxonomy at all (`type=kpop` answers zero rows, verified live) — the
 * only K-pop in the catalog is what a title search for "kpop" finds (2
 * titles live: "KPop Demon Hunters" and one more). The predicate mirrors
 * that search over the loaded rows: the title or a genre naming K-pop,
 * hyphen- and space-blind, so "K-pop", "KPop" and "K Pop" all match.
 */
internal fun isKpopPost(post: Post): Boolean {
    fun key(text: String?) = text?.lowercase()?.replace("-", "")?.replace(" ", "") ?: ""
    return "kpop" in key(post.displayTitle) || "kpop" in key(post.genres)
}

/**
 * Video audit #31: the Home filter chips over the rails, with the
 * post-level fallback for Anime and K-pop. Movies and Series keep the
 * rail-level match — their rails ARE the type. Anime and K-pop have no
 * rail of their own, so a rail survives when it holds a matching title —
 * and shows only those titles. A rail that already matches by type/key
 * (e.g. a panel "anime" rail) passes through whole, un-narrowed. A rail
 * with no matching title drops, so the empty message stays honest.
 * Pure, so the chip filter is testable.
 */
internal fun filteredHomeSections(sections: List<HomeSection>, filter: HomeFilter): List<HomeSection> {
    if (filter == HomeFilter.All) return sections
    return when (filter) {
        HomeFilter.Movies, HomeFilter.Series -> sections.filter { homeFilterMatches(it, filter) }
        HomeFilter.Anime -> sections.mapNotNull { section ->
            if (homeFilterMatches(section, filter)) section
            else section.copy(items = section.items.filter(::isAnimePost)).takeIf { it.items.isNotEmpty() }
        }
        HomeFilter.Kpop -> sections.mapNotNull { section ->
            if (homeFilterMatches(section, filter)) section
            else section.copy(items = section.items.filter(::isKpopPost)).takeIf { it.items.isNotEmpty() }
        }
        HomeFilter.All -> sections
    }
}

/**
 * H9 (2027 Home fix, round 2): mood filters — "Something short", "Feel
 * good", "Mind-bending", "Dark & gritty". A discovery prompt, not a
 * category: the moods filter the rails below the mood row (which sits
 * after the Top 10 chart), where the sticky category row under the hero
 * is the navigation. Null means no mood — every rail shows.
 */
enum class HomeMood(val label: String) {
    Short("Something short"),
    FeelGood("Feel good"),
    MindBending("Mind-bending"),
    DarkGritty("Dark & gritty"),
    ;
}

/**
 * Whether this post matches the mood. Pure, so the chip filter is
 * testable. The predicates are honest heuristics over fields the list
 * row actually carries — genres, animation flag, content type — never a
 * claim about data the row does not have:
 *
 * - Short: a film is one sitting; a series is a commitment. No duration
 *   field exists on the list wire ([Post] carries none — duration lives
 *   only on Detail's `PostDetail.runtimeMinutes`), so "short" is the
 *   type, not a runtime claim, and the tiles paint no duration.
 * - Feel good: Comedy / Family / Romance / Animation — or any animated
 *   title (animation reads as feel-good even when the genre list does
 *   not say so).
 * - Mind-bending: Sci-Fi / Thriller / Mystery.
 * - Dark & gritty: Crime / Horror / Thriller. (Thriller matches both
 *   moods — a title can be mind-bending AND dark; the filter narrows,
 *   it does not sort into one box.)
 *
 * Genre matching is case-insensitive on the post's [Post.genreList]
 * (already placeholder-filtered), with the "Sci-Fi" / "Science Fiction"
 * spellings unified.
 */
internal fun moodMatchesPost(post: Post, mood: HomeMood?): Boolean {
    if (mood == null) return true
    return when (mood) {
        HomeMood.Short -> post.contentType == "movie"
        HomeMood.FeelGood -> post.isAnimated == true ||
            post.genreList.any { it.lowercase() in FeelGoodGenres }
        HomeMood.MindBending -> post.genreList.any { it.lowercase() in MindBendingGenres }
        HomeMood.DarkGritty -> post.genreList.any { it.lowercase() in DarkGrittyGenres }
    }
}

private val FeelGoodGenres = setOf("comedy", "family", "romance", "animation", "musical")
private val MindBendingGenres = setOf("sci-fi", "science fiction", "scifi", "thriller", "mystery", "psychological")
private val DarkGrittyGenres = setOf("crime", "horror", "thriller")

/**
 * H11 (2027 Home fix, round 2): the Continue Watching row a saved post
 * is already in — by server handle first (the v2 rows' `id` is a
 * negative sentinel, never a post id, so the id alone cannot match),
 * falling back to the title when the handle is absent. Pure, so the
 * match is testable. Returns null when the saved title is not being
 * watched — the strip then shows the poster with no progress bar, never
 * an invented one.
 *
 * The fallback is year-blind on purpose: the tile's [Post] carries no
 * year ([ResumeTile.of] builds it from a local row), so a year-strict
 * key would never match a saved post that has one. The same-title
 * remake caveat follows: with no handle, a title match is the best the
 * wire can say — the progress shown is the real row's, never invented.
 */
internal fun continueWatchingMatchFor(
    post: Post,
    continueWatching: List<ResumeTile>,
): ResumeTile? {
    val ref = post.workRef?.trim()?.takeIf { it.isNotBlank() }
    if (ref != null) {
        continueWatching.firstOrNull { it.workRef == ref }?.let { return it }
    }
    val titleKey = UserWords.workKey(null, post.displayTitle, null)
    return continueWatching.firstOrNull { tile ->
        UserWords.workKey(null, tile.post.displayTitle, null) == titleKey
    }
}

data class HomeSection(
    val key: String,
    val title: String,
    val type: String?,
    val items: List<Post> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /**
     * The server sort this rail asks for (`/api/v1/posts?sort=`), or null for
     * the server default: newest release year first — v1 orders by
     * `_NEWEST_YEAR_SQL DESC, p.id DESC` (dirty years sort last, `p.id DESC`
     * is only the tiebreak), v2 by year desc. Only "Most copies"
     * sets one today: `sort=trending` ranks by how many prints and mirrors
     * the catalog holds for a work — the ledger has no play-count column,
     * so the rail is labelled for the viewer, never "Trending" (which would
     * promise popularity data that does not exist).
     */
    val sort: String? = null,
    /**
     * The v1 post id this rail is related to (`/api/v1/related?work_id=`),
     * or null for an ordinary list rail. Only "Because you watch" sets
     * one: it is seeded from the first Continue Watching post, and it
     * hides when there is no seed or the server answers an empty page.
     */
    val relatedWorkId: Int? = null,
    /**
     * True when the operator's feed, not a posts call, owns this rail. A
     * panel rail already has its rows — it is never loaded, retried, or
     * refreshed through [HomeViewModel.loadSection]; the next remote-config
     * fetch rebuilds it.
     */
    val panelManaged: Boolean = false,
    /** Server `industry=` filter (`bollywood`, `south_indian`, etc.), or null when unfiltered. */
    val industry: String? = null,
    /**
     * E2 "For you" (Nov 2026): the reason line under the rail title
     * ("Because you watched One Piece"), or null for an ordinary rail.
     * Only the "For you" row sets one: it names the single anchor the
     * row's picks were scored against, so the reason is true of every
     * pick in the row, not a summary of a blended profile.
     */
    val subtitle: String? = null,
    /**
     * E2 "For you" (Nov 2026): true for the on-device personalised row.
     * It is computed from this device's history, never loaded through
     * [HomeViewModel.loadSection] and never cached — a restored copy
     * would be loaded through the posts route as if it were a built-in
     * rail, the exact hazard [cachedSectionsOf] guards against.
     */
    val forYou: Boolean = false,
    /**
     * REA #2 (Netflix, Nov 2026): the "97% Match" per pick, keyed by post
     * id — the [matchPercentage] of each pick's ranker score. Only the
     * "For you" row sets one: every other rail has no ranker score to
     * present, and a % with no score behind it would be invented. Empty
     * for every other rail. Excluded from the Home cache with the row
     * itself ([cachedSectionsOf] drops `forYou` sections), so no codec
     * change is owed.
     */
    val matchScores: Map<Int, Int> = emptyMap(),
) {
    val visible: Boolean get() = items.isNotEmpty() && error == null

    /**
     * True when Browse can reproduce this rail's order. Browse takes a type
     * and a sort — the trending sort is the one Browse reproduces (its v2
     * path already sorted by trending), so a trending rail has a "See all".
     * Any other server sort still has none: Browse would silently re-sort
     * it to freshest-first. A related rail has no Browse equivalent either
     * — Browse cannot reproduce a seed's neighbours. A "For you" rail has
     * none either: Browse cannot reproduce a taste-ranked order, so its
     * "See all" would silently re-sort the picks to freshest-first.
     */
    val browseEquivalent: Boolean get() = (sort == null || sort == "trending") && relatedWorkId == null && !forYou

    /**
     * A rail that already has rows and whose newest refresh came back empty-handed. Without
     * this the old rows stayed on screen and the failure went nowhere — `visible` was false, so
     * the section rendered no branch at all and the rail simply vanished from the page.
     */
    val refreshFailure: String? get() = error?.takeIf { items.isNotEmpty() }
}

data class HomeUiState(
    val sections: List<HomeSection> = emptyList(),
    val isRefreshing: Boolean = false,
    val fatal: String? = null,
    /** Real local rows only — see [WatchHistoryMerge] for why an unnameable pull is dropped. */
    val continueWatching: List<ResumeTile> = emptyList(),
    /**
     * A4-F2: this device has watch-history rows at all — finished titles
     * included. The rail only shows resumable rows, so [continueWatching]
     * is empty both for a viewer who finished everything and for a viewer
     * who never pressed play; only the second one is a first run. The
     * "No active shows" placeholder shows for the first, never the second
     * — on a fresh install it read as an error on a populated Home (Robo
     * matrix-12l8h97dvq326), while Pick 3 already onboards a new viewer.
     */
    val hasWatchHistory: Boolean = false,
    /** Set when the operator has published a downloadable build; null means "no news". */
    val updateNotice: AppUpdate? = null,
    /** Live broadcasts from the admin panel; a dismissed one stays dismissed for this session. */
    val broadcasts: List<PanelBroadcast> = emptyList(),
    /**
     * Whole days of free VIP trial left, live from the ledger — null when there is
     * no trial to explain (no grant recorded, trial over, viewer is VIP, or the
     * note was dismissed). The screen renders the number, never a hardcoded "3".
     */
    val trialDaysRemaining: Int? = null,
    /**
     * True when the device had no network at the last load. The banner that
     * reads this only shows when something saved is on screen — an offline
     * phone with nothing saved gets the error state, not a banner over a
     * blank page.
     */
    val isOffline: Boolean = false,
    /** Session 121 (P-26): the ids on this device's My List, so the hero's toggle reads live. */
    val favoriteIds: Set<Int> = emptySet(),
    /**
     * Phase 7 (M1): the same membership by server handle. A v2 row's `id` is
     * the negative work-id sentinel, never a v1 post id, so the id set alone
     * cannot say whether a v2 tile is saved — the handle set can.
     */
    val favoriteWorkRefs: Set<String> = emptySet(),
    /** Phase 7 (D1): the title of the Continue Watching tile just removed, until Undo or dismiss. */
    val removedResumeTitle: String? = null,
    /**
     * Gap A5: when the cached Home on screen was last refreshed from the
     * server (null until Home has ever loaded). The screen dates the rows
     * from this, never from a hardcoded "just now".
     */
    val lastHomeSyncMs: Long? = null,
    /** Gap A6: true once the first-run "Pick 3 you like" card is done (picked 3 or dismissed). */
    val pickThreeDone: Boolean = false,
    /**
     * Pick 3 (home review, Nov 2026): true once the card has been displayed
     * on a previous Home visit. The card is a one-shot — first display
     * retires it, whether the viewer picked, dismissed, or scrolled past —
     * but the shot is the whole visit, not one frame: the first build also
     * read this flag in [showPickThree], so the card retired itself the
     * instant it composed and no user or Robo run ever saw it. The visit
     * latch ([pickThreeShownThisVisit]) is what keeps the card up while
     * it is showing; this flag only keeps it away on later visits.
     */
    val pickThreeShown: Boolean = false,
    /**
     * Pick 3 (fixed 2026-11-15): true while the card is on screen on THIS
     * Home visit. Set by [onPickThreeShown] the moment the card composes,
     * cleared by [onPickThreeVisitEnded] when the card leaves composition
     * (the viewer left Home, or scrolled the card out of the feed).
     * Session-only — never persisted; the persisted [pickThreeShown] is
     * what retires the card for good.
     */
    val pickThreeShownThisVisit: Boolean = false,
    /**
     * First-run (video audit #11, Nov 2026): true once the viewer has left
     * Home for the first time. The Pick 3 card is an onboarding offer — it
     * may only appear while this is false. Leaving Home closes the
     * onboarding window for good: a card that never appeared on the first
     * Home visit (slow rails, viewer left early) is gone, not deferred,
     * because a deferred card is the mid-session interruption the audit
     * caught — it popped up only after the viewer came back from Live TV.
     */
    val onboardingSeen: Boolean = false,
    /**
     * The browse jump-off chips from GET /api/v1/facets (genres, languages,
     * industries, types, each with the count its tap delivers). Null until
     * the call answers, and null again when it fails — no chips, never a
     * failed Home, and the screen must not pretend the silence means "no
     * genres exist". Language chips are not rendered yet: Search has no
     * language filter to land on, so the group waits for one.
     */
    val facets: FacetsResponse? = null,
    /**
     * MX #7 — Kids Lock: when on, the screen reads through [visibleSections]
     * and [visibleContinueWatching] instead of the raw lists — the raw
     * lists stay whole underneath, so turning the mode off restores the
     * full Home with no re-fetch. The ViewModel flips this one flag from
     * the DataStore flow; the filtering itself is a pure function of it.
     */
    val kidsMode: Boolean = false,
    /**
     * H11 (2027 Home fix, round 2): the viewer's saved titles as display
     * posts — the My List strip between the broadcasts/sync line and rail
     * 1. Populated from `latestFavorites` via [myListDisplayPost] in
     * [observeFavorites]; empty means no My List, and the strip simply
     * does not render. Appended last so no positional construction breaks.
     */
    val savedPosts: List<Post> = emptyList(),
) {
    /**
     * MX #7 — Kids Lock: the rails a kid sees. Each rail keeps only
     * kid-appropriate rows; a rail left empty by the filter drops out,
     * exactly as an empty rail does in adult mode. A rail still loading or
     * carrying an error stays — the loading skeletons and the error row
     * are the same in kids mode, only the rows inside are filtered.
     */
    val visibleSections: List<HomeSection>
        get() = if (!kidsMode) sections else sections
            .map { section -> section.copy(items = section.items.filter { KidsLock.isKidAppropriate(it) }) }
            .filter { it.visible || it.loading || it.error != null }

    /** MX #7 — Kids Lock: Continue Watching with only the rows a kid may see. */
    val visibleContinueWatching: List<ResumeTile>
        get() = if (!kidsMode) continueWatching else continueWatching
            .filter { KidsLock.isKidAppropriate(it.post) }

    /**
     * H11: the My List strip in kids mode — the viewer's own saved titles,
     * kid-appropriate only. A saved adult title is still an adult title,
     * even though the viewer chose it.
     */
    val visibleSavedPosts: List<Post>
        get() = if (!kidsMode) savedPosts else savedPosts.filter { KidsLock.isKidAppropriate(it) }
    /**
     * Pick 3 (home review, Nov 2026): the three titles the redesigned card
     * offers — the first rail with at least three rows AFTER the hero's own
     * titles are taken out, first three only. The hero shows its section's
     * first six slides (HERO_LIMIT in HomeScreen), and Audit #18 says the
     * same title never appears twice on Home — so the hero's six are not
     * candidates: the picks come from the next titles of that rail, or the
     * next rail when it runs short. Empty when no rail can offer three
     * eligible titles, so the card waits instead of inventing titles.
     * Identity is the work key, not the row id: one film filed twice has
     * two row ids, and the hero exclusion follows [selectHeroSection], so
     * a stale panel hero that lost the slot to "Recently added" excludes
     * Recent's titles, not the stale list's.
     */
    val pickThreeCandidates: List<Post>
        get() {
            val heroKeys = selectHeroSection(sections, currentCalendarYear())
                ?.items.orEmpty().take(HERO_SLIDE_COUNT)
                .mapTo(HashSet()) { UserWords.workKey(it.workRef, it.displayTitle, it.year) }
            return sections.firstOrNull { section ->
                section.items.count { post ->
                    UserWords.workKey(post.workRef, post.displayTitle, post.year) !in heroKeys
                } >= 3
            }?.items
                ?.filter { post ->
                    UserWords.workKey(post.workRef, post.displayTitle, post.year) !in heroKeys
                }
                ?.take(3)
                .orEmpty()
        }

    /**
     * Gap A6: the card shows only to a brand-new viewer — nothing
     * continued, nothing saved, card not done — and only once a rail can
     * offer three real titles to pick from. First-run (video audit #11,
     * Nov 2026): and only while the onboarding window is still open — the
     * viewer has not left Home yet ([onboardingSeen] false). The old B5
     * rule made the card wait for the first browse or play, so it could
     * only ever appear AFTER the viewer had left Home and come back —
     * the mid-session pop-in the audit caught, right after Live TV. The
     * wait is gone: the card offers itself on the first Home visit, as
     * soon as the rails can offer three titles, and leaving Home retires
     * it unshown rather than deferring it.
     * Pick 3 (fixed 2026-11-15): and only once — but the once is a whole
     * Home visit. The visit latch ([pickThreeShownThisVisit]) keeps the
     * card up while it is showing, even after the persisted shown flag
     * lands and even after the first one or two picks (the empty-favourites
     * gate is an entry condition, not a live one — the third pick retires
     * the card through [pickThreeDone]). A viewer who scrolled past it is
     * never asked again: leaving Home clears the latch, and the persisted
     * flag then keeps the card away.
     */
    val showPickThree: Boolean
        get() = !kidsMode && !pickThreeDone && !onboardingSeen && (pickThreeShownThisVisit ||
            (!pickThreeShown && continueWatching.isEmpty() &&
                favoriteIds.isEmpty() && pickThreeCandidates.size >= 3))

    /**
     * Pick 3 (fixed 2026-11-15): the card reported shown on this visit.
     * Latches the card visible for the rest of the visit AND records the
     * persisted shown flag, so a later visit never offers it again. The
     * first build only set [pickThreeShown], and [showPickThree] required
     * it false — so the card retired itself the frame it appeared and no
     * user or Robo run ever saw it. The latch is the fix: display must
     * not be the thing that hides the card.
     */
    fun onPickThreeShown(): HomeUiState =
        copy(pickThreeShown = true, pickThreeShownThisVisit = true)

    /**
     * Pick 3: the Home visit ended — the card scrolled out of the feed or
     * the viewer left Home. The visit latch clears; the persisted shown
     * flag already keeps the card away on later visits.
     */
    fun onPickThreeVisitEnded(): HomeUiState = copy(pickThreeShownThisVisit = false)
}

/**
 * A build the operator published through remote config.
 *
 * The published body carries the APK's version code when the operator set
 * one, so this can compare versions instead of guessing: a published code
 * at or below this build means this build already has it, and no notice
 * shows. A body with no code (0) falls back to the old rule — a published,
 * http(s) URL is the whole signal. `required` is true only when the
 * operator set a minimum this build falls below: that update is not
 * optional, and the dialog offers no "Later".
 *
 * @param required true when this build is below the operator's minimum version.
 * @param sizeBytes the published APK's size in bytes, 0 when unpublished;
 * the dialog shows it so a viewer on mobile data knows what the tap costs.
 */
data class AppUpdate(
    val url: String,
    val publishedAt: Long,
    val required: Boolean = false,
    val sizeBytes: Long = 0,
) {
    companion object {
        /** Null unless the operator actually published a usable download link. */
        fun from(config: VipRemoteConfig?, currentVersionCode: Int = 0): AppUpdate? {
            val url = config?.apkUpdateUrl?.trim().orEmpty()
            if (!WireText.isHttpUrl(url)) return null
            // A published code is the operator's own verdict on whether this
            // build is behind: at or below it, this build already has the
            // update. A code of 0 means "no code published", and the link
            // alone is the signal, as before.
            val publishedCode = config?.apkUpdateVersionCode ?: 0
            if (publishedCode > 0 && currentVersionCode > 0 &&
                publishedCode <= currentVersionCode
            ) {
                return null
            }
            val minCode = config?.minVersionCode ?: 0
            val required = minCode > 0 && currentVersionCode > 0 &&
                currentVersionCode < minCode
            return AppUpdate(
                url = url,
                publishedAt = config?.updatedAt ?: 0L,
                required = required,
                sizeBytes = config?.apkUpdateSizeBytes ?: 0,
            )
        }
    }
}

private const val RESERVE_NOTE = """
    Reserved rails awaiting server support (see docs/HANDOFF_appui.md):
      - (none — "Because you watch" shipped with GET /api/v1/related and
        the genre / language jump-off with GET /api/v1/facets.)
    None of these render until the backend returns real rows; they are NOT faked.
    "Most copies" left this list in D2.2: its order already exists as
    sort=trending on /api/v1/posts (most prints and mirrors — no play counts
    exist, so it is not popularity), and the app already calls that sort.
"""

/** The key of the "Because you watch" rail, inserted after "Recently added". */
internal const val BECAUSE_YOU_WATCH_KEY = "because_you_watch"

/**
 * The rails Home actually renders, in order. Pure, so the test can pin the
 * questions that matter without a repository: five rails, "Most copies"
 * last, and only that rail asks the server for a sort.
 *
 * Only rails v1 can honestly serve today. `type = null` == recently added
 * (the API default order is newest release year first — v1
 * `_NEWEST_YEAR_SQL DESC, p.id DESC`, v2 year desc — not bare `p.id DESC`;
 * live-verified: the newest rail walks 2027, then 2026). "Most copies" is
 * `sort=trending` on the same live route — the server ranks by how many
 * prints and mirrors the catalog holds for a work, because the ledger has
 * no play-count column. The raw name stays here as the order's name; the
 * viewer reads "Most available" ([viewerTitleForPanelSection] maps it at
 * merge, home fix Nov 2026). "Trending" stays unused because it
 * would promise popularity data that does not exist (the same naming rule
 * SearchViewModel's [SearchSort] follows).
 */
internal fun initialHomeSections(): List<HomeSection> = listOf(
    HomeSection(key = "recent", title = "Recently added", type = null, loading = true),
    HomeSection(key = "movie", title = "Movies", type = "movie", loading = true),
    HomeSection(key = "series", title = "Series", type = "series", loading = true),
    HomeSection(key = "season", title = "Complete series", type = "complete_season", loading = true),
    HomeSection(key = "most_sources", title = "Most copies", type = null, sort = "trending", loading = true),
)

/**
 * Regional cinema rails backed directly by `GET /api/v2/browse?industry=` and
 * `GET /api/v1/posts?industry=`. Kept separate from [initialHomeSections] so
 * [initialHomeSections] preserves its pinned 5-rail contract, while live Home
 * exposes Bollywood and South Indian rails right behind them.
 */
internal fun regionalHomeSections(): List<HomeSection> = listOf(
    HomeSection(key = "bollywood", title = "Bollywood", type = null, industry = "bollywood", loading = true),
    HomeSection(key = "south_indian", title = "South Indian", type = null, industry = "south_indian", loading = true),
)

/**
 * The v2 `industry=` values a Home rail's [HomeSection.industry] key
 * actually filters on. "south_indian" is not a catalog value — the catalog
 * carries the four southern industries — so it expands through
 * [IndustryFilter]; every other key passes through as its single server
 * value, and null stays unfiltered. The v1 fallback keeps the raw key:
 * v1 answers "south_indian" from its own broad clause.
 */
internal fun industryWireValues(industry: String?): List<String>? =
    industry?.let {
        IndustryFilter.entries.firstOrNull { filter -> filter.wireValue == it }
            ?.wireValues?.takeIf { values -> values.isNotEmpty() }
            ?: listOf(it)
    }

/**
 * D2.7: the Home trial note, as a pure decision. The note shows only while the
 * trial is genuinely running (seconds left above zero), the viewer is not already
 * VIP (a VIP has nothing to be warned about), and the viewer has not dismissed it
 * (dismissal is persisted — the note never returns). Days round up, the way
 * [DeviceTrial.daysRemaining] rounds them everywhere else: 20 hours left is
 * "1 day remaining", never "0 days" on a trial that is still live.
 */
internal fun trialNoteDaysRemaining(
    trialSecondsLeft: Double?,
    isVip: Boolean,
    dismissed: Boolean,
): Int? {
    if (isVip || dismissed) return null
    val seconds = trialSecondsLeft ?: return null
    return DeviceTrial.daysRemaining(seconds).takeIf { it > 0 }
}

/**
 * The viewer's word for a panel section's title. The operator's hero slot is
 * titled "Hero" in the panel — a slot name, not a shelf a viewer understands
 * (the Nov 2026 home review flagged the rail under the carousel reading
 * "Hero") — so that rail reads "Featured". "Most copies" reads the same way
 * (Gemini home review, Nov 2026: an internal mirror-count metric that makes
 * the app sound like a torrent index), so that rail reads "Most available":
 * the order underneath is still the server's copy-count sort — the ledger
 * has no play counts — so "Top 10 Today" (the old mapping) promised a
 * measured daily chart that does not exist; "Most available" says what the
 * sort actually is. Making the ranking real (by plays) is a server
 * follow-up. Every other title passes through untouched: the operator owns
 * the names. Pure, so the mapping is assertable without a repository.
 */
internal fun viewerTitleForPanelSection(title: String): String =
    when (title) {
        "Hero" -> "Featured"
        "Most copies" -> "Most available"
        else -> title
    }

/**
 * The operator's feed, merged over the built-in rails. Pure, so the test can pin
 * the questions that matter without a repository: panel sections first, in the
 * panel's order, then the built-in rails that no panel section owns by key —
 * re-sorted by the panel's `home_section_order` when it published one
 * ([applyPanelRailOrder]); every row the operator hid is dropped from both,
 * because the server hides it too but a phone must never trust one layer
 * alone; a dismissed broadcast never returns until the next app start. A
 * panel section with no rows renders nothing — an empty operator rail is not
 * a loading state.
 */
internal fun mergePanelFeed(
    current: List<HomeSection>,
    config: VipRemoteConfig,
    dismissedBroadcastIds: Set<Int>,
    regionId: String = UserRegion.DEFAULT,
    isVip: Boolean? = null,
    appVersion: String = "",
): Pair<List<HomeSection>, List<PanelBroadcast>> {
    val hidden = config.hiddenWorkIds.toSet()
    val panelSections = config.sections.map { panel ->
        HomeSection(
            key = "panel:${panel.key}",
            title = viewerTitleForPanelSection(panel.title),
            type = null,
            items = panel.items.filter { it.id !in hidden },
            panelManaged = true,
        )
    }.filter { it.items.isNotEmpty() }
    val panelKeys = config.sections.map { it.key }.toSet()
    val builtIn = current
        .filter { !it.panelManaged && it.key !in panelKeys }
        // The built-ins carry their raw order names ("Most copies"); the
        // viewer reads the mapped word, same as a panel section.
        .map {
            it.copy(
                title = viewerTitleForPanelSection(it.title),
                items = it.items.filter { post -> post.id !in hidden },
            )
        }
    val broadcasts = config.broadcasts.filter {
        it.id !in dismissedBroadcastIds && it.title.isNotBlank() &&
            BroadcastTargeting.visible(it, regionId, isVip, appVersion)
    }
    // Home fix (Nov 2026): the panel's rail order, applied to the built-in
    // rails — the panel sections above already ride in the panel's own
    // order. An empty order keeps the app's order.
    return (panelSections + applyPanelRailOrder(builtIn, config.homeSectionOrder)) to broadcasts
}

/**
 * Home fix (Nov 2026): the panel's rail order, as a pure function so the
 * rule is pinned in a test without a repository. The keys the operator
 * listed move to the front in exactly that order; keys the list does not
 * name keep their relative order behind them; a key the list names that
 * no rail carries is ignored. The order can never drop a rail — a
 * half-written list only re-sorts the rails it names.
 */
internal fun applyPanelRailOrder(
    builtIn: List<HomeSection>,
    order: List<String>,
): List<HomeSection> {
    if (order.isEmpty()) return builtIn
    val byKey = builtIn.associateBy { it.key }
    val ordered = order.mapNotNull { byKey[it] }
    val orderedKeys = ordered.map { it.key }.toSet()
    return ordered + builtIn.filter { it.key !in orderedKeys }
}

/** The built-in rail the hero falls back to when the panel's hero is stale. */
internal const val RECENT_SECTION_KEY = "recent"

/**
 * Pick 3 (home review, Nov 2026): how many slides the hero shows — the
 * same count as HERO_LIMIT in HomeScreen. The hero exclusion in
 * [HomeUiState.pickThreeCandidates] mirrors it, so the picks never repeat
 * a hero slide. The operator can hide the hero (heroMax 0), which this
 * count cannot see; over-excluding then only shifts the picks down a rail,
 * never onto a hero title.
 */
internal const val HERO_SLIDE_COUNT = 6

/**
 * Hero (Nov 2026): which rail the banner rides. The panel's sections come
 * first by [mergePanelFeed], so the banner's default is the operator's hero
 * list — but that list is curation, and curation goes stale: the panel once
 * published McLintock! (1963), Tarzan's Revenge, The Stranger (1946) as the
 * six-slide hero while the newest feed walked 2027s. A stale hero shows old
 * films on the first screen, which is exactly the bug the newest sort was
 * built to fix — so a stale panel hero loses the slot to "Recently added".
 *
 * Stale means [isStaleHero]: no title from this year or last. Fresh panel
 * curation is used as-is — the operator may feature a title on purpose, and
 * this never second-guesses a fresh list. A built-in first rail is newest
 * by construction, so it is never stale-checked.
 *
 * Returns null only while the fallback is still in flight: a stale panel
 * hero plus a loading Recent rail means hold the skeleton, never flash the
 * old hero for one frame and then swap it. A Recent rail that failed or came
 * back empty is different — then the stale hero is still better than no
 * hero at all, and it shows.
 */
internal fun selectHeroSection(sections: List<HomeSection>, currentYear: Int): HomeSection? {
    val first = sections.firstOrNull { it.visible } ?: return null
    if (!first.panelManaged) return first
    if (!isStaleHero(first.items, currentYear)) return first
    val recent = sections.firstOrNull {
        it.key == RECENT_SECTION_KEY || it.key == "panel:$RECENT_SECTION_KEY"
    }
    return when {
        recent?.visible == true -> recent
        recent?.loading == true -> null
        else -> first
    }
}

/**
 * True when a hero list carries no recent title. Plausibility reuses
 * [displayYearLabel]: a year outside 1900..currentYear+1 is not a release
 * year at all (a site mis-parse, never a film), so it cannot make the list
 * fresh — an all-null-year list is stale by the same rule. Fresh means at
 * least one title from this year or last: a 2027 title in 2026 is fresh
 * (an announced release belongs in the hero), a 2025 title in 2026 is
 * fresh, a 1963 list is not.
 */
internal fun isStaleHero(items: List<Post>, currentYear: Int): Boolean {
    val newest = items
        .mapNotNull { displayYearLabel(it.year, currentYear)?.toIntOrNull() }
        .maxOrNull() ?: return true
    return newest < currentYear - 1
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val syncedProgressDao: SyncedProgressDao,
    private val favoritesRepository: FavoritesRepository,
    private val followedSeriesRepository: FollowedSeriesRepository,
    private val titleFeedbackRepository: TitleFeedbackRepository,
    private val adCadence: AdCadenceGovernor,
    private val adFreeReader: AdFreeReader,
    private val settingsRepository: ServerSettingsRepository,
    private val syncAccountRepository: SyncAccountRepository,
    private val connectivityProbe: ConnectivityProbe,
    /** REA #5: the "Downloads For You" ledger — what Smart Downloads fetched. */
    private val downloadsForYouDao: DownloadsForYouDao,
    /** REA #5: the keeps list, so the row shows only episodes still on the device. */
    private val cacheRepository: CacheRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    /** A URL the viewer already declined stays declined for this app session. */
    private var dismissedUpdateUrl: String? = null

    /** A broadcast the viewer dismissed stays dismissed for this app session. */
    private val dismissedBroadcastIds = mutableSetOf<Int>()

    init {
        observeContinueWatching()
        observeFavorites()
        observeDownloadsForYou()
        observeTrialNote()
        observePickThreeDone()
        observePickThreeShown()
        observeOnboardingSeen()
        observeKidsMode()
        _uiState.update { it.copy(sections = initialHomeSections() + regionalHomeSections()) }
        // Feel-instant S8: warm the connection at Home first paint, once
        // per process — the first search or detail tap then never pays TLS
        // + cold connection. The repository's own guard makes this a no-op
        // after the first call, and it never blocks the paint: the call
        // runs on a background scope and swallows its own failures.
        // Detail keeps its own call as the deep-link backstop.
        viewModelScope.launch { catalogRepository.warmConnectionIfNeeded() }
        // Gap A5: cache-first — the saved Home renders before the network
        // answers, and the refresh runs behind it. The restore is awaited
        // first so a fresh load can never be overwritten by stale rows.
        viewModelScope.launch {
            restoreCachedHome()
            loadAll(force = true)
        }
        loadFacets()
        checkForUpdate()
        askAppOpenAd()
    }

    /** The seed post id the "Because you watch" rail is loaded for, or null when no rail is loaded. */
    private var relatedSeedId: Int? = null

    /**
     * E2 "For you": the favourites the row's anchor can be drawn from.
     * The state only keeps the ids ([HomeUiState.favoriteIds]), so the
     * full rows live here — updated by [observeFavorites], read by
     * [refreshForYou]. Never leaves this ViewModel.
     */
    private var latestFavorites: List<FavoritePost> = emptyList()

    /**
     * The app-open surface, asked once per Home entry and capped at one impression per four hours
     * of wall clock.
     *
     * Nothing is awaited and nothing is rendered from the answer, and that is the whole design: an
     * app-open ad that the first frame had to wait for *is* the 10-second timer redirect the owner
     * ruled out, in a different costume. The governor records the impression when a network can fill
     * the slot and answers `None` when it cannot, and a `None` costs this screen exactly the work of
     * one suspended call — no placeholder, no spinner, no held catalog.
     */
    private fun askAppOpenAd() {
        viewModelScope.launch {
            val adFree = adFreeReader.isAdFree()
            adCadence.offer(AdSurface.APP_OPEN, adFree = adFree)
        }
    }

    /**
     * Continue watching, from rows this device wrote while playing and, when a sync pass
     * pulled a newer position for the same scope, the server's timestamp. The merge is pure
     * ([WatchHistoryMerge]) and drops any handle this device cannot name — a card must never
     * be a guess at what a server row was.
     *
     * A row leaves here as a [ResumeTile], not a bare [Post]: where viewing stopped is the
     * only reason this rail exists, and a tile that hides it is indistinguishable from any
     * other poster on the page.
     */
    private fun observeContinueWatching() {
        viewModelScope.launch {
            combine(
                watchHistoryDao.observeRecent(WatchHistoryMerge.DEFAULT_LIMIT * 2),
                syncedProgressDao.observeRecent(WatchHistoryMerge.DEFAULT_LIMIT * 2),
                favoritesRepository.observeRefusedIds(),
            ) { local, synced, refused ->
                // A resume row is a cached surface like any other: it names a work this device
                // watched, and the ledger may have been refused an answer about it since.
                // `flagOf` is a constant null because `watch_history` keeps no adult column — the
                // table is written by playback, and no payload ever told us a flag. Null is "not
                // told", which [AdultPolicy] reads as no evidence either way, so the refusal is
                // what removes a card here and a 200 later puts it back.
                //
                // A4-F2: the second half of the pair is whether this device has
                // ANY history rows — finished titles included. The merge above
                // drops finished rows from the rail, so `continueWatching` is
                // empty both for a viewer who finished everything and for a
                // viewer who never pressed play; only the raw local list tells
                // the two apart, and only the first one earns the "No active
                // shows" line (see HomeUiState.hasWatchHistory).
                AdultPolicy.listableRows(
                    rows = WatchHistoryMerge.merge(local, synced),
                    flagOf = { null },
                    refusedOf = { it.postId in refused },
                ) to local.isNotEmpty()
            }
                .collect { (rows, hasHistory) ->
                    val items = rows.mapNotNull { ResumeTile.of(it) }
                    _uiState.update { it.copy(continueWatching = items, hasWatchHistory = hasHistory) }
                    syncBecauseYouWatch(items.firstOrNull()?.post?.id)
                    refreshForYou()
                }
        }
    }

    /**
     * GET /api/v1/facets — the browse jump-off chips. One call per Home
     * load; a failed call means no chips, never a failed Home, and the
     * last good facets stay on screen rather than being wiped by a
     * failed refresh.
     */
    private fun loadFacets() {
        viewModelScope.launch {
            val facets = try {
                catalogRepository.getFacets()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (facets != null) {
                _uiState.update { it.copy(facets = facets) }
            }
        }
    }

    /**
     * The "Because you watch" rail, seeded from the first Continue Watching
     * post. The rail is inserted after "Recently added" and loaded once;
     * a later refresh reloads it like any other rail through [loadSection].
     * A new seed replaces the rail and reloads it; no seed removes it.
     * A seed that is already loaded is a no-op, so a re-emitted watch
     * history does not reload the rail for nothing.
     */
    private fun syncBecauseYouWatch(seedPostId: Int?) {
        if (seedPostId == relatedSeedId) return
        relatedSeedId = seedPostId
        val current = _uiState.value.sections
        if (seedPostId == null) {
            if (current.any { it.key == BECAUSE_YOU_WATCH_KEY }) {
                _uiState.update { state ->
                    state.copy(sections = state.sections.filterNot { it.key == BECAUSE_YOU_WATCH_KEY })
                }
            }
            return
        }
        val existing = current.firstOrNull { it.key == BECAUSE_YOU_WATCH_KEY }
        val section = (existing ?: HomeSection(
            key = BECAUSE_YOU_WATCH_KEY,
            title = "Because you watch",
            type = null,
            loading = true,
        )).copy(relatedWorkId = seedPostId, loading = true, error = null)
        val sections = if (existing != null) {
            current.map { if (it.key == BECAUSE_YOU_WATCH_KEY) section else it }
        } else {
            val index = current.indexOfFirst { it.key == "recent" }
                .takeIf { it >= 0 }?.plus(1) ?: 1
            current.toMutableList().apply { add(index.coerceIn(0, size), section) }
        }
        _uiState.update { it.copy(sections = sections) }
        viewModelScope.launch { loadSection(section) }
    }

    /**
     * E2 "For you": re-pick the personalised row from this device's own
     * history. The pool is every rail's items; the exclusions are
     * everything the viewer already has (watched, saved, followed) plus
     * the hero and every rail above the row's slot, so the row never
     * repeats a title and render-time dedupe never eats it. The followed
     * list is a one-shot Room read (there is no Flow for it) — a failure
     * reads as "nothing followed", never a failed Home. A recompute that
     * lands on the row already on screen is a no-op: the observers that
     * call this fire on every position write while a title plays, and a
     * no-op update must not recompose the page for nothing. There is no
     * loop here: nothing that reads `sections` calls this back.
     */
    private fun refreshForYou() {
        viewModelScope.launch {
            val followed = runCatching { followedSeriesRepository.getAllFollowed() }
                .getOrDefault(emptyList())
            // REA #2: the viewer's thumbs, read once per refresh like the
            // followed list — a failure reads as "no votes", never a
            // failed Home. The repository already guards its own read.
            val feedback = titleFeedbackRepository.allFeedback()
            val state = _uiState.value
            val sections = state.sections
            val index = forYouInsertIndex(sections)
            val excluded = buildList {
                sections.take(index).forEach { section ->
                    section.items.forEach { post ->
                        add(ForYouWork(post.workRef, post.displayTitle, post.year, post.id))
                    }
                }
                selectHeroSection(sections, currentCalendarYear())?.items.orEmpty()
                    .forEach { post ->
                        add(ForYouWork(post.workRef, post.displayTitle, post.year, post.id))
                    }
                state.continueWatching.forEach { tile ->
                    add(ForYouWork(tile.workRef, tile.post.displayTitle, tile.post.year, tile.post.id))
                }
                latestFavorites.forEach { favorite ->
                    add(
                        ForYouWork(
                            favorite.workRef,
                            favorite.cleanTitle?.takeIf { it.isNotBlank() } ?: favorite.title,
                            favorite.year?.toIntOrNull(),
                            favorite.id,
                        ),
                    )
                }
                followed.forEach { series ->
                    add(
                        ForYouWork(
                            series.workRef,
                            series.cleanTitle?.takeIf { it.isNotBlank() } ?: series.title,
                            null,
                        ),
                    )
                }
            }
            val fresh = selectForYou(
                watched = state.continueWatching,
                favorites = latestFavorites,
                followed = followed,
                pool = sections.flatMap { it.items },
                excluded = excluded,
                kidsMode = state.kidsMode,
                feedback = feedback,
            )
            // REA #2: publish the row's percentages for Detail — and
            // clear them when the row drops, so a stale % never outlives
            // the row that minted it. REA #4: publish the row itself for
            // the shuffle — the player give-up card draws from the same
            // pool, so it clears with the row too.
            MatchScoreStore.set(fresh?.matchScores.orEmpty())
            ForYouPoolStore.set(fresh)
            _uiState.update { current ->
                val existing = current.sections.firstOrNull { it.key == FOR_YOU_KEY }
                if (existing == fresh) {
                    current
                } else {
                    current.copy(sections = insertForYouSection(current.sections, fresh))
                }
            }
            // REA #5: the Home load is the moment the rails exist to
            // resolve the row's tiles from — rebuild it here as well as
            // on ledger changes, so a fetch that landed while Home was
            // open appears without a reload.
            rebuildDownloadsForYouRow()
        }
    }

    /**
     * REA #5: the "Downloads For You" row — rebuilt whenever the ledger
     * changes and whenever the "For you" row is recomputed (the Home
     * load). The keeps list is read fresh on every rebuild, so an
     * episode the player auto-deleted never lingers in the row past the
     * next rebuild.
     */
    private fun observeDownloadsForYou() {
        viewModelScope.launch {
            downloadsForYouDao.observeAll().collect {
                rebuildDownloadsForYouRow()
            }
        }
    }

    private fun rebuildDownloadsForYouRow() {
        viewModelScope.launch {
            val entries = runCatching { downloadsForYouDao.listAll() }.getOrDefault(emptyList())
            val liveHandles = runCatching { cacheRepository.list() }
                .getOrDefault(emptyList())
                .mapNotNull { it.resourceId }
                .toSet()
            val pool = _uiState.value.sections.flatMap { it.items }
            val row = buildDownloadsForYouSection(entries, pool, liveHandles)
            _uiState.update { current ->
                current.copy(sections = insertDownloadsForYouSection(current.sections, row))
            }
        }
    }

    /**
     * Session 121 (P-26): the hero's My List toggle reads live — a favorite added
     * on the details page flips the hero's button without a reload, and vice versa.
     */
    private fun observeFavorites() {
        viewModelScope.launch {
            favoritesRepository.getAllFavorites().collect { favorites ->
                latestFavorites = favorites
                _uiState.update {
                    it.copy(
                        favoriteIds = favorites.map { fav -> fav.id }.toSet(),
                        favoriteWorkRefs = favorites.mapNotNull { fav -> fav.workRef }.toSet(),
                        // H11: the My List strip reads the same saved rows
                        // the My List screen shows, through the same
                        // title-suffix stripping.
                        savedPosts = favorites.map { myListDisplayPost(it) },
                    )
                }
                refreshForYou()
                // Gap A6: the third pick completes the card — the viewer has
                // a My List now, so the first-run card retires itself instead
                // of asking for a fourth pick.
                if (favorites.size >= 3 && !_uiState.value.pickThreeDone) {
                    setPickThreeDone()
                }
            }
        }
    }

    /**
     * Gap A6: the card's done flag, persisted so a finished card never
     * returns. Dismissal and the third pick both land here.
     */
    private fun observePickThreeDone() {
        viewModelScope.launch {
            settingsRepository.pickThreeDone.collect { done ->
                _uiState.update { it.copy(pickThreeDone = done) }
            }
        }
    }

    /**
     * First-run (video audit #11, Nov 2026): the onboarding window, from
     * DataStore — true once the viewer has left Home for the first time.
     * Same shape as [observePickThreeDone].
     */
    private fun observeOnboardingSeen() {
        viewModelScope.launch {
            settingsRepository.onboardingSeen.collect { seen ->
                _uiState.update { it.copy(onboardingSeen = seen) }
            }
        }
    }

    /**
     * MX #7 — Kids Lock: one flag from DataStore. Flipping it only flips
     * the flag — the raw lists stay whole underneath, so the screen
     * re-filters in place and turning the mode off restores the full Home
     * with no re-fetch.
     */
    private fun observeKidsMode() {
        viewModelScope.launch {
            settingsRepository.kidsModeActive.collect { active ->
                _uiState.update { it.copy(kidsMode = active) }
                refreshForYou()
            }
        }
    }

    fun dismissPickThree() {
        setPickThreeDone()
    }

    /**
     * Pick 3 (home review, Nov 2026; fixed 2026-11-15): the card is a
     * one-shot — but the shot is the whole Home visit, not one frame.
     * Called from the card's own LaunchedEffect, so the latch lands the
     * moment the card is on screen; the card stays up until the viewer
     * dismisses it, picks a third title, or leaves Home, and the persisted
     * flag keeps it away on later visits. Same shape as [setPickThreeDone].
     */
    fun markPickThreeShown() {
        _uiState.update { it.onPickThreeShown() }
        viewModelScope.launch {
            runCatching { settingsRepository.setPickThreeShown(true) }
        }
    }

    /**
     * Pick 3: the Home visit ended — the card scrolled out of the feed
     * or the viewer left Home. Clears the visit latch (session only);
     * the persisted shown flag already keeps the card away on later
     * visits. Never touches the persisted flag.
     */
    fun endPickThreeVisit() {
        _uiState.update { it.onPickThreeVisitEnded() }
    }

    /**
     * Pick 3: the shown flag, persisted so a displayed card never returns.
     * Same shape as [observePickThreeDone].
     */
    private fun observePickThreeShown() {
        viewModelScope.launch {
            settingsRepository.pickThreeShown.collect { shown ->
                _uiState.update { it.copy(pickThreeShown = shown) }
            }
        }
    }

    private fun setPickThreeDone() {
        _uiState.update { it.copy(pickThreeDone = true) }
        viewModelScope.launch {
            runCatching { settingsRepository.setPickThreeDone(true) }
        }
    }

    /**
     * Gap A5: show the last good Home instantly. A corrupt or empty cache
     * is a no-op — the network load below proceeds exactly as before.
     */
    private suspend fun restoreCachedHome() {
        val json = runCatching { settingsRepository.homeCacheJson.first() }.getOrNull() ?: return
        val payload = decodeHomeCache(json) ?: return
        val restored = restoreHomeSections(payload)
        val existingKeys = restored.mapTo(HashSet()) { it.key }
        val merged = restored + regionalHomeSections().filter { it.key !in existingKeys }
        _uiState.update {
            it.copy(sections = merged, lastHomeSyncMs = payload.updatedAtMs)
        }
    }

    /** Gap A5: keep the cache behind the screen — only a load that actually returned rows. */
    private suspend fun saveHomeCacheIfWorthwhile() {
        val sections = _uiState.value.sections
        if (sections.none { it.items.isNotEmpty() }) return
        val nowMs = System.currentTimeMillis()
        runCatching { settingsRepository.saveHomeCacheJson(encodeHomeCache(sections, nowMs)) }
        _uiState.update { it.copy(lastHomeSyncMs = nowMs) }
    }

    /**
     * Session 121 (P-26): the hero's My List toggle — the same add/remove the details page uses.
     *
     * A v2 row has no v1 post id (its `id` is the negative work-id sentinel),
     * so Room cannot key it: the toggle first resolves the work to its v1 post
     * through the `work_ref` bridge and toggles THAT row. An unresolvable work
     * is a no-op here — the detail screen runs the same resolution and is the
     * place that can say why.
     */
    fun toggleFavorite(post: Post) {
        viewModelScope.launch {
            val target = resolveV1Post(post) ?: return@launch
            if (favoritesRepository.isFavorite(target.id).first()) {
                favoritesRepository.removeFavorite(target.id)
            } else {
                favoritesRepository.addFavorite(target)
            }
        }
    }

    /** The v1 post for a row, or the row itself when it already is one. Null when unresolvable. */
    private suspend fun resolveV1Post(post: Post): Post? {
        if (post.workId == null) return post
        val ref = post.workRef?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            catalogRepository.getPosts(workRef = ref, page = 1, limit = 1).results.firstOrNull()
        }.getOrNull()
    }

    /**
     * Phase 7 (D1): remove one title from Continue Watching. The server row goes
     * first (the `removeProgress` endpoint the app defined but never called),
     * then the local rows — the tile disappears either way, because a row the
     * server kept while the device forgot it would come back on the next sync.
     * The tile is kept in memory for Undo: the position it held is the one
     * thing a re-watch cannot give back.
     */
    private var lastRemovedTile: ResumeTile? = null

    fun removeFromContinueWatching(tile: ResumeTile) {
        lastRemovedTile = tile
        _uiState.update { it.copy(removedResumeTitle = tile.post.title) }
        viewModelScope.launch {
            runCatching {
                syncAccountRepository.removeProgress(
                    workRef = tile.workRef,
                    season = tile.season,
                    episode = tile.episode,
                )
            }
            runCatching { watchHistoryDao.remove(tile.workRef, tile.season, tile.episode) }
            runCatching { syncedProgressDao.remove(tile.workRef, tile.season, tile.episode) }
        }
    }

    /** Put the removed tile back: the local row first, then a forced progress report. */
    fun undoRemoveFromContinueWatching() {
        val tile = lastRemovedTile ?: return
        lastRemovedTile = null
        _uiState.update { it.copy(removedResumeTitle = null) }
        viewModelScope.launch {
            runCatching {
                watchHistoryDao.insertIfAbsent(
                    WatchHistoryEntity(
                        workRef = tile.workRef,
                        postId = tile.post.id,
                        title = tile.post.title,
                        posterUrl = tile.post.posterUrl,
                        seasonNumber = tile.season,
                        episodeNumber = tile.episode,
                        positionMs = tile.positionMs,
                        durationMs = tile.durationMs,
                        updatedAt = System.currentTimeMillis(),
                        imdbRating = tile.post.imdbRating,
                    ),
                )
            }
            runCatching {
                syncAccountRepository.reportProgress(
                    workRef = tile.workRef,
                    season = tile.season,
                    episode = tile.episode,
                    positionMs = tile.positionMs,
                    durationMs = tile.durationMs,
                    forced = true,
                )
            }
        }
    }

    fun clearRemovedResumeNotice() {
        lastRemovedTile = null
        _uiState.update { it.copy(removedResumeTitle = null) }
    }

    fun refresh() {
        loadAll(force = true)
        loadFacets()
        checkForUpdate()
    }

    /**
     * REA #4 (Netflix, Nov 2026): one tap, one title — a weighted random
     * pick from the "For you" pool ([ForYouPoolStore]), so the viewer who
     * cannot choose still gets instant play. Null when the ranker has no
     * row (yet), so the button that calls this is never offered then.
     * The last pick lives in the store, so a shuffle here and a shuffle
     * on the player give-up card never repeat each other.
     */
    fun playSomething(): Post? = ForYouPoolStore.pick()

    fun dismissUpdate() {
        dismissedUpdateUrl = _uiState.value.updateNotice?.url
        _uiState.update { it.copy(updateNotice = null) }
    }

    fun dismissBroadcast(id: Int) {
        dismissedBroadcastIds.add(id)
        _uiState.update { it.copy(broadcasts = it.broadcasts.filter { broadcast -> broadcast.id != id }) }
    }

    /**
     * The trial note, read the way Settings reads the same countdown: collected,
     * not sampled, so a grant that landed while Home was closed shows up on the
     * next open. Mirrors SettingsViewModel.observeTrial — the seconds the server
     * said were left, minus the time since this device heard the answer. The
     * entitlement is only a veto for a *known* VIP: a null entitlement (no key,
     * or not loaded yet) does not hide the note, because the trial is granted
     * to the install itself and most trial viewers hold no key at all.
     */
    private fun observeTrialNote() {
        viewModelScope.launch {
            combine(
                settingsRepository.trialRecordedAtMs,
                settingsRepository.trialNoteDismissed,
                syncAccountRepository.entitlement,
            ) { recordedAtMs, dismissed, entitlement ->
                val seconds = if (recordedAtMs <= 0L) {
                    null
                } else {
                    DeviceTrial.secondsRemaining(
                        storedSecondsRemaining = settingsRepository.trialSecondsRemaining.first(),
                        storedAtMs = recordedAtMs,
                        nowMs = System.currentTimeMillis(),
                    )
                }
                trialNoteDaysRemaining(seconds, isVip = entitlement?.vip == true, dismissed = dismissed)
            }.collect { days ->
                _uiState.update { it.copy(trialDaysRemaining = days) }
            }
        }
    }

    /** The trial note is dismissed once, for good — the dismissal is persisted. */
    fun dismissTrialNote() {
        _uiState.update { it.copy(trialDaysRemaining = null) }
        viewModelScope.launch {
            settingsRepository.setTrialNoteDismissed(true)
        }
    }

    /**
     * Remote config is decoration on Home: an unreachable endpoint or a malformed body
     * must never turn into an error banner over a catalog that loaded fine. One fetch
     * serves three things — the update notice, the panel's sections and broadcasts,
     * and the hide list — so a phone that got the body has all of them or none.
     */
    private fun checkForUpdate() {
        viewModelScope.launch {
            val config = try {
                catalogRepository.getRemoteConfig()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            }
            // A degraded body is a shape guarantee, not the operator's
            // decision: the settings row could not be read, so its sections,
            // broadcasts, hides and update link are shipped defaults. Merging
            // any of it would wipe the operator's real rails with defaults —
            // the fail-open the server's own docstring warns about. Keep the
            // last good state instead, exactly as the watcher does.
            if (config.settingsUnavailable) return@launch
            val notice = AppUpdate.from(config, Brand.versionCode)
                ?.takeUnless { it.url == dismissedUpdateUrl }
            // The audience for a targeted broadcast is decided here, from
            // what this phone knows: the viewer's region card, and the tier
            // the account store has actually loaded. A tier that has not
            // loaded yet is unknown, not free — the filter hides both tiered
            // broadcasts until it knows, rather than guessing.
            val regionId = try {
                settingsRepository.userRegion.first()
            } catch (_: Exception) {
                UserRegion.DEFAULT
            }
            val isVip = syncAccountRepository.entitlement.value?.vip
            _uiState.update { state ->
                val (sections, broadcasts) = mergePanelFeed(
                    state.sections, config, dismissedBroadcastIds,
                    regionId = regionId, isVip = isVip,
                    appVersion = Brand.versionName,
                )
                state.copy(sections = sections, broadcasts = broadcasts, updateNotice = notice)
            }
            // A panel change re-deals the candidate pool (and the rails
            // above "For you"), so the row is re-picked, not left stale.
            refreshForYou()
        }
    }

    fun retrySection(key: String) {
        val section = _uiState.value.sections.firstOrNull { it.key == key } ?: return
        // A panel rail has nothing to retry: its rows come with the config, and the
        // next refresh re-fetches them. Retrying through the posts route would
        // overwrite the operator's rail with a generic list. A "For you" rail
        // has nothing to retry either: its rows are computed, not loaded, and
        // the next history change re-picks them.
        if (section.panelManaged || section.forYou) return
        // loadSection is suspend (it hits the repository); run it on the ViewModel's scope.
        viewModelScope.launch { loadSection(section) }
    }

    private fun loadAll(force: Boolean) {
        viewModelScope.launch {
            // Read once per load, not per rail: the banner that reads this
            // says "offline" about the load that just happened, and four
            // rails asking the same question would answer four times.
            val offline = connectivityProbe.current() == NetworkClass.NONE
            _uiState.update { it.copy(isRefreshing = true, fatal = null, isOffline = offline) }
            // The rows before this load, so the cache is only re-stamped when
            // the network actually returned new rows: re-saving the restored
            // rows with a fresh timestamp after a failed refresh would claim
            // a freshness the load never earned.
            val beforeItems = _uiState.value.sections.associate { it.key to it.items }
            try {
                coroutineScope {
                    _uiState.value.sections
                        .filter { !it.panelManaged && !it.forYou && (it.items.isEmpty() || force) }
                        .map { async { loadSection(it) } }
                        .awaitAll()
                }
                _uiState.update { it.copy(isRefreshing = false) }
                // The rails just re-dealt the candidate pool, so the
                // personalised row is re-picked from the fresh rows.
                refreshForYou()
                val changed = _uiState.value.sections.any { it.items != beforeItems[it.key] }
                if (changed) saveHomeCacheIfWorthwhile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        fatal = friendlyNetworkError(error, "The catalog didn't answer. Try again in a moment."),
                    )
                }
            }
        }
    }

    private suspend fun loadSection(section: HomeSection) {
        if (section.panelManaged) return
        // "For you" is computed from history, never loaded: a posts call
        // here would overwrite the picks with a generic freshest-first
        // list. The guard is the belt to the braces in [loadAll], which
        // never hands this section over in the first place.
        if (section.forYou) return
        markSection(section.key) { it.copy(loading = true, error = null) }
        try {
            val results = when {
                section.relatedWorkId != null -> {
                    // "Because you watch": the dedicated route, seeded from
                    // the first Continue Watching post. An empty page hides
                    // the rail; the v2 list cannot serve a seed, so there
                    // is no v2 path here.
                    catalogRepository.getRelated(
                        workId = section.relatedWorkId, page = 1, limit = 18,
                    ).results
                }
                else -> {
                    // Phase 7 (M1): the v2 list first — one row per work, keyset-safe —
                    // with the v1 list as the fallback. An empty v2 page falls back too:
                    // a section type the v2 side cannot serve must not read as "no titles".
                    // A rail that asks for a server sort (only "Most copies" today) keeps
                    // it on both paths; the others ask for freshest-first ("newest").
                    val page = catalogRepository.browseWorksV2(
                        type = section.type,
                        sort = section.sort ?: "newest",
                        limit = 18,
                        industries = industryWireValues(section.industry),
                    )
                    if (page != null && page.posts.isNotEmpty()) {
                        page.posts
                    } else if (section.sort == "trending") {
                        // "Most copies": the dedicated /api/v1/trending route
                        // first, the list it replaces behind it — an old
                        // server answers the old route, so the rail never
                        // loses rows to a route it does not have yet.
                        try {
                            catalogRepository.getTrending(type = section.type, page = 1, limit = 18).results
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            catalogRepository.getPosts(
                                type = section.type,
                                industry = section.industry,
                                sort = section.sort,
                                page = 1,
                                limit = 18,
                            ).results
                        }
                    } else {
                        catalogRepository.getPosts(
                            type = section.type,
                            industry = section.industry,
                            sort = section.sort,
                            page = 1,
                            limit = 18,
                        ).results
                    }
                }
            }
            markSection(section.key) { it.copy(items = results, loading = false, error = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            markSection(section.key) {
                it.copy(loading = false, error = friendlyNetworkError(error, "Could not load this row."))
            }
        }
    }

    private fun markSection(key: String, transform: (HomeSection) -> HomeSection) {
        _uiState.update { state ->
            state.copy(sections = state.sections.map { if (it.key == key) transform(it) else it })
        }
    }
}

/** REA #5 (Netflix, Nov 2026): the "Downloads For You" home row. */
internal const val DOWNLOADS_FOR_YOU_KEY = "downloads_for_you"

/** REA #5: the row's title, as painted. */
internal const val DOWNLOADS_FOR_YOU_TITLE = "Downloads For You"

/**
 * REA #5: the "Downloads For You" row, pure so the rule is pinned in a
 * test without a repository: one tile per fetched series, newest fetch
 * first, only while its episode is still on the device. A fetched series
 * no rail still carries has no poster to paint, so it is left out rather
 * than drawn poster-less; a row whose handle is gone (watched and
 * auto-deleted, or deleted by hand) is invisible until the runner's
 * sweep removes its ledger row. Null when nothing is both fetched and
 * still kept — the row never appears empty.
 */
internal fun buildDownloadsForYouSection(
    entries: List<DownloadsForYouEntry>,
    pool: List<Post>,
    liveHandles: Set<String>,
    maxItems: Int = 10,
): HomeSection? {
    val posts = entries
        .filter { it.handle in liveHandles }
        .sortedByDescending { it.fetchedAt }
        .mapNotNull { entry -> pool.firstOrNull { it.id == entry.postId } }
        .distinctBy { it.id }
        .take(maxItems)
    if (posts.isEmpty()) return null
    return HomeSection(
        key = DOWNLOADS_FOR_YOU_KEY,
        title = DOWNLOADS_FOR_YOU_TITLE,
        type = null,
        items = posts,
        subtitle = "Downloaded for you — ready offline",
    )
}

/**
 * REA #5: where the row goes — right after the "For you" row when that
 * rail exists (the two personal rows sit together), else second. A
 * section already at the key is replaced in place, so the row never
 * appears twice.
 */
internal fun insertDownloadsForYouSection(
    sections: List<HomeSection>,
    row: HomeSection?,
): List<HomeSection> {
    val without = sections.filterNot { it.key == DOWNLOADS_FOR_YOU_KEY }
    if (row == null) return without
    val afterForYou = without.indexOfFirst { it.key == FOR_YOU_KEY }
        .takeIf { it >= 0 }?.plus(1) ?: 1
    val index = afterForYou.coerceIn(0, without.size)
    return without.toMutableList().apply { add(index, row) }
}

