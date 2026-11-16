package com.raddstream.app.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both POST response shapes plus the server's error vocabulary. */
class FilepressApiParsingTest {

    @Test
    fun `first POST shape parses to the token`() {
        val outcome = Filepress.parseApiResponse(200, """{"status":true,"data":"tok123"}""")
        assertTrue(outcome is Filepress.ApiOutcome.Ok)
        assertEquals("tok123", ((outcome as Filepress.ApiOutcome.Ok).data as JsonValue.Str).value)
    }

    @Test
    fun `second POST shape parses to the link list`() {
        val outcome = Filepress.parseApiResponse(200, """{"status":true,"data":["https://cdn.example.com/a.mp4"]}""")
        assertTrue(outcome is Filepress.ApiOutcome.Ok)
        val data = (outcome as Filepress.ApiOutcome.Ok).data as JsonValue.Arr
        assertEquals("https://cdn.example.com/a.mp4", (data.items[0] as JsonValue.Str).value)
    }

    @Test
    fun `status false returns the data string verbatim`() {
        assertEquals(
            Filepress.ApiOutcome.Fail("METHOD_HIDDEN"),
            Filepress.parseApiResponse(200, """{"status":false,"data":"METHOD_HIDDEN"}"""),
        )
        assertEquals(
            Filepress.ApiOutcome.Fail("INDEX_CLONE_FAILED"),
            Filepress.parseApiResponse(200, """{"status":false,"data":"INDEX_CLONE_FAILED"}"""),
        )
    }

    @Test
    fun `status false falls back to message then api_error`() {
        assertEquals(
            Filepress.ApiOutcome.Fail("something broke"),
            Filepress.parseApiResponse(200, """{"status":false,"message":"something broke"}"""),
        )
        assertEquals(
            Filepress.ApiOutcome.Fail("api_error"),
            Filepress.parseApiResponse(200, """{"status":false}"""),
        )
    }

    @Test
    fun `http statuses map to blocked dead and http notes`() {
        assertEquals(Filepress.ApiOutcome.Fail("blocked:403"), Filepress.parseApiResponse(403, ""))
        assertEquals(Filepress.ApiOutcome.Fail("blocked:429"), Filepress.parseApiResponse(429, ""))
        assertEquals(Filepress.ApiOutcome.Fail("dead:404"), Filepress.parseApiResponse(404, ""))
        assertEquals(Filepress.ApiOutcome.Fail("dead:410"), Filepress.parseApiResponse(410, ""))
        assertEquals(Filepress.ApiOutcome.Fail("http:500"), Filepress.parseApiResponse(500, ""))
        assertEquals(Filepress.ApiOutcome.Fail("http:522"), Filepress.parseApiResponse(522, ""))
    }

    @Test
    fun `bad json and non-object json are bad_json`() {
        assertEquals(Filepress.ApiOutcome.Fail("bad_json"), Filepress.parseApiResponse(200, "not json"))
        assertEquals(Filepress.ApiOutcome.Fail("bad_json"), Filepress.parseApiResponse(200, "[1,2]"))
        assertEquals(Filepress.ApiOutcome.Fail("bad_json"), Filepress.parseApiResponse(200, ""))
    }
}
