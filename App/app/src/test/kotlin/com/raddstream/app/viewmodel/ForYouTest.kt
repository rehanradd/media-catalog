package com.raddstream.app.viewmodel

import com.raddstream.app.data.local.FavoritePost
import com.raddstream.app.data.local.FollowedSeries
import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * E2 "For you": the on-device personalised row. Every rule is pure, so
 * the whole ranker is pinned here without a repository: the anchor is the
 * strongest watched title, picks must overlap it, junk never matches, and
 * nothing is shown when there is nothing honest to show.
 */
class ForYouTest {

    private fun post(
        id: Int,
        title: String,
        genres: String? = null,
        language: String? = null,
        industry: String? = null,
        contentType: String? = null,
        isAnimated: Boolean? = null,
        year: Int? = null,
        workRef: String? = null,
        imdbRating: String? = null,
    ) = Post(
        id = id,
        title = title,
        year = year,
        contentType = contentType,
        isAnimated = isAnimated,
        imdbRating = imdbRating,
        genres = genres,
        industry = industry,
        language = language,
        workRef = workRef,
    )

    private fun tile(
        watched: Post,
        positionMs: Long,
        durationMs: Long,
        workRef: String = "work:${watched.id}",
    ) = ResumeTile(
        post = watched,
        season = 0,
        episode = 0,
        positionMs = positionMs,
        durationMs = durationMs,
        workRef = workRef,
    )

    private fun favorite(
        id: Int,
        title: String,
        workRef: String? = null,
        contentType: String? = null,
        addedAt: Long = 1L,
    ) = FavoritePost(
        id = id,
        title = title,
        contentType = contentType,
        addedAt = addedAt,
        workRef = workRef,
    )

    private fun followed(
        workRef: String,
        title: String,
        followedAt: Long = 1L,
    ) = FollowedSeries(
        workRef = workRef,
        title = title,
        followedAt = followedAt,
    )

    private fun actionAnchor() = tile(
        watched = post(id = 1, title = "Action Hero", year = 2024),
        positionMs = 95_000L,
        durationMs = 100_000L,
        workRef = "work:hero",
    )

    /** The anchor's full rail row, so its genres resolve. */
    private fun actionAnchorRow() = post(
        id = 1,
        title = "Action Hero",
        genres = "Action, Drama",
        language = "English",
        industry = "hollywood",
        contentType = "movie",
        year = 2024,
        workRef = "work:hero",
    )

    private fun actionCandidate(id: Int = 2, title: String = "Action Two") = post(
        id = id,
        title = title,
        genres = "Action",
        year = 2025,
    )

    @Test
    fun `no history means no row`() {
        assertNull(
            selectForYou(
                watched = emptyList(),
                favorites = emptyList(),
                followed = emptyList(),
                pool = listOf(actionCandidate()),
                excluded = emptyList(),
                kidsMode = false,
            ),
        )
    }

    @Test
    fun `finished anchor beats a newer half-watch`() {
        val newerHalf = tile(
            watched = post(id = 9, title = "New Half", year = 2025),
            positionMs = 40_000L,
            durationMs = 100_000L,
            workRef = "work:half",
        )
        val olderFinished = actionAnchor()
        val pool = listOf(actionAnchorRow(), actionCandidate())
        val row = selectForYou(
            watched = listOf(newerHalf, olderFinished),
            favorites = emptyList(),
            followed = emptyList(),
            pool = pool,
            excluded = listOf(ForYouWork("work:hero", "Action Hero", 2024)),
            kidsMode = false,
        )
        assertEquals("Because you watched Action Hero", row?.subtitle)
        assertEquals(listOf("Action Two"), row?.items?.map { it.title })
    }

    @Test
    fun `recency breaks ties inside a bucket`() {
        val newerFinished = tile(
            watched = post(id = 8, title = "New Finished", year = 2025),
            positionMs = 95_000L,
            durationMs = 100_000L,
            workRef = "work:new",
        )
        val pool = listOf(
            post(id = 8, title = "New Finished", genres = "Drama", year = 2025, workRef = "work:new"),
            actionAnchorRow(),
            post(id = 3, title = "Drama Two", genres = "Drama", year = 2025),
        )
        val row = selectForYou(
            watched = listOf(newerFinished, actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = pool,
            excluded = listOf(
                ForYouWork("work:new", "New Finished", 2025),
                ForYouWork("work:hero", "Action Hero", 2024),
            ),
            kidsMode = false,
        )
        assertEquals("Because you watched New Finished", row?.subtitle)
        assertEquals(listOf("Drama Two"), row?.items?.map { it.title })
    }

    @Test
    fun `junk genre tokens never match`() {
        assertTrue(knownTasteGenres(post(id = 1, title = "X", genres = "Hindi Dub")).isEmpty())
        assertEquals(
            listOf("action", "drama"),
            knownTasteGenres(post(id = 1, title = "X", genres = "Action, NETFLIX, Drama")),
        )
        // The anchor's only genre is junk, so nothing can match it.
        val junkAnchor = tile(
            watched = post(id = 1, title = "Dub Nights", year = 2024),
            positionMs = 95_000L,
            durationMs = 100_000L,
            workRef = "work:junk",
        )
        val pool = listOf(
            post(id = 1, title = "Dub Nights", genres = "Hindi Dub", year = 2024, workRef = "work:junk"),
            post(id = 2, title = "Dub Days", genres = "Hindi Dub", year = 2025),
        )
        assertNull(
            selectForYou(
                watched = listOf(junkAnchor),
                favorites = emptyList(),
                followed = emptyList(),
                pool = pool,
                excluded = listOf(ForYouWork("work:junk", "Dub Nights", 2024)),
                kidsMode = false,
            ),
        )
    }

    @Test
    fun `language alone does not qualify`() {
        val anchor = pickAnchor(
            watched = listOf(actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = listOf(actionAnchorRow()),
            kidsMode = false,
        )!!
        val sameLanguageOnly = post(
            id = 2,
            title = "Quiet English Film",
            language = "English",
            year = 2025,
        )
        assertEquals(2, scoreCandidate(sameLanguageOnly, anchor))
        assertNull(
            selectForYou(
                watched = listOf(actionAnchor()),
                favorites = emptyList(),
                followed = emptyList(),
                pool = listOf(actionAnchorRow(), sameLanguageOnly),
                excluded = listOf(ForYouWork("work:hero", "Action Hero", 2024)),
                kidsMode = false,
            ),
        )
    }

    @Test
    fun `one shared genre qualifies`() {
        val anchor = pickAnchor(
            watched = listOf(actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = listOf(actionAnchorRow()),
            kidsMode = false,
        )!!
        // "Brave Nights" shares no title word with "Action Hero", so the
        // score is the one genre alone.
        assertEquals(3, scoreCandidate(post(id = 2, title = "Brave Nights", genres = "Action", year = 2025), anchor))
    }

    @Test
    fun `title-token franchise match qualifies without a genre`() {
        // The anchor is not on any rail: title tokens are all it lends.
        val anchorTile = tile(
            watched = post(id = 99, title = "One Piece", year = 2023),
            positionMs = 95_000L,
            durationMs = 100_000L,
            workRef = "work:piece",
        )
        val pool = listOf(post(id = 2, title = "One Piece Film Red", year = 2022))
        val row = selectForYou(
            watched = listOf(anchorTile),
            favorites = emptyList(),
            followed = emptyList(),
            pool = pool,
            excluded = listOf(ForYouWork("work:piece", "One Piece", 2023)),
            kidsMode = false,
        )
        assertEquals("Because you watched One Piece", row?.subtitle)
        assertEquals(listOf("One Piece Film Red"), row?.items?.map { it.title })
    }

    @Test
    fun `excludes watched saved hero and earlier rails`() {
        val pool = listOf(
            actionAnchorRow(),
            post(id = 3, title = "Watched Twin", genres = "Action", year = 2025, workRef = "work:twin"),
            post(id = 4, title = "Saved Twin", genres = "Action", year = 2025),
            post(id = 5, title = "Hero Twin", genres = "Action", year = 2025),
            post(id = 6, title = "Earlier Twin", genres = "Action", year = 2025),
            actionCandidate(id = 7, title = "Fresh Action"),
        )
        val watchedTwin = tile(
            watched = post(id = 3, title = "Watched Twin", year = 2025),
            positionMs = 95_000L,
            durationMs = 100_000L,
            workRef = "work:twin",
        )
        val row = selectForYou(
            watched = listOf(actionAnchor(), watchedTwin),
            favorites = listOf(favorite(id = 4, title = "Saved Twin")),
            followed = emptyList(),
            pool = pool,
            excluded = listOf(
                ForYouWork("work:hero", "Action Hero", 2024),
                ForYouWork("work:twin", "Watched Twin", 2025),
                ForYouWork(null, "Saved Twin", null, postId = 4),
                ForYouWork(null, "Hero Twin", 2025),
                ForYouWork(null, "Earlier Twin", 2025),
            ),
            kidsMode = false,
        )
        assertEquals(listOf("Fresh Action"), row?.items?.map { it.title })
    }

    @Test
    fun `cap is ten and strongest first`() {
        val pool = buildList {
            add(actionAnchorRow())
            repeat(12) { i ->
                add(
                    post(
                        id = 10 + i,
                        title = "Action $i",
                        genres = if (i == 0) "Action, Drama" else "Action",
                        year = 2025,
                    ),
                )
            }
        }
        val row = selectForYou(
            watched = listOf(actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = pool,
            excluded = listOf(ForYouWork("work:hero", "Action Hero", 2024)),
            kidsMode = false,
        )
        assertEquals(10, row?.items?.size)
        // The two-genre pick outranks the one-genre picks.
        assertEquals("Action 0", row?.items?.first()?.title)
    }

    @Test
    fun `kids mode drops an adult anchor and filters candidates`() {
        val adultAnchor = tile(
            watched = post(id = 1, title = "Horror Night", year = 2024),
            positionMs = 95_000L,
            durationMs = 100_000L,
            workRef = "work:horror",
        )
        val pool = listOf(
            post(id = 1, title = "Horror Night", genres = "Horror", year = 2024, workRef = "work:horror"),
            post(id = 2, title = "Horror Two", genres = "Horror", year = 2025),
        )
        assertNull(
            selectForYou(
                watched = listOf(adultAnchor),
                favorites = emptyList(),
                followed = emptyList(),
                pool = pool,
                excluded = listOf(ForYouWork("work:horror", "Horror Night", 2024)),
                kidsMode = true,
            ),
        )
        // "Family Two" shares the Family genre; "Scary Toon" shares the
        // anchor's language and the "toon" word, so it scores in adult
        // mode — but its only genre is Horror, so kids mode filters it.
        val kidAnchor = tile(
            watched = post(id = 1, title = "Family Toon", year = 2024),
            positionMs = 95_000L,
            durationMs = 100_000L,
            workRef = "work:toon",
        )
        val kidPool = listOf(
            post(id = 1, title = "Family Toon", genres = "Animation, Family", language = "English", year = 2024, workRef = "work:toon"),
            post(id = 2, title = "Family Two", genres = "Family", language = "English", year = 2025),
            post(id = 3, title = "Scary Toon", genres = "Horror", language = "English", year = 2025),
        )
        val row = selectForYou(
            watched = listOf(kidAnchor),
            favorites = emptyList(),
            followed = emptyList(),
            pool = kidPool,
            excluded = listOf(ForYouWork("work:toon", "Family Toon", 2024)),
            kidsMode = true,
        )
        assertEquals(listOf("Family Two"), row?.items?.map { it.title })
        val adultRow = selectForYou(
            watched = listOf(kidAnchor),
            favorites = emptyList(),
            followed = emptyList(),
            pool = kidPool,
            excluded = listOf(ForYouWork("work:toon", "Family Toon", 2024)),
            kidsMode = false,
        )
        assertEquals(listOf("Family Two", "Scary Toon"), adultRow?.items?.map { it.title })
    }

    @Test
    fun `favorite anchors as saved and followed as follow`() {
        val pool = listOf(
            post(id = 4, title = "Saved Drama", genres = "Drama", year = 2024, workRef = "work:saved"),
            post(id = 5, title = "Drama Two", genres = "Drama", year = 2025),
        )
        val saved = selectForYou(
            watched = emptyList(),
            favorites = listOf(favorite(id = 4, title = "Saved Drama", workRef = "work:saved")),
            followed = emptyList(),
            pool = pool,
            excluded = listOf(ForYouWork("work:saved", "Saved Drama", 2024)),
            kidsMode = false,
        )
        assertEquals("Because you saved Saved Drama", saved?.subtitle)
        assertEquals(listOf("Drama Two"), saved?.items?.map { it.title })

        val followPool = listOf(
            post(id = 6, title = "Followed Saga", genres = "Fantasy", year = 2024, workRef = "work:saga"),
            post(id = 7, title = "Fantasy Two", genres = "Fantasy", year = 2025),
        )
        val followedRow = selectForYou(
            watched = emptyList(),
            favorites = emptyList(),
            followed = listOf(followed(workRef = "work:saga", title = "Followed Saga")),
            pool = followPool,
            excluded = listOf(ForYouWork("work:saga", "Followed Saga", 2024)),
            kidsMode = false,
        )
        assertEquals("Because you follow Followed Saga", followedRow?.subtitle)
        assertEquals(listOf("Fantasy Two"), followedRow?.items?.map { it.title })
    }

    @Test
    fun `rating adds a point but a missing rating never penalises`() {
        val anchor = pickAnchor(
            watched = listOf(actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = listOf(actionAnchorRow()),
            kidsMode = false,
        )!!
        val rated = actionCandidate().copy(imdbRating = "8.5")
        val unrated = actionCandidate()
        val junkRated = actionCandidate().copy(imdbRating = "junk")
        // Base 5: one shared genre (+3) plus the shared "action" title
        // word (+2). The real rating adds one; a missing or junk rating
        // adds nothing and never penalises.
        assertEquals(6, scoreCandidate(rated, anchor))
        assertEquals(5, scoreCandidate(unrated, anchor))
        assertEquals(5, scoreCandidate(junkRated, anchor))
    }

    @Test
    fun `bare title excludes only when the work is unidentifiable`() {
        // Watched "Marco" with no ref and no year: the bare title excludes
        // a dateless "Marco", but never a dated one — same title + same
        // year can be two different films (the Marco rule).
        val pool = listOf(
            actionAnchorRow(),
            post(id = 2, title = "Marco", genres = "Action", year = null),
            post(id = 3, title = "Marco", genres = "Action", year = 2022),
            actionCandidate(id = 4, title = "Action Two"),
        )
        val row = selectForYou(
            watched = listOf(actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = pool,
            excluded = listOf(
                ForYouWork("work:hero", "Action Hero", 2024),
                ForYouWork(null, "Marco", null),
            ),
            kidsMode = false,
        )
        // "Action Two" outscores the dated "Marco" (shared title word on
        // top of the genre), so it leads; the dateless "Marco" is excluded.
        assertEquals(listOf("Action Two", "Marco"), row?.items?.map { it.title })
    }

    @Test
    fun `anchor buckets order finished before started`() {
        assertEquals(0, anchorBucket(95_000L, 100_000L))
        assertEquals(1, anchorBucket(50_000L, 100_000L))
        assertEquals(2, anchorBucket(20_000L, 100_000L))
        assertEquals(3, anchorBucket(1_000L, 100_000L))
        // Unknown duration reads as halfway, never finished.
        assertEquals(1, anchorBucket(600_000L, 0L))
    }

    @Test
    fun `row goes after Because you watch, else after Recently added`() {
        val because = HomeSection(key = "because_you_watch", title = "Because you watch", type = null)
        val recent = HomeSection(key = "recent", title = "Recently added", type = null)
        val movie = HomeSection(key = "movie", title = "Movies", type = "movie")
        val row = HomeSection(key = FOR_YOU_KEY, title = FOR_YOU_TITLE, type = null, forYou = true)
        val placed = insertForYouSection(listOf(recent, because, movie), row)
        assertEquals(listOf("recent", "because_you_watch", "for_you", "movie"), placed.map { it.key })
        val withoutBecause = insertForYouSection(listOf(recent, movie), row)
        assertEquals(listOf("recent", "for_you", "movie"), withoutBecause.map { it.key })
        val bare = insertForYouSection(listOf(movie), row)
        assertEquals(listOf("movie", "for_you"), bare.map { it.key })
        assertEquals(listOf("movie"), insertForYouSection(listOf(movie, row), null).map { it.key })
    }

    @Test
    fun `forYou rail has no Browse equivalent and is never cached`() {
        assertFalse(HomeSection(key = FOR_YOU_KEY, title = FOR_YOU_TITLE, type = null, forYou = true).browseEquivalent)
        assertTrue(HomeSection(key = "movie", title = "Movies", type = "movie").browseEquivalent)
        val forYou = HomeSection(key = FOR_YOU_KEY, title = FOR_YOU_TITLE, type = null, forYou = true)
        val normal = HomeSection(key = "movie", title = "Movies", type = "movie")
        assertEquals(listOf("movie"), cachedSectionsOf(listOf(forYou, normal)).map { it.key })
    }

    @Test
    fun `match percentage scales the score from 65 to 99`() {
        assertEquals(65, matchPercentage(3))
        assertEquals(99, matchPercentage(17))
        assertEquals(82, matchPercentage(10))
        // Clamped both ways: a feedback bonus past the top still reads
        // 99%, and a score below the floor still reads 65%.
        assertEquals(99, matchPercentage(19))
        assertEquals(65, matchPercentage(0))
    }

    @Test
    fun `row carries a match score per pick`() {
        val row = selectForYou(
            watched = listOf(actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = listOf(actionAnchorRow(), actionCandidate()),
            excluded = listOf(ForYouWork("work:hero", "Action Hero", 2024)),
            kidsMode = false,
        )
        // "Action Two": one shared genre (+3) and the shared "action"
        // title word (+2) = 5, which reads 69%.
        assertEquals(mapOf(2 to 69), row?.matchScores)
    }

    @Test
    fun `a down-vote vetoes its work from the row`() {
        val downVoted = actionCandidate(id = 2, title = "Action Two")
            .copy(workRef = "work:two")
        assertNull(
            selectForYou(
                watched = listOf(actionAnchor()),
                favorites = emptyList(),
                followed = emptyList(),
                pool = listOf(actionAnchorRow(), downVoted),
                excluded = listOf(ForYouWork("work:hero", "Action Hero", 2024)),
                kidsMode = false,
                feedback = mapOf("work:two" to -1),
            ),
        )
    }

    @Test
    fun `an up-vote lends its genres to the ranker`() {
        val anchor = pickAnchor(
            watched = listOf(actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = listOf(actionAnchorRow()),
            kidsMode = false,
        )!!
        val liked = post(id = 9, title = "Comedy Gold", genres = "Comedy", year = 2024, workRef = "work:gold")
        val candidate = post(id = 2, title = "Comedy Two", genres = "Comedy", language = "English", year = 2025)
        // Without the vote the candidate scores 2 (shared language only)
        // and never qualifies; with it the shared Comedy genre pays +2.
        assertEquals(2, scoreCandidate(candidate, anchor))
        val signals = feedbackSignals(mapOf("work:gold" to 1), listOf(liked))
        assertEquals(4, scoreCandidate(candidate, anchor, signals))
        val row = selectForYou(
            watched = listOf(actionAnchor()),
            favorites = emptyList(),
            followed = emptyList(),
            pool = listOf(actionAnchorRow(), liked, candidate),
            excluded = listOf(ForYouWork("work:hero", "Action Hero", 2024)),
            kidsMode = false,
            feedback = mapOf("work:gold" to 1),
        )
        assertEquals(listOf("Comedy Two"), row?.items?.map { it.title })
        assertEquals(mapOf(2 to 67), row?.matchScores)
    }

    @Test
    fun `match score store holds the row until it is cleared`() {
        MatchScoreStore.set(mapOf(2 to 69, 5 to 82))
        assertEquals(69, MatchScoreStore.get(2))
        assertEquals(82, MatchScoreStore.get(5))
        assertNull(MatchScoreStore.get(9))
        MatchScoreStore.clear()
        assertNull(MatchScoreStore.get(2))
    }

    // REA #4 — Play Something: the shuffle draws from the "For you" row,
    // weighted by Match %, never repeats the last pick while the pool has
    // more, and never offers a shuffle with nothing behind it.

    private fun forYouSection(vararg picks: Pair<Post, Int>) = HomeSection(
        key = FOR_YOU_KEY,
        title = FOR_YOU_TITLE,
        type = null,
        items = picks.map { it.first },
        matchScores = picks.toMap().mapKeys { it.key.id },
    )

    @Test
    fun `play something returns null without a row`() {
        assertNull(pickPlaySomething(null))
        assertNull(
            pickPlaySomething(
                HomeSection(key = FOR_YOU_KEY, title = FOR_YOU_TITLE, type = null),
            ),
        )
    }

    @Test
    fun `play something always picks the only title`() {
        val only = post(id = 1, title = "Only One")
        val section = forYouSection(only to 97)
        assertEquals(only, pickPlaySomething(section, Random(0)))
        // A one-title pool has nothing else to offer and repeats honestly.
        assertEquals(only, pickPlaySomething(section, Random(0), lastPickedPostId = 1))
    }

    @Test
    fun `play something never repeats the last pick while the pool has more`() {
        val first = post(id = 1, title = "First")
        val second = post(id = 2, title = "Second")
        val section = forYouSection(first to 90, second to 90)
        repeat(20) { seed ->
            assertEquals(
                second,
                pickPlaySomething(section, Random(seed.toLong()), lastPickedPostId = 1),
            )
        }
    }

    @Test
    fun `play something weights by match percent`() {
        val heavy = post(id = 1, title = "Heavy")
        val light = post(id = 2, title = "Light")
        val section = forYouSection(heavy to 99, light to 1)
        val random = Random(42)
        val picks = (1..200).map { pickPlaySomething(section, random) }
        val heavyWins = picks.count { it == heavy }
        assertTrue(
            "a 99% title should win the large majority of draws, won $heavyWins/200",
            heavyWins > 160,
        )
    }

    @Test
    fun `play something keeps scoreless titles eligible`() {
        val first = post(id = 1, title = "First")
        val second = post(id = 2, title = "Second")
        val section = HomeSection(
            key = FOR_YOU_KEY,
            title = FOR_YOU_TITLE,
            type = null,
            items = listOf(first, second),
            matchScores = emptyMap(),
        )
        // No scores at all: both titles still draw — a missing score is a
        // weight of 1, never an exclusion.
        val picks = (1..50).map { pickPlaySomething(section, Random(it.toLong())) }.toSet()
        assertEquals(setOf(first, second), picks)
    }

    @Test
    fun `the pool store holds the row for the give-up card until it clears`() {
        val first = post(id = 1, title = "First")
        val second = post(id = 2, title = "Second")
        val section = forYouSection(first to 90, second to 90)
        ForYouPoolStore.set(section)
        assertTrue(ForYouPoolStore.hasPicks())
        // Two picks in a row never repeat while the pool has more than one.
        val one = ForYouPoolStore.pick(Random(7))!!
        val two = ForYouPoolStore.pick(Random(7))!!
        assertTrue(one != two)
        ForYouPoolStore.set(null)
        assertFalse(ForYouPoolStore.hasPicks())
        assertNull(ForYouPoolStore.pick())
    }
}
