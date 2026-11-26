package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveTvChannelCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun channelMetaLineIncludesLanguageAndHidesUnknown() {
        val channel = LiveTvChannel(
            id = 1,
            name = "Geo News",
            category = "news",
            country = "PK",
            language = "urdu",
            logoUrl = "",
            streamId = "stream-1",
        )
        assertEquals("News · Pakistan · Urdu", channelMetaLine(channel))

        val unknownLang = channel.copy(language = "Unknown")
        assertEquals("News · Pakistan", channelMetaLine(unknownLang))

        val duplicateValue = channel.copy(country = "Pakistan", language = "pakistan")
        assertEquals("News · Pakistan", channelMetaLine(duplicateValue))

        // Live TV review (Nov 2026): the registry writes two-letter codes
        // ("ur"), not words — the meta line spells the language out.
        val codeLang = channel.copy(language = "ur")
        assertEquals("News · Pakistan · Urdu", channelMetaLine(codeLang))
    }

    @Test
    fun channelMetaLineKeepsTheFullTripleForReligiousPakistanUrdu() {
        // A4-F2: the triple that was truncated mid-segment on the card
        // ("Religious · Pakistan · Ur…") — the data half of the fix: the
        // line itself is built whole, and the card below wraps it to two
        // lines instead of cutting it to one.
        val channel = LiveTvChannel(
            id = 1,
            name = "Madani Channel",
            category = "religious",
            country = "PK",
            language = "urdu",
            logoUrl = "",
            streamId = "stream-1",
        )
        assertEquals("Religious · Pakistan · Urdu", channelMetaLine(channel))
    }

    @Test
    fun channelMetaLineHidesAnUnknownCountry() {
        // "Unknown" labels (Nov 2026): the registry can carry the literal
        // word "Unknown" where a channel's country was never recorded — a
        // missing value, not a country — so the meta line drops the segment
        // instead of reading "News · Unknown · Urdu".
        val channel = LiveTvChannel(
            id = 1,
            name = "Geo News",
            category = "news",
            country = "Unknown",
            language = "urdu",
            logoUrl = "",
            streamId = "stream-1",
        )
        assertEquals("News · Urdu", channelMetaLine(channel))
        assertEquals("News · Urdu", channelMetaLine(channel.copy(country = "N/A")))
        assertEquals("News · Pakistan · Urdu", channelMetaLine(channel.copy(country = "PK")))
    }

    @Test
    fun countryLabelDropsPlaceholdersButPassesUnknownCodesThrough() {
        assertNull(countryLabel("Unknown"))
        assertNull(countryLabel("unknown"))
        assertNull(countryLabel("N/A"))
        assertNull(countryLabel(null))
        assertNull(countryLabel("  "))
        assertEquals("Pakistan", countryLabel("PK"))
        assertEquals("xx", countryLabel("xx"))
    }

    @Test
    fun languageLabelMapsCodesAndPassesUnknownThrough() {
        // Live TV review (Nov 2026): mirrors countryLabel — known codes
        // map to names, unknown codes pass through untouched, never guessed.
        assertEquals("Urdu", languageLabel("ur"))
        assertEquals("Urdu", languageLabel("urdu"))
        assertEquals("Urdu", languageLabel("Urdu"))
        assertEquals("Hindi", languageLabel("hi"))
        assertEquals("English", languageLabel("en"))
        assertEquals("Punjabi", languageLabel("pa"))
        assertEquals("Tamil", languageLabel("ta"))
        assertEquals("Telugu", languageLabel("te"))
        assertEquals("Bengali", languageLabel("bn"))
        assertEquals("Malayalam", languageLabel("ml"))
        assertEquals("xx", languageLabel("xx"))
        assertEquals(null, languageLabel(null))
        assertEquals(null, languageLabel("  "))
    }

    @Test
    fun liveTvChannelCardAnnouncesLanguageInSemantics() {
        val channel = LiveTvChannel(
            id = 2,
            name = "ARY Digital",
            category = "entertainment",
            country = "PK",
            language = "Urdu",
            logoUrl = "",
            streamId = "stream-2",
        )
        compose.setContent {
            RaddStreamTheme {
                LiveTvChannelCard(
                    channel = channel,
                    onClick = {},
                    modifier = Modifier.width(180.dp),
                )
            }
        }
        compose.onNode(
            hasContentDescription("ARY Digital, live · Entertainment · Pakistan · Urdu"),
        ).assertExists()
    }

    @Test
    fun channelMetaTakesNaturalHeightWithNoBlankSecondLine() {
        // P2-9 (B-40): reverses the old minLines = 2 pin. The reserve
        // bought equal heights with a blank second line under every short
        // meta ("News · Pakistan") — visible gaps, top-heavy cards. Cards
        // now take their natural height and the grid aligns by the fixed
        // 16:9 logo plate, so a one-line meta is one line tall and a long
        // triple still wraps to two lines under maxLines = 2.
        //
        // The tag lives inside Card(onClick = …), a clickable that merges
        // its descendants' semantics into the card node — so the merged
        // tree has no meta node at all (CI 44646400985 found 0 nodes) and
        // the lookup searches the unmerged tree (A4-F2 fixup 3, same tag).
        // The long meta is deliberately long ("Entertainment · Saudi
        // Arabia · Portuguese") so it wraps to two lines at 180dp under
        // any font metric, while the short meta fits on one.
        val short = LiveTvChannel(
            id = 1,
            name = "Geo News",
            category = "news",
            country = "PK",
            language = "Unknown",
            logoUrl = "",
            streamId = "stream-1",
        )
        val long = LiveTvChannel(
            id = 2,
            name = "Hum TV",
            category = "entertainment",
            country = "SA",
            language = "portuguese",
            logoUrl = "",
            streamId = "stream-2",
        )
        compose.setContent {
            RaddStreamTheme {
                Row {
                    LiveTvChannelCard(
                        channel = short,
                        onClick = {},
                        modifier = Modifier.width(180.dp),
                    )
                    LiveTvChannelCard(
                        channel = long,
                        onClick = {},
                        modifier = Modifier.width(180.dp),
                    )
                }
            }
        }
        val metas = compose.onAllNodesWithTag("liveTv_channelMeta", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(2, metas.size)
        val shortHeight = metas[0].boundsInRoot.height
        val longHeight = metas[1].boundsInRoot.height
        assertTrue(
            "Short meta ($shortHeight px) is not shorter than the long meta ($longHeight px) — " +
                "the blank second line is still reserved",
            shortHeight < longHeight,
        )
        assertTrue(
            "Long meta ($longHeight px) is taller than two lines of the short meta ($shortHeight px) — " +
                "maxLines = 2 is not capping it",
            longHeight < 2.5f * shortHeight,
        )
    }
}
