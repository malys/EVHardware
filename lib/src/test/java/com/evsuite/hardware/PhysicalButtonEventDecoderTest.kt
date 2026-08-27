package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalButtonEventDecoderTest {
    @Test fun `every R69 hardkey code decodes`() {
        val expected = mapOf(
            5 to PhysicalButtonEventDecoder.Button.PHONE,
            6 to PhysicalButtonEventDecoder.Button.UP,
            7 to PhysicalButtonEventDecoder.Button.DOWN,
            8 to PhysicalButtonEventDecoder.Button.OK,
            9 to PhysicalButtonEventDecoder.Button.LEFT,
            10 to PhysicalButtonEventDecoder.Button.RIGHT,
            16 to PhysicalButtonEventDecoder.Button.PHONE,
            23 to PhysicalButtonEventDecoder.Button.CENTER,
            24 to PhysicalButtonEventDecoder.Button.VOLUME_UP,
            25 to PhysicalButtonEventDecoder.Button.VOLUME_DOWN,
            87 to PhysicalButtonEventDecoder.Button.MEDIA_NEXT,
            88 to PhysicalButtonEventDecoder.Button.MEDIA_PREVIOUS,
            110 to PhysicalButtonEventDecoder.Button.SOURCE,
            164 to PhysicalButtonEventDecoder.Button.MUTE,
            17 to PhysicalButtonEventDecoder.Button.STAR_LEFT,
            18 to PhysicalButtonEventDecoder.Button.STAR_RIGHT,
            286 to PhysicalButtonEventDecoder.Button.STAR_RIGHT,
            287 to PhysicalButtonEventDecoder.Button.ASSISTANT
        )
        expected.forEach { (code, button) ->
            val decoder = PhysicalButtonEventDecoder()
            decoder.accept(code, true, false)
            assertEquals(button, decoder.accept(code, false, false)?.button)
        }
    }
    @Test fun `two releases inside the window make one double`() {
        val decoder = PhysicalButtonEventDecoder()
        decoder.accept(17, true, false, atMillis = 0)
        assertEquals(PhysicalButtonEventDecoder.Press.SHORT, decoder.accept(17, false, false, atMillis = 10)?.press)
        decoder.accept(17, true, false, atMillis = 100)
        assertEquals(PhysicalButtonEventDecoder.Press.DOUBLE, decoder.accept(17, false, false, atMillis = 200)?.press)
    }

    @Test fun `a second press after the window is another single`() {
        val decoder = PhysicalButtonEventDecoder()
        decoder.accept(17, true, false, atMillis = 0)
        decoder.accept(17, false, false, atMillis = 0)
        decoder.accept(17, true, false, atMillis = 1_000)
        assertEquals(PhysicalButtonEventDecoder.Press.SHORT, decoder.accept(17, false, false, atMillis = 1_000)?.press)
    }

    @Test fun `three presses give one double and then a single`() {
        // Sinon un troisième appui rendrait un second DOUBLE, et la règle partirait deux fois.
        val decoder = PhysicalButtonEventDecoder()
        val presses = listOf(0L, 100L, 200L).map {
            decoder.accept(17, true, false, atMillis = it)
            decoder.accept(17, false, false, atMillis = it)?.press
        }
        assertEquals(
            listOf(
                PhysicalButtonEventDecoder.Press.SHORT,
                PhysicalButtonEventDecoder.Press.DOUBLE,
                PhysicalButtonEventDecoder.Press.SHORT
            ),
            presses
        )
    }

    @Test fun `a long press does not pair with the release that follows`() {
        val decoder = PhysicalButtonEventDecoder()
        decoder.accept(17, true, false, atMillis = 0)
        decoder.accept(17, false, false, atMillis = 0)          // SHORT
        decoder.accept(17, true, false, atMillis = 100)
        decoder.accept(17, true, true, atMillis = 100)          // LONG
        decoder.accept(17, false, false, atMillis = 150)        // release, suppressed
        decoder.accept(17, true, false, atMillis = 200)
        assertEquals(
            PhysicalButtonEventDecoder.Press.SHORT,
            decoder.accept(17, false, false, atMillis = 250)?.press
        )
    }

    @Test fun `long press fires once and suppresses release`() {
        val decoder = PhysicalButtonEventDecoder()
        assertNull(decoder.accept(17, true, false))
        assertEquals(PhysicalButtonEventDecoder.Press.LONG, decoder.accept(17, true, true)?.press)
        assertNull(decoder.accept(17, true, true))
        assertNull(decoder.accept(17, false, false))
    }
}
