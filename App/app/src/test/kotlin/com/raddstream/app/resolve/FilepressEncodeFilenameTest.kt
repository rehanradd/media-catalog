package com.raddstream.app.resolve

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors the server's `_filepress_encode_filename`. */
class FilepressEncodeFilenameTest {

    @Test
    fun `spaces and brackets in the last segment are encoded`() {
        assertEquals(
            "https://cdn.example.com/a b/c%20d%20%5B1%5D.mp4",
            Filepress.encodeFilename("https://cdn.example.com/a b/c d [1].mp4"),
        )
    }

    @Test
    fun `already-encoded segment is not double-encoded`() {
        assertEquals(
            "https://cdn.example.com/my%20file.mp4",
            Filepress.encodeFilename("https://cdn.example.com/my%20file.mp4"),
        )
    }

    @Test
    fun `plus is encoded as percent-2B like the server encodes it`() {
        // urllib quote(safe=".-_~") encodes '+' as %2B. The "plus stays a
        // plus" rule is on the DECODE side: unquote never turns '+' into a
        // space (that is unquote_plus) — so an already-encoded %2B decodes
        // to '+' and re-encodes to %2B, never to a space.
        assertEquals(
            "https://cdn.example.com/a%2Bb.mp4",
            Filepress.encodeFilename("https://cdn.example.com/a+b.mp4"),
        )
        assertEquals(
            "https://cdn.example.com/a%2Bb.mp4",
            Filepress.encodeFilename("https://cdn.example.com/a%2Bb.mp4"),
        )
    }

    @Test
    fun `safe chars pass through and unicode is utf-8 encoded`() {
        assertEquals(
            "https://cdn.example.com/a-b_c~d.mp4",
            Filepress.encodeFilename("https://cdn.example.com/a-b_c~d.mp4"),
        )
        assertEquals(
            "https://cdn.example.com/caf%C3%A9.mp4",
            Filepress.encodeFilename("https://cdn.example.com/café.mp4"),
        )
    }

    @Test
    fun `no slash or empty input is unchanged`() {
        assertEquals("plain.mp4", Filepress.encodeFilename("plain.mp4"))
        assertEquals("", Filepress.encodeFilename(""))
    }
}
