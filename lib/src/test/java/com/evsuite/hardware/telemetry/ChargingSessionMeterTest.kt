package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargingSessionMeterTest {
    @Test fun integratesTrapezoidsAndRoundsForPayloads() {
        val meter = ChargingSessionMeter()
        assertEquals(0.0, meter.sample(0L, true, 10f)!!, 0.0)
        assertEquals(0.25, meter.sample(60_000L, true, 20f)!!, 0.0)
    }

    @Test fun closedOrNeverOpenedSessionIsOmitted() {
        val meter = ChargingSessionMeter()
        assertNull(meter.sample(0L, null, null))
        meter.sample(1L, true, 5f)
        assertNull(meter.sample(2L, false, 0f))
    }

    @Test fun missingRateAndLongGapNeverInventEnergy() {
        val meter = ChargingSessionMeter(maxSampleGapMs = 1_000L)
        meter.sample(0L, true, 10f)
        assertEquals(0.0, meter.sample(500L, true, null)!!, 0.0)
        assertEquals(0.0, meter.sample(2_000L, true, 10f)!!, 0.0)
        assertEquals(0.0, meter.sample(4_000L, true, 10f)!!, 0.0)
    }

    @Test fun negativeChargeRateCannotReduceSessionTotal() {
        val meter = ChargingSessionMeter()
        meter.sample(0L, true, 10f)
        assertEquals(0.08, meter.sample(60_000L, true, -5f)!!, 0.0)
    }
}
