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
    fun `plus stays a plus, never a space`() {
        assertEquals(
            "https://cdn.example.com/a+b.mp4",
            Filepress.encodeFilename("https://cdn.example.com/a+b.mp4"),
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
