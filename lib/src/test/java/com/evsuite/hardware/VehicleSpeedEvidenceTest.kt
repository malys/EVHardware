package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSpeedEvidenceTest {

    @Test fun `the two conversions are distinct versions`() {
        assertEquals(1, VehicleSpeedEvidence.MPS_TIMES_3_6_V1)
        assertEquals(2, VehicleSpeedEvidence.KMH_DIRECT_V2)
        assertEquals(VehicleSpeedEvidence.KMH_DIRECT_V2, VehicleSpeedEvidence.CURRENT)
    }

    @Test fun `a distance from the old conversion never matches the current one`() {
        val old = VehicleSpeedEvidence(FirmwareInfo.Gen.SWI68, VehicleSpeedEvidence.MPS_TIMES_3_6_V1)
        assertFalse(old.matchesCurrent())
    }

    @Test fun `evidence naming an unknown firmware is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            VehicleSpeedEvidence(FirmwareInfo.Gen.UNKNOWN, VehicleSpeedEvidence.CURRENT)
        }
    }

    @Test fun `a conversion version must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            VehicleSpeedEvidence(FirmwareInfo.Gen.SWI68, 0)
        }
    }

    @Test fun `an unknown firmware makes no claim rather than a wrong one`() {
        // No vehicle in a JVM test, so the generation is UNKNOWN and current() must stay silent.
        assertNull(VehicleSpeedEvidence.current())
    }

    @Test fun `evidence for a different firmware does not match this one`() {
        val other = VehicleSpeedEvidence(FirmwareInfo.Gen.SWI69, VehicleSpeedEvidence.CURRENT)
        assertFalse(other.matchesCurrent())
        assertTrue(other.conversionVersion == VehicleSpeedEvidence.CURRENT)
    }
}
