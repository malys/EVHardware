package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryCapacityConfigTest {

    /** The reported MG4: 64 kWh nameplate, about 61.7 kWh usable, 95 % state of health. */
    private val mg4 = BatteryCapacityConfig.of(61.7, 95.0)!!

    @Test fun `state of health scales the usable pack`() {
        assertEquals(58.615, mg4.effectiveUsableKwh, 1e-6)
    }

    @Test fun `energy at a state of charge is an estimate with a band`() {
        val energy = mg4.energyAtSocKwh(56.1)
        assertEquals(Provenance.ESTIMATED, energy.provenance)
        assertEquals(32.883, energy.value!!, 1e-3)
        assertNotNull("a declared capacity is never drawn as a reading", energy.uncertainty)
        assertEquals(energy.value!! * 0.05, energy.uncertainty!!, 1e-6)
    }

    @Test fun `a full pack is the effective capacity and an empty one is zero`() {
        assertEquals(58.615, mg4.energyAtSocKwh(100.0).value!!, 1e-6)
        assertEquals(0.0, mg4.energyAtSocKwh(0.0).value!!, 1e-9)
    }

    @Test fun `an absent or impossible state of charge is unavailable, not zero`() {
        listOf(null, -1.0, 101.0, Double.NaN).forEach {
            val energy = mg4.energyAtSocKwh(it)
            assertEquals("soc=$it", Provenance.UNAVAILABLE, energy.provenance)
            assertNull(energy.value)
        }
    }

    @Test fun `energy converts back to state of charge with the same relative band`() {
        val soc = mg4.socPercentForEnergy(58.615)
        assertEquals(100.0, soc.value!!, 1e-6)
        assertEquals(5.0, soc.uncertainty!!, 1e-6)
    }

    @Test fun `figures outside the plausible range are refused rather than clamped`() {
        assertNull(BatteryCapacityConfig.of(0.0, 95.0))
        assertNull(BatteryCapacityConfig.of(500.0, 95.0))
        assertNull(BatteryCapacityConfig.of(61.7, 0.0))
        assertNull(BatteryCapacityConfig.of(61.7, 140.0))
        assertNull(BatteryCapacityConfig.of(null, 95.0))
        assertNull(BatteryCapacityConfig.of(61.7, null))
    }

    @Test fun `the nameplate figure would overstate a full pack`() {
        // Entering 64 kWh instead of the usable 61.7 inflates every forecast in the optimistic
        // direction — which is the mistake this field's wording exists to prevent.
        val nameplate = BatteryCapacityConfig.of(64.0, 95.0)!!
        assertTrue(nameplate.effectiveUsableKwh > mg4.effectiveUsableKwh)
        assertEquals(2.185, nameplate.effectiveUsableKwh - mg4.effectiveUsableKwh, 1e-3)
    }
}
