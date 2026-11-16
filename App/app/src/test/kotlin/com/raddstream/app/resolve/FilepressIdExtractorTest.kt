package com.raddstream.app.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors the server's `_filepress_extract_id`: 24-hex id, or "". */
class FilepressIdExtractorTest {

    @Test
    fun `filebee file path extracts the id`() {
        assertEquals(
            "646fcb392a2127d45896d91f",
            Filepress.extractId("https://filebee.xyz/file/646fcb392a2127d45896d91f"),
        )
    }

    @Test
    fun `filepress mirrors extract the id`() {
        assertEquals(
            "646fcb392a2127d45896d91f",
            Filepress.extractId("https://new1.filepress.lat/file/646fcb392a2127d45896d91f"),
        )
        assertEquals(
            "646fcb392a2127d45896d91f",
            Filepress.extractId("https://new2.filepress.lat/file/646fcb392a2127d45896d91f/download"),
        )
    }

    @Test
    fun `trailing slash and query still extract`() {
        assertEquals(
            "646fcb392a2127d45896d91f",
            Filepress.extractId("https://filebee.xyz/file/646fcb392a2127d45896d91f/"),
        )
        assertEquals(
            "646fcb392a2127d45896d91f",
            Filepress.extractId("https://filebee.xyz/file/646fcb392a2127d45896d91f?x=1"),
        )
    }

    @Test
    fun `bare 24-hex id extracts`() {
        assertEquals("646fcb392a2127d45896d91f", Filepress.extractId("646fcb392a2127d45896d91f"))
    }

    @Test
    fun `invalid urls return empty`() {
        assertEquals("", Filepress.extractId(""))
        assertEquals("", Filepress.extractId("https://filebee.xyz/file/not-an-id"))
        assertEquals("", Filepress.extractId("https://filebee.xyz/file/646fcb392a2127d45896d91")) // 23 hex
        assertEquals("", Filepress.extractId("https://filebee.xyz/file/646FCB392A2127D45896D91F")) // uppercase
        assertEquals("", Filepress.extractId("https://1fichier.com/?8pced1qb5np5i8bj84cf"))
    }
}
