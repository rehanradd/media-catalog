package com.raddstream.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MX idea #3 (2026-11-19): the HW/SW decoder mode — its storage contract
 * and its toggle. The persisted name is the enum name, exactly like
 * [ViewMode]; the service, the store and the chip all read the same enum,
 * so there is one spelling of each mode everywhere.
 */
class DecoderModeTest {

    @Test
    fun `stored names round-trip to the mode they name`() {
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromName("HARDWARE"))
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.fromName("SOFTWARE"))
    }

    @Test
    fun `an unknown or absent stored name is hardware, never a guess`() {
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromName(null))
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromName(""))
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromName("hw"))
        assertEquals(DecoderMode.HARDWARE, DecoderMode.fromName("HW_PLUS"))
    }

    @Test
    fun `the toggle flips the mode and flips it back`() {
        assertEquals(DecoderMode.SOFTWARE, DecoderMode.HARDWARE.other())
        assertEquals(DecoderMode.HARDWARE, DecoderMode.SOFTWARE.other())
        assertEquals(DecoderMode.HARDWARE, DecoderMode.HARDWARE.other().other())
    }

    @Test
    fun `hardware is the shipped default`() {
        assertEquals(DecoderMode.HARDWARE, PlayerUiState().decoderMode)
    }
}
