package com.mg4.hardware

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
    @Test fun `long press fires once and suppresses release`() {
        val decoder = PhysicalButtonEventDecoder()
        assertNull(decoder.accept(17, true, false))
        assertEquals(PhysicalButtonEventDecoder.Press.LONG, decoder.accept(17, true, true)?.press)
        assertNull(decoder.accept(17, true, true))
        assertNull(decoder.accept(17, false, false))
    }
}
