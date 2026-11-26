package com.raddstream.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
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
 * P2 cast (Nov 2026): the Actor screen + cast row at 1.3x font scale —
 * the configuration that truncates labels (see
 * DetailLargeFontPaparazziTest). The 12sp cast names, the birth line,
 * the bio and the "Known for" rows must wrap or ellipsize cleanly,
 * never clip or overlap. Its OWN class with its OWN single Paparazzi
 * rule: two `@get:Rule` Paparazzi instances in ONE class crash every
 * test. Record with `:app:recordPaparazziDebug` (paparazzi.yml,
 * workflow_dispatch); verify runs as ci.yml's explicit
 * `:app:verifyPaparazziDebug` step.
 */
class ActorLargeFontPaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenHeight = 1280,
            screenWidth = 720,
            // Paparazzi's density is com.android.resources.Density (the
            // enum), not Compose's Density — XHIGH is 320dpi, so 720px
            // renders 360dp wide, the narrow width that truncates.
            density = Density.XHIGH,
            xdpi = 320,
            ydpi = 320,
            fontScale = 1.3f,
        ),
    )

    @Composable
    private fun themed(content: @Composable () -> Unit) {
        RaddStreamTheme(darkTheme = true) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    private val foundState = ActorUiState(
        rawName = "Shah Rukh Khan",
        canonicalName = "Shah Rukh Khan",
        person = Person(
            tmdbPersonId = 10990,
            name = "Shah Rukh Khan",
            photoUrl = "https://image.tmdb.org/t/p/w185/abc123.jpg",
            biography = "Shah Rukh Khan is an Indian actor and film producer who works " +
                "in Hindi films. He has appeared in more than 100 films and earned " +
                "numerous accolades, including 14 Filmfare Awards.",
            birthday = "1965-11-02",
            placeOfBirth = "New Delhi, India",
            knownFor = listOf(PersonKnownFor("Jawan", "movie", 2023)),
        ),
        personState = PersonState.Found,
        works = listOf(
            Post(id = 1, title = "Jawan", year = 2023, contentType = "movie"),
            Post(id = 2, title = "Pathaan", year = 2023, contentType = "movie"),
        ),
        worksLoading = false,
    )

    @Test
    fun actorFoundLargeFont() {
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
    fun castRowLargeFont() {
        paparazzi.snapshot {
            themed {
                CastRow(
                    cast = listOf("Shah Rukh Khan", "Kajol", "Aamir Khan"),
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
