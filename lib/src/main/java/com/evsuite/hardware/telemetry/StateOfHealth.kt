package com.evsuite.hardware.telemetry

import kotlin.math.abs

/** Where a window's kilowatt-hours came from. Two sources are never averaged together. */
enum class SohEnergySource {
    /**
     * The charging service's own counters (transactions 75 and 77). Measured by the car, with
     * nothing integrated here — the best source, and the one no drive has yet proven moves.
     */
    VEHICLE_COUNTERS,

    /**
     * This app's integral of `BMS_PACK_VOL × BMS_PACK_CRNT`, carried by
     * `BatteryPowerEvidence.PACK_PAIR_DERIVED_V2`. Arithmetic on two vehicle readings, with the
     * sign convention still unproven on any generation.
     */
    PACK_PAIR_INTEGRAL,
}

/**
 * One discharge between two charges: what it cost, and what the charge gauge said it cost.
 *
 * The ratio is a usable capacity, and it needs no capacity to compute — which is the whole
 * reason this exists. Every other kWh figure in EVSuite multiplies a percentage by a number
 * from a specification sheet.
 */
data class SohWindow(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val socDropPercent: Double,
    val netKwh: Double,
    val source: SohEnergySource,
    val meanOutsideTempCelsius: Double?,
    val distanceKm: Double?,
) {
    val usableCapacityKwh: Double get() = netKwh * 100.0 / socDropPercent
}

/** The pack as this history sees it, always estimated and always with its band. */
data class StateOfHealthEstimate(
    val usableCapacityKwh: Provenanced<Double>,
    val stateOfHealthPercent: Provenanced<Double>,
    val source: SohEnergySource,
    val windowCount: Int,
    val coldWindowsExcluded: Int,
    val spanDays: Double,
    val spanKm: Double?,
    /**
     * Newest third minus oldest third, in points of health, or null when the history cannot
     * support the comparison. This is the figure the screen leads with: a node bias that moves
     * the absolute cancels out of a difference between two estimates made the same way.
     */
    val trendPercentPoints: Double?,
)

sealed interface StateOfHealthResult {
    data class Ready(val estimate: StateOfHealthEstimate) : StateOfHealthResult

    data class Unavailable(
        val reason: UnavailableReason,
        val windowsSeen: Int = 0,
        val coldWindowsExcluded: Int = 0,
    ) : StateOfHealthResult
}

/**
 * State of health as a ratio, because this car publishes no capacity to read.
 *
 * `INFO_EV_BATTERY_CAPACITY`, `EV_BATTERY_LEVEL` and the `EV_CURRENT_BATTERY_CAPACITY`
 * candidate are declared and never published on SWI68 — null in all 612 snapshots of the
 * 2026-09-13 drive — so [BatteryCapacityConfig] asks the driver instead. The energy that left
 * the pack over the charge it cost is a capacity, and both halves of it are recorded in the
 * [BatteryLedgerEntry] stream.
 *
 * **What this can and cannot see.** A charge step on this car is about one point, so a window
 * of 25 points carries ±4 % before anything else goes wrong, and a year of real degradation is
 * two or three points. One window is therefore never a health figure: the estimate is the
 * median of at least [MIN_WINDOWS], and the band never claims to be tighter than the
 * quantisation allows. The absolute figure also inherits whatever node the source measures at
 * — pack terminals or somewhere downstream, unknowable from here — which is why
 * [StateOfHealthEstimate.trendPercentPoints] is the figure worth reading and the absolute one
 * is shown beside it with its source named.
 */
class StateOfHealthEstimator(
    private val minSocDropPercent: Double = MIN_SOC_DROP_PERCENT,
    private val minWindows: Int = MIN_WINDOWS,
    private val minTempCelsius: Double = MIN_TEMP_CELSIUS,
    private val distanceTolerance: Double = DISTANCE_TOLERANCE,
) {
    fun estimate(
        entries: List<BatteryLedgerEntry>,
        usableCapacityKwhWhenNew: Double,
    ): StateOfHealthResult {
        if (!usableCapacityKwhWhenNew.isFinite() || usableCapacityKwhWhenNew <= 0.0) {
            return StateOfHealthResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }
        val all = windows(entries)
        val cold = all.count { it.isColderThan(minTempCelsius) }
        val usable = all.filterNot { it.isColderThan(minTempCelsius) }
        if (usable.isEmpty()) {
            return StateOfHealthResult.Unavailable(
                UnavailableReason.INSUFFICIENT_SAMPLES, all.size, cold,
            )
        }
        // Preference, not popularity: a counter the car keeps outranks an integral this app
        // made, however many windows the integral managed to produce.
        val chosen = SOURCE_PREFERENCE
            .map { source -> usable.filter { it.source == source } }
            .firstOrNull { it.size >= minWindows }
            ?: return StateOfHealthResult.Unavailable(
                UnavailableReason.INSUFFICIENT_SAMPLES, usable.size, cold,
            )

        val capacities = chosen.map { it.usableCapacityKwh }.sorted()
        val capacity = median(capacities)
        val drops = chosen.map { it.socDropPercent }.sorted()
        // Two floors, and the wider wins: what the windows disagree about, and what a gauge
        // that moves a point at a time can resolve at all.
        val spread = (percentile(capacities, 0.75) - percentile(capacities, 0.25)) / 2.0
        val quantisation = capacity * SOC_STEP_PERCENT / median(drops)
        val resolution = sourceStepKwh(chosen.first().source) * 100.0 / median(drops)
        val band = maxOf(spread, quantisation, resolution)
        val health = capacity * 100.0 / usableCapacityKwhWhenNew
        val healthBand = band * 100.0 / usableCapacityKwhWhenNew

        val ordered = chosen.sortedBy { it.endedAtMs }
        val spanDays = (ordered.last().endedAtMs - ordered.first().startedAtMs) / MS_PER_DAY
        val distances = ordered.mapNotNull { it.distanceKm }
        return StateOfHealthResult.Ready(
            StateOfHealthEstimate(
                usableCapacityKwh = Provenanced.estimated(capacity, band),
                stateOfHealthPercent = Provenanced.estimated(health, healthBand),
                source = chosen.first().source,
                windowCount = chosen.size,
                coldWindowsExcluded = cold,
                spanDays = spanDays,
                spanKm = distances.sum().takeIf { distances.isNotEmpty() },
                trendPercentPoints = trend(ordered, usableCapacityKwhWhenNew, spanDays),
            )
        )
    }

    /**
     * Every discharge in the ledger that can be measured, newest last.
     *
     * A window runs from one entry to the last entry before the charge rises again. A rise is
     * how a charge is detected — the charging status' semantics are unproven on this car and
     * the port-connected property is never published, but a charge that puts energy into a
     * pack raises its state of charge on every vehicle ever built.
     */
    internal fun windows(entries: List<BatteryLedgerEntry>): List<SohWindow> {
        val ordered = entries.filter { it.socPercent.isFinite() }.sortedBy { it.atMs }
        if (ordered.size < 2) return emptyList()
        val result = ArrayList<SohWindow>()
        var anchor = ordered.first()
        var previous = anchor
        for (index in 1 until ordered.size) {
            val entry = ordered[index]
            if (entry.socPercent > previous.socPercent + SOC_RISE_EPSILON) {
                measure(anchor, previous)?.let(result::add)
                anchor = entry
            }
            previous = entry
        }
        measure(anchor, previous)?.let(result::add)
        return result
    }

    /** The window, or null when nothing in it can be trusted to say what it cost. */
    private fun measure(start: BatteryLedgerEntry, end: BatteryLedgerEntry): SohWindow? {
        val drop = (start.socPercent - end.socPercent).toDouble()
        if (drop < minSocDropPercent) return null
        val temp = meanTemp(start, end)
        val distance = distanceOf(start, end)
        vehicleCounterKwh(start, end)?.let { net ->
            return SohWindow(
                start.atMs, end.atMs, drop, net, SohEnergySource.VEHICLE_COUNTERS, temp, distance,
            )
        }
        packPairKwh(start, end)?.let { net ->
            return SohWindow(
                start.atMs, end.atMs, drop, net, SohEnergySource.PACK_PAIR_INTEGRAL, temp, distance,
            )
        }
        return null
    }

    /**
     * The car's own counters over the window, or null.
     *
     * A counter that went backwards was reset, which means the car charged inside a window the
     * charge gauge said it did not. That contradiction is refused rather than reconciled.
     */
    private fun vehicleCounterKwh(start: BatteryLedgerEntry, end: BatteryLedgerEntry): Double? {
        val consumed = delta(start.vehicleConsumedKwh, end.vehicleConsumedKwh) ?: return null
        val regenerated = delta(start.vehicleRegeneratedKwh, end.vehicleRegeneratedKwh) ?: return null
        if (consumed < 0.0 || regenerated < 0.0) return null
        return (consumed - regenerated).takeIf { it > 0.0 }
    }

    /**
     * The app's own integral over the window, or null.
     *
     * Guarded by the odometer, which is the point: this integral only counts the seconds the
     * app was awake and sampling, and a window where the car drove further than the app
     * watched is missing energy it has no way to notice. Without an odometer at both ends there
     * is no way to check that, so the window is refused rather than believed.
     */
    private fun packPairKwh(start: BatteryLedgerEntry, end: BatteryLedgerEntry): Double? {
        val energy = delta(start.packEnergyKwh, end.packEnergyKwh)?.takeIf { it > 0.0 } ?: return null
        val odometer = delta(start.odometerKm, end.odometerKm) ?: return null
        val integrated = delta(start.integratedDistanceKm, end.integratedDistanceKm) ?: return null
        if (odometer < 0.0 || integrated < 0.0) return null
        if (odometer > 0.0 && abs(integrated - odometer) > distanceTolerance * odometer) return null
        return energy
    }

    private fun trend(
        ordered: List<SohWindow>,
        capacityWhenNew: Double,
        spanDays: Double,
    ): Double? {
        if (ordered.size < MIN_TREND_WINDOWS || spanDays < MIN_TREND_DAYS) return null
        val third = ordered.size / 3
        val oldest = ordered.take(third).map { it.usableCapacityKwh }.sorted()
        val newest = ordered.takeLast(third).map { it.usableCapacityKwh }.sorted()
        if (oldest.isEmpty() || newest.isEmpty()) return null
        return (median(newest) - median(oldest)) * 100.0 / capacityWhenNew
    }

    private fun meanTemp(start: BatteryLedgerEntry, end: BatteryLedgerEntry): Double? {
        val first = start.outsideTempCelsius?.toDouble() ?: return null
        val second = end.outsideTempCelsius?.toDouble() ?: return null
        return (first + second) / 2.0
    }

    private fun distanceOf(start: BatteryLedgerEntry, end: BatteryLedgerEntry): Double? =
        delta(start.odometerKm, end.odometerKm)?.takeIf { it >= 0.0 }
            ?: delta(start.integratedDistanceKm, end.integratedDistanceKm)?.takeIf { it >= 0.0 }

    private fun delta(start: Float?, end: Float?): Double? =
        if (start == null || end == null || !start.isFinite() || !end.isFinite()) null
        else (end - start).toDouble()

    private fun delta(start: Double?, end: Double?): Double? =
        if (start == null || end == null || !start.isFinite() || !end.isFinite()) null
        else end - start

    private fun SohWindow.isColderThan(limit: Double): Boolean =
        meanOutsideTempCelsius != null && meanOutsideTempCelsius < limit

    companion object {
        /**
         * Below this a window says more about the gauge's resolution than about the pack:
         * one point of quantisation at each end is ±4 % over 25 points, and real degradation
         * is two or three points a year.
         */
        const val MIN_SOC_DROP_PERCENT = 25.0

        /** A median needs a majority to mean anything, and six is where the band stops moving. */
        const val MIN_WINDOWS = 6

        /** A cold pack delivers less usable energy, and that is weather rather than health. */
        const val MIN_TEMP_CELSIUS = 5.0

        /** How far the app's own integral may fall short of the odometer before the window is refused. */
        const val DISTANCE_TOLERANCE = 0.10

        /** The step this car's charge gauge takes, and so one floor under every band. */
        const val SOC_STEP_PERCENT = 1.0

        /**
         * The step the vehicle's own counter takes — a whole kilowatt-hour.
         *
         * Not a guess: the 2026-09-13 drive spent 1,1 % of the pack and read zero on
         * `saic_consumed_kwh_since_start`, and the 2026-09-15 drive moved it. RI-002's verdict is
         * that the counter was below its *resolution* rather than below its bind point, and that
         * resolution is a whole kWh. Over a sixty-point window that is a couple of percent of the
         * capacity, which is the same order as a year of degradation — so it belongs in the band
         * rather than in a footnote. A median over several windows averages the rounding down; the
         * floor states what a single window could not have resolved.
         */
        const val COUNTER_STEP_KWH = 1.0

        /** What one window's energy figure could be wrong by, before anything else goes wrong. */
        internal fun sourceStepKwh(source: SohEnergySource): Double = when (source) {
            SohEnergySource.VEHICLE_COUNTERS -> COUNTER_STEP_KWH
            // An integral of a float property has no step of its own; what it can be wrong about
            // is coverage, and the odometer guard is what refuses a window for that.
            SohEnergySource.PACK_PAIR_INTEGRAL -> 0.0
        }

        const val MIN_TREND_WINDOWS = 12
        const val MIN_TREND_DAYS = 60.0

        /** A charge rise past the gauge's own step. Noise at the step is not a charge. */
        private const val SOC_RISE_EPSILON = 0.6f

        private const val MS_PER_DAY = 86_400_000.0

        private val SOURCE_PREFERENCE = listOf(
            SohEnergySource.VEHICLE_COUNTERS,
            SohEnergySource.PACK_PAIR_INTEGRAL,
        )

        internal fun median(sorted: List<Double>): Double = percentile(sorted, 0.5)

        /** Nearest-rank, which needs no interpolation rule to argue about on six samples. */
        internal fun percentile(sorted: List<Double>, fraction: Double): Double {
            if (sorted.isEmpty()) return Double.NaN
            if (sorted.size == 1) return sorted.first()
            val rank = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            val next = (rank + 1).coerceAtMost(sorted.size - 1)
            val weight = fraction * (sorted.size - 1) - rank
            return sorted[rank] + (sorted[next] - sorted[rank]) * weight
        }
    }
}
