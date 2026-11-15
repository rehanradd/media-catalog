package com.raddstream.app.images

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B2 (home review, 2026-11-15): TMDB `/original/` posters are full-size
 * files the app downloads whole and downsamples locally — rewriting the
 * size segment to the smallest TMDB width covering the tile is a pure
 * byte saving. Only originals move; everything else keeps its URL (and
 * its Coil cache key).
 */
class PosterUrlPolicyTest {

    private val original =
        "https://image.tmdb.org/t/p/original/y9Be0IePdacy1M8FNsIDBz7AdEQ.jpg"

    @Test
    fun `original becomes w500 for a 500px tile`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/y9Be0IePdacy1M8FNsIDBz7AdEQ.jpg",
            PosterUrlPolicy.sizedForTile(original, 500),
        )
    }

    @Test
    fun `original becomes w342 for a 300px tile`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w342/y9Be0IePdacy1M8FNsIDBz7AdEQ.jpg",
            PosterUrlPolicy.sizedForTile(original, 300),
        )
    }

    @Test
    fun `default AUTO width 640 becomes w780`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w780/y9Be0IePdacy1M8FNsIDBz7AdEQ.jpg",
            PosterUrlPolicy.sizedForTile(original, 640),
        )
    }

    @Test
    fun `wider than 780 keeps original`() {
        assertEquals(original, PosterUrlPolicy.sizedForTile(original, 1000))
    }

    @Test
    fun `already sized variants are untouched`() {
        val sized = "https://image.tmdb.org/t/p/w500/dDYoLdHvsnvxLRgGe1tEhgeqQn2.jpg"
        assertEquals(sized, PosterUrlPolicy.sizedForTile(sized, 500))
        val small = "https://image.tmdb.org/t/p/w342/hTUHZ337JzqPvRXBQ3lu0GBkOvz.jpg"
        assertEquals(small, PosterUrlPolicy.sizedForTile(small, 500))
    }

    @Test
    fun `non-TMDB hosts are untouched`() {
        val amazon =
            "https://m.media-amazon.com/images/M/MV5BNGYyY2I5MzktMDg2MC00Nzc4LWIwNmYtMjg3NzE1ODQyMDllXkEyXkFqcGc@._SX300.jpg"
        assertEquals(amazon, PosterUrlPolicy.sizedForTile(amazon, 500))
        val art = "https://raddstream.duckdns.org/api/v1/art?id=abc&k=poster&n=0"
        assertEquals(art, PosterUrlPolicy.sizedForTile(art, 500))
    }

    @Test
    fun `a lookalike path on another host is untouched`() {
        val fake = "https://example.com/t/p/original/x.jpg"
        assertEquals(fake, PosterUrlPolicy.sizedForTile(fake, 500))
    }
}
