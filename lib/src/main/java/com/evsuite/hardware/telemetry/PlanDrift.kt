package com.evsuite.hardware.telemetry

/**
 * Whether the plan the driver chose at the kerb is still the plan they are driving.
 *
 * A plan is a prediction, and predictions go wrong on the road: a headwind, a cold pack, traffic,
 * a driver going faster than the row they picked. The moment worth a line is the one where the
 * charge left on arrival has fallen under the reserve while there is still a charger behind the
 * car rather than ahead of it, and nobody notices that by watching a percentage move.
 *
 * **This costs nothing.** Charge and the odometer are already being read once a second, and the
 * plan was written down when it was chosen. There is no request here, no quota and no network —
 * the whole of it is a division. Only a *re-proposal* costs a request, and that happens on a tap
 * somewhere else.
 *
 * **The alarm sits outside the model's own band, never on a point.** The rate a drive is actually
 * spending is measured through two quantised instruments — a gauge that steps a whole percent and
 * an odometer that answers whole kilometres — so it arrives with a band of its own, and the plan
 * arrived with one too. Drift is the two bands failing to overlap. Anything narrower would fire on
 * a hill.
 *
 * **A drift that costs nothing is not reported.** Driving faster than the row that was chosen is
 * the driver's business, and a companion that says so while still arriving at 30 % is a companion
 * that gets ignored by the time it matters. So [Verdict.Short] needs both halves: the spending
 * left the plan's band *and* the arrival no longer clears the reserve.
 */
object PlanDrift {

    /**
     * Below this the instruments' own steps dominate whatever they measured.
     *
     * At 15 km a whole percent of gauge is already 0,07 %/km of band — about a third of what this
     * car spends — and under it the measurement says less than the plan it would be contradicting.
     */
    const val MIN_DISTANCE_KM = 15.0

    /** The finest step the charge gauge publishes. Two readings differ by at most this. */
    const val SOC_RESOLUTION_PERCENT = 1.0

    /** The adapter's odometer answers whole kilometres, so a difference of them carries one. */
    const val ODOMETER_RESOLUTION_KM = 1.0

    /**
     * The plan as it was handed to the car, frozen at the moment the driver chose it.
     *
     * @param legKm the leg being driven — to the charging stop where the plan has one, and to the
     *   destination where it does not. Never the whole route past a stop: arriving under the
     *   reserve at a destination the plan says to charge before is not drift, it is the plan.
     * @param plannedRatePercentPerKm the rate the plan was made with, climb already folded in by
     *   [ChargeStopPlan.effectiveRate].
     * @param reservePercent the charge the driver refuses to go below.
     */
    data class Followed(
        val legKm: Double,
        val socAtDeparturePercent: Double,
        val plannedRatePercentPerKm: Double,
        val plannedUncertaintyPercentPerKm: Double,
        val reservePercent: Double,
    )

    /** Why there is nothing to say, kept apart because the reasons read differently. */
    enum class Reason {
        /** Nothing was chosen, or it was forgotten. */
        NO_PLAN,

        /** The gauge is unreadable. */
        NO_CHARGE,

        /** Nothing can say how far this car has come since the plan. */
        NO_DISTANCE,

        /** Too little road behind the car for the measurement to mean anything. */
        TOO_SOON,

        /** The car has more charge than it left with; the departure figure no longer anchors. */
        CHARGED_EN_ROUTE,

        /** The leg is behind the car. */
        ARRIVED,
    }

    /** What the drive is actually doing, whatever the verdict on it. */
    data class Reading(
        val drivenKm: Double,
        val remainingKm: Double,
        /** Charge per kilometre this drive has actually spent. */
        val observedPercentPerKm: Double,
        /** Charge left at the end of the leg if the rest is spent like the part behind. */
        val arrivalPercent: Double,
        /** Half-width on [arrivalPercent]. */
        val bandPercent: Double,
    )

    sealed interface Verdict {
        /** The plan is still true: the spending is inside its band, or it costs the arrival nothing. */
        data class Holding(val reading: Reading) : Verdict

        /**
         * The plan stopped being true.
         *
         * @param shortfallPercent how far under the reserve the pessimistic edge of the arrival
         *   falls. Always positive, and it is what a slower speed has to give back.
         */
        data class Short(val reading: Reading, val shortfallPercent: Double) : Verdict

        data class Unavailable(val reason: Reason) : Verdict
    }

    /**
     * @param drivenKm road covered since [plan] was chosen — an odometer difference, not a trip.
     * @param remainingKm the car's own remaining distance where the head unit is guiding on this
     *   leg, which is the better number because it knows about the road actually taken. Null
     *   falls back to the leg's own arithmetic.
     */
    fun check(
        plan: Followed?,
        drivenKm: Double?,
        socNowPercent: Double?,
        remainingKm: Double? = null,
    ): Verdict {
        if (plan == null || !plan.legKm.isFinite() || plan.legKm <= 0.0) {
            return Verdict.Unavailable(Reason.NO_PLAN)
        }
        if (plan.plannedRatePercentPerKm <= 0.0) return Verdict.Unavailable(Reason.NO_PLAN)
        if (socNowPercent == null || !socNowPercent.isFinite() || socNowPercent !in 0.0..100.0) {
            return Verdict.Unavailable(Reason.NO_CHARGE)
        }
        if (drivenKm == null || !drivenKm.isFinite() || drivenKm < 0.0) {
            return Verdict.Unavailable(Reason.NO_DISTANCE)
        }
        if (drivenKm < MIN_DISTANCE_KM) return Verdict.Unavailable(Reason.TOO_SOON)

        val spent = plan.socAtDeparturePercent - socNowPercent
        // A gauge that rose by a point down a long descent is a gauge, not a charging session.
        // Anything past its own step means the car was plugged in and the departure figure
        // stopped anchoring anything.
        if (spent < -SOC_RESOLUTION_PERCENT) return Verdict.Unavailable(Reason.CHARGED_EN_ROUTE)

        val remaining = remainingKm?.takeIf { it.isFinite() } ?: (plan.legKm - drivenKm)
        if (remaining <= 0.0) return Verdict.Unavailable(Reason.ARRIVED)

        val observed = (spent / drivenKm).coerceAtLeast(0.0)
        // Both instruments quantise, and both errors land on the same division. Added rather
        // than combined in quadrature: they are two systematic steps, not two noise sources,
        // and the wide reading is the one that refuses to raise an alarm it cannot support.
        val observedBand =
            (SOC_RESOLUTION_PERCENT + observed * ODOMETER_RESOLUTION_KM) / drivenKm

        val arrival = socNowPercent - observed * remaining
        val band = observedBand * remaining
        val reading = Reading(drivenKm, remaining, observed, arrival, band)

        val outsideBand =
            observed - plan.plannedRatePercentPerKm >
                plan.plannedUncertaintyPercentPerKm + observedBand
        val shortfall = plan.reservePercent - (arrival - band)
        return if (outsideBand && shortfall > 0.0) {
            Verdict.Short(reading, shortfall)
        } else {
            Verdict.Holding(reading)
        }
    }
}
