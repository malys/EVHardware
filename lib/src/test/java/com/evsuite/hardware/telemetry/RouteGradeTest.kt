package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGradeTest {

    private val pack = BatteryCapacityConfig(usableCapacityKwhWhenNew = 61.7, stateOfHealthPercent = 100.0)

    @Test
    fun `a kilometre of climb costs what the physics says, near enough to check by hand`() {
        // 1750 kg over 1000 m is 17,2 MJ = 4,77 kWh at the wheels, 5,61 kWh from the pack at
        // 85 %, which is 9,1 % of 61,7 kWh.
        val cost = RouteGrade.of(1000.0, 0.0, pack)
        assertNotNull(cost)
        assertEquals(9.1, cost!!.percent, 0.2)
        assertTrue("the band carries the constants", cost.uncertaintyPercent > cost.percent * 0.4)
    }

    @Test
    fun `a flat route costs nothing, and says so as a number rather than as silence`() {
        val cost = RouteGrade.of(0.0, 0.0, pack)
        assertEquals(0.0, cost!!.percent, 1e-9)
    }

    @Test
    fun `a descent returns charge, but never as much as the same climb spent`() {
        val down = RouteGrade.of(0.0, 1000.0, pack)!!
        val up = RouteGrade.of(1000.0, 0.0, pack)!!
        assertTrue("a descent returns charge", down.percent < 0.0)
        assertTrue("regeneration is not free", -down.percent < up.percent)
    }

    @Test
    fun `a round trip over a col is not a free lunch`() {
        val roundTrip = RouteGrade.of(1000.0, 1000.0, pack)!!
        val climbOnly = RouteGrade.of(1000.0, 0.0, pack)!!
        assertTrue("a col costs something both ways", roundTrip.percent > 0.0)
        assertTrue("but less than climbing twice", roundTrip.percent < climbOnly.percent)
        // Two thirds of a kWh per hundred metres of col, there and back. If this ever comes out
        // at zero the efficiencies have been made equal and the arithmetic has stopped being
        // physics.
        assertEquals(4.4, roundTrip.percent, 0.3)
    }

    @Test
    fun `no profile is null, and null is not a flat road`() {
        assertNull(RouteGrade.of(null, 0.0, pack))
        assertNull(RouteGrade.of(0.0, null, pack))
        assertNull(RouteGrade.of(100.0, 100.0, null))
        assertNull(RouteGrade.of(Double.NaN, 0.0, pack))
        assertNull(RouteGrade.of(-10.0, 0.0, pack))
    }

    @Test
    fun `a smaller pack means the same col costs a larger share of it`() {
        val small = RouteGrade.of(1000.0, 0.0, BatteryCapacityConfig(40.0, 100.0))!!
        val large = RouteGrade.of(1000.0, 0.0, pack)!!
        assertTrue(small.percent > large.percent)
    }
}
