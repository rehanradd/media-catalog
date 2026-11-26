package com.raddstream.app.ui.navigation

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The one route-argument codec. A handle, a URI or a title is free TEXT that can contain `/`,
 * `?` and `%`, and a route segment splits on those characters — so every destination carrying
 * one encodes on the way in and decodes on the way out, identically. Three destinations needed
 * this and each had its own copy of the same four lines; one owner means a route can never be
 * built one way and parsed another. The decoder is `internal` (not private) so [DeepLinks]
 * parses with the same codec instead of a second copy.
 */
internal fun encodeRouteArg(raw: String): String =
    URLEncoder.encode(raw, StandardCharsets.UTF_8.name())

internal fun decodeRouteArg(encoded: String): String =
    try {
        URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        // A malformed escape is not a reason to lose the whole argument: show what the caller
        // actually typed rather than a percent-sign soup.
        encoded
    }

/** All navigation destinations as a sealed class. */
sealed class Screen(val route: String) {
    object Setup : Screen("setup")
    object Region : Screen("region")
    object Home : Screen("home")
    object Search : Screen("search?query={query}&genre={genre}&industry={industry}&language={language}&focus={focus}") {
        const val ARG_QUERY = "query"
        const val ARG_GENRE = "genre"
        const val ARG_INDUSTRY = "industry"
        const val ARG_LANGUAGE = "language"
        const val ARG_FOCUS = "focus"

        /** Value of [ARG_FOCUS] that focuses the search field on entry (P1-2). */
        const val FOCUS_ON_ENTRY = "1"

        /** The tab's plain address — the bottom bar navigates here, with no query and no focus. */
        const val PLAIN_ROUTE = "search"

        /**
         * A search opened with a query already filled in (an actor tap on a
         * detail card), or with a facet already applied (a genre/industry/
         * language chip on Home). A blank value is omitted, so the plain tab
         * and the old actor links keep working.
         *
         * P1-2: [focusOnEntry] is the Home header glyph's deep link — the
         * field is focused and the keyboard raised on entry. The tab tap
         * keeps the plain route, so it stays unfocused.
         */
        fun createRoute(query: String? = null, genre: String? = null, industry: String? = null, language: String? = null, focusOnEntry: Boolean = false): String {
            val args = listOfNotNull(
                query.takeIf { !it.isNullOrBlank() }?.let { "query=${encodeRouteArg(it)}" },
                genre.takeIf { !it.isNullOrBlank() }?.let { "genre=${encodeRouteArg(it)}" },
                industry.takeIf { !it.isNullOrBlank() }?.let { "industry=${encodeRouteArg(it)}" },
                language.takeIf { !it.isNullOrBlank() }?.let { "language=${encodeRouteArg(it)}" },
                if (focusOnEntry) "$ARG_FOCUS=$FOCUS_ON_ENTRY" else null,
            )
            return if (args.isEmpty()) PLAIN_ROUTE else "search?" + args.joinToString("&")
        }
    }
    object LiveTv : Screen("live_tv")
    object MyList : Screen("my_list")
    /**
     * Settings — same shape as [Search]: [route] is the NavGraph pattern
     * with the optional `scrollTo` argument, [PLAIN_ROUTE] is the bare
     * destination the bottom nav navigates to. The deep link
     * `?scrollTo=contact` (Help's Contact row, Settings review Nov 2026
     * D2) lands on the contact card instead of the top of the screen.
     */
    object Settings : Screen("settings?scrollTo={scrollTo}") {
        const val ARG_SCROLL_TO = "scrollTo"
        const val PLAIN_ROUTE = "settings"

        /** Build the route, optionally deep-linking straight to the contact card. */
        fun createRoute(scrollTo: String? = null): String =
            if (scrollTo == null) PLAIN_ROUTE else "$PLAIN_ROUTE?$ARG_SCROLL_TO=$scrollTo"

        /** [ARG_SCROLL_TO] value that lands on the contact card. */
        const val SCROLL_TO_CONTACT = "contact"
    }
    /**
     * The debug-only field-test harness (plan §8). It exists in every build
     * as a route, but no NavGraph entry and no Settings row exist unless
     * DEBUG — in release it is unreachable.
     */
    object ResolveTest : Screen("resolve_test")
    /**
     * The G-10 press/download test: the second screen of the debug-only
     * field-test harness. Same DEBUG-only reachability as [ResolveTest].
     */
    object PressTest : Screen("press_test")
    /**
     * The debug-only diagnostics screen (APP-13): live event timeline,
     * problems view, device + session info, export/share. Same DEBUG-only
     * reachability as [ResolveTest] — the route exists in every build, but
     * no NavGraph entry and no Settings row exist unless DEBUG.
     */
    object Diagnostics : Screen("diagnostics")
    object Help : Screen("help")
    object Languages : Screen("languages")
    object WatchHistory : Screen("watch_history")
    object Downloads : Screen("downloads")
    object VipUpgrade : Screen("vip_upgrade")
    /** Session 123: the account screens — the dashboard and the profile. (Coins and check-in were removed 2026-11-17; their routes are gone with them.) */
    object Dashboard : Screen("dashboard")
    object Profile : Screen("profile")
    /**
     * H10 (2027 Home fix, round 2): Browse takes a sort now, so the
     * trending rail's "See all" lands on the same order — "browse/all?
     * sort=trending" — instead of silently re-sorting to freshest-first.
     * The sort is a query, not a path segment: a sort is a filter on the
     * list, not a place. Null means "no sort" (the old behaviour), and
     * the default keeps every existing browse link working.
     */
    object Browse : Screen("browse/{type}?sort={sort}") {
        const val ARG_TYPE = "type"
        const val ARG_SORT = "sort"
        fun createRoute(type: String?, sort: String? = null): String =
            "browse/${type ?: "all"}?sort=${sort ?: ""}"
    }
    object Detail : Screen("detail/{postId}?autoplay={autoplay}&workId={workId}") {
        const val ARG_POST_ID = "postId"
        const val ARG_AUTOPLAY = "autoplay"
        /**
         * Phase 7 (M1): the v2 work id, when the row came from a v2 list. A v2 row
         * has no v1 post id — its `id` is the negative work-id sentinel — so the
         * detail route carries the work id in its own argument and `postId`
         * stays 0. A v1 row passes no workId and the route behaves as before.
         */
        const val ARG_WORK_ID = "workId"
        fun createRoute(postId: Int, autoplay: Boolean = false): String =
            "detail/$postId?autoplay=$autoplay&workId=-1"
        fun createRouteForWork(workId: Long, autoplay: Boolean = false): String =
            "detail/0?autoplay=$autoplay&workId=$workId"
    }
    /**
     * Detail redesign (Nov 2026): one season's episodes, as plain
     * numbered tiles on their own sub-page. The season number is a path
     * segment, not a query — a season is a place, not a filter. The
     * work id rides along exactly as on [Detail], for the same reason:
     * a v2 row has no v1 post id. The page shares Detail's ViewModel
     * (same back-stack entry), so it reads the same layout and progress
     * without refetching.
     */
    object DetailSeason : Screen("detail/{postId}/season/{season}?workId={workId}") {
        const val ARG_POST_ID = "postId"
        const val ARG_SEASON = "season"
        const val ARG_WORK_ID = "workId"
        fun createRoute(postId: Int, season: Int, workId: Long = -1L): String =
            "detail/$postId/season/$season?workId=$workId"
        fun createRouteForWork(workId: Long, season: Int): String =
            "detail/0/season/$season?workId=$workId"
    }
    /**
     * The player is addressed by the server's OPAQUE STREAM HANDLE, never by a URL: a route
     * argument lands in the back stack, in saved state and in a deep link, so an address in
     * here is an address that leaves the app. The handle resolves against our own
     * `/api/v1/stream?id=…` redirector at playback time.
     */
    object Player : Screen("player/{streamId}/{title}") {
        const val ARG_STREAM_ID = "streamId"
        const val ARG_TITLE = "title"

        fun createRoute(streamId: String, title: String): String =
            "player/${encodeRouteArg(streamId)}/${encodeRouteArg(title)}"

        fun decodeStreamId(encoded: String): String = decodeRouteArg(encoded)

        fun decodeTitle(encoded: String): String = decodeRouteArg(encoded)
    }

    /**
     * The provider's own page, for a link the server says is a page and not a file
     * (`ProviderPageLinks`). Addressed by the opaque handle for exactly the reason [Player] is:
     * the screen loads OUR redirector for that handle and lets the server's 302 decide where it
     * lands, so no upstream address is ever composed here, saved in a back stack, or painted on
     * the screen. What opens is the hoster's player — visibly, with its own controls and its own
     * conditions — because this app has no other way to present that page, and takes no
     * shortcut around it.
     */
    object Embed : Screen("embed/{streamId}/{title}") {
        const val ARG_STREAM_ID = "streamId"
        const val ARG_TITLE = "title"

        fun createRoute(streamId: String, title: String): String =
            "embed/${encodeRouteArg(streamId)}/${encodeRouteArg(title)}"

        fun decodeStreamId(encoded: String): String = decodeRouteArg(encoded)

        fun decodeTitle(encoded: String): String = decodeRouteArg(encoded)
    }

    /**
     * A finished public download addressed by its own local content/file URI. It is not a
     * stream handle and must not be routed through the redirector: the bytes are already here.
     */
    object PlayerLocal : Screen("player_local/{uri}/{title}") {
        const val ARG_URI = "uri"
        const val ARG_TITLE = "title"

        fun createRoute(uri: String, title: String): String =
            "player_local/${encodeRouteArg(uri)}/${encodeRouteArg(title)}"

        fun decode(encoded: String): String = decodeRouteArg(encoded)
    }

    /** The phone's own videos (Movies/ and Download/), listed from MediaStore. */
    object LocalFiles : Screen("local_files")

    /**
     * NAV-4/NAV-5: the catch-all for a deep link that parses to nothing — a
     * `radd://` URI with no known shape. A link that leads nowhere says so,
     * with a way home, instead of silently opening Home as if nothing happened.
     */
    object DeadLink : Screen("dead_link")
}
