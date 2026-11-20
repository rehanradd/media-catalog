package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun channelMetaReservesTwoLinesSoCardHeightsStayEven() {
        // Screen audit #6 (Nov 2026): the audit asked for a one-line
        // clamp, which would re-cut the A4-F2 triple ("Religious ·
        // Pakistan · Ur…"). The fix is the other direction: minLines = 2
        // reserves the second line always, so a short meta ("News ·
        // Pakistan") leaves the same blank second line a long meta fills
        // — uniform card heights, full triple readable. Without minLines
        // the short card's meta is one line tall and the row staggers.
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
            name = "Madani Channel",
            category = "religious",
            country = "PK",
            language = "urdu",
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
        val metas = compose.onAllNodes(hasTestTag("liveTv_channelMeta")).fetchSemanticsNodes()
        assertEquals(2, metas.size)
        assertEquals(metas[0].boundsInRoot.height, metas[1].boundsInRoot.height, 0.01f)
    }
}
