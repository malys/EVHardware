package com.evsuite.hardware.telemetry

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How long the rest of a charge takes, from the driver's own watched charges (CP-089).
 *
 * The unit is minutes per point of charge, measured on [ChargeEnergy.steps]: the wall clock
 * between two ledger entries over the points between them. No kWh per point and no power sign
 * stand between the history and the answer. A band of [BAND_PERCENT] points that no watched
 * charge crossed is a refusal, never an extrapolation.
 */
data class ChargeDurationEstimate(
    val fromPercent: Double,
    val targetPercent: Int,
    /** Median charge per band, summed. */
    val minutes: Double,
    /** Fastest and slowest past charge per band, summed: the range the history allows. */
    val lowMinutes: Double,
    val highMinutes: Double,
    /** From [LAST_STRETCH_PERCENT] to the target, median; null when the path does not cross it. */
    val lastStretchMinutes: Double?,
    /** True when only charges at a similar outside temperature were used. */
    val similarTemperature: Boolean,
)

sealed interface ChargeDurationResult {
    data class Ready(val estimate: ChargeDurationEstimate) : ChargeDurationResult
    data class Refused(val reason: ChargeDurationRefusal, val bandFromPercent: Int? = null) :
        ChargeDurationResult
}

enum class ChargeDurationRefusal { AT_TARGET, NO_WATCHED_CHARGE, MISSING_BAND }

/** One band of the charge in progress against the same band in past charges. */
data class ChargeCurveCheck(
    val bandFromPercent: Int,
    val minutesPerPoint: Double,
    val usualLowMinutesPerPoint: Double,
    val usualHighMinutesPerPoint: Double,
    val pastCharges: Int,
) {
    /** Slower than the slowest past charge in that band: less power than the history's low edge. */
    val slower: Boolean get() = minutesPerPoint > usualHighMinutesPerPoint
}

object ChargeDuration {

    fun estimate(
        charges: List<ChargeEnergy>,
        socPercent: Double,
        targetPercent: Int,
        outsideCelsius: Double?,
    ): ChargeDurationResult {
        if (socPercent >= targetPercent) return ChargeDurationResult.Refused(ChargeDurationRefusal.AT_TARGET)
        if (outsideCelsius != null) {
            val similar = charges.filter { charge ->
                val low = charge.minOutsideTempCelsius
                val high = charge.maxOutsideTempCelsius
                low != null && high != null && abs((low + high) / 2.0 - outsideCelsius) <= SIMILAR_TEMPERATURE_CELSIUS
            }
            val ready = along(history(similar), socPercent, targetPercent, similar = true)
            if (ready is ChargeDurationResult.Ready) return ready
        }
        return along(history(charges), socPercent, targetPercent, similar = false)
    }

    fun curveCheck(charges: List<ChargeEnergy>, bandFromPercent: Int, minutesPerPoint: Double): ChargeCurveCheck? {
        val past = history(charges)[bandFromPercent / BAND_PERCENT].orEmpty()
        if (past.size < MIN_PAST_CHARGES) return null
        return ChargeCurveCheck(bandFromPercent, minutesPerPoint, past.min(), past.max(), past.size)
    }

    /** Band index to one minutes-per-point figure per past charge that crossed it. */
    fun history(charges: List<ChargeEnergy>): Map<Int, List<Double>> {
        val byBand = HashMap<Int, MutableList<Double>>()
        charges.forEach { charge ->
            charge.steps.filter { it.toSocPercent > it.fromSocPercent && it.durationMs > 0 }
                .groupBy { band(it.midSocPercent) }
                .forEach { (band, steps) ->
                    val points = steps.sumOf { it.toSocPercent - it.fromSocPercent }
                    byBand.getOrPut(band) { ArrayList() } += steps.sumOf { it.durationMs } / MINUTE_MS / points
                }
        }
        return byBand
    }

    private fun along(
        history: Map<Int, List<Double>>,
        socPercent: Double,
        targetPercent: Int,
        similar: Boolean,
    ): ChargeDurationResult {
        if (history.isEmpty()) return ChargeDurationResult.Refused(ChargeDurationRefusal.NO_WATCHED_CHARGE)
        var minutes = 0.0
        var low = 0.0
        var high = 0.0
        var lastStretch = 0.0
        val first = band(socPercent)
        val last = ceil(targetPercent / BAND_PERCENT.toDouble()).toInt() - 1
        for (band in first..last) {
            val past = history[band]?.sorted()
                ?: return ChargeDurationResult.Refused(ChargeDurationRefusal.MISSING_BAND, band * BAND_PERCENT)
            val from = maxOf(socPercent, band * BAND_PERCENT.toDouble())
            val to = minOf(targetPercent.toDouble(), (band + 1) * BAND_PERCENT.toDouble())
            val median = past[past.size / 2]
            minutes += (to - from) * median
            low += (to - from) * past.first()
            high += (to - from) * past.last()
            val stretchFrom = maxOf(from, LAST_STRETCH_PERCENT.toDouble())
            if (to > stretchFrom) lastStretch += (to - stretchFrom) * median
        }
        val crosses = socPercent < LAST_STRETCH_PERCENT && targetPercent > LAST_STRETCH_PERCENT
        return ChargeDurationResult.Ready(
            ChargeDurationEstimate(
                fromPercent = socPercent,
                targetPercent = targetPercent,
                minutes = minutes,
                lowMinutes = low,
                highMinutes = high,
                lastStretchMinutes = if (crosses) lastStretch else null,
                similarTemperature = similar,
            ),
        )
    }

    private fun band(socPercent: Double): Int =
        floor(socPercent / BAND_PERCENT).toInt().coerceIn(0, 100 / BAND_PERCENT - 1)

    const val BAND_PERCENT = 10
    const val LAST_STRETCH_PERCENT = 80
    const val SIMILAR_TEMPERATURE_CELSIUS = 8.0
    const val MIN_PAST_CHARGES = 3
    private const val MINUTE_MS = 60_000.0
}
