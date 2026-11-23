package com.evsuite.hardware.telemetry

import kotlin.math.roundToInt

/**
 * A trip out of 100, and the five measured parts it is made of (CP-099).
 *
 * Every part is a measurement this car is known to give: the speed channel, read at 1 Hz by
 * [EnergyTripAccumulator] and taken as a slope over two seconds by [SpeedSlope]; the battery
 * power at the same rate; and the charge against the driver's own fitted expectation from
 * [EcoVerdict]. A part that could not be measured is null and the total is taken over the
 * others — a town drive has no motorway speed to judge — and a trip where none could be
 * measured has no score at all, never a default one.
 *
 * The scales are calibration knobs rather than physics, set from the car's 108 exported trips
 * of 2026-10/11 where they could be and from the stated reasoning where not: no exported track
 * is fine enough (one sample per 5 s) to recalibrate them. A score that starts near 100 has
 * nothing left to reward.
 */
data class EcoScore(
    val total: Int,
    /**
     * Of the speed shed, the share shed beyond [FIRM_MS2]: braking that late leaves the motor
     * less room to take it back. Trips recorded before CP-099 keep the hard stops per 10 km.
     */
    val anticipation: Int?,
    /**
     * Of the speed gained, the share gained beyond [FIRM_MS2]. Trips recorded before CP-099
     * keep the share of moving time beyond the hard-acceleration threshold.
     */
    val smoothness: Int?,
    /** Charge against the driver's own expectation at this speed and temperature. */
    val efficiency: Int?,
    /** On the distance at [FAST_FROM_KMH] or more, the mean km/h above [CRUISE_KMH]. */
    val speed: Int? = null,
    /** Of what the brakes had to absorb, the share the battery took back. */
    val regeneration: Int? = null,
) {
    companion object {
        fun of(summary: EnergyTripSummary, verdict: EcoVerdict?): EcoScore? {
            // Under a junction's length nothing was measured: a 600 m crawl across a car park
            // scored 100 on smoothness alone and counted in the average, the record and the run
            // like a 250 km drive (2026-10-26/29 history).
            val distanceKm = summary.recordedDistanceKm?.takeIf { it >= MIN_DISTANCE_KM } ?: return null
            val anticipation = share(summary.slowedFirmKmh, summary.slowedKmh, MIN_SPEED_CHANGE_KMH)
                ?.let { scale(it, best = 0.0, worst = FIRM_SHARE_AT_ZERO) }
                ?: summary.harshBrakings?.takeIf { summary.slowedKmh == null }?.let { brakings ->
                    scale(brakings / distanceKm * PER_10_KM, best = 0.0, worst = BRAKINGS_PER_10_KM_AT_ZERO)
                }
            val smoothness = share(summary.gainedFirmKmh, summary.gainedKmh, MIN_SPEED_CHANGE_KMH)
                ?.let { scale(it, best = 0.0, worst = FIRM_SHARE_AT_ZERO) }
                ?: summary.harshMs?.takeIf { summary.gainedKmh == null }?.let { harsh ->
                    summary.movingMs?.takeIf { it >= MIN_MOVING_MS }?.let { moving ->
                        scale(harsh.toDouble() / moving * PERCENT, best = 0.0, worst = HARSH_SHARE_AT_ZERO)
                    }
                }
            val efficiency = verdict?.takeIf { it.band.value != null }?.deltaPercent?.let {
                scale(it, best = DELTA_AT_FULL, worst = DELTA_AT_ZERO)
            }
            val speed = summary.fastKm?.takeIf { it >= MIN_FAST_KM }?.let { km ->
                summary.fastExcessKmhKm?.let { scale(it / km, best = 0.0, worst = EXCESS_KMH_AT_ZERO) }
            }
            val regeneration = share(summary.brakingRecoveredKwh, summary.brakingDemandKwh, MIN_BRAKING_KWH)
                ?.let { scale(it, best = RECOVERED_AT_FULL, worst = RECOVERED_AT_ZERO) }
            val parts = listOf(
                anticipation to ANTICIPATION_WEIGHT,
                smoothness to SMOOTHNESS_WEIGHT,
                efficiency to EFFICIENCY_WEIGHT,
                speed to SPEED_WEIGHT,
                regeneration to REGENERATION_WEIGHT,
            ).filter { it.first != null }
            if (parts.isEmpty()) return null
            val total = parts.sumOf { it.first!! * it.second } / parts.sumOf { it.second }
            return EcoScore(total.roundToInt(), anticipation, smoothness, efficiency, speed, regeneration)
        }

        /** [part] over [whole] in percent, or null below [minWhole] where one event is the whole trip. */
        private fun share(part: Double?, whole: Double?, minWhole: Double): Double? {
            if (part == null || whole == null || whole < minWhole) return null
            return (part / whole * PERCENT).coerceIn(0.0, PERCENT)
        }

        /** 100 at [best], 0 at [worst], straight in between and clamped outside. */
        private fun scale(value: Double, best: Double, worst: Double): Int? {
            if (!value.isFinite()) return null
            val share = ((worst - value) / (worst - best)).coerceIn(0.0, 1.0)
            return (share * PERCENT).roundToInt()
        }

        /** Shorter than the fitter's own segment, a single junction would be a habit. */
        const val MIN_DISTANCE_KM = 2.0
        const val MIN_MOVING_MS = 60_000L

        /**
         * Firm, not hard: 1,5 m/s² is where a calm driver stops easing off and starts pressing.
         * The hard threshold (2,5) stays the split ring's cue; scoring on it gave 0 or 100 and
         * little between — the same 10 km commute read 100 three times and 0 four times.
         */
        const val FIRM_MS2 = 1.5

        /** Half the speed changed firmly is a drive with nothing gentle left in it. */
        const val FIRM_SHARE_AT_ZERO = 50.0

        /** Four stops from 50 km/h: below that one stop is the whole verdict. */
        const val MIN_SPEED_CHANGE_KMH = 200.0

        /** Pre-CP-099 trips only. Six hard stops every 10 km is no anticipation left to measure. */
        const val BRAKINGS_PER_10_KM_AT_ZERO = 6.0

        /**
         * Pre-CP-099 trips only. One percent of moving time spent hard is the bottom of the
         * scale: on the car's 20 newest trips the share ran 0,00–0,85 %.
         */
        const val HARSH_SHARE_AT_ZERO = 1.0

        /** 15 % under the driver's own expectation is full marks; 25 % over is none. */
        const val DELTA_AT_FULL = -15.0
        const val DELTA_AT_ZERO = 25.0

        /**
         * Speed is judged only where the air dominates. From 95 km/h on, every km/h above 110
         * counts: the drag grows with its square, and 130 against 110 is about 40 % more of it.
         * The expectation in [efficiency] is the driver's own at the same speed, so without this
         * part a faster drive was never worse than a slower one.
         */
        const val FAST_FROM_KMH = 95.0
        const val CRUISE_KMH = 110.0
        const val EXCESS_KMH_AT_ZERO = 25.0
        const val MIN_FAST_KM = 2.0

        /**
         * About 0,8 makes it back through motor and inverter at best, so 70 % recovered is full
         * marks; 20 % is braking the friction brakes took almost all of. Below 0,05 kWh of
         * braking a single stop decides it.
         */
        const val RECOVERED_AT_FULL = 70.0
        const val RECOVERED_AT_ZERO = 20.0
        const val MIN_BRAKING_KWH = 0.05

        const val ANTICIPATION_WEIGHT = 0.25
        const val SMOOTHNESS_WEIGHT = 0.15
        const val EFFICIENCY_WEIGHT = 0.30
        const val SPEED_WEIGHT = 0.15
        const val REGENERATION_WEIGHT = 0.15

        private const val PER_10_KM = 10.0
        private const val PERCENT = 100.0
    }
}
