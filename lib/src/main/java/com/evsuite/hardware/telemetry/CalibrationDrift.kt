package com.evsuite.hardware.telemetry

/** What the ledger says about the charge gauge's own reference. */
enum class CalibrationVerdict {
    /** The gauge has seen an end of the pack recently; nothing to suggest. */
    SETTLED,

    /** Drifting towards having nothing to anchor on, with no evidence that it has. */
    WATCH,

    /** Either the anchor is long gone, or a step says the reference already moved. */
    RE_ANCHOR_SUGGESTED,
}

/**
 * A charge step no energy explains.
 *
 * The clean case is a parked car: it stood still, drove nowhere, and woke up several points
 * lower. Nothing consumed that charge — the BMS re-estimated it, and the size of the step is
 * the error that had accumulated.
 */
data class UnexplainedSocStep(
    val atMs: Long,
    val dropPercent: Double,
    val elapsedHours: Double,
    val parked: Boolean,
)

data class CalibrationDriftReport(
    val verdict: CalibrationVerdict,
    val lastFullChargeAtMs: Long?,
    val lastLowChargeAtMs: Long?,
    val daysSinceFullCharge: Double?,
    val equivalentCyclesSinceFullCharge: Double,
    val steps: List<UnexplainedSocStep>,
    /** Width of the health estimate's band in points, when there is an estimate. */
    val estimateSpreadPercentPoints: Double?,
    val ledgerSpanDays: Double,
)

/**
 * When the charge gauge last had anything to correct itself against.
 *
 * Every figure this project produces is anchored on state of charge, and that estimate is
 * counted between rare anchor points — a full charge, a deep discharge, a long rest. A car
 * charged to 80 % and never run below 30 % visits none of them, and the error accumulates
 * silently until the gauge jumps.
 *
 * **This notices; it never acts.** No interface on this head unit recalibrates a BMS, and
 * re-anchoring is something the pack does for itself during a full charge. Nor are the app's
 * own forecasts corrected against an observed step: a corrected figure would be this app's
 * opinion of the charge, drawn beside the car's own gauge saying something else, with no way
 * for a driver to tell which is which. The gauge is the car's. This says it moved.
 */
class CalibrationDrift(
    private val fullSocPercent: Double = FULL_SOC_PERCENT,
    private val lowSocPercent: Double = LOW_SOC_PERCENT,
    private val settledDays: Double = SETTLED_DAYS,
    private val reAnchorDays: Double = RE_ANCHOR_DAYS,
    private val stepPercent: Double = STEP_PERCENT,
    private val selfDischargePercentPerDay: Double = SELF_DISCHARGE_PERCENT_PER_DAY,
) {
    fun analyse(
        entries: List<BatteryLedgerEntry>,
        nowMs: Long,
        estimate: StateOfHealthEstimate? = null,
    ): CalibrationDriftReport? {
        val ordered = entries.filter { it.socPercent.isFinite() }.sortedBy { it.atMs }
        if (ordered.size < 2) return null

        val lastFull = ordered.lastOrNull { it.socPercent >= fullSocPercent }
        val lastLow = ordered.lastOrNull { it.socPercent <= lowSocPercent }
        val daysSinceFull = lastFull?.let { (nowMs - it.atMs) / MS_PER_DAY }
        val cyclesSinceFull = cycles(ordered.filter { lastFull == null || it.atMs >= lastFull.atMs })
        val steps = steps(ordered)
        val spread = estimate?.stateOfHealthPercent?.uncertainty?.times(2.0)

        val verdict = when {
            steps.isNotEmpty() -> CalibrationVerdict.RE_ANCHOR_SUGGESTED
            daysSinceFull == null || daysSinceFull > reAnchorDays ->
                CalibrationVerdict.RE_ANCHOR_SUGGESTED
            daysSinceFull <= settledDays -> CalibrationVerdict.SETTLED
            else -> CalibrationVerdict.WATCH
        }
        return CalibrationDriftReport(
            verdict = verdict,
            lastFullChargeAtMs = lastFull?.atMs,
            lastLowChargeAtMs = lastLow?.atMs,
            daysSinceFullCharge = daysSinceFull,
            equivalentCyclesSinceFullCharge = cyclesSinceFull,
            steps = steps,
            estimateSpreadPercentPoints = spread,
            ledgerSpanDays = (ordered.last().atMs - ordered.first().atMs) / MS_PER_DAY,
        )
    }

    /**
     * Steps between two entries where the car went nowhere.
     *
     * A parked pack does lose charge — slowly, and the allowance below says how slowly. What is
     * refused is the reading that a car standing still for an hour spent four points on nothing.
     * The odometer has to be readable at both ends and identical: without it, a drive the app
     * did not sample is indistinguishable from a step, and calling that a calibration problem
     * would be an invented fact rather than an observed one.
     */
    private fun steps(ordered: List<BatteryLedgerEntry>): List<UnexplainedSocStep> {
        val result = ArrayList<UnexplainedSocStep>()
        for (index in 1 until ordered.size) {
            val previous = ordered[index - 1]
            val entry = ordered[index]
            val startOdometer = previous.odometerKm ?: continue
            val endOdometer = entry.odometerKm ?: continue
            if (endOdometer != startOdometer) continue
            if (previous.parked == false || entry.parked == false) continue
            val drop = (previous.socPercent - entry.socPercent).toDouble()
            if (drop < stepPercent) continue
            val hours = (entry.atMs - previous.atMs) / MS_PER_HOUR
            if (hours <= 0.0) continue
            val allowance = selfDischargePercentPerDay * hours / 24.0
            if (drop <= allowance) continue
            result.add(UnexplainedSocStep(entry.atMs, drop, hours, parked = true))
        }
        return result
    }

    /** Equivalent full cycles: every point of charge spent, over a hundred. */
    private fun cycles(ordered: List<BatteryLedgerEntry>): Double {
        var spent = 0.0
        for (index in 1 until ordered.size) {
            val drop = ordered[index - 1].socPercent - ordered[index].socPercent
            if (drop > 0f) spent += drop.toDouble()
        }
        return spent / 100.0
    }

    companion object {
        /** What counts as a full charge for anchoring: the gauge rarely reports a clean 100. */
        const val FULL_SOC_PERCENT = 97.0
        const val LOW_SOC_PERCENT = 15.0
        const val SETTLED_DAYS = 30.0
        const val RE_ANCHOR_DAYS = 90.0

        /** Smaller than this and a step is quantisation plus a cold night, not a re-estimate. */
        const val STEP_PERCENT = 3.0

        /** A parked MG4 is allowed to lose this much a day before a drop needs explaining. */
        const val SELF_DISCHARGE_PERCENT_PER_DAY = 1.0

        private const val MS_PER_DAY = 86_400_000.0
        private const val MS_PER_HOUR = 3_600_000.0
    }
}
