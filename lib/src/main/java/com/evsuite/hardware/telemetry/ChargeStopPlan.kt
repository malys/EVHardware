package com.evsuite.hardware.telemetry

import kotlin.math.abs

/**
 * Whether a route needs a charging stop, and in how many kilometres.
 *
 * This is the sentence the whole project was described by — *"il faudra recharger une fois dans
 * N km"* — and it is arithmetic, not a planner. [chain] walks a route long enough to need the
 * sentence twice, and that is all it does: there is deliberately no optimisation here, no
 * charging curve, no cost function and no search over which chargers to use. ABRP does that and
 * does it better; what the car cannot tell its driver is the one thing this answers.
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

    /**
     * What a stop is planned to leave with.
     *
     * Not a full pack: the last fifth of a rapid charge takes as long as the first three, and a
     * plan that assumed it would be waited for is a plan about a driver nobody knows. The driver
     * declares it (CP-054) and this is only the figure a plan starts from.
     */
    const val DEFAULT_DEPARTURE_PERCENT = 80.0

    /**
     * How many driving legs a chain may hold.
     *
     * A trip needing a fourth stop is a day's drive, and this screen is not the place it should
     * be planned. The cap is also what guarantees the walk terminates whatever the arithmetic
     * does.
     */
    const val MAX_LEGS = 4

    /** Under this a leg is not a drive, it is the same leg again. */
    const val MIN_LEG_KM = 1.0

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

    /**
     * [NO_PROGRESS] and [TOO_MANY_LEGS] are [chain]'s alone: one leg either goes somewhere or the
     * chain is not a plan, and a trip past [MAX_LEGS] is one this screen should not answer.
     */
    enum class Reason { NO_CHARGE, NO_ROUTE, NO_RATE, BAND_TOO_WIDE, NO_PROGRESS, TOO_MANY_LEGS }

    /**
     * One leg of a chained plan: the drive from [startKm] to [endKm] on [startPercent] of charge.
     *
     * Distances are measured from the origin, not from the start of the leg, because that is
     * where everything else on the screen measures from — the charger search, the route geometry
     * and the waypoints handed to the car.
     *
     * A leg whose [plan] is [Plan.Refused] ends where it started: it is the point the trip stops
     * being planned, kept in the list so the legs before it still read.
     */
    data class Leg(
        val startKm: Double,
        val endKm: Double,
        val startPercent: Double,
        val plan: Plan,
    ) {
        /** True when the leg ends at a charging stop rather than at the destination. */
        val isStop: Boolean get() = plan is Plan.Stop
    }

    /** Every leg of one trip, in the order they are driven. */
    data class Chain(val legs: List<Leg>) {

        /** The legs that end at a charging stop — as many as the trip needs, and never more. */
        val stops: List<Leg> get() = legs.filter { it.isStop }

        /** The arrival at the destination, or null when the chain never got that far. */
        val arrival: Plan.NoStop? get() = legs.lastOrNull()?.plan as? Plan.NoStop

        /** Why the chain stopped short, or null when it reaches the destination. */
        val refusal: Reason? get() = (legs.lastOrNull()?.plan as? Plan.Refused)?.reason
    }

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

        return leg(
            socPercent,
            routeKm,
            effectiveRate(rate, routeKm, grade),
            reservePercent,
            bandOverLeg = false,
        )
    }

    /**
     * The same trip planned as far as it goes, charging on the way.
     *
     * [of] answers one question — does this charge reach the far end — and a route long enough to
     * need two stops makes it answer it about a car that never stops. Over 680 km the band on
     * that non-existent drive is tens of percent wide and the plan is refused, which is honest
     * about the wrong trip: whether a stop is needed near Brive is not uncertain at all.
     *
     * So the route is walked leg by leg. Each leg is planned on the charge held at its start, and
     * **each leg carries the band of its own distance** — that, not a looser cap, is what makes a
     * long trip plannable. [MAX_BAND_PERCENT] is unchanged; it now measures the drive it was
     * always about.
     *
     * What this is still not: an optimiser. There is no charging curve, no cost function and no
     * search over which chargers to use. The charge a stop is left with is [departurePercent], a
     * figure the driver declares, because how long they plug in is theirs and modelling it would
     * be inventing a number this app cannot measure.
     *
     * @param departurePercent the charge each stop is planned to leave with.
     * @param maxLegs how many driving legs may be planned before the trip is refused as one this
     *   screen should not be answering. The refusal itself is appended as a further leg.
     */
    fun chain(
        socPercent: Double?,
        routeKm: Double?,
        rate: SocRate?,
        reservePercent: Double = DEFAULT_RESERVE_PERCENT,
        grade: RouteGrade.Cost? = null,
        departurePercent: Double = DEFAULT_DEPARTURE_PERCENT,
        maxLegs: Int = MAX_LEGS,
    ): Chain {
        // The refusals that are about the trip rather than about a leg of it. One leg holding
        // them keeps the shape of the answer the same whatever went wrong.
        if (socPercent == null || !socPercent.isFinite()) return refusal(Reason.NO_CHARGE)
        if (routeKm == null || !routeKm.isFinite() || routeKm <= 0.0) {
            return refusal(Reason.NO_ROUTE)
        }
        if (rate == null || rate.percentPerKm <= 0.0) return refusal(Reason.NO_RATE)

        // Folded once, over the whole route, exactly as [of] folds it. A leg that spread a
        // whole-route total of climb over its own length would charge the col to every leg.
        val totalKm: Double = routeKm
        val effective = effectiveRate(rate, totalKm, grade)
        val leaveWith = departurePercent.coerceIn(0.0, 100.0)
        val legs = ArrayList<Leg>()
        var startKm = 0.0
        var socAtStart: Double = socPercent
        while (legs.size < maxLegs) {
            val step = leg(socAtStart, totalKm - startKm, effective, reservePercent, true)
            if (step !is Plan.Stop) {
                // NoStop ends at the destination; a refusal ends where the driver would be
                // standing when the plan stopped being one.
                val endKm = if (step is Plan.NoStop) totalKm else startKm
                legs += Leg(startKm, endKm, socAtStart, step)
                return Chain(legs)
            }
            // A leg that gets nowhere never ends: the charge it leaves with buys no distance, and
            // another turn of this loop would plan the same leg again. Said once, as a refusal.
            if (step.afterKm < MIN_LEG_KM) {
                legs += Leg(startKm, startKm, socAtStart, Plan.Refused(Reason.NO_PROGRESS))
                return Chain(legs)
            }
            legs += Leg(startKm, startKm + step.afterKm, socAtStart, step)
            startKm += step.afterKm
            socAtStart = leaveWith
        }
        legs += Leg(startKm, startKm, socAtStart, Plan.Refused(Reason.TOO_MANY_LEGS))
        return Chain(legs)
    }

    /**
     * One leg's plan.
     *
     * @param bandOverLeg where the band is measured. [of] measures it over the whole route,
     *   because a single-stop plan is a claim about arriving at the far end of it. [chain]
     *   measures it over the distance this leg actually drives, which is the stop it is about.
     */
    private fun leg(
        socPercent: Double,
        routeKm: Double,
        effective: SocRate,
        reservePercent: Double,
        bandOverLeg: Boolean,
    ): Plan {
        val spendable = socPercent - reservePercent
        val needed = effective.percentPerKm * routeKm
        if (spendable >= needed) {
            val band = 2.0 * effective.uncertaintyPercentPerKm * routeKm
            if (band > MAX_BAND_PERCENT) return Plan.Refused(Reason.BAND_TOO_WIDE)
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
        val band =
            2.0 * effective.uncertaintyPercentPerKm * (if (bandOverLeg) reachKm else routeKm)
        if (band > MAX_BAND_PERCENT) return Plan.Refused(Reason.BAND_TOO_WIDE)
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

    private fun refusal(reason: Reason) =
        Chain(listOf(Leg(0.0, 0.0, 0.0, Plan.Refused(reason))))

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
