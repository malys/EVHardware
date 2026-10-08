package com.evsuite.hardware

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.probe.VendorSurfaceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CR-046. Owner decisions pinned here, off the car: a rule never switches the low beams OFF,
 * never forces the high beam ON, and touches no light on a generation nobody has proven.
 */
class ExteriorLightsTest {

    private val gen = FirmwareInfo.Gen.SWI68
    private val proven = setOf(gen)

    @Test
    fun `low beams can be set ON or AUTOMATIC, never OFF`() {
        val allowed = ExteriorLights.HEADLIGHT_SWITCH_ALLOWED
        assertNotNull(ExteriorLights.writeRefusal(gen, allowed, ExteriorLights.SWITCH_OFF, proven))
        assertNull(ExteriorLights.writeRefusal(gen, allowed, ExteriorLights.SWITCH_ON, proven))
        assertNull(ExteriorLights.writeRefusal(gen, allowed, ExteriorLights.SWITCH_AUTOMATIC, proven))
    }

    @Test
    fun `high beam can be set OFF or AUTOMATIC, never forced ON`() {
        val allowed = ExteriorLights.HIGH_BEAM_SWITCH_ALLOWED
        assertNotNull(ExteriorLights.writeRefusal(gen, allowed, ExteriorLights.SWITCH_ON, proven))
        assertNull(ExteriorLights.writeRefusal(gen, allowed, ExteriorLights.SWITCH_OFF, proven))
        assertNull(ExteriorLights.writeRefusal(gen, allowed, ExteriorLights.SWITCH_AUTOMATIC, proven))
    }

    @Test
    fun `daytime running is never a value a rule can write`() {
        listOf(
            ExteriorLights.HEADLIGHT_SWITCH_ALLOWED,
            ExteriorLights.HIGH_BEAM_SWITCH_ALLOWED,
            ExteriorLights.FOG_SWITCH_ALLOWED,
        ).forEach { assertFalse(ExteriorLights.SWITCH_DAYTIME_RUNNING in it) }
    }

    @Test
    fun `an unproven generation refuses every light write`() {
        assertNotNull(
            ExteriorLights.writeRefusal(
                FirmwareInfo.Gen.SWI69, ExteriorLights.FOG_SWITCH_ALLOWED, ExteriorLights.SWITCH_ON, proven
            )
        )
        // Until a capture proves one, no generation is proven at all.
        assertTrue(ExteriorLights.PROVEN.isEmpty())
        FirmwareInfo.Gen.entries.forEach { assertFalse(ExteriorLights.isProven(it)) }
    }

    @Test
    fun `daytime running lights are not headlights on`() {
        assertEquals(true, ExteriorLights.isOn(ExteriorLights.STATE_ON))
        assertEquals(false, ExteriorLights.isOn(ExteriorLights.STATE_DAYTIME_RUNNING))
        assertEquals(false, ExteriorLights.isOn(ExteriorLights.STATE_OFF))
        assertNull(ExteriorLights.isOn(null))
    }

    @Test
    fun `the editor cannot even express a forbidden light value`() {
        val lowBeam = ActionType.SET_HEADLIGHTS.spec.options.map { it.value }.toSet()
        val highBeam = ActionType.SET_HIGH_BEAM.spec.options.map { it.value }.toSet()
        assertEquals(ExteriorLights.HEADLIGHT_SWITCH_ALLOWED, lowBeam)
        assertEquals(ExteriorLights.HIGH_BEAM_SWITCH_ALLOWED, highBeam)
    }

    @Test
    fun `light actions are gated and unproven, light entries supported nowhere yet`() {
        val actions = listOf(ActionType.SET_HEADLIGHTS, ActionType.SET_HIGH_BEAM, ActionType.SET_FOG_LIGHTS)
        actions.forEach {
            assertTrue("${it.name} must be gated", it.gated)
            assertFalse("${it.name} must stay unproven until a capture", it.writeProven)
            assertEquals(emptySet<FirmwareGen>(), FirmwareSupport.gensOf(it))
        }
        listOf(ConditionType.HEADLIGHTS, ConditionType.HIGH_BEAM_ON, ConditionType.FOG_LIGHTS_ON).forEach {
            assertEquals(emptySet<FirmwareGen>(), FirmwareSupport.gensOf(it))
        }
    }

    @Test
    fun `a light property is named in the capture diff`() {
        assertEquals(
            "CPM 0x11400E00/0 (HEADLIGHTS_STATE)",
            VendorSurfaceRules.label("CPM 0x11400E00/0"),
        )
        assertEquals("CPM 0x2160F404/0", VendorSurfaceRules.label("CPM 0x2160F404/0"))
        assertEquals("VehicleSetting.getX", VendorSurfaceRules.label("VehicleSetting.getX"))
    }
}
