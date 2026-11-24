package com.raddstream.app.ui

import com.raddstream.app.ui.screens.parseTimeToMs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-01 (APP-10): the Play-From dialog crashed on a ~19-digit time —
 * `parseTimeToMs` called unguarded `toLong()` inside the confirm click
 * handler, so a part past Long.MAX_VALUE threw NumberFormatException and
 * took the player down. Every overflow path now returns 0 instead of
 * throwing, and the dialog's text field filters to digits + ':' (capped
 * at 11 chars) so such input cannot even be typed or pasted in.
 *
 * Plain JVM: the parser is pure string arithmetic, no Context, no screen.
 */
class PlayFromTimeTest {

    @Test
    fun `twelve thirty-four is 754 seconds`() {
        assertEquals(754_000L, parseTimeToMs("12:34"))
    }

    @Test
    fun `one hour two minutes three seconds`() {
        assertEquals(3_723_000L, parseTimeToMs("1:02:03"))
    }

    @Test
    fun `a single number is minutes`() {
        assertEquals(300_000L, parseTimeToMs("5"))
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        assertEquals(754_000L, parseTimeToMs(" 12:34 "))
    }

    @Test
    fun `a twenty-digit part does not crash and reads as zero`() {
        assertEquals(0L, parseTimeToMs("99999999999999999999"))
    }

    @Test
    fun `a nineteen-digit part past Long MAX does not crash and reads as zero`() {
        assertEquals(0L, parseTimeToMs("9999999999999999999"))
    }

    @Test
    fun `a nineteen-digit part with a second part does not crash`() {
        assertEquals(0L, parseTimeToMs("9999999999999999999:00"))
    }

    @Test
    fun `a minutes value that fits Long but overflows times sixty thousand does not crash`() {
        assertEquals(0L, parseTimeToMs("153722867280912930"))
    }

    @Test
    fun `an hours value that overflows the hour maths does not crash`() {
        assertEquals(0L, parseTimeToMs("2562047788015215:00:00"))
    }

    @Test
    fun `garbage reads as zero`() {
        assertEquals(0L, parseTimeToMs("abc"))
        assertEquals(0L, parseTimeToMs(""))
        assertEquals(0L, parseTimeToMs("12:ab"))
        assertEquals(0L, parseTimeToMs("1:02:03:04"))
        assertEquals(0L, parseTimeToMs(":"))
    }
}
