package com.raddstream.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Feel-instant S3c: the FTS `MATCH` query for raw keystrokes — pure, so it
 * is pinned here without a database. The rule is the safety of the whole
 * local search: no operator a viewer types may survive into the query.
 */
class FtsPrefixQueryTest {

    @Test
    fun `a blank query builds nothing`() {
        assertNull(buildFtsPrefixQuery(""))
        assertNull(buildFtsPrefixQuery("   "))
    }

    @Test
    fun `one word becomes one prefix term`() {
        assertEquals("aveng*", buildFtsPrefixQuery("aveng"))
    }

    @Test
    fun `words are ANDed, each prefix-starred`() {
        assertEquals("the* aveng*", buildFtsPrefixQuery("the aveng"))
    }

    @Test
    fun `quotes dashes and parens are split away, never passed through`() {
        // Only punctuation is stripped: "OR" and "1" are letters and digits,
        // so they survive as tokens — the operators around them do not.
        assertEquals("aveng*", buildFtsPrefixQuery("aveng\" -- ("))
        assertEquals("aveng* OR* 1* 1*", buildFtsPrefixQuery("aveng\" OR (1=1) --"))
        assertEquals("spider* man*", buildFtsPrefixQuery("spider-man"))
    }

    @Test
    fun `more than five tokens are capped at five`() {
        assertEquals(
            "one* two* three* four* five*",
            buildFtsPrefixQuery("one two three four five six seven"),
        )
    }

    @Test
    fun `non-latin letters survive the split`() {
        assertEquals("जवान*", buildFtsPrefixQuery("जवान"))
    }
}
