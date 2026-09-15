package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `a flat route plans exactly as it did before grade existed`() {
        val flat = RouteGrade.of(0.0, 0.0, BatteryCapacityConfig(61.7, 100.0))
        assertEquals(
            ChargeStopPlan.of(90.0, 100.0, rate(0.4, 0.04)),
            ChargeStopPlan.of(90.0, 100.0, rate(0.4, 0.04), grade = flat),
        )
    }

    @Test
    fun `a col between here and there is charge that has to be spent`() {
        val pack = BatteryCapacityConfig(61.7, 100.0)
        val plain = ChargeStopPlan.of(90.0, 200.0, rate(0.3, 0.02)) as ChargeStopPlan.Plan.NoStop
        val overACol = ChargeStopPlan.of(
            90.0, 200.0, rate(0.3, 0.02),
            grade = RouteGrade.of(1200.0, 400.0, pack),
        ) as ChargeStopPlan.Plan.NoStop

        assertTrue("the climb costs charge", overACol.arrivalPercent < plain.arrivalPercent)
        assertTrue("and it is less certain", overACol.bandPercent > plain.bandPercent)

        // Downhill the other way, and the same route returns some of it.
        val backDown = ChargeStopPlan.of(
            90.0, 200.0, rate(0.3, 0.02),
            grade = RouteGrade.of(400.0, 1200.0, pack),
        ) as ChargeStopPlan.Plan.NoStop
        assertTrue(backDown.arrivalPercent > plain.arrivalPercent)
        assertTrue(
            "the descent gives back less than the climb took",
            backDown.arrivalPercent - plain.arrivalPercent <
                plain.arrivalPercent - overACol.arrivalPercent,
        )
    }

    @Test
    fun `a climb brings the stop forward rather than leaving it where it was`() {
        val pack = BatteryCapacityConfig(61.7, 100.0)
        val plain = ChargeStopPlan.of(45.0, 300.0, rate(0.4, 0.02)) as ChargeStopPlan.Plan.Stop
        val overACol = ChargeStopPlan.of(
            45.0, 300.0, rate(0.4, 0.02),
            grade = RouteGrade.of(1200.0, 0.0, pack),
        ) as ChargeStopPlan.Plan.Stop
        assertTrue(overACol.afterKm < plain.afterKm)
    }

    @Test
    fun `a car already below its reserve is told to stop immediately`() {
        val plan = ChargeStopPlan.of(8.0, 50.0, rate(0.4, 0.01))
        assertTrue(plan is ChargeStopPlan.Plan.Stop)
        plan as ChargeStopPlan.Plan.Stop
        assertEquals(0.0, plan.afterKm, 0.001)
    }

    // --- chain ----------------------------------------------------------------------------

    @Test
    fun `Auzielle to Paris is two stops where one plan refused the whole trip`() {
        // 680 km at 0,28 +- 0,028 %/km: the single-stop plan measures its band over all of it,
        // 2 x 0,028 x 680 = 38 %, and refuses. Each leg is a third of that road.
        val whole = ChargeStopPlan.of(80.0, 680.0, rate(0.28, 0.028))
        assertTrue(whole is ChargeStopPlan.Plan.Refused)
        assertEquals(
            ChargeStopPlan.Reason.BAND_TOO_WIDE,
            (whole as ChargeStopPlan.Plan.Refused).reason,
        )

        val chain = ChargeStopPlan.chain(80.0, 680.0, rate(0.28, 0.028))
        assertNull("the chain reaches Paris", chain.refusal)
        assertTrue("and stops twice on the way", chain.stops.size >= 2)
        assertNotNull(chain.arrival)
        // Every stop is somewhere on the road, in the order they are driven.
        var previous = 0.0
        for (stop in chain.stops) {
            assertTrue(stop.endKm > previous)
            assertTrue(stop.endKm < 680.0)
            previous = stop.endKm
        }
        // A stop leaves with the declared charge, not with a full pack.
        assertEquals(
            ChargeStopPlan.DEFAULT_DEPARTURE_PERCENT,
            chain.legs[1].startPercent,
            0.001,
        )
    }

    @Test
    fun `a route one charge covers is the plan of already made`() {
        val single = ChargeStopPlan.of(90.0, 200.0, rate(0.3, 0.02)) as ChargeStopPlan.Plan.NoStop
        val chain = ChargeStopPlan.chain(90.0, 200.0, rate(0.3, 0.02))
        assertEquals(1, chain.legs.size)
        assertEquals(single, chain.arrival)
        assertEquals(0.0, chain.legs[0].startKm, 0.001)
        assertEquals(200.0, chain.legs[0].endKm, 0.001)
    }

    @Test
    fun `a last leg too uncertain to vouch for refuses itself and not the legs before it`() {
        // 414 km at 0,3 +- 0,06 %/km — the fifth the vehicle's own range figure is worth. The
        // leg to the stop is 194 km and its band holds; the 220 km left after it are reachable
        // on the charge the stop leaves with, and arriving there is a 26 % wide claim.
        val chain = ChargeStopPlan.chain(80.0, 414.0, rate(0.3, 0.06))
        assertEquals(ChargeStopPlan.Reason.BAND_TOO_WIDE, chain.refusal)
        assertEquals("the stop before it still reads", 1, chain.stops.size)
        assertTrue(chain.stops.all { it.plan is ChargeStopPlan.Plan.Stop })
    }

    @Test
    fun `a departure charge that buys no distance is refused rather than looped over`() {
        // Leaving a stop on the reserve itself: the next leg reaches nowhere, for ever.
        val chain = ChargeStopPlan.chain(
            80.0, 900.0, rate(0.3, 0.01), reservePercent = 10.0, departurePercent = 10.0,
        )
        assertEquals(ChargeStopPlan.Reason.NO_PROGRESS, chain.refusal)
        assertEquals("the first leg was still driveable", 1, chain.stops.size)
    }

    @Test
    fun `a trip needing more legs than the cap is refused as one`() {
        val chain = ChargeStopPlan.chain(80.0, 4000.0, rate(0.3, 0.01))
        assertEquals(ChargeStopPlan.Reason.TOO_MANY_LEGS, chain.refusal)
        assertEquals(ChargeStopPlan.MAX_LEGS, chain.stops.size)
    }

    @Test
    fun `the refusals about the trip are answered by a chain of one leg`() {
        assertEquals(
            ChargeStopPlan.Reason.NO_CHARGE,
            ChargeStopPlan.chain(null, 100.0, rate(0.3, 0.01)).refusal,
        )
        assertEquals(
            ChargeStopPlan.Reason.NO_ROUTE,
            ChargeStopPlan.chain(80.0, null, rate(0.3, 0.01)).refusal,
        )
        assertEquals(
            ChargeStopPlan.Reason.NO_RATE,
            ChargeStopPlan.chain(80.0, 100.0, null).refusal,
        )
    }
}
