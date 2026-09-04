package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeStopPlanTest {

    private fun rate(percentPerKm: Double, uncertainty: Double) = SocRate(
        percentPerKm = percentPerKm,
        uncertaintyPercentPerKm = uncertainty,
        source = SocRate.Source.TRIP_HISTORY,
        sampleCount = 8,
    )

    @Test
    fun `a short route on a full pack needs no stop`() {
        val plan = ChargeStopPlan.of(90.0, 100.0, rate(0.4, 0.04))
        assertTrue(plan is ChargeStopPlan.Plan.NoStop)
        plan as ChargeStopPlan.Plan.NoStop
        assertEquals(50.0, plan.arrivalPercent, 0.001)
        assertEquals(40.0, plan.marginPercent, 0.001)
    }

    @Test
    fun `the reserve is what makes a marginal trip need a stop`() {
        // 100 km at 0.4 %/km spends 40 %; from 45 % that arrives at 5, under a 10 % reserve.
        val withoutReserve = ChargeStopPlan.of(45.0, 100.0, rate(0.4, 0.02), reservePercent = 0.0)
        val withReserve = ChargeStopPlan.of(45.0, 100.0, rate(0.4, 0.02))
        assertTrue(withoutReserve is ChargeStopPlan.Plan.NoStop)
        assertTrue(withReserve is ChargeStopPlan.Plan.Stop)
    }

    @Test
    fun `the stop distance is pessimistic, not the midpoint`() {
        // 35 % spendable at 0.4 +- 0.04 %/km: the midpoint reaches 87,5 km, the top of the
        // band only 79,5. The driver is told the shorter one.
        val plan = ChargeStopPlan.of(45.0, 200.0, rate(0.4, 0.04))
        assertTrue(plan is ChargeStopPlan.Plan.Stop)
        plan as ChargeStopPlan.Plan.Stop
        assertEquals(79.5, plan.afterKm, 0.5)
        assertTrue("pessimistic", plan.afterKm < 35.0 / 0.4)
        assertEquals(200.0 - plan.afterKm, plan.shortfallKm, 0.001)
    }

    @Test
    fun `a long route with a loose rate is refused rather than shown wide`() {
        val plan = ChargeStopPlan.of(80.0, 600.0, rate(0.4, 0.05))
        assertEquals(
            ChargeStopPlan.Plan.Refused(ChargeStopPlan.Reason.BAND_TOO_WIDE),
            plan,
        )
    }

    @Test
    fun `the same route with a tight rate is planned`() {
        val plan = ChargeStopPlan.of(80.0, 600.0, rate(0.4, 0.01))
        assertTrue(plan is ChargeStopPlan.Plan.Stop)
    }

    @Test
    fun `missing inputs each name themselves`() {
        assertEquals(
            ChargeStopPlan.Plan.Refused(ChargeStopPlan.Reason.NO_CHARGE),
            ChargeStopPlan.of(null, 100.0, rate(0.4, 0.01)),
        )
        assertEquals(
            ChargeStopPlan.Plan.Refused(ChargeStopPlan.Reason.NO_ROUTE),
            ChargeStopPlan.of(80.0, null, rate(0.4, 0.01)),
        )
        assertEquals(
            ChargeStopPlan.Plan.Refused(ChargeStopPlan.Reason.NO_RATE),
            ChargeStopPlan.of(80.0, 100.0, null),
        )
    }

    @Test
    fun `a car already below its reserve is told to stop immediately`() {
        val plan = ChargeStopPlan.of(8.0, 50.0, rate(0.4, 0.01))
        assertTrue(plan is ChargeStopPlan.Plan.Stop)
        plan as ChargeStopPlan.Plan.Stop
        assertEquals(0.0, plan.afterKm, 0.001)
    }
}
