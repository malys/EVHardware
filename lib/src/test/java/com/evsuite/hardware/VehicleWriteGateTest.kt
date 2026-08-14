package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate decides whether a rule may change a car that is moving, so its rule is spelled out
 * here rather than trusted to reading.
 */
class VehicleWriteGateTest {

    @Test
    fun `standstill is the default and the only speed allowed at zero`() {
        assertEquals(VehicleWriteGate.Decision.ALLOWED, VehicleWriteGate.decide(0f))
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(1f))
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(90f))
    }

    @Test
    fun `every positive speed is refused`() {
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(0.01f))
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(20f))
    }

    @Test
    fun `an unreadable speed fails closed`() {
        assertEquals(VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(null))
        assertEquals(VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(-1f))
        assertEquals(
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED,
            VehicleWriteGate.decide(Float.NaN)
        )
    }
}
