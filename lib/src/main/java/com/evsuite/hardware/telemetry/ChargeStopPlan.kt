package com.evsuite.hardware.telemetry

import kotlin.math.abs

/**
 * Whether a route needs a charging stop, and in how many kilometres.
 *
 * This is the sentence the whole project was described by — *"il faudra recharger une fois dans
 * N km"* — and it is arithmetic, not a planner. There is deliberately no optimisation here: no
 * charging curve, no multi-stop search, no cost function. ABRP does that and does it better;
 * what the car cannot tell its driver is the one thing this answers.
 *
 * It plans to a **reserve**, never to zero. A forecast that says the car arrives at 3 % is not
 * saying the trip works, it is saying the trip works if nothing goes wrong, and something goes
 * wrong. The reserve is a stated number the driver can change rather than an optimism built into
 * the arithmetic where nobody can see it.
 *
 * Uncertainty is the reason this refuses. [SocRate] carries a band, the band grows with
 * distance, and past some distance "you will arrive between 5 % and 45 %" is not a plan. The
 * refusal is visible; a wide answer rendered as a plan is not.
 */
object ChargeStopPlan {

    /** What a careful driver keeps in the pack. Not a physical limit — a stated choice. */
    const val DEFAULT_RESERVE_PERCENT = 10.0

    /**
     * Wider than this and the plan is refused. Looser than [ArrivalSocForecast.MAX_BAND_PERCENT]
     * because a stop decision survives more uncertainty than a printed arrival figure: "you will
     * need to charge" holds even when "you will arrive at 12 %" does not.
     */
    const val MAX_BAND_PERCENT = 25.0

    sealed interface Plan {
        /** The route completes with the reserve intact. */
        data class NoStop(
            val arrivalPercent: Double,
            val marginPercent: Double,
            val bandPercent: Double,
        ) : Plan

        /** A stop is needed, this far along the route. */
        data class Stop(
            val afterKm: Double,
            val bandKm: Double,
            val shortfallKm: Double,
            val bandPercent: Double,
        ) : Plan

        /** Nothing worth acting on, and the reason. */
        data class Refused(val reason: Reason) : Plan
    }

    enum class Reason { NO_CHARGE, NO_ROUTE, NO_RATE, BAND_TOO_WIDE }

    /**
     * @param socPercent charge now.
     * @param routeKm the whole remaining route.
     * @param rate charge spent per kilometre, with its own uncertainty.
     * @param reservePercent the charge the driver refuses to go below.
     * @param grade what the route's climb and descent cost, from [RouteGrade], or null when no
     *   elevation profile came back. Null is not zero: a plan with no profile is exactly the
     *   plan this made before profiles existed.
     */
    fun of(
        socPercent: Double?,
        routeKm: Double?,
        rate: SocRate?,
        reservePercent: Double = DEFAULT_RESERVE_PERCENT,
        grade: RouteGrade.Cost? = null,
    ): Plan {
        if (socPercent == null || !socPercent.isFinite()) return Plan.Refused(Reason.NO_CHARGE)
        if (routeKm == null || !routeKm.isFinite() || routeKm <= 0.0) {
            return Plan.Refused(Reason.NO_ROUTE)
        }
        if (rate == null || rate.percentPerKm <= 0.0) return Plan.Refused(Reason.NO_RATE)

        val effective = effectiveRate(rate, routeKm, grade)
        val band = 2.0 * effective.uncertaintyPercentPerKm * routeKm
        if (band > MAX_BAND_PERCENT) return Plan.Refused(Reason.BAND_TOO_WIDE)

        val spendable = socPercent - reservePercent
        val needed = effective.percentPerKm * routeKm
        if (spendable >= needed) {
            return Plan.NoStop(
                arrivalPercent = socPercent - needed,
                marginPercent = spendable - needed,
                bandPercent = band,
            )
        }

        // Pessimistic on purpose: the distance the car reaches if it spends at the top of the
        // band. Being told to charge earlier than strictly necessary is a cost of minutes;
        // being told to charge later is a cost of a tow truck.
        val worstRate = effective.percentPerKm + effective.uncertaintyPercentPerKm
        val reachKm = (spendable / worstRate).coerceAtLeast(0.0)
        val bestRate =
            (effective.percentPerKm - effective.uncertaintyPercentPerKm).coerceAtLeast(1e-6)
        val optimisticKm = spendable / bestRate
        return Plan.Stop(
            afterKm = reachKm,
            bandKm = (optimisticKm - reachKm).coerceAtLeast(0.0),
            shortfallKm = routeKm - reachKm,
            bandPercent = band,
        )
    }

    /**
     * The rate with the climb folded into it, so the rest of the arithmetic never learns that
     * grade exists.
     *
     * The profile is a total and not a position: the col may be at kilometre 10 or at 400 and
     * this spreads it evenly over the route either way, which makes a stop before a late col
     * slightly early and one after an early col slightly late. Early is the safe direction and
     * the error is small next to the rate's own band; the upgrade, when a route needs it, is a
     * per-segment profile rather than two cumulative numbers.
     *
     * Public because [PlanDrift] has to compare a drive against the rate the plan was actually
     * made with, and a second copy of this fold would drift away from the one that planned.
     */
    fun effectiveRate(rate: SocRate, routeKm: Double, grade: RouteGrade.Cost?): SocRate {
        if (grade == null) return rate
        return rate.copy(
            // Floored, not clamped away: a descent long enough to make the net rate negative
            // would otherwise plan a car that gains charge for ever.
            percentPerKm = (rate.percentPerKm + grade.percent / routeKm).coerceAtLeast(1e-6),
            uncertaintyPercentPerKm = rate.uncertaintyPercentPerKm +
                abs(grade.uncertaintyPercent) / routeKm,
        )
    }
}
