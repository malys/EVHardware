package com.evsuite.hardware.telemetry

/** One rise in charge: a charge session, identified the only way this car makes possible. */
data class BatteryChargeSession(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val startSocPercent: Double,
    val endSocPercent: Double,
    /**
     * Mean and peak points of charge per hour. The rate is what separates a wall box from a
     * fast charger here: the car publishes no charger type, and a DC session is an order of
     * magnitude faster than an AC one on the same pack.
     */
    val meanPercentPerHour: Double,
    val peakPercentPerHour: Double,
    /** Outside temperature, named as such — the pack's own is unpublished on this firmware. */
    val meanOutsideTempCelsius: Double?,
) {
    val gainedPercent: Double get() = endSocPercent - startSocPercent
    val durationHours: Double get() = (endedAtMs - startedAtMs) / 3_600_000.0
}

data class BatteryExposureReport(
    val spanDays: Double,
    /** Hours the ledger actually covers, which is not the same as the span it stretches over. */
    val observedHours: Double,
    val hoursAboveHighSoc: Double,
    val hoursBelowLowSoc: Double,
    val meanSocPercent: Double,
    val equivalentFullCycles: Double,
    val kmPerEquivalentCycle: Double?,
    val sessions: List<BatteryChargeSession>,
)

/**
 * What this pack has been subjected to — which is not the same question as how healthy it is.
 *
 * Calendar ageing is driven by time spent at a high charge and by temperature, cycle ageing by
 * throughput and rate. The car reports none of it and keeps no history a driver can read, while
 * this app has been recording the raw material since the ledger existed.
 *
 * Exposure is never combined with [StateOfHealthEstimate] into a score. They are two
 * measurements with two provenances, and a single number would hide which one moved.
 */
class BatteryExposure(
    private val highSocPercent: Double = HIGH_SOC_PERCENT,
    private val lowSocPercent: Double = LOW_SOC_PERCENT,
) {
    fun analyse(entries: List<BatteryLedgerEntry>): BatteryExposureReport? {
        val ordered = entries.filter { it.socPercent.isFinite() }.sortedBy { it.atMs }
        if (ordered.size < 2) return null

        var observedHours = 0.0
        var aboveHours = 0.0
        var belowHours = 0.0
        var socHours = 0.0
        var spentPercent = 0.0
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val entry = ordered[index]
            val hours = (entry.atMs - previous.atMs) / MS_PER_HOUR
            if (hours <= 0.0) continue
            val start = previous.socPercent.toDouble()
            val end = entry.socPercent.toDouble()
            observedHours += hours
            aboveHours += hours * fractionAbove(start, end, highSocPercent)
            belowHours += hours * (1.0 - fractionAbove(start, end, lowSocPercent))
            socHours += hours * (start + end) / 2.0
            if (start > end) spentPercent += start - end
        }
        if (observedHours <= 0.0) return null

        val cycles = spentPercent / 100.0
        val odometers = ordered.mapNotNull { it.odometerKm }
        val km = if (odometers.size >= 2) (odometers.last() - odometers.first()).toDouble() else null
        return BatteryExposureReport(
            spanDays = (ordered.last().atMs - ordered.first().atMs) / MS_PER_DAY,
            observedHours = observedHours,
            hoursAboveHighSoc = aboveHours,
            hoursBelowLowSoc = belowHours,
            meanSocPercent = socHours / observedHours,
            equivalentFullCycles = cycles,
            kmPerEquivalentCycle = km?.takeIf { cycles > 0.0 && it >= 0.0 }?.div(cycles),
            sessions = sessions(ordered),
        )
    }

    /**
     * The share of a segment spent above a threshold, taking the charge as a straight line
     * between its two ends.
     *
     * Attributing the whole segment to whichever side its mean fell on would count a night that
     * ended at 85 % as entirely below 80 %, and the overnight hours at a high charge are exactly
     * what calendar ageing is about.
     */
    private fun fractionAbove(start: Double, end: Double, threshold: Double): Double {
        if (start >= threshold && end >= threshold) return 1.0
        if (start <= threshold && end <= threshold) return 0.0
        return ((if (start > threshold) start else end) - threshold) / kotlin.math.abs(end - start)
    }

    /** Consecutive rises, merged: one session, however many entries the ledger wrote during it. */
    private fun sessions(ordered: List<BatteryLedgerEntry>): List<BatteryChargeSession> {
        val result = ArrayList<BatteryChargeSession>()
        var index = 1
        while (index < ordered.size) {
            if (ordered[index].socPercent <= ordered[index - 1].socPercent + RISE_EPSILON) {
                index++
                continue
            }
            val start = ordered[index - 1]
            var peak = 0.0
            var temps = 0.0
            var tempCount = 0
            var last = start
            while (index < ordered.size &&
                ordered[index].socPercent > last.socPercent + RISE_EPSILON
            ) {
                val entry = ordered[index]
                val hours = (entry.atMs - last.atMs) / MS_PER_HOUR
                if (hours > 0.0) {
                    val rate = (entry.socPercent - last.socPercent) / hours
                    if (rate > peak) peak = rate
                }
                entry.outsideTempCelsius?.let { temps += it.toDouble(); tempCount++ }
                last = entry
                index++
            }
            val hours = (last.atMs - start.atMs) / MS_PER_HOUR
            if (hours > 0.0) {
                result.add(
                    BatteryChargeSession(
                        startedAtMs = start.atMs,
                        endedAtMs = last.atMs,
                        startSocPercent = start.socPercent.toDouble(),
                        endSocPercent = last.socPercent.toDouble(),
                        meanPercentPerHour = (last.socPercent - start.socPercent) / hours,
                        peakPercentPerHour = peak,
                        meanOutsideTempCelsius = (temps / tempCount).takeIf { tempCount > 0 },
                    )
                )
            }
        }
        return result
    }

    companion object {
        const val HIGH_SOC_PERCENT = 80.0
        const val LOW_SOC_PERCENT = 10.0

        /** Same step the health estimator calls a charge, so the two agree on what a rise is. */
        private const val RISE_EPSILON = 0.6f

        private const val MS_PER_HOUR = 3_600_000.0
        private const val MS_PER_DAY = 86_400_000.0
    }
}
