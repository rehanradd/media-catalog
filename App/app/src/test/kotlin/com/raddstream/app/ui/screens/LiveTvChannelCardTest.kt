package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
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
    fun channelSubtitleWrapsToTwoLinesInsteadOfTruncatingMidSegment() {
        // A4-F2: the render half — on a narrow 2-column card the triple
        // wraps to two lines instead of ellipsizing to one. The pin is
        // height, not text: the meta Text is paint-only
        // (clearAndSetSemantics), so the card's spoken sentence carries
        // the words and the tagged node carries the height. A short meta
        // ("News · Urdu") is the one-line control — both go through the
        // same type scale, so the comparison holds at any scale.
        // The tag lives inside Card(onClick = …), a clickable that merges
        // its descendants' semantics into the card node, so the tag is
        // only visible in the unmerged tree — the merged-tree lookup
        // finds nothing (CI 44236763707: "could not find any node").
        val long = LiveTvChannel(
            id = 1,
            name = "Madani Channel",
            category = "religious",
            country = "PK",
            language = "urdu",
            logoUrl = "",
            streamId = "stream-1",
        )
        val short = long.copy(name = "Geo News", category = "news")
        compose.setContent {
            RaddStreamTheme {
                LiveTvChannelCard(channel = short, onClick = {}, modifier = Modifier.width(140.dp))
            }
        }
        val oneLineHeight = compose.onNodeWithTag("liveTv_channelMeta", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height
        compose.setContent {
            RaddStreamTheme {
                LiveTvChannelCard(channel = long, onClick = {}, modifier = Modifier.width(140.dp))
            }
        }
        val twoLineHeight = compose.onNodeWithTag("liveTv_channelMeta", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "Long meta is $twoLineHeight px, one-line control is $oneLineHeight px — expected a wrap to two lines",
            twoLineHeight > oneLineHeight + 1f,
        )
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
}
