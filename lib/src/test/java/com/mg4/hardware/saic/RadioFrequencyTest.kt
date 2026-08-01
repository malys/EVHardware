package com.mg4.hardware.saic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A driver types a station, not a transaction argument. What the parser must never do is
 * turn text it did not understand into a frequency anyway.
 */
class RadioFrequencyTest {

    private fun fm(khz: Int) = RadioFrequency.Station(SaicRadio.BAND_FM, khz)
    private fun am(khz: Int) = RadioFrequency.Station(SaicRadio.BAND_AM, khz)

    @Test
    fun `a bare number lands on the only band it can belong to`() {
        assertEquals(fm(103_500), RadioFrequency.parse("103.5"))
        assertEquals(fm(87_500), RadioFrequency.parse("87.5"))
        assertEquals(fm(108_000), RadioFrequency.parse("108"))
        assertEquals(am(1080), RadioFrequency.parse("1080"))
        assertEquals(am(522), RadioFrequency.parse("522"))
    }

    @Test
    fun `the band may be written out, before or after`() {
        assertEquals(fm(103_500), RadioFrequency.parse("FM 103.5"))
        assertEquals(fm(103_500), RadioFrequency.parse("103.5 fm"))
        assertEquals(am(1080), RadioFrequency.parse("1080 AM"))
    }

    @Test
    fun `a decimal comma is a decimal point`() {
        // Five of the six locales this app ships in write it that way.
        assertEquals(fm(103_500), RadioFrequency.parse("103,5"))
    }

    @Test
    fun `kilohertz written out is understood too`() {
        assertEquals(fm(87_500), RadioFrequency.parse("87500"))
    }

    @Test
    fun `text naming no station is refused rather than approximated`() {
        assertNull(RadioFrequency.parse(""))
        assertNull(RadioFrequency.parse("Radio 4"))
        assertNull(RadioFrequency.parse("250"))
        assertNull(RadioFrequency.parse("109.1"))
    }

    @Test
    fun `a band that contradicts its own frequency is refused`() {
        // The driver said two things. Silently keeping one of them is how a rule ends up on
        // a station nobody chose.
        assertNull(RadioFrequency.parse("AM 103.5"))
        assertNull(RadioFrequency.parse("FM 1080"))
    }
}
