package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
    fun channelMetaTakesNaturalHeightWithNoBlankSecondLine() {
        // P2-9 (B-40): reverses the old minLines = 2 pin. The reserve
        // bought equal heights with a blank second line under every short
        // meta ("News · Pakistan") — visible gaps, top-heavy cards. Cards
        // now take their natural height and the grid aligns by the fixed
        // 16:9 logo plate, so a one-line meta is one line tall.
        //
        // The pin is metric-independent: the card meta must measure
        // exactly as tall as the same text in the same style with the
        // same bottom padding and maxLines = 1. With the old minLines = 2
        // the card meta would be a full line (~16dp) taller than the
        // reference, far outside the 1px tolerance. No wrapping is
        // asserted here: whether a long triple wraps to two lines is
        // pinned by the Paparazzi populated golden (byte-identical after
        // this change), and Robolectric wrap behaviour is font-metric
        // dependent — the first version of this test asserted a wrap and
        // failed on CI with both metas at 35px, one line (run
        // 45799626946), and the wrap pin that fixup 69356fcc removed
        // died the same way.
        //
        // The tag lives inside Card(onClick = …), a clickable that merges
        // its descendants' semantics into the card node — so the merged
        // tree has no meta node at all (CI 44646400985 found 0 nodes) and
        // the lookup searches the unmerged tree (A4-F2 fixup 3, same tag).
        val short = LiveTvChannel(
            id = 1,
            name = "Geo News",
            category = "news",
            country = "PK",
            language = "Unknown",
            logoUrl = "",
            streamId = "stream-1",
        )
        compose.setContent {
            RaddStreamTheme {
                Column {
                    LiveTvChannelCard(
                        channel = short,
                        onClick = {},
                        modifier = Modifier.width(180.dp),
                    )
                    Text(
                        text = "News · Pakistan",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.width(160.dp).padding(bottom = 10.dp)
                            .testTag("liveTv_metaReference"),
                    )
                }
            }
        }
        val metas = compose.onAllNodesWithTag("liveTv_channelMeta", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, metas.size)
        val references = compose.onAllNodesWithTag("liveTv_metaReference", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, references.size)
        assertEquals(
            references[0].boundsInRoot.height,
            metas[0].boundsInRoot.height,
            1.0f,
        )
    }
}
