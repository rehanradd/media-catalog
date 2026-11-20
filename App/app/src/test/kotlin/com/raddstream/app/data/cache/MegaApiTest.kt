package com.raddstream.app.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MEGA g API client, against a fake transport — no network. The response
 * shapes are the ones the Phase C lane observed live: a JSON object with
 * `g`/`at`/`s` on success, a bare negative integer on failure.
 */
class MegaApiTest {

    private fun apiFor(status: Int, body: String) =
        MegaApi(httpPost = { _, _ -> MegaApi.HttpResult(status, body) })

    @Test
    fun mintOkParsesGUrlAttrsAndSize() {
        val api = apiFor(200, """{"g":"https://gfs206n481.userstorage.mega.co.nz/dl/abc","at":"QUJD","s":944948612,"fa":"0"}""")
        val result = api.mint("78QnEaBK")
        assertTrue(result is MegaApi.MintResult.Ok)
        val mint = (result as MegaApi.MintResult.Ok).mint
        assertEquals("https://gfs206n481.userstorage.mega.co.nz/dl/abc", mint.gUrl)
        assertEquals("QUJD", mint.attrsBase64Url)
        assertEquals(944948612L, mint.size)
    }

    @Test
    fun mintDeadMapsNotFoundAccessAndBanned() {
        assertEquals(MegaApi.MintResult.Dead(-9), apiFor(200, "-9").mint("x"))
        assertEquals(MegaApi.MintResult.Dead(-11), apiFor(200, "-11").mint("x"))
        assertEquals(MegaApi.MintResult.Dead(-16), apiFor(200, "-16").mint("x"))
    }

    @Test
    fun mintQuotaMapsError18AndHttp509() {
        assertEquals(MegaApi.MintResult.Quota, apiFor(200, "-18").mint("x"))
        assertEquals(MegaApi.MintResult.Quota, apiFor(509, "").mint("x"))
    }

    @Test
    fun mintTransportOnHttpErrorsAndUnusableAnswers() {
        assertTrue(apiFor(403, "").mint("x") is MegaApi.MintResult.Transport)
        assertTrue(apiFor(500, "").mint("x") is MegaApi.MintResult.Transport)
        assertTrue(apiFor(200, "{}").mint("x") is MegaApi.MintResult.Transport)
        assertTrue(apiFor(200, """{"g":"https://x/"}""").mint("x") is MegaApi.MintResult.Transport)
        assertTrue(apiFor(200, "not json").mint("x") is MegaApi.MintResult.Transport)
    }

    @Test
    fun mintTransportOnEmptyNodeId() {
        assertTrue(apiFor(200, "{}").mint("") is MegaApi.MintResult.Transport)
        assertTrue(apiFor(200, "{}").mint("   ") is MegaApi.MintResult.Transport)
    }

    @Test
    fun mintTransportWhenTheCallThrows() {
        val api = MegaApi(httpPost = { _, _ -> throw java.io.IOException("no route") })
        assertTrue(api.mint("x") is MegaApi.MintResult.Transport)
    }

    @Test
    fun fieldExtractionHandlesEscapes() {
        val api = MegaApi(httpPost = { _, _ -> MegaApi.HttpResult(200, "{}") })
        assertEquals("https://x/a/b", api.extractStringField("""{"g":"https:\/\/x\/a\/b"}""", "g"))
        assertEquals(-9L, api.extractLongField("""{"s":-9}""", "s"))
        assertEquals(null, api.extractStringField("""{"g":123}""", "g"))
    }
}
