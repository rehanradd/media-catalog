package com.raddstream.app.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.ui.theme.RaddStreamTheme
import org.junit.Assert.assertEquals
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
