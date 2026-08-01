package com.mg4.hardware

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gate decides whether a rule may change a car that is moving, so its rule is spelled out
 * here rather than trusted to reading. The threshold is configurable now; what must not move
 * is that an unreadable speed refuses, whatever it is set to.
 */
class VehicleWriteGateTest {

    @After
    fun reset() {
        VehicleWriteGate.allowUpToKmh = 0f
    }

    @Test
    fun `standstill is the default and the only speed allowed at zero`() {
        assertEquals(VehicleWriteGate.Decision.ALLOWED, VehicleWriteGate.decide(0f))
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(1f))
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(90f))
    }

    @Test
    fun `a raised threshold allows a write below it and refuses above`() {
        assertEquals(VehicleWriteGate.Decision.ALLOWED, VehicleWriteGate.decide(18f, 20f))
        assertEquals(VehicleWriteGate.Decision.ALLOWED, VehicleWriteGate.decide(20f, 20f))
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(21f, 20f))
    }

    @Test
    fun `an unreadable speed still fails closed whatever the threshold`() {
        // The threshold moves where "moving" begins; it never turns the gate off. A speed
        // the vehicle cannot report is the case the gate exists for.
        val max = VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH
        assertEquals(VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(null, max))
        assertEquals(VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED, VehicleWriteGate.decide(-1f, max))
        assertEquals(
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED,
            VehicleWriteGate.decide(Float.NaN, max)
        )
    }

    @Test
    fun `the threshold cannot be pushed past the cap`() {
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(80f, 200f))

        VehicleWriteGate.allowUpToKmh = 200f
        assertEquals(VehicleWriteGate.MAX_ALLOWED_THRESHOLD_KMH, VehicleWriteGate.allowUpToKmh)

        VehicleWriteGate.allowUpToKmh = -5f
        assertEquals(0f, VehicleWriteGate.allowUpToKmh)
    }

    @Test
    fun `park rescues an unreadable speed`() {
        // The case this exists for: the speed property answers nothing, so the gate has no
        // evidence at all — and the gear supplies some.
        assertEquals(VehicleWriteGate.Decision.ALLOWED, VehicleWriteGate.decide(null, 0f, parked = true))
        assertEquals(
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED,
            VehicleWriteGate.decide(null, 0f, parked = false)
        )
        // An unreadable gear leaves the refusal exactly where it was.
        assertEquals(
            VehicleWriteGate.Decision.REFUSED_UNKNOWN_SPEED,
            VehicleWriteGate.decide(null, 0f, parked = null)
        )
    }

    @Test
    fun `park never overrides a speed that did read`() {
        // If the gear says park while the car reads 30 km/h, one of the two is wrong. The
        // gate refuses: trusting the gear here is how a stale signal would unlock writes at
        // speed, which is the single outcome it exists to prevent.
        assertEquals(
            VehicleWriteGate.Decision.REFUSED_MOVING,
            VehicleWriteGate.decide(30f, 0f, parked = true)
        )
    }

    @Test
    fun `the stored threshold drives the single-argument decision`() {
        VehicleWriteGate.allowUpToKmh = 30f
        assertEquals(VehicleWriteGate.Decision.ALLOWED, VehicleWriteGate.decide(29f))
        assertEquals(VehicleWriteGate.Decision.REFUSED_MOVING, VehicleWriteGate.decide(31f))
    }
}
