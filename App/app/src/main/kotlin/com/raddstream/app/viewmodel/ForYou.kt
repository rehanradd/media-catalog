package com.raddstream.app.viewmodel

import com.raddstream.app.data.local.FavoritePost
import com.raddstream.app.data.local.FollowedSeries
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WireText
import com.raddstream.app.ui.components.UserWords
import kotlin.random.Random

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
 * The highest score the ranker can mint without feedback: shared genres
 * 6 (3 × at most 2) + language 2 + industry 2 + type 1 + animated 1 +
 * title tokens 4 (2 × at most 2) + a real IMDb rating ≥ 7.0 1 = 17.
 * A feedback bonus can push a score past it — see [matchPercentage].
 */
internal const val FOR_YOU_MAX_SCORE = 17

/**
 * REA #2 (Netflix, Nov 2026): the ranker score as a "97% Match" — a
 * presentation scale over the real score, never a new signal. The lowest
 * score a pick can carry ([FOR_YOU_MIN_SCORE]) reads 65%: a title the
 * ranker barely claims is still a match, just a weak one. The highest
 * score the ranker can mint without feedback ([FOR_YOU_MAX_SCORE]) reads
 * 99%: Netflix never prints 100%, and neither do we — a perfect score
 * would promise a certainty the ranker does not have. Linear between,
 * clamped, so a feedback bonus that pushes a score past 17 still reads
 * 99% and a score below the floor still reads 65%.
 */
internal fun matchPercentage(score: Int): Int =
    (65 + (score - FOR_YOU_MIN_SCORE) * 34 / (FOR_YOU_MAX_SCORE - FOR_YOU_MIN_SCORE))
        .coerceIn(65, 99)

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
 * REA #2: what the viewer's up-votes lend the ranker — the genres and
 * title words of every up-voted work the pool still carries. A candidate
 * that shares any of them earns one +2 affinity bonus (see
 * [scoreCandidate]), not one point per shared word, so a single
 * enthusiastic vote cannot outrank the anchor's own signals. Down-votes
 * lend nothing here — they exclude (see [selectForYou]), they never
 * subtract.
 */
internal data class FeedbackSignals(
    val genres: Set<String> = emptySet(),
    val tokens: Set<String> = emptySet(),
)

/** The up-voted works' genres + title words, resolved from the pool. */
internal fun feedbackSignals(feedback: Map<String, Int>, pool: List<Post>): FeedbackSignals {
    val upvoted = feedback.filterValues { it > 0 }.keys
    if (upvoted.isEmpty()) return FeedbackSignals()
    val resolved = pool.filter { it.workRef in upvoted }
    return FeedbackSignals(
        genres = resolved.flatMap { knownTasteGenres(it) }.toSet(),
        tokens = resolved.flatMap { titleTokens(it.displayTitle) }.toSet(),
    )
}

/**
 * How much one candidate owes its anchor. Shared genres pay most, then
 * language and industry, then type and animation, then franchise words;
 * the candidate's own real IMDb rating (≥7.0) pays one point. A missing
 * or unparseable rating pays nothing — never a penalty, never an
 * invented number: the tile paints the candidate's own rating, or
 * nothing, exactly as the E2 Ratings rail does.
 */
internal fun scoreCandidate(
    candidate: Post,
    anchor: ForYouAnchor,
    feedbackSignals: FeedbackSignals = FeedbackSignals(),
): Int {
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
    // REA #2: one +2 affinity bonus when the candidate shares a genre or
    // a title word with anything the viewer thumbed up — the vote's echo,
    // not a second anchor.
    if (feedbackSignals.genres.isNotEmpty() || feedbackSignals.tokens.isNotEmpty()) {
        val sharesGenre = knownTasteGenres(candidate).intersect(feedbackSignals.genres).isNotEmpty()
        val sharesToken = titleTokens(candidate.displayTitle).intersect(feedbackSignals.tokens).isNotEmpty()
        if (sharesGenre || sharesToken) score += 2
    }
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
    /**
     * REA #2: the viewer's thumbs as workRef → thumb (1 / -1). A
     * down-vote excludes its work from the picks; an up-vote lends its
     * genres and title words to the ranker through [feedbackSignals].
     * Empty by default, so every existing caller keeps its shape.
     */
    feedback: Map<String, Int> = emptyMap(),
): HomeSection? {
    val anchor = pickAnchor(watched, favorites, followed, pool, kidsMode) ?: return null
    val signals = feedbackSignals(feedback, pool)
    val scored = pool
        .filterNot { isExcluded(it, excluded) }
        // REA #2: a down-vote is a veto — the work never appears in the
        // row, however well it scores. A vote is keyed by workRef, so a
        // candidate with no handle cannot be vetoed by one.
        .filterNot { it.workRef != null && feedback[it.workRef] == -1 }
        .filterNot { UserWords.workKey(it.workRef, it.displayTitle, it.year) == anchor.workKey }
        .filter { !kidsMode || KidsLock.isKidAppropriate(it) }
        .map { candidate -> candidate to scoreCandidate(candidate, anchor, signals) }
        .filter { (_, score) -> score >= FOR_YOU_MIN_SCORE }
        .sortedByDescending { (_, score) -> score }
        .take(maxPicks)
    if (scored.isEmpty()) return null
    return HomeSection(
        key = FOR_YOU_KEY,
        title = FOR_YOU_TITLE,
        type = null,
        items = scored.map { (candidate, _) -> candidate },
        subtitle = anchor.explanation(),
        forYou = true,
        // REA #2: the "97% Match" per pick, keyed by post id — the
        // presentation scale of the score that claimed it.
        matchScores = scored.associate { (candidate, score) -> candidate.id to matchPercentage(score) },
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

/**
 * REA #2: the bridge from Home's "For you" row to Detail — the match
 * percentages for the row on screen, keyed by post id. In-memory only:
 * the row is recomputed on every Home load, so the store is overwritten
 * there and never read from disk; a Detail opened from Search (or a
 * cold start straight to Detail) finds nothing here and paints no % —
 * the % is a property of the current Home row, not of the title. A row
 * that drops to nothing clears the store, so a stale % never outlives
 * the row that minted it.
 */
internal object MatchScoreStore {
    private val scores = mutableMapOf<Int, Int>()

    fun set(scores: Map<Int, Int>) {
        this.scores.clear()
        this.scores.putAll(scores)
    }

    fun get(postId: Int): Int? = scores[postId]

    fun clear() {
        scores.clear()
    }
}

/**
 * REA #4 (Netflix, Nov 2026): one-tap shuffle from the "For you" pool.
 * The pick is weighted by the row's Match % — a 97% title is roughly
 * twice as likely as a 50% one — so the shuffle still respects the
 * ranker instead of drawing blind. A pick with no score still counts
 * at 1, so it stays eligible. The last pick is skipped while the pool
 * has more than one title, so two taps never play the same title twice
 * in a row; a one-title pool has nothing else to offer and repeats
 * honestly. Pure, so the weighting is pinned in [ForYouTest] with a
 * seeded [Random] instead of asserted on a device.
 */
internal fun pickPlaySomething(
    section: HomeSection?,
    random: Random = Random.Default,
    lastPickedPostId: Int? = null,
): Post? {
    val items = section?.items.orEmpty()
    if (items.isEmpty()) return null
    if (items.size == 1) return items.first()
    val pool = items.filterNot { it.id == lastPickedPostId }.ifEmpty { items }
    val weights = pool.map { (section?.matchScores?.get(it.id) ?: 1).coerceAtLeast(1) }
    val total = weights.sum()
    var roll = random.nextInt(total)
    for ((index, post) in pool.withIndex()) {
        roll -= weights[index]
        if (roll < 0) return post
    }
    return pool.last()
}

/**
 * REA #4: the bridge from Home's "For you" row to anywhere a shuffle is
 * offered — the player give-up card included. In-memory only, like
 * [MatchScoreStore]: Home sets it when the row is (re)computed, and a
 * row that drops to nothing clears it, so a shuffle never plays a title
 * the ranker no longer stands behind. The last pick is remembered here
 * (not in each caller) so a tap on Home and a tap on the give-up card
 * never play the same title twice in a row either.
 */
internal object ForYouPoolStore {
    private var section: HomeSection? = null
    private var lastPickedPostId: Int? = null

    fun set(section: HomeSection?) {
        this.section = section
        if (section == null) lastPickedPostId = null
    }

    fun hasPicks(): Boolean = !section?.items.isNullOrEmpty()

    fun pick(random: Random = Random.Default): Post? {
        val picked = pickPlaySomething(section, random, lastPickedPostId) ?: return null
        lastPickedPostId = picked.id
        return picked
    }
}
