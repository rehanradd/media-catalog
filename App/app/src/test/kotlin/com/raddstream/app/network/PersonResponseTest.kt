package com.raddstream.app.network

import com.raddstream.app.network.models.PersonResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 cast (Nov 2026): one JSON test per new data class — the person
 * payload from `GET /api/v1/person`, decoded with the app's own Gson
 * (see `ModelsTest` for the pattern). The 404 envelope decodes to
 * success:false with no data, so the repository can map it to null
 * without ever inventing a photo.
 */
class PersonResponseTest {

    private val gson = buildRaddGson()

    @Test
    fun personDecodes() {
        val json = """
            {
              "success": true,
              "data": {
                "tmdb_person_id": 10990,
                "name": "Shah Rukh Khan",
                "photo_url": "https://image.tmdb.org/t/p/w185/abc123.jpg",
                "biography": "Indian actor.",
                "birthday": "1965-11-02",
                "place_of_birth": "New Delhi, India",
                "known_for": [
                  {"title": "Jawan", "media_type": "movie", "year": 2023},
                  {"title": "Pathaan", "media_type": "movie", "year": 2023},
                  {"title": "Untitled", "media_type": "movie", "year": null}
                ]
              }
            }
        """.trimIndent()
        val response = gson.fromJson(json, PersonResponse::class.java)
        assertTrue(response.success)
        val person = response.data!!
        assertEquals(10990, person.tmdbPersonId)
        // The canonical name is what the works search uses — it must
        // survive the wire exactly, not the raw scraped string.
        assertEquals("Shah Rukh Khan", person.name)
        assertEquals("https://image.tmdb.org/t/p/w185/abc123.jpg", person.photoUrl)
        assertEquals("1965-11-02", person.birthday)
        assertEquals("New Delhi, India", person.placeOfBirth)
        assertEquals(3, person.knownFor.size)
        assertEquals("Jawan", person.knownFor[0].title)
        assertEquals(2023, person.knownFor[0].year)
        assertNull(person.knownFor[2].year)
    }

    @Test
    fun personWithoutPhotoDecodes() {
        // A person TMDb knows but holds no profile photo for: null photo,
        // empty bio, no credits. The screen renders an initials circle
        // for this — the null must survive, never become a wrong URL.
        // (This is NOT "Various Artist" — U2 measured that name as no
        // person at all, i.e. the 404 envelope below.)
        val json = """
            {
              "success": true,
              "data": {
                "tmdb_person_id": 999999,
                "name": "Test Person",
                "photo_url": null,
                "biography": "",
                "birthday": null,
                "place_of_birth": null,
                "known_for": []
              }
            }
        """.trimIndent()
        val person = gson.fromJson(json, PersonResponse::class.java).data!!
        assertEquals("Test Person", person.name)
        assertNull(person.photoUrl)
        assertEquals("", person.biography)
        assertNull(person.birthday)
        assertTrue(person.knownFor.isEmpty())
    }

    @Test
    fun notFoundEnvelopeDecodes() {
        val json = """{"success": false, "error": "person_not_found"}"""
        val response = gson.fromJson(json, PersonResponse::class.java)
        assertEquals(false, response.success)
        assertEquals("person_not_found", response.error)
        assertNull(response.data)
    }
}
