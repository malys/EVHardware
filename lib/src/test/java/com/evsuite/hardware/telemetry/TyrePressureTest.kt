package com.evsuite.hardware.telemetry

import com.evsuite.hardware.ExteriorLights
import com.evsuite.hardware.catalog.ConditionType
import com.evsuite.hardware.catalog.SnapshotKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CR-050 (lowest tyre pressure) and the CR-051 probe additions, pinned off the car. */
class TyrePressureTest {

    @Test
    fun `lowest is the smallest of the four`() {
        assertEquals(215f, TirePressureSnapshot(250f, 240f, 215f, 260f).lowestKpa)
    }

    @Test
    fun `one wheel not heard from leaves no answer rather than the lowest of three`() {
        // The missing sensor may be the flat tyre: the other three being fine proves nothing.
        assertNull(TirePressureSnapshot(250f, null, 215f, 260f).lowestKpa)
        assertNull(TirePressureSnapshot(null, null, null, null).lowestKpa)
    }

    @Test
    fun `the condition is comparable, read-only and keyed to the lowest pressure`() {
        val c = ConditionType.TYRE_PRESSURE_LOWEST
        assertTrue(c.comparable)
        assertEquals(SnapshotKeys.KEY_TYRE_PRESSURE_LOWEST_KPA, c.snapshotKey)
        assertNotNull(c.snapshotKey)
    }

    @Test
    fun `the hazard light ids are named for the probe`() {
        assertEquals("HAZARD_LIGHTS_STATE", ExteriorLights.NAMES[ExteriorLights.PROP_HAZARD_LIGHTS_STATE])
        assertEquals("HAZARD_LIGHTS_SWITCH", ExteriorLights.NAMES[ExteriorLights.PROP_HAZARD_LIGHTS_SWITCH])
        // Probed only: no read path, so no value can leak into a rule before a capture proves it.
        assertTrue(ExteriorLights.PROVEN.isEmpty())
    }
}
