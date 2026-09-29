package com.evsuite.hardware.telemetry

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * What a drive asked of the pack at the moments a pack minds most (CP-090): hard discharge at a
 * low charge or in the cold, and regeneration pushed into a cold pack.
 *
 * Measured quantities only, never a wear figure. "Hard" is the trip's own: discharge above
 * [HARD_SHARE] of that trip's peak, so a gentle driver's hardest moments count as well as a
 * heavy one's.
 *
 * [TripSample.batteryPowerKw]'s sign is unvalidated on this car (RI-002), so each trip orients
 * itself: the charge fell, so the way the power integral moved over the trip is discharge. A trip
 * whose charge did not fall, or whose integral did not move, cannot say which way is which and
 * gives no figure.
 */
data class DrivingStress(
    /** Minutes of hard discharge below [LOW_SOC_PERCENT] charge or below [COLD_CELSIUS] outside. */
    val stressedMinutes: Double,
    /** Energy regenerated below [COLD_CELSIUS] outside; null when the temperature was never read. */
    val coldRegeneratedKwh: Double?,
) {
    companion object {

        /**
         * Null when the trip cannot say: no orientation, or more than [MAX_UNDECIDED_SHARE] of
         * its time with an unread power, or a hard moment with an unread charge or temperature.
         */
        fun of(samples: List<TripSample>): DrivingStress? {
            val socs = samples.mapNotNull { it.socPercent }
            if (socs.size < 2 || socs.first() - socs.last() < MIN_SOC_DROP) return null
            val segments = samples.zipWithNext().filter { (a, b) -> b.atMs - a.atMs in 1..MAX_GAP_MS }
            val net = segments.sumOf { (a, b) -> (a.batteryPowerKw ?: 0f) * hours(a, b) }
            val orientation = when {
                net >= MIN_NET_KWH -> 1.0
                net <= -MIN_NET_KWH -> -1.0
                else -> return null
            }
            val peak = samples.mapNotNull { it.batteryPowerKw }.maxOfOrNull { it * orientation } ?: return null
            if (peak <= 0.0) return null

            var totalMs = 0L
            var undecidedMs = 0L
            var stressedMs = 0L
            var coldKnown = false
            var coldRegen = 0.0
            segments.forEach { (a, b) ->
                val ms = b.atMs - a.atMs
                totalMs += ms
                // An unread power could have been a hard one.
                val power = a.batteryPowerKw ?: run { undecidedMs += ms; return@forEach }
                val discharge = power * orientation
                val cold = a.outsideTempCelsius?.let { it < COLD_CELSIUS }
                val low = a.socPercent?.let { it < LOW_SOC_PERCENT }
                if (cold != null) coldKnown = true
                if (cold == true && discharge < 0.0) coldRegen += -discharge * hours(a, b)
                if (discharge <= HARD_SHARE * peak) return@forEach
                when {
                    cold == true || low == true -> stressedMs += ms
                    cold == null || low == null -> undecidedMs += ms
                }
            }
            if (totalMs == 0L || undecidedMs > MAX_UNDECIDED_SHARE * totalMs) return null
            return DrivingStress(stressedMs / MINUTE_MS, coldRegen.takeIf { coldKnown })
        }

        /** Per local month, over the trips that could say; a month none could say is absent. */
        fun monthly(trips: List<StoredTrip>, zone: ZoneId): List<MonthlyDrivingStress> = trips
            .mapNotNull { trip -> of(trip.samples.orEmpty())?.let { trip.summary.startedAtMs to it } }
            .groupBy { (atMs, _) -> YearMonth.from(Instant.ofEpochMilli(atMs).atZone(zone)) }
            .map { (month, list) ->
                val regen = list.mapNotNull { it.second.coldRegeneratedKwh }
                MonthlyDrivingStress(
                    month = month,
                    stressedMinutes = list.sumOf { it.second.stressedMinutes },
                    coldRegeneratedKwh = regen.takeIf { it.isNotEmpty() }?.sum(),
                    trips = list.size,
                )
            }
            .sortedBy { it.month }

        private fun hours(a: TripSample, b: TripSample) = (b.atMs - a.atMs) / HOUR_MS

        const val HARD_SHARE = 0.6
        const val LOW_SOC_PERCENT = 20f
        const val COLD_CELSIUS = 0f
        /** A trip that spent less than a point cannot orient its power. */
        const val MIN_SOC_DROP = 1f
        const val MIN_NET_KWH = 0.1
        /** A longer hole in the track is a pause, not a stretch of known power. */
        const val MAX_GAP_MS = 5 * 60_000L
        /** A dropped reading here and there is not a blind trip; more than this is. */
        const val MAX_UNDECIDED_SHARE = 0.1
        private const val MINUTE_MS = 60_000.0
        private const val HOUR_MS = 3_600_000.0
    }
}

data class MonthlyDrivingStress(
    val month: YearMonth,
    val stressedMinutes: Double,
    val coldRegeneratedKwh: Double?,
    /** Trips that could say; the others are not counted as zero. */
    val trips: Int,
)
