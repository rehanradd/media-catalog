package com.raddstream.app.network.models

import com.google.gson.annotations.SerializedName
import java.util.Calendar

/**
 * M3 (home review, Nov 2026): the year a tile or header prints. Measured
 * on the live catalog: year >= 2027 is 53 rows, year < 1900 is 17, one
 * 5-digit year (20215) — a site mis-parse, e.g. "The Gentlemen (Season
 * 1-2)" filed as 2027. An impossible year is hidden (null) instead of
 * printed, so a bad parse can never show "2027" for a 2024 work. Next
 * year's films are allowed (announced releases); anything earlier than
 * 1900 or later than next year is not a release year. Pure, so the bound
 * is assertable without a clock.
 */
internal fun displayYearLabel(year: Int?, currentYear: Int): String? {
    val y = year ?: return null
    if (y < 1900 || y > currentYear + 1) return null
    return y.toString()
}

/**
 * M11 (home review, Nov 2026): a title is a new release when its year is
 * plausible and within the last two calendar years. The upper bound is
 * stricter than [displayYearLabel]'s: display allows next year's films
 * (announced releases), but NEW means released — a 2027 film in 2026 has
 * not come out yet, so it never earns the badge. A mis-parsed 2027 or
 * 20215 can never earn it either.
 */
internal fun isNewReleaseYear(year: Int?, currentYear: Int): Boolean {
    val y = year ?: return false
    if (y < 1900 || y > currentYear) return false
    return y >= currentYear - 1
}

/** The current calendar year, the bound [displayYearLabel] measures against. */
internal fun currentCalendarYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

/*
 * ─────────────────────────────────────────────────────────────────────────────
 *  v1 WIRE MODELS — a field-by-field map of what the server ACTUALLY serialises.
 * ─────────────────────────────────────────────────────────────────────────────
 * Ground truth (verified 2026-10-22 against `Fast_Fetch/viewer.py` + `fast_fetch.py`
 * and against a live `Fast_Fetch/multimovies.db`):
 *
 *  GET /api/v1/posts            → [PostsResponse]            (the served list view)
 *  GET /api/v1/posts/{id}       → [PostDetailResponse]       (post view + `releases[]`,
 *                               each release with `sources[]` projected by the link view)
 *  GET /api/v1/stream           → [StreamResponse]           (our own redirector, `redirect=0`)
 *  GET /api/v1/stats            → [StatsResponse]            (scale only, no per-site rows)
 *  GET /api/v1/trending         → [PostsResponse]            (the "Most copies" order,
 *                               `sort=trending` on /api/v1/posts, as its own route)
 *  GET /api/v1/related          → [PostsResponse]            ("Because you watch", scored
 *                               on the seed post's genres, cast, industry, type and year)
 *  GET /api/v1/facets           → [FacetsResponse]           (browse jump-off chips, each
 *                               with the count its tap delivers)
 *
 * Those four are the ENTIRE served surface. There is no `/api/v1/sync`, no
 * `/api/v2` endpoint at all, and the public answers carry NO source-site, hoster or upstream
 * URL anywhere — the redaction stops at the HTTP edge, so a key that is not here cannot be
 * rendered by this app by accident. `/api/v1/rules` is operator-only (loopback) and 404s
 * publicly, so this client must never try to unwrap a bridge itself. Every v2 type in this
 * package is a target contract, not a live endpoint (see [V2Wire] and [EndpointGaps]).
 *
 * Rules these models obey:
 *  1. A field exists only if the server sends that key. Where v1 ships free TEXT
 *     (`season`, `episode`, `quality`) the structured value is a documented DERIVATION
 *     computed by [ReleaseLabelParsing] — never a phantom `@SerializedName`.
 *  2. JSON type == Kotlin type. Numeric-or-text columns use the lenient adapters in
 *     `RaddGson.kt` (see its KDoc for why `is_temporary` is 0/1 on one endpoint and a
 *     boolean on another).
 *  3. Unknown keys are ignored by Gson, so an additive server change can never crash
 *     decoding (API_V2.md §16.6).
 *  4. Adult policy (App/AGENTS.md, API_V2.md §13): `is_18_plus` is deliberately NOT
 *     modelled anywhere in this app. The server filters at the SQL level and answers
 *     404 for a flagged id; the client neither reads, stores, renders nor requests the
 *     flag. `is_animated` is unrestricted metadata and IS modelled.
 */

/** Exact response of GET /api/v1/posts. `page`/`limit`/`total`/`total_pages` are always JSON ints. */
data class PostsResponse(
    val page: Int = 1,
    val limit: Int = 20,
    val total: Int = 0,
    @SerializedName("total_pages")
    val totalPages: Int = 1,
    val results: List<Post> = emptyList(),
    /**
     * True when the server's exact search found nothing and this page is the
     * one-edit-away fallback instead. The screen says so out loud, so a
     * viewer who typed "Avngers" knows why "Avengers" is what came back.
     */
    val fuzzy: Boolean = false,
    /**
     * App-design audit #37: the server's "did you mean" spelling for the
     * query ("avtar" → "Avatar"), or null when the query needs no
     * correction. Added to the wire ahead of the backend (the backend
     * worker is adding the field server-side); a server that does not
     * send it simply shows no line.
     */
    val suggestion: String? = null,
)

/** One entry of GET /api/v1/facets — a jump-off chip with its honest count. */
data class FacetEntry(
    val key: String,
    val label: String,
    val count: Int = 0,
)

/**
 * Exact response of GET /api/v1/facets. Every group is present even when
 * empty, and a count is the number of public posts the matching filter
 * returns — a chip never promises a count its tap cannot keep.
 */
data class FacetsResponse(
    val genres: List<FacetEntry> = emptyList(),
    val languages: List<FacetEntry> = emptyList(),
    val industries: List<FacetEntry> = emptyList(),
    val types: List<FacetEntry> = emptyList(),
)

/** One catalog row of GET /api/v1/posts (the served `post_brief` view: identity + title fields + counts). */
data class Post(
    val id: Int,
    val title: String,
    @SerializedName("clean_title")
    val cleanTitle: String? = null,
    /**
     * The server's own display title (`display_title`) — the cleaned
     * title it prints on its own surfaces, with year/language/type
     * suffixes already stripped ("No Hard Feelings v2 – HDCAMRip" where
     * `clean_title` still reads "No Hard Feelings (2023) v2 – HDCAMRip
     * [HINDI HQ DUBBED] Full Movie"). Null on a server whose projection
     * predates the key — callers must fall back to [cleanTitle], never
     * assume it. Read it through [UserWords.detailTitle], never raw.
     */
    @SerializedName("display_title")
    val displayTitleWire: String? = null,
    /** `posts.year` — INTEGER affinity, so JSON number or null (a text year decodes to null). */
    val year: Int? = null,
    /**
     * `posts.content_type` — the values `classify_media()` ever writes are
     * `movie | series | complete_season | ongoing_season`. Animation is NOT one of them:
     * it is the separate [isAnimated] flag, which is also what the server's own `type=anime`
     * filter answers from (see [WorkType.fromContentType]).
     */
    @SerializedName("content_type")
    val contentType: String? = null,
    /**
     * `posts.is_animated` — an INTEGER 0/1 column, so it arrives as the JSON numbers `1`/`0`
     * on both list and detail rows, and [com.raddstream.app.network.LenientBooleanAdapter]
     * reads that alongside `true`/`false`. Metadata, not a restricted flag.
     *
     * Null means the server sent no key at all — an older projection, or a `full` payload
     * that omits it — and null must read as "we do not know", never as "not animated": the
     * tile keeps its plain `content_type` label and [WorkType.fromContentType] keeps the
     * series/movie it already had.
     */
    @SerializedName("is_animated")
    val isAnimated: Boolean? = null,
    /** `posts.seasons` — free TEXT ("2", "S01–S03"), nullable. */
    val seasons: String? = null,
    @SerializedName("poster_url")
    val posterUrl: String? = null,
    /**
     * `posts.backdrop_urls` on the list row — the same wide TMDB/CDN artwork the detail
     * route sends, as TEXT holding a JSON array of URLs (or one bare URL). Read it with
     * [backdropList], never as a `List<String>`: the wire type is a String. Null on a
     * server whose list projection predates the column, which is why every wide-artwork UI
     * must fall back to [posterUrl] rather than assume it.
     */
    @SerializedName("backdrop_urls")
    val backdropUrls: String? = null,
    /** `posts.imdb_rating` is TEXT in the ledger ("6.9", "8"), not a JSON number. */
    @SerializedName("imdb_rating")
    val imdbRating: String? = null,
    @SerializedName("imdb_id")
    val imdbId: String? = null,
    @SerializedName("tmdb_id")
    val tmdbId: Int? = null,
    /** TEXT; in practice either CSV ("Action, Drama") or a JSON-array string ("[\"A, B\"]"). */
    val genres: String? = null,
    val industry: String? = null,
    val country: String? = null,
    val language: String? = null,
    /**
     * Whether at least one print of this work claims subtitles, as the server's own
     * release-label grammar answered it (`viewer._subtitled_posts`). Subtitles are a
     * property of a print, never of the post row, so this is an existence test the ledger
     * makes on our behalf — not a per-release promise. `false` means the work's labels
     * never said so; a row from a server that predates the key decodes to null and simply
     * earns no badge rather than an assumed one.
     */
    val subtitled: Boolean? = null,
    @SerializedName("release_count")
    val releaseCount: Int? = null,
    @SerializedName("source_count")
    val sourceCount: Int? = null,
    /**
     * The server-minted handle for THIS work — the only name the sync account accepts.
     * The live v1 projection sends it on both the list and the detail row (measured
     * 2026-10-28), but a row may still carry none: a database that predates the column, or a
     * `full` payload that omitted it. A null therefore means "this title cannot be named to
     * the account surface", and nothing may invent a stand-in for it. It is modelled (rather
     * than read from a raw map) so [com.raddstream.app.sync] has one typed place to ask.
     */
    @SerializedName("work_ref")
    val workRef: String? = null,
    /**
     * The v2 work id (`cat_works.id`) for a row that came from a v2 list — null on
     * every v1 row. The two id spaces are different numbers for the same title, so
     * this is the ONLY field that may name a v2 work: [id] on a v2 row is the
     * negative sentinel `-workId` (never a v1 post id), and detail navigation
     * branches on this field first. A null here means "this row is a v1 post;
     * open it by [id]".
     */
    val workId: Long? = null,
) {
    /** Display helper so UI never has to know that `year` is numeric on the wire. */
    val yearLabel: String? get() = displayYearLabel(year, currentCalendarYear())
    /** The one type word a tile or banner paints — "Anime" for an animated series. */
    val typeLabel: String? get() = WorkTypeLabel.of(contentType, isAnimated)
    val genreList: List<String> get() = WireText.listOf(genres).filter { WireText.isKnownGenre(it) }
    val backdropList: List<String> get() = WireText.listOf(backdropUrls)
    /** The widest artwork this row carries: the first backdrop, else the poster, else none. */
    val heroArtUrl: String? get() = backdropList.firstOrNull() ?: posterUrl?.takeIf { it.isNotBlank() }
    val displayTitle: String get() = cleanTitle?.takeIf { it.isNotBlank() } ?: title
}

/** Response of GET /api/v1/posts/{id}. Unknown id or an adult-flagged id → 404 + `success:false`. */
data class PostDetailResponse(
    val success: Boolean = false,
    val data: PostDetail? = null,
    val error: String? = null,
)

/** POST /api/v2/enrich — ask the server to fill just this one title, live. Exactly one id. */
data class LiveFillRequest(
    @SerializedName("work_id") val workId: Long? = null,
    @SerializedName("post_id") val postId: Int? = null,
)

/**
 * The enrich answer. `filled` carries the work-level value for every
 * fillable field that has one after the attempt — newly filled or already
 * stored on a sibling post — keyed by the server's field names
 * (`rating`, `genres`, `synopsis`, `cast`, `director`, `poster`,
 * `backdrop`, `year`, `imdb_id`, `tmdb_id`). `poster` is already shaped
 * like the detail's own art (a public CDN URL, or one of our own
 * `/api/v1/art` handles); `backdrop` is the same JSON-array string the
 * detail carries. `ambiguous` is not modelled: the app never shows it
 * (Gson ignores the unknown key, and the test below proves it).
 */
data class LiveFillResponse(
    val ok: Boolean = false,
    val status: String? = null,
    val filled: Map<String, String> = emptyMap(),
    val sources: Map<String, String> = emptyMap(),
    @SerializedName("fields_filled") val fieldsFilled: Int = 0,
)

/** The raw `posts` row as the public may see it, plus its `releases`. */
data class PostDetail(
    val id: Int,
    val title: String,
    @SerializedName("clean_title")
    val cleanTitle: String? = null,
    /**
     * The server's own display title — see [Post.displayTitleWire].
     * The detail screen prefers it (then [cleanTitle], then [title]),
     * with [UserWords.detailTitle] stripping any suffix the server left.
     */
    @SerializedName("display_title")
    val displayTitleWire: String? = null,
    val year: Int? = null,
    /** Minutes, from the work row the server matched this post to — null when it has none. */
    @SerializedName("runtime_minutes")
    val runtimeMinutes: Int? = null,
    @SerializedName("content_type")
    val contentType: String? = null,
    val seasons: String? = null,
    @SerializedName("poster_url")
    val posterUrl: String? = null,
    /** TEXT holding a JSON array of URL strings (no width/kind metadata — API_V2.md §14). */
    @SerializedName("backdrop_urls")
    val backdropUrls: String? = null,
    @SerializedName("imdb_rating")
    val imdbRating: String? = null,
    @SerializedName("imdb_id")
    val imdbId: String? = null,
    @SerializedName("tmdb_id")
    val tmdbId: Int? = null,
    val genres: String? = null,
    val synopsis: String? = null,
    /**
     * `posts.cast_names` — the scraped cast list as one raw string
     * ("Shah Rukh Khan, Kajol"), or null when the catalog has none for this
     * title. Read it with [parseCastNames], never raw: scrapers join names
     * with commas or with spaces, and some rows carry a synopsis fragment
     * instead of names. Null means "no cast data", never "no cast" — the
     * detail screen shows no cast section at all.
     */
    @SerializedName("cast_names")
    val castNames: String? = null,
    /** `posts.is_animated` INTEGER 0/1 — metadata, not a restricted flag. */
    @SerializedName("is_animated")
    val isAnimated: Boolean? = null,
    val industry: String? = null,
    val country: String? = null,
    val language: String? = null,
    val releases: List<ReleaseDetail> = emptyList(),
    /**
     * Detail review (Nov 2026, P1): the server caps the detail at 1,000
     * releases (`viewer.py`: `release_total` / `releases_truncated` on
     * the post dict) — the rendered list is not the work's whole release
     * set. Nullable because Gson does not run Kotlin default initialisers
     * for a missing key; [totalReleases] / [isTruncated] are the only
     * forms callers should read.
     */
    @SerializedName("release_total")
    val releaseTotal: Int? = null,
    @SerializedName("releases_truncated")
    val releasesTruncated: Boolean? = null,
    /**
     * The server's collapsed quality ladder, one group per season/episode scope
     * (see [OptionGroup]). The quality switch and the download card read THIS, not
     * the raw `releases[].sources[]`, so one rung means one row to the viewer.
     */
    @SerializedName("option_groups")
    val optionGroups: List<OptionGroup> = emptyList(),
    /** Same handle as [Post.workRef], null until the server sends one. See [Post.workRef]. */
    @SerializedName("work_ref")
    val workRef: String? = null,
    /**
     * Season posters and episode stills the server has mapped for this work. Empty when
     * the catalog has not run the TMDB artwork backfill; [ReleaseLayoutBuilder] then keeps
     * its honest fallback of spreading the work's own wide art. Never the root poster
     * copied once per season — the backfill refuses to write a season it has no poster for.
     *
     * The raw list is nullable because Gson does not run Kotlin default initialisers for a
     * missing key; [seasonArtwork] is the only form callers should read.
     */
    @SerializedName("season_artwork")
    val seasonArtworkRows: List<SeasonArtwork>? = null,
    /**
     * B2 (Nov 2026): the v2 `episode_guide`, one totals-only row per
     * season. The raw list is nullable because Gson does not run Kotlin
     * default initialisers for a missing key (the v1 detail never sends
     * it); [episodeGuide] is the only form callers should read.
     */
    @SerializedName("episode_guide")
    val episodeGuideRows: List<EpisodeGuideEntry>? = null,
) {
    val yearLabel: String? get() = displayYearLabel(year, currentCalendarYear())
    /** Same rule as [Post.typeLabel], so the detail header cannot disagree with its tile. */
    val typeLabel: String? get() = WorkTypeLabel.of(contentType, isAnimated)
    val genreList: List<String> get() = WireText.listOf(genres).filter { WireText.isKnownGenre(it) }
    val backdropList: List<String> get() = WireText.listOf(backdropUrls)
    val seasonArtwork: List<SeasonArtwork> get() = seasonArtworkRows.orEmpty()
    /** The per-season totals — empty when the server sent no guide (v1 detail, or an old v2). */
    val episodeGuide: List<EpisodeGuideEntry> get() = episodeGuideRows.orEmpty()
    /** True when the server cut this detail's release list at its cap. */
    val isTruncated: Boolean get() = releasesTruncated == true
    /** The work's true release count — the capped list's size when untruncated. */
    val totalReleases: Int get() = releaseTotal ?: releases.size
    val displayTitle: String get() = cleanTitle?.takeIf { it.isNotBlank() } ?: title

    /**
     * Live fill (detail open): true when a field the detail screen shows
     * is still blank, so the ViewModel may ask the server to fill just
     * this title in the background. A missing backdrop counts — the
     * server can fill one from TMDb when the title has it.
     */
    fun hasMissingDisplayFields(): Boolean =
        imdbRating.isNullOrBlank() || synopsis.isNullOrBlank() ||
            posterUrl.isNullOrBlank() || backdropUrls.isNullOrBlank() ||
            castNames.isNullOrBlank() || genres.isNullOrBlank() ||
            year == null

    /**
     * Live fill (detail open): patch this detail's blanks from the
     * server's one-title fill answer, in place. Only blanks are filled —
     * a value already on screen is never overwritten by a fill. The
     * director the server may have filled has no field on this screen,
     * so it is ignored here (it still lands in the DB for the next
     * projection run). The keys are the server's field names
     * (`rating`, `cast`, `poster`, `backdrop`, …), not the wire's.
     * Pure: the same input always answers the same copy.
     */
    fun withLiveFill(filled: Map<String, String>): PostDetail {
        fun take(key: String, current: String?): String? {
            val value = filled[key]?.takeIf { it.isNotBlank() } ?: return current
            return if (current.isNullOrBlank()) value else current
        }
        return copy(
            imdbRating = take("rating", imdbRating),
            genres = take("genres", genres),
            synopsis = take("synopsis", synopsis),
            castNames = take("cast", castNames),
            posterUrl = take("poster", posterUrl),
            backdropUrls = take("backdrop", backdropUrls),
            year = year ?: filled["year"]?.toIntOrNull(),
            imdbId = take("imdb_id", imdbId),
            tmdbId = tmdbId ?: filled["tmdb_id"]?.toIntOrNull(),
        )
    }
}

/**
 * One row of `season_artwork` from `GET /api/v1/posts/{id}`.
 *
 * `kind` is `season_poster` (with `episode` 0) or `episode_still`. `season` uses the
 * server's TMDB numbering, which is also the numbering [ReleaseDetail.seasonNumber]
 * reads, so a poster is attached to the season the viewer is looking at.
 */
data class SeasonArtwork(
    val kind: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val url: String? = null,
)

/**
 * One row of the v2 `episode_guide` (see [PostDetail.episodeGuide]): a
 * season's totals only — its number and its episode total from the
 * server's `cat_episodes`, never an enumeration. Built in Kotlin by
 * `WireWorkDetailResponse.toPostDetail`, never deserialised from JSON
 * directly, so its fields are plain non-null defaults.
 */
data class EpisodeGuideEntry(
    val season: Int = 0,
    val episodeCount: Int = 0,
    val enumeratedEpisodeCount: Int = 0,
    val packOnly: Boolean = false,
)

/**
 * One `releases` row, exactly as the public view projects it:
 * `id, post_id, release_title, season, episode, quality, codec, size, languages`
 * + the joined, already-projected `sources` (see [SourceDetail]).
 *
 * PHANTOM FIELDS REMOVED (they mapped keys the API never returns, so they were
 * permanently null and silently killed season/episode grouping — App/AGENTS.md §6):
 *   `season_number`, `episode_number`, `episode_title`, `resolution`, `audio`.
 * The first two and `resolution`/`audio` are now DERIVATIONS of columns that do exist
 * (`season`, `episode`, `quality`+`release_title`, `languages`); `episode_title` has no
 * server counterpart at all, so it is gone and [releaseTitle] is the honest label.
 *
 * PROVENANCE FIELDS ARE ABSENT ON PURPOSE: `source_site`, `origin_url`, `intermediate_url`
 * and `created_at` are not in the served view any more, so no UI can render them by
 * accident and no test can pin them back into existence.
 */
data class ReleaseDetail(
    val id: Int,
    @SerializedName("post_id")
    val postId: Int? = null,
    @SerializedName("release_title")
    val releaseTitle: String? = null,
    /** Free TEXT: "S01", "1", "Batch", null — see [seasonNumber]. */
    val season: String? = null,
    /** Free TEXT: "E01", "1", null — see [episodeNumber]. */
    val episode: String? = null,
    /** Free TEXT quality rung label ("1080p", "HDTS", "720p WEB-DL"). */
    val quality: String? = null,
    val codec: String? = null,
    /** Free TEXT size label ("2.4GB"); [sizeBytes] parses it. */
    val size: String? = null,
    /** Audio languages of the print (TEXT, e.g. "English" or "Hindi|Tamil"). */
    val languages: String? = null,
    val sources: List<SourceDetail> = emptyList(),
) {
    /**
     * The print descriptor (coverage, season, episode range, score) — the honest v1 view.
     *
     * NOT cached on purpose: Gson instantiates these models via `Unsafe` when there is no
     * no-arg constructor, and a `by lazy` delegate would then be null at first access.
     * Callers that need several fields should read this ONCE and reuse the descriptor —
     * the derived getters below each parse the label again, which is fine for one release
     * and wasteful in a list (see docs/HANDOFF_appnet.md for the UI-side adoption).
     */
    val printDescriptor: PrintDescriptor get() = ReleaseLabelParsing.describe(this)

    /** Parsed from [season] then the label. null = the server never said which season. */
    val seasonNumber: Int? get() = ReleaseLabelParsing.seasonOf(printDescriptor)

    /**
     * Parsed from [episode] then the label. null for a pack/whole-season file — which is the
     * truth, not missing data: see [printDescriptor]'s `coverageType`.
     */
    val episodeNumber: Int? get() = ReleaseLabelParsing.episodeOf(printDescriptor)

    /**
     * The print axis, as the server would name it: the resolution rung found in
     * `quality` + `release_title` ("1080p"), or null when the label names none.
     * Re-points the old phantom `resolution` key.
     */
    val resolution: String? get() = ReleaseLabelParsing.resolutionOf(printDescriptor)

    /** Re-points the old phantom `audio` key: v1's audio languages live in `languages`. */
    val audio: String? get() = languages

    /** Parsed from the `size` column, else from the label. null = no byte figure anywhere. */
    val sizeBytes: Long? get() = ReleaseLabelParsing.sizeBytesOf(printDescriptor)

    /**
     * Links of this print, best-first. When the server publishes `rank_score` on a link that
     * is used as-is; otherwise [LinkClassification.rankOf] derives the same score from our own
     * classification (`kind`/`playability`/`tier`/`state`) — never from a hoster name, which
     * is no longer on the wire.
     */
    val rankedSources: List<SourceDetail>
        get() = sources.sortedWith(
            compareByDescending<SourceDetail> { LinkClassification.rankOf(it) }
                .thenByDescending { if (it.status == "active") 1 else 0 }
                .thenBy { it.streamId },
        )
}

/**
 * One link of one print, as the public may see it — the served `link_view`.
 *
 * There is deliberately NO hoster name, NO service slug, NO source/origin/final URL and no
 * raw ledger `id` in this shape: the server projects them away before the response leaves it
 * (`Fast_Fetch/redact.py` `VIEWS["link"]`), and what it publishes instead is
 *  - [streamId] — an opaque signed handle, the only key the client has for the link, and
 *  - [playUrl] — OUR OWN `/api/v1/stream?id=<handle>` redirector, which answers a 302 to the
 *    bytes when it can and never tells anyone who hosts them.
 * Everything else here describes OUR behaviour (kind, playability, ephemerality, resumability,
 * measured http status, size, mime), which is exactly what a player needs and nothing of what
 * a provenance surface could show.
 *
 * [kind] is the server's own vocabulary — `direct | resolve | download | external` — where
 * `external` means "only a page outside this app can present it". The client maps that to an
 * honest download-only offer labelled "Not playable here"; it never opens one.
 */
data class SourceDetail(
    @SerializedName("stream_id")
    val streamId: String = "",
    @SerializedName("play_url")
    val playUrl: String? = null,
    @SerializedName("link_id")
    val linkId: Int? = null,
    @SerializedName("release_id")
    val releaseId: Int? = null,
    /** Ours: "Fast direct" when the link resumes, else "Standard". Never a hoster name. */
    val label: String? = null,
    /** Ours: `direct | resolve | download | external`. */
    val kind: String? = null,
    /** Ours: `stream | download | both | resolve_required | none` (the served classification). */
    val playability: String? = null,
    /** Ours: `hls | progressive | dash | html | unknown`. */
    val protocol: String? = null,
    /** Ours: `container` of the file we would hand over, when measured. */
    val container: String? = null,
    /** Server-written scrape status: `active | dead | expired`. */
    val status: String? = null,
    val state: String? = null,
    val playable: Boolean? = null,
    val downloadable: Boolean? = null,
    /** INTEGER 0/1 here (a JSON number) but a real boolean on /api/v1/stream — [LenientBooleanAdapter] accepts both. */
    @SerializedName("is_temporary")
    val isTemporary: Boolean? = null,
    @SerializedName("is_resumable")
    val isResumable: Boolean? = null,
    val resumable: Boolean? = null,
    val ephemeral: Boolean? = null,
    @SerializedName("http_status")
    val httpStatus: Int? = null,
    @SerializedName("file_size_bytes")
    val fileSizeBytes: Long? = null,
    @SerializedName("size_bytes")
    val sizeBytes: Long? = null,
    @SerializedName("mime_type")
    val mimeType: String? = null,
    @SerializedName("rank_score")
    val rankScore: Int? = null,
    @SerializedName("tier")
    val tier: Int? = null,
    @SerializedName("latency_ms")
    val latencyMs: Int? = null,
    @SerializedName("speed_mbps")
    val speedMbps: Double? = null,
    @SerializedName("last_evidence_at")
    val lastEvidenceAt: String? = null,
    @SerializedName("expires_at")
    val expiresAt: Double? = null,
) {
    /**
     * The only URL the player may be handed: OUR OWN redirector. A link with no `play_url`
     * has nothing to play — the raw upstream address is not on this model any more, so there
     * is nothing else it could fall back to.
     */
    val bestUrl: String?
        get() = playUrl?.trim()?.takeIf { WireText.isHttpUrl(it) }

    /** What this link *is* and what a client can do with it (link axis, [LinkKind]). */
    val classification: LinkAssessment
        get() = LinkClassification.classify(this)

    val isEphemeral: Boolean get() = isTemporary == true || ephemeral == true
    val isResumableLink: Boolean get() = isResumable == true || resumable == true
    val isDead: Boolean get() = status == "dead" || status == "expired" || state == "dead"
}

/**
 * GET /api/v1/stream?id=<handle>&redirect=0 → the public answer of our own redirector.
 *
 * The public view of this endpoint carries NO url at all: `redirect=1` answers a bare 302 and
 * the player follows it, so a client that wants bytes never needs to read a body, and a client
 * that reads the body gets only OUR classification. Addressing a playback is therefore
 * structural: [streamHandle] + our base URL, never a served media address.
 *
 * `redirect=0` keys (verified against the served view): `success, stream_id, kind, mime_type,
 * file_size_bytes, resumable, expires_in`, and `error` on the failure paths.
 */
data class StreamResponse(
    val success: Boolean = false,
    @SerializedName("stream_id")
    val streamHandle: String? = null,
    /** Ours: `direct | resolve | download | external`. */
    val kind: String? = null,
    @SerializedName("mime_type")
    val mimeType: String? = null,
    @SerializedName("file_size_bytes")
    val fileSizeBytes: Long? = null,
    val resumable: Boolean? = null,
    /** Seconds our handle stays valid; null = durable. */
    @SerializedName("expires_in")
    val expiresIn: Long? = null,
    /** Client-side decrypt scheme: "aes-ctr" when this copy's bytes are ciphertext
     * the app must decrypt on-device. Null/absent = plain bytes. The value names
     * the scheme, never the hoster. */
    val decrypt: String? = null,
    /** Opaque node id for the decrypting proxy to mint the ciphertext URL. */
    @SerializedName("decrypt_node_id")
    val decryptNodeId: String? = null,
    /** The link key, memory-only: never logged, never persisted, never banked. */
    @SerializedName("decrypt_key")
    val decryptKey: String? = null,
    val cached: Boolean? = null,
    val refreshed: Boolean? = null,
    val error: String? = null,
) {
    /**
     * The answer is usable when the redirector handed back a handle of ours. There is no URL
     * on this payload to check: the address a client plays is the one it already has,
     * `play_url`, and the 302 behind it either yields bytes or it does not.
     */
    val streamId: String? get() = streamHandle?.takeIf { it.isNotBlank() }?.let { if (success) it else null }

    /** True when the answer is "looked successful but produced nothing usable". */
    val isFalseSuccess: Boolean get() = success && streamId == null

    /** Expiry of the handle in epoch millis, or null when the link is durable. */
    fun expiresAtEpochMillis(nowEpochMillis: Long): Long? =
        if (expiresIn == null) null else nowEpochMillis + expiresIn * 1000L
}

/** GET /api/v1/stats — snake_case exactly as viewer.py:2165-2172 emits them. */
data class StatsResponse(
    val posts: Int = 0,
    val releases: Int = 0,
    val sources: Int = 0,
    @SerializedName("active_sources")
    val activeSources: Int? = null,
    @SerializedName("resumable_sources")
    val resumableSources: Int? = null,
    @SerializedName("temporary_sources")
    val temporarySources: Int? = null,
)
