package com.raddstream.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.raddstream.app.network.models.Person
import com.raddstream.app.network.models.PersonKnownFor
import com.raddstream.app.network.models.Post
import com.raddstream.app.ui.components.CastRow
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ActorUiState
import com.raddstream.app.viewmodel.PersonState
import org.junit.Rule
import org.junit.Test

/**
 * P2 cast (Nov 2026): the Actor screen + the cast row had no Paparazzi
 * test — the round-2 re-rate would have judged them from code only.
 * These shots render the real [ActorContent] / [CastRow] with fake state
 * (the Profile pattern) so the next re-rate judges renders.
 *
 * Photos never load in Paparazzi (no network): a `photoUrl` renders the
 * branded fallback disc inside the circle, which is exactly the honest
 * loading state — the golden pins the layout, not a downloaded photo.
 * Record with `:app:recordPaparazziDebug` (paparazzi.yml,
 * workflow_dispatch); verify runs as ci.yml's explicit
 * `:app:verifyPaparazziDebug` step. Registered in all three Paparazzi
 * lists (build.gradle.kts include + exclude, paparazzi.yml, ci.yml) —
 * a class missing from any one never records or never verifies.
 */
class ActorPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    /** Dark-first, like the shipped app: RaddStreamTheme defaults to the brand scheme. */
    @Composable
    private fun themed(content: @Composable () -> Unit) {
        RaddStreamTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    private val foundPerson = Person(
        tmdbPersonId = 10990,
        name = "Shah Rukh Khan",
        photoUrl = "https://image.tmdb.org/t/p/w185/abc123.jpg",
        biography = "Shah Rukh Khan is an Indian actor and film producer who works " +
            "in Hindi films. He has appeared in more than 100 films and earned " +
            "numerous accolades, including 14 Filmfare Awards. He has been " +
            "referred to in the media as the \"Badshah of Bollywood\" and " +
            "\"King Khan\". His films have grossed billions worldwide, and he " +
            "remains one of the most recognisable faces of Indian cinema.",
        birthday = "1965-11-02",
        placeOfBirth = "New Delhi, India",
        knownFor = listOf(
            PersonKnownFor("Jawan", "movie", 2023),
            PersonKnownFor("Pathaan", "movie", 2023),
            PersonKnownFor("Dunki", "movie", 2023),
        ),
    )

    private val works = listOf(
        Post(id = 1, title = "Jawan", year = 2023, contentType = "movie"),
        Post(id = 2, title = "Pathaan", year = 2023, contentType = "movie"),
        Post(id = 3, title = "Dunki", year = 2023, contentType = "movie"),
    )

    private val foundState = ActorUiState(
        rawName = "Shah Rukh Khan",
        canonicalName = "Shah Rukh Khan",
        person = foundPerson,
        personState = PersonState.Found,
        works = works,
        worksLoading = false,
    )

    @Test
    fun actorFound() {
        paparazzi.snapshot {
            themed {
                ActorContent(
                    state = foundState,
                    onBackClick = {},
                    onPostClick = {},
                    onOpenSearch = {},
                    onRetryWorks = {},
                )
            }
        }
    }

    @Test
    fun actorNotFound() {
        // TMDb knows nobody by this name ("Various Artist" is the real
        // case: a music pseudo-name, not a person). Initials circle, the
        // no-biography line, no works — and never a wrong photo.
        paparazzi.snapshot {
            themed {
                ActorContent(
                    state = ActorUiState(
                        rawName = "Various Artist",
                        personState = PersonState.NotFound,
                        worksLoading = false,
                    ),
                    onBackClick = {},
                    onPostClick = {},
                    onOpenSearch = {},
                    onRetryWorks = {},
                )
            }
        }
    }

    @Test
    fun actorUnavailable() {
        // TMDb outage (503): the header still renders (initials, raw
        // name, "details unavailable" line) and the works grid still
        // loads by the raw name.
        paparazzi.snapshot {
            themed {
                ActorContent(
                    state = ActorUiState(
                        rawName = "Shah Rukh Khan",
                        personState = PersonState.Unavailable,
                        works = works.take(2),
                        worksLoading = false,
                    ),
                    onBackClick = {},
                    onPostClick = {},
                    onOpenSearch = {},
                    onRetryWorks = {},
                )
            }
        }
    }

    @Test
    fun actorWorksLoading() {
        paparazzi.snapshot {
            themed {
                ActorContent(
                    state = foundState.copy(works = emptyList(), worksLoading = true),
                    onBackClick = {},
                    onPostClick = {},
                    onOpenSearch = {},
                    onRetryWorks = {},
                )
            }
        }
    }

    @Test
    fun castRow() {
        // The redesigned row: photo (fallback disc in Paparazzi), null
        // photo (initials), and a name absent from the map (initials).
        paparazzi.snapshot {
            themed {
                CastRow(
                    cast = listOf(
                        "Shah Rukh Khan",
                        "Kajol",
                        "Aamir Khan",
                        "Deepika Padukone",
                    ),
                    photos = mapOf(
                        "Shah Rukh Khan" to "https://image.tmdb.org/t/p/w185/abc123.jpg",
                        "Kajol" to null,
                    ),
                    onActorClick = {},
                )
            }
        }
    }
}
