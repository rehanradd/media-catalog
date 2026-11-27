package com.raddstream.app.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `displayUrl wraps an original in wsrv at the tile width as webp`() {
        assertEquals(
            "https://wsrv.nl/?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw780%2Fy9Be0IePdacy1M8FNsIDBz7AdEQ.jpg&w=640&output=webp&q=72",
            PosterUrlPolicy.displayUrl(original, 640),
        )
    }

    @Test
    fun `wsrv width is capped at the TMDB size so a w342 source is never upscaled`() {
        val small = "https://image.tmdb.org/t/p/w342/hTUHZ337JzqPvRXBQ3lu0GBkOvz.jpg"
        assertEquals(
            "https://wsrv.nl/?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw342%2FhTUHZ337JzqPvRXBQ3lu0GBkOvz.jpg&w=342&output=webp&q=72",
            PosterUrlPolicy.displayUrl(small, 640),
        )
    }

    @Test
    fun `wsrv width follows the tile when the source is wider`() {
        val sized = "https://image.tmdb.org/t/p/w500/dDYoLdHvsnvxLRgGe1tEhgeqQn2.jpg"
        assertEquals(
            "https://wsrv.nl/?url=https%3A%2F%2Fimage.tmdb.org%2Ft%2Fp%2Fw500%2FdDYoLdHvsnvxLRgGe1tEhgeqQn2.jpg&w=400&output=webp&q=72",
            PosterUrlPolicy.displayUrl(sized, 400),
        )
    }

    @Test
    fun `displayUrl wraps a neutral upload host and leaves the rest untouched`() {
        val imgur = "https://i.imgur.com/abc123.jpg"
        assertEquals(
            "https://wsrv.nl/?url=https%3A%2F%2Fi.imgur.com%2Fabc123.jpg&w=500&output=webp&q=72",
            PosterUrlPolicy.displayUrl(imgur, 500),
        )
        val art = "https://raddstream.duckdns.org/api/v1/art?id=abc&k=poster&n=0"
        assertEquals(art, PosterUrlPolicy.displayUrl(art, 500))
        val random = "https://some-random-host.net/img.png"
        assertEquals(random, PosterUrlPolicy.displayUrl(random, 500))
    }

    // Video audit issue 10 (Nov 2026): live-TV logos bypass the proxy —
    // imgur rate-limits wsrv's fetcher (429), so a proxied logo paid a
    // ~10 s dead wait before the fallback interceptor retried it direct.
    @Test
    fun `displayUrl with bypassProxy returns the sized direct url`() {
        val imgur = "https://i.imgur.com/abc123.jpg"
        assertEquals(imgur, PosterUrlPolicy.displayUrl(imgur, 500, bypassProxy = true))
        // A TMDB original is still right-sized, just not proxied.
        assertEquals(
            "https://image.tmdb.org/t/p/w780/y9Be0IePdacy1M8FNsIDBz7AdEQ.jpg",
            PosterUrlPolicy.displayUrl(original, 640, bypassProxy = true),
        )
        // And the default stays proxied.
        assertTrue(PosterUrlPolicy.isWsrvUrl(PosterUrlPolicy.displayUrl(imgur, 500)))
    }

    @Test
    fun `displayUrl leaves a non-positive width untouched`() {
        assertEquals(original, PosterUrlPolicy.displayUrl(original, 0))
        assertEquals(original, PosterUrlPolicy.displayUrl(original, -10))
    }

    @Test
    fun `wrapping a wsrv url again is a no-op`() {
        val once = PosterUrlPolicy.displayUrl(original, 640)
        assertEquals(once, PosterUrlPolicy.displayUrl(once, 640))
        assertTrue(PosterUrlPolicy.isWsrvUrl(once))
    }

    @Test
    fun `directUrlOfWsrv recovers the direct url and refuses the rest`() {
        val wrapped = PosterUrlPolicy.displayUrl(original, 640)
        assertEquals(
            "https://image.tmdb.org/t/p/w780/y9Be0IePdacy1M8FNsIDBz7AdEQ.jpg",
            PosterUrlPolicy.directUrlOfWsrv(wrapped),
        )
        assertNull(PosterUrlPolicy.directUrlOfWsrv(original))
        assertNull(PosterUrlPolicy.directUrlOfWsrv("https://wsrv.nl/?w=640&output=webp&q=72"))
        assertFalse(PosterUrlPolicy.isWsrvUrl(original))
    }
}
