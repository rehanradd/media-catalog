package com.raddstream.app.viewmodel

import com.raddstream.app.data.local.FavoritePost
import com.raddstream.app.data.local.FollowedSeries
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WireText
import com.raddstream.app.ui.components.UserWords

/**
 * "For you" — the on-device personalised row (E2, Nov 2026).
 *
 * RESEARCH (20 Nov 2026 — sources in App/docs/SESSION_HISTORY.md):
 * - Netflix names its signals: your own viewing + ratings, finish vs
 *   abandon, title metadata (genre, cast, year), language, and recency —
 *   recent activity outweighs older activity; no demographics. A row is
 *   one specialist ranker: it picks which titles go in, in what order,
 *   strongest first, and evidence selection names the reason next to the
 *   pick ("similar to something you watched").
 * - "Because you watched X" is one anchor title and things like it — the
 *   title says why. Plex Shortlist does the same per person ("Picked for
 *   You" blends the whole history; "Because you watched X" is one title).
 * - On-device personalisation (2026) keeps the raw signals on the phone:
 *   a local feature store, no round trip, rank locally, never ship the
 *   history anywhere.
 *
 * AUDIT (20 Nov 2026, file:line in the session log):
 * - Home ALREADY has "Because you watch" — server-side GET
 *   /api/v1/related seeded from the FIRST Continue Watching post only.
 *   "For you" is the distinct, on-device sibling: the anchor is the
 *   STRONGEST watched title (finished beats started, recent breaks ties),
 *   the scoring runs on this phone, the candidates are the rails already
 *   on the device, and it works offline from the cached Home.
 * - The signals are thin by design: watch_history stores title + position
 *   + rating only (no genres — ResumeTile.of builds a bare Post), a
 *   favourite stores title + type + rating (no genres), a followed series
 *   stores title only. Genre/language/industry come from resolving the
 *   anchor back to a full rail Post by id or workRef; an anchor that no
 *   rail still carries contributes title tokens only. That is the honest
 *   variant the data supports — never a guessed genre.
 * - `Post.genreList` is NOT a safe genre source for matching: it only
 *   drops placeholders, so junk tokens the dev-cleanup audit measured
 *   ("Hindi Dub" 11,047 works, "NETFLIX", "COMPLETED") would match as
 *   genres. The ranker matches only [KNOWN_TASTE_GENRES].
 * - HomeScreen already dedupes across rails (Audit #18): a title never
 *   appears twice. "For you" sits right after "Because you watch", so its
 *   picks are claimed before the rails below render — and the ranker
 *   excludes everything above it, so render-time dedupe never eats the row.
 */
internal const val FOR_YOU_KEY = "for_you"

/** The row title the viewer reads. */
internal const val FOR_YOU_TITLE = "For you"

/** At most this many picks in the row. */
internal const val FOR_YOU_MAX_PICKS = 10

/**
 * A pick needs at least this much overlap with its anchor to be claimed
 * as "for you": one shared genre (+3) qualifies; one shared language
 * (+2) or one shared title token (+2) alone never does.
 */
internal const val FOR_YOU_MIN_SCORE = 3

/**
 * The genre words the ranker is allowed to match on — the standard genre
 * vocabulary, nothing else. Anything the catalog writes that is not in
 * this set ("Hindi Dub", "NETFLIX", "COMPLETED", "Animes") is a junk
 * token, a language, or a label — never a genre signal, and it must
 * never make two unrelated titles look alike.
 */
internal val KNOWN_TASTE_GENRES: Set<String> = setOf(
    "action", "adventure", "animation", "biography", "comedy", "crime",
    "documentary", "drama", "family", "fantasy", "history", "horror",
    "music", "musical", "mystery", "romance", "sci fi", "science fiction",
    "thriller", "war", "western", "sport", "short",
)

/** The known genres a row carries, normalised the way the set spells them. */
internal fun knownTasteGenres(post: Post): List<String> =
    post.genreList
        .map { it.trim().lowercase().replace("-", " ").replace(Regex("\\s+"), " ") }
        .filter { it in KNOWN_TASTE_GENRES }
        .distinct()

/** The known languages a row carries ("Hindi + English" splits into two). */
internal fun knownLanguages(post: Post): List<String> =
    WireText.listOf(post.language)
        .filter { WireText.isKnownLanguage(it) }
        .map { it.trim().lowercase() }
        .distinct()

/**
 * Words too generic — or secretly a language — to count as a franchise
 * signal. "Hindi" and "dubbed" would otherwise match every Hindi-dubbed
 * title to every other one, which is language-by-stealth, not taste.
 * Titles in non-Latin scripts carry no tokens at all: their genre and
 * language signals still work, their franchise signal does not.
 */
internal val TITLE_TOKEN_STOPWORDS: Set<String> = setOf(
    "the", "and", "with", "from", "season", "episode", "part", "chapter",
    "movie", "film", "hindi", "english", "tamil", "telugu", "malayalam",
    "kannada", "punjabi", "bengali", "urdu", "dubbed", "dub", "subbed",
    "uncut", "unrated",
)

/** The significant words of a title: lowercase, ≥3 letters, stopwords out. */
internal fun titleTokens(title: String): List<String> =
    title.lowercase().replace(Regex("[^a-z0-9]+"), " ").split(" ")
        .filter { it.length >= 3 && it !in TITLE_TOKEN_STOPWORDS }
        .distinct()

/** One work the ranker must never recommend, by whatever identity it has. */
internal data class ForYouWork(
    val workRef: String?,
    val title: String,
    val year: Int?,
    /** The catalog row id when one is known (a v1 id, or a v2 sentinel). */
    val postId: Int? = null,
)

internal enum class AnchorKind { WATCHED, SAVED, FOLLOWED }

/**
 * The one title this row is built from, and the signals it lends the
 * picks. Signals come from the anchor's full rail Post when one still
 * carries it; an anchor no rail carries lends title tokens only — never
 * a guessed genre, language, or type.
 */
internal data class ForYouAnchor(
    val kind: AnchorKind,
    val title: String,
    val workKey: String,
    val genres: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val industry: String? = null,
    val contentType: String? = null,
    val isAnimated: Boolean = false,
    val tokens: List<String> = emptyList(),
) {
    /** The reason line the row paints under "For you". */
    fun explanation(): String = when (kind) {
        AnchorKind.WATCHED -> "Because you watched $title"
        AnchorKind.SAVED -> "Because you saved $title"
        AnchorKind.FOLLOWED -> "Because you follow $title"
    }
}

/**
 * Which completion bucket a watched scope sits in. Finishing is the
 * strongest endorsement a viewer gives, so a finished title anchors the
 * row before a half-watched one however recent the half-watch is; inside
 * a bucket the list is already most-recent-first, so the first tile wins.
 * An unknown duration reads as halfway — never 0, never finished.
 */
internal fun anchorBucket(positionMs: Long, durationMs: Long): Int {
    val fraction = ContinueWatchingTile.progressFraction(positionMs, durationMs)
        ?: if (positionMs >= 60_000L) 0.5f else 0.1f
    return when {
        fraction >= 0.9f -> 0
        fraction >= 0.5f -> 1
        fraction >= 0.1f -> 2
        else -> 3
    }
}

private fun resolveInPool(pool: List<Post>, postId: Int?, workRef: String?): Post? =
    pool.firstOrNull { candidate ->
        (postId != null && postId > 0 && candidate.id == postId) ||
            (!workRef.isNullOrBlank() && candidate.workRef == workRef)
    }

private fun anchorFromResolved(
    kind: AnchorKind,
    title: String,
    workRef: String?,
    year: Int?,
    resolved: Post?,
    fallbackContentType: String?,
): ForYouAnchor = ForYouAnchor(
    kind = kind,
    title = title,
    workKey = UserWords.workKey(workRef, title, year),
    genres = resolved?.let { knownTasteGenres(it) }.orEmpty(),
    languages = resolved?.let { knownLanguages(it) }.orEmpty(),
    industry = resolved?.industry?.trim()?.lowercase()
        ?.takeIf { WireText.isKnownValue(it) },
    contentType = resolved?.contentType ?: fallbackContentType,
    isAnimated = resolved?.isAnimated == true,
    tokens = titleTokens(title),
)

/**
 * The anchor this row is built from, or null when there is nothing to
 * build from: no watch history, no favourites, no followed series.
 * Watched titles anchor first (a finished one before a half-watched
 * one, recent first inside a bucket); a saved title anchors when
 * nothing was watched; a followed series anchors when nothing was
 * watched or saved. In kids mode only a watched title whose resolved
 * row is itself kid-appropriate may anchor — a favourite or a followed
 * series carries no genre to verify, and naming an adult title in the
 * reason line would show it to a kid.
 */
internal fun pickAnchor(
    watched: List<ResumeTile>,
    favorites: List<FavoritePost>,
    followed: List<FollowedSeries>,
    pool: List<Post>,
    kidsMode: Boolean,
): ForYouAnchor? {
    val watchedAnchors = watched
        .map { tile ->
            val resolved = resolveInPool(pool, tile.post.id, tile.workRef)
            if (kidsMode && (resolved == null || !KidsLock.isKidAppropriate(resolved))) {
                null
            } else {
                anchorBucket(tile.positionMs, tile.durationMs) to anchorFromResolved(
                    kind = AnchorKind.WATCHED,
                    title = tile.post.displayTitle,
                    workRef = tile.workRef,
                    year = tile.post.year,
                    resolved = resolved,
                    fallbackContentType = null,
                )
            }
        }
        .filterNotNull()
        .sortedBy { (bucket, _) -> bucket }
    watchedAnchors.firstOrNull()?.let { (_, anchor) -> return anchor }
    if (kidsMode) return null
    favorites.sortedByDescending { it.addedAt }.firstOrNull()?.let { favorite ->
        val resolved = resolveInPool(pool, favorite.id, favorite.workRef)
        return anchorFromResolved(
            kind = AnchorKind.SAVED,
            title = favorite.cleanTitle?.takeIf { it.isNotBlank() } ?: favorite.title,
            workRef = favorite.workRef,
            year = favorite.year?.toIntOrNull(),
            resolved = resolved,
            fallbackContentType = favorite.contentType,
        )
    }
    followed.sortedByDescending { it.followedAt }.firstOrNull()?.let { series ->
        val resolved = resolveInPool(pool, null, series.workRef)
        return anchorFromResolved(
            kind = AnchorKind.FOLLOWED,
            title = series.cleanTitle?.takeIf { it.isNotBlank() } ?: series.title,
            workRef = series.workRef,
            year = null,
            resolved = resolved,
            fallbackContentType = null,
        )
    }
    return null
}

/**
 * How much one candidate owes its anchor. Shared genres pay most, then
 * language and industry, then type and animation, then franchise words;
 * the candidate's own real IMDb rating (≥7.0) pays one point. A missing
 * or unparseable rating pays nothing — never a penalty, never an
 * invented number: the tile paints the candidate's own rating, or
 * nothing, exactly as the E2 Ratings rail does.
 */
internal fun scoreCandidate(candidate: Post, anchor: ForYouAnchor): Int {
    var score = 0
    val sharedGenres = knownTasteGenres(candidate).intersect(anchor.genres.toSet())
    score += 3 * minOf(sharedGenres.size, 2)
    if (knownLanguages(candidate).intersect(anchor.languages.toSet()).isNotEmpty()) score += 2
    if (anchor.industry != null &&
        candidate.industry?.trim()?.lowercase() == anchor.industry
    ) {
        score += 2
    }
    if (anchor.contentType != null && candidate.contentType == anchor.contentType) score += 1
    if (anchor.isAnimated && candidate.isAnimated == true) score += 1
    val sharedTokens = titleTokens(candidate.displayTitle).intersect(anchor.tokens.toSet())
    score += 2 * minOf(sharedTokens.size, 2)
    val rating = candidate.imdbRating?.trim()?.toDoubleOrNull()
    if (rating != null && rating >= 7.0) score += 1
    return score
}

private fun exclusionSets(excluded: List<ForYouWork>): Pair<Set<String>, Set<String>> {
    val fullKeys = excluded
        .map { UserWords.workKey(it.workRef, it.title, it.year) }
        .toSet()
    // A work with neither a work ref nor a year is unidentifiable except
    // by its bare title — and only then may the bare title exclude. A
    // titled work WITH a year never excludes by bare title: same title +
    // same year can be two different films (the Marco rule), so a bare
    // title must never hide a dated twin.
    val bareTitles = excluded
        .filter { it.workRef.isNullOrBlank() && it.year == null }
        .map { UserWords.normalizeDedupTitle(it.title) }
        .toSet()
    return fullKeys to bareTitles
}

private fun isExcluded(candidate: Post, fullKeys: Set<String>, bareTitles: Set<String>): Boolean {
    if (UserWords.workKey(candidate.workRef, candidate.displayTitle, candidate.year) in fullKeys) {
        return true
    }
    return candidate.workRef.isNullOrBlank() && candidate.year == null &&
        UserWords.normalizeDedupTitle(candidate.displayTitle) in bareTitles
}

/** True when the candidate is one of the excluded works, by any identity it carries. */
private fun isExcluded(candidate: Post, excluded: List<ForYouWork>): Boolean {
    if (excluded.any { it.postId != null && it.postId != 0 && it.postId == candidate.id }) {
        return true
    }
    val (fullKeys, bareTitles) = exclusionSets(excluded)
    return isExcluded(candidate, fullKeys, bareTitles)
}

/**
 * The "For you" section, or null when there is nothing honest to show:
 * no anchor at all, or no candidate reaches [FOR_YOU_MIN_SCORE].
 * Candidates are the rails already on this device — no new network call,
 * so the row works offline from the cached Home. Picks are the
 * highest-scoring first (pool order breaks ties, so the order is
 * deterministic); the anchor itself, everything already watched, saved,
 * followed, or sitting in a rail above this one is excluded, so the row
 * never repeats a title the viewer already has. In kids mode only
 * kid-appropriate candidates survive — the render-time filter then has
 * nothing left to do to this row.
 */
internal fun selectForYou(
    watched: List<ResumeTile>,
    favorites: List<FavoritePost>,
    followed: List<FollowedSeries>,
    pool: List<Post>,
    excluded: List<ForYouWork>,
    kidsMode: Boolean,
    maxPicks: Int = FOR_YOU_MAX_PICKS,
): HomeSection? {
    val anchor = pickAnchor(watched, favorites, followed, pool, kidsMode) ?: return null
    val picks = pool
        .filterNot { isExcluded(it, excluded) }
        .filterNot { UserWords.workKey(it.workRef, it.displayTitle, it.year) == anchor.workKey }
        .filter { !kidsMode || KidsLock.isKidAppropriate(it) }
        .map { candidate -> candidate to scoreCandidate(candidate, anchor) }
        .filter { (_, score) -> score >= FOR_YOU_MIN_SCORE }
        .sortedByDescending { (_, score) -> score }
        .take(maxPicks)
        .map { (candidate, _) -> candidate }
    if (picks.isEmpty()) return null
    return HomeSection(
        key = FOR_YOU_KEY,
        title = FOR_YOU_TITLE,
        type = null,
        items = picks,
        subtitle = anchor.explanation(),
        forYou = true,
    )
}

/**
 * Where the row goes: right after "Because you watch" when that rail
 * exists, else right after "Recently added", else second. The ranker
 * excludes everything above this index, so the position is also the
 * dedupe contract: rails above keep their titles, this row claims its
 * picks before the rails below render.
 */
internal fun forYouInsertIndex(sections: List<HomeSection>): Int {
    val afterBecause = sections.indexOfFirst { it.key == BECAUSE_YOU_WATCH_KEY }
        .takeIf { it >= 0 }?.plus(1)
    if (afterBecause != null) return afterBecause
    val afterRecent = sections.indexOfFirst { it.key == RECENT_SECTION_KEY }
        .takeIf { it >= 0 }?.plus(1)
    if (afterRecent != null) return afterRecent
    return 1
}

/**
 * Insert (or replace) the row, or drop it when there is none. A section
 * already at the key is replaced in place — the row never appears twice.
 */
internal fun insertForYouSection(
    sections: List<HomeSection>,
    forYou: HomeSection?,
): List<HomeSection> {
    val without = sections.filterNot { it.key == FOR_YOU_KEY }
    if (forYou == null) return without
    val index = forYouInsertIndex(without).coerceIn(0, without.size)
    return without.toMutableList().apply { add(index, forYou) }
}
