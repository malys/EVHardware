package com.evsuite.hardware.telemetry

import kotlin.math.roundToInt

/**
 * A trip out of 100, and the three measured parts it is made of.
 *
 * Every part is a measurement this car is known to give: hard braking and hard acceleration
 * from the speed channel, counted at 1 Hz by [EnergyTripAccumulator], and the charge against
 * the driver's own fitted expectation from [EcoVerdict]. A part that could not be measured is
 * null and the total is taken over the others; a trip where none could be measured has no score
 * at all, never a default one.
 *
 * The scales are calibration knobs rather than physics. They are set so a calm town drive at
 * the driver's usual consumption lands in the sixties and the eighties need effort: a score that
 * starts near 100 has nothing left to reward.
 */
data class EcoScore(
    val total: Int,
    /** Hard braking per 10 km: braking hard throws away what regeneration could not take back. */
    val anticipation: Int?,
    /** Share of moving time beyond the hard-acceleration threshold, either sign. */
    val smoothness: Int?,
    /** Charge against the driver's own expectation at this speed and temperature. */
    val efficiency: Int?,
) {
    companion object {
        fun of(summary: EnergyTripSummary, verdict: EcoVerdict?): EcoScore? {
            val distanceKm = summary.recordedDistanceKm
            val anticipation = summary.harshBrakings?.let { brakings ->
                distanceKm?.takeIf { it >= MIN_DISTANCE_KM }?.let { km ->
                    scale(brakings / km * PER_10_KM, best = 0.0, worst = BRAKINGS_PER_10_KM_AT_ZERO)
                }
            }
            val smoothness = summary.harshMs?.let { harsh ->
                summary.movingMs?.takeIf { it >= MIN_MOVING_MS }?.let { moving ->
                    scale(harsh.toDouble() / moving * PERCENT, best = 0.0, worst = HARSH_SHARE_AT_ZERO)
                }
            }
            val efficiency = verdict?.takeIf { it.band.value != null }?.deltaPercent?.let {
                scale(it, best = DELTA_AT_FULL, worst = DELTA_AT_ZERO)
            }
            val parts = listOf(
                anticipation to ANTICIPATION_WEIGHT,
                smoothness to SMOOTHNESS_WEIGHT,
                efficiency to EFFICIENCY_WEIGHT,
            ).filter { it.first != null }
            if (parts.isEmpty()) return null
            val total = parts.sumOf { it.first!! * it.second } / parts.sumOf { it.second }
            return EcoScore(total.roundToInt(), anticipation, smoothness, efficiency)
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

        /** Six hard stops every 10 km is a drive with no anticipation left to measure. */
        const val BRAKINGS_PER_10_KM_AT_ZERO = 6.0

        /** A quarter of moving time spent hard is the bottom of the scale. */
        const val HARSH_SHARE_AT_ZERO = 25.0

        /** 15 % under the driver's own expectation is full marks; 25 % over is none. */
        const val DELTA_AT_FULL = -15.0
        const val DELTA_AT_ZERO = 25.0

        const val ANTICIPATION_WEIGHT = 0.35
        const val SMOOTHNESS_WEIGHT = 0.25
        const val EFFICIENCY_WEIGHT = 0.40

        private const val PER_10_KM = 10.0
        private const val PERCENT = 100.0
    }
}
