package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanDriftTest {

    /** 300 km at 0,20 %/km ± 0,02: leaves at 90 %, plans to arrive at 30 %, reserve 10 %. */
    private val plan = PlanDrift.Followed(
        legKm = 300.0,
        socAtDeparturePercent = 90.0,
        plannedRatePercentPerKm = 0.20,
        plannedUncertaintyPercentPerKm = 0.02,
        reservePercent = 10.0,
    )

    /** Charge left after [km] at [ratePerKm], as the gauge would read it. */
    private fun socAfter(km: Double, ratePerKm: Double) =
        plan.socAtDeparturePercent - km * ratePerKm

    @Test
    fun `a drive spending what the plan assumed is holding`() {
        val verdict = PlanDrift.check(plan, drivenKm = 100.0, socNowPercent = socAfter(100.0, 0.20))
        val holding = verdict as PlanDrift.Verdict.Holding
        assertEquals(0.20, holding.reading.observedPercentPerKm, 1e-9)
        assertEquals(200.0, holding.reading.remainingKm, 1e-9)
        assertEquals(30.0, holding.reading.arrivalPercent, 1e-9)
    }

    @Test
    fun `spending inside the plan's own band raises nothing`() {
        // 0,215 %/km against a plan of 0,20 ± 0,02, with the measurement's own band on top:
        // the two overlap, so there is no drift to report however the arrival lands.
        val verdict = PlanDrift.check(plan, drivenKm = 100.0, socNowPercent = socAfter(100.0, 0.215))
        assertTrue(verdict.toString(), verdict is PlanDrift.Verdict.Holding)
    }

    @Test
    fun `spending twice the band past the plan, and short of the reserve, drifts`() {
        // 0,26 %/km: 0,06 past the plan, about twice the two bands added. Arrival lands at 4 %.
        val verdict = PlanDrift.check(plan, drivenKm = 100.0, socNowPercent = socAfter(100.0, 0.26))
        val short = verdict as PlanDrift.Verdict.Short
        assertEquals(0.26, short.reading.observedPercentPerKm, 1e-9)
        assertEquals(12.0, short.reading.arrivalPercent, 1e-9)
        assertTrue(short.shortfallPercent > 0.0)
        // The shortfall is measured at the pessimistic edge, so it is wider than 10 − 12.
        assertEquals(plan.reservePercent - (12.0 - short.reading.bandPercent), short.shortfallPercent, 1e-9)
    }

    @Test
    fun `spending past the plan but still arriving well clear of the reserve is not reported`() {
        // Same 0,26 %/km, but only 60 km of leg left to spend it on: the arrival still clears
        // the reserve, and a companion that speaks here is one nobody reads when it matters.
        val near = plan.copy(legKm = 100.0)
        val verdict = PlanDrift.check(near, drivenKm = 40.0, socNowPercent = socAfter(40.0, 0.26))
        assertTrue(verdict.toString(), verdict is PlanDrift.Verdict.Holding)
    }

    @Test
    fun `the car's own remaining distance wins over the leg's arithmetic`() {
        val verdict = PlanDrift.check(
            plan,
            drivenKm = 100.0,
            socNowPercent = socAfter(100.0, 0.20),
            remainingKm = 240.0,
        )
        val holding = verdict as PlanDrift.Verdict.Holding
        assertEquals(240.0, holding.reading.remainingKm, 1e-9)
        assertEquals(70.0 - 240.0 * 0.20, holding.reading.arrivalPercent, 1e-9)
    }

    @Test
    fun `too little road behind the car says so rather than measuring`() {
        val verdict = PlanDrift.check(plan, drivenKm = 5.0, socNowPercent = 88.0)
        assertEquals(
            PlanDrift.Verdict.Unavailable(PlanDrift.Reason.TOO_SOON),
            verdict,
        )
    }

    @Test
    fun `a charge en route is refused, a descent of one point is not`() {
        assertEquals(
            PlanDrift.Verdict.Unavailable(PlanDrift.Reason.CHARGED_EN_ROUTE),
            PlanDrift.check(plan, drivenKm = 100.0, socNowPercent = 95.0),
        )
        // 91 % after 100 km: one point above departure, which is the gauge's own step.
        val recovered = PlanDrift.check(plan, drivenKm = 100.0, socNowPercent = 91.0)
        val holding = recovered as PlanDrift.Verdict.Holding
        assertEquals(0.0, holding.reading.observedPercentPerKm, 1e-9)
    }

    @Test
    fun `no plan, no charge, no distance and an arrival each say which`() {
        assertEquals(
            PlanDrift.Verdict.Unavailable(PlanDrift.Reason.NO_PLAN),
            PlanDrift.check(null, 100.0, 60.0),
        )
        assertEquals(
            PlanDrift.Verdict.Unavailable(PlanDrift.Reason.NO_CHARGE),
            PlanDrift.check(plan, 100.0, null),
        )
        assertEquals(
            PlanDrift.Verdict.Unavailable(PlanDrift.Reason.NO_DISTANCE),
            PlanDrift.check(plan, null, 60.0),
        )
        assertEquals(
            PlanDrift.Verdict.Unavailable(PlanDrift.Reason.ARRIVED),
            PlanDrift.check(plan, 320.0, 20.0),
        )
    }

    @Test
    fun `the measurement's band shrinks as the road behind it grows`() {
        val early = PlanDrift.check(plan, 20.0, socAfter(20.0, 0.20)) as PlanDrift.Verdict.Holding
        val late = PlanDrift.check(plan, 200.0, socAfter(200.0, 0.20)) as PlanDrift.Verdict.Holding
        // Same rate, same route: the only thing that changed is how much evidence there is.
        assertTrue(
            "${early.reading.bandPercent} vs ${late.reading.bandPercent}",
            early.reading.bandPercent > late.reading.bandPercent,
        )
    }

    @Test
    fun `the rate the plan was made with is the one with the climb already in it`() {
        // What the app hands over: the planner's own fold, not a second copy of it.
        val rate = SocRate(0.20, 0.02, SocRate.Source.TRIP_HISTORY, sampleCount = 8)
        val effective = ChargeStopPlan.effectiveRate(rate, 300.0, RouteGrade.Cost(6.0, 1.5))
        assertEquals(0.22, effective.percentPerKm, 1e-9)
        assertEquals(0.025, effective.uncertaintyPercentPerKm, 1e-9)
    }
}
