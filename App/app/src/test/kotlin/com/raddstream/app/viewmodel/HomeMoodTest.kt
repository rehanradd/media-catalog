package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H9 (2027 Home fix, round 2): the mood filters. The predicates are
 * honest heuristics over fields the list row actually carries — genres,
 * animation flag, content type — never a claim about data the row does
 * not have (no duration field exists on the list wire, so "short" is the
 * type — a film is one sitting — not a runtime claim).
 */
class HomeMoodTest {

    private fun post(
        title: String = "Title",
        contentType: String? = "movie",
        genres: String? = null,
        isAnimated: Boolean? = null,
    ) = Post(
        id = 1,
        title = title,
        cleanTitle = title,
        year = 2024,
        contentType = contentType,
        genres = genres,
        isAnimated = isAnimated,
    )

    @Test
    fun noMoodMatchesEverything() {
        val movie = post()
        val series = post(contentType = "series")
        assertTrue(moodMatchesPost(movie, null))
        assertTrue(moodMatchesPost(series, null))
    }

    @Test
    fun shortMeansAMovieNotASeries() {
        assertTrue(moodMatchesPost(post(contentType = "movie"), HomeMood.Short))
        assertFalse(moodMatchesPost(post(contentType = "series"), HomeMood.Short))
        assertFalse(moodMatchesPost(post(contentType = "complete_season"), HomeMood.Short))
        assertFalse(moodMatchesPost(post(contentType = "ongoing_season"), HomeMood.Short))
        assertFalse(moodMatchesPost(post(contentType = null), HomeMood.Short))
    }

    @Test
    fun feelGoodMatchesComedyFamilyRomanceAnimation() {
        assertTrue(moodMatchesPost(post(genres = "Comedy"), HomeMood.FeelGood))
        assertTrue(moodMatchesPost(post(genres = "Family, Drama"), HomeMood.FeelGood))
        assertTrue(moodMatchesPost(post(genres = "Romance"), HomeMood.FeelGood))
        assertTrue(moodMatchesPost(post(genres = "Animation"), HomeMood.FeelGood))
        // Animation reads as feel-good even when the genre list is silent.
        assertTrue(moodMatchesPost(post(genres = null, isAnimated = true), HomeMood.FeelGood))
        assertFalse(moodMatchesPost(post(genres = "Horror"), HomeMood.FeelGood))
        assertFalse(moodMatchesPost(post(genres = "Action, Thriller"), HomeMood.FeelGood))
    }

    @Test
    fun mindBendingMatchesSciFiThrillerMystery() {
        assertTrue(moodMatchesPost(post(genres = "Sci-Fi"), HomeMood.MindBending))
        assertTrue(moodMatchesPost(post(genres = "Science Fiction"), HomeMood.MindBending))
        assertTrue(moodMatchesPost(post(genres = "Thriller"), HomeMood.MindBending))
        assertTrue(moodMatchesPost(post(genres = "Mystery"), HomeMood.MindBending))
        assertFalse(moodMatchesPost(post(genres = "Comedy"), HomeMood.MindBending))
        assertFalse(moodMatchesPost(post(genres = null), HomeMood.MindBending))
    }

    @Test
    fun darkGrittyMatchesCrimeHorrorThriller() {
        assertTrue(moodMatchesPost(post(genres = "Crime"), HomeMood.DarkGritty))
        assertTrue(moodMatchesPost(post(genres = "Horror"), HomeMood.DarkGritty))
        assertTrue(moodMatchesPost(post(genres = "Thriller"), HomeMood.DarkGritty))
        assertFalse(moodMatchesPost(post(genres = "Comedy"), HomeMood.DarkGritty))
        assertFalse(moodMatchesPost(post(genres = "Romance"), HomeMood.DarkGritty))
    }

    @Test
    fun genreMatchingIsCaseInsensitive() {
        assertTrue(moodMatchesPost(post(genres = "COMEDY"), HomeMood.FeelGood))
        assertTrue(moodMatchesPost(post(genres = "sci-fi"), HomeMood.MindBending))
        assertTrue(moodMatchesPost(post(genres = "HORROR"), HomeMood.DarkGritty))
    }

    @Test
    fun thrillerMatchesBothMoods() {
        // A title can be mind-bending AND dark; the filter narrows, it
        // does not sort into one box.
        val thriller = post(genres = "Thriller")
        assertTrue(moodMatchesPost(thriller, HomeMood.MindBending))
        assertTrue(moodMatchesPost(thriller, HomeMood.DarkGritty))
    }
}
