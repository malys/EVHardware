package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalButtonEventDecoderTest {
    @Test fun `every R69 hardkey code decodes`() {
        val expected = mapOf(
            5 to PhysicalButtonEventDecoder.Button.PHONE,
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

    @Test fun `the SystemUI broadcast adds only the cluster pad`() {
        val systemUi = PhysicalButtonEventDecoder.Source.SYSTEM_UI
        val expected = mapOf(
            6 to PhysicalButtonEventDecoder.Button.UP,
            7 to PhysicalButtonEventDecoder.Button.DOWN,
            8 to PhysicalButtonEventDecoder.Button.OK,
            9 to PhysicalButtonEventDecoder.Button.LEFT,
            10 to PhysicalButtonEventDecoder.Button.RIGHT
        )
        expected.forEach { (code, button) ->
            val decoder = PhysicalButtonEventDecoder()
            decoder.accept(code, true, false, source = systemUi)
            assertEquals(button, decoder.accept(code, false, false, source = systemUi)?.button)
        }
    }

    @Test fun `a press on both broadcasts is one press`() {
        // Next track: 87 on the report, 5 on SystemUI. Read as one space, SystemUI's 5 was a
        // phone press; and the phone itself (5 and 16) arrived twice, i.e. as a double.
        val decoder = PhysicalButtonEventDecoder()
        val systemUi = PhysicalButtonEventDecoder.Source.SYSTEM_UI
        val events = listOfNotNull(
            decoder.accept(87, true, false, atMillis = 0),
            decoder.accept(5, true, false, atMillis = 0, source = systemUi),
            decoder.accept(87, false, false, atMillis = 50),
            decoder.accept(5, false, false, atMillis = 50, source = systemUi),
            decoder.accept(5, true, false, atMillis = 1_000),
            decoder.accept(16, true, false, atMillis = 1_000, source = systemUi),
            decoder.accept(5, false, false, atMillis = 1_050),
            decoder.accept(16, false, false, atMillis = 1_050, source = systemUi)
        )
        assertEquals(listOf("MEDIA_NEXT:SHORT", "PHONE:SHORT"), events.map { it.value })
    }

    @Test fun `an unlisted key is reported with an id of its own`() {
        val decoder = PhysicalButtonEventDecoder()
        decoder.accept(3, true, false)
        val event = decoder.accept(3, false, false)!!
        assertNull(event.button)
        assertEquals(
            PhysicalButtonEventDecoder.Source.HARDKEY_REPORT to 3,
            PhysicalButtonEventDecoder.unknownKey(event.keyId)
        )
        // Same code, other broadcast, other key.
        val systemUi = PhysicalButtonEventDecoder.Source.SYSTEM_UI
        decoder.accept(30, true, false, source = systemUi)
        val other = decoder.accept(30, false, false, source = systemUi)!!
        assertEquals(PhysicalButtonEventDecoder.Source.SYSTEM_UI to 30, PhysicalButtonEventDecoder.unknownKey(other.keyId))
        assertEquals("KEY_${event.keyId}:SHORT", event.value)
    }

    @Test fun `stored button ids are unchanged`() {
        // Rules on disk hold these numbers; changing one silently rebinds a rule.
        val ids = PhysicalButtonEventDecoder.Button.entries.associate { it.name to it.id }
        assertEquals(
            mapOf(
                "PHONE" to 5, "UP" to 6, "DOWN" to 7, "OK" to 8, "LEFT" to 9, "RIGHT" to 10,
                "SOURCE" to 110, "CENTER" to 23, "VOLUME_UP" to 24, "VOLUME_DOWN" to 25,
                "MEDIA_NEXT" to 87, "MEDIA_PREVIOUS" to 88, "MUTE" to 164, "STAR_LEFT" to 17,
                "STAR_RIGHT" to 286, "ASSISTANT" to 287
            ),
            ids
        )
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
