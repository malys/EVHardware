package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.UnavailableReason
import kotlin.math.abs
import kotlin.math.max

enum class ResidualContext { CLIMATE_ACTIVE, CLIMATE_INACTIVE, CLIMATE_UNKNOWN }

enum class ResidualFinding {
    DISTINGUISHABLE,
    NOT_DISTINGUISHABLE_FROM_ZERO,
    NEGATIVE_MODEL_ERROR,
}

/** Model-derived energy. Construction requires the uncertainty needed to render it honestly. */
data class AttributedEnergyEstimate(
    val valueKwh: Double,
    val uncertaintyKwh: Double,
) {
    init {
        require(valueKwh.isFinite())
        require(uncertaintyKwh.isFinite() && uncertaintyKwh >= 0.0)
    }

    val bandLowKwh: Double get() = valueKwh - uncertaintyKwh
    val bandHighKwh: Double get() = valueKwh + uncertaintyKwh
}

data class ResidualAttribution(
    val context: ResidualContext,
    val estimate: AttributedEnergyEstimate,
    val finding: ResidualFinding,
    val intervalCount: Int,
    val averageFanLevel: Double?,
    val averageCabinOutsideDeltaCelsius: Double?,
)

data class EnergyAttribution(
    val totalConsumedKwh: Provenanced<Double>,
    val modelledTraction: AttributedEnergyEstimate,
    val residuals: List<ResidualAttribution>,
    val measuredRegenerationKwh: Provenanced<Double>,
    val netBatteryEnergyKwh: Provenanced<Double>,
    /** Measured total not covered by a modelled interval. Never hidden inside a residual. */
    val unmodelledDiscrepancyKwh: Provenanced<Double>,
    val reconciliationErrorKwh: Double,
)

sealed interface EnergyAttributionResult {
    data class Ready(val attribution: EnergyAttribution) : EnergyAttributionResult
    data class Unavailable(val reason: UnavailableReason) : EnergyAttributionResult
}

/**
 * Splits a measured trip total without claiming a nonexistent per-consumer vehicle counter.
 *
 * Each model-covered interval contributes traction and a residual. Climate state selects the
 * residual's context, not its value: an HVAC-off residual is model error/other auxiliary load,
 * never climate energy. Missing or out-of-envelope intervals remain a visible discrepancy.
 */
object EnergyAttributionCalculator {
    fun calculate(trip: StoredTrip, model: EnergyModel?): EnergyAttributionResult {
        val summary = trip.summary
        if (model == null || summary.batteryPowerEvidence != model.evidence) {
            return EnergyAttributionResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
        val totalConsumed = summary.consumedKwh
        val regeneration = summary.regeneratedKwh
        if (totalConsumed == null || regeneration == null ||
            !totalConsumed.isFinite() || !regeneration.isFinite() ||
            totalConsumed < 0.0 || regeneration < 0.0
        ) {
            return EnergyAttributionResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }
        val samples = trip.samples.orEmpty()
        if (samples.size < 2) {
            return EnergyAttributionResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }

        var tractionKwh = 0.0
        var tractionUncertaintyKwh = 0.0
        var coveredMeasuredKwh = 0.0
        val groups = ResidualContext.entries.associateWith { ResidualAccumulator() }
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val durationMs = current.atMs - previous.atMs
            if (durationMs !in 1..MAX_SAMPLE_GAP_MS) continue
            val previousSpeed = previous.speedKmh?.toDouble()
            val currentSpeed = current.speedKmh?.toDouble()
            val previousPower = previous.batteryPowerKw?.toDouble()
            val currentPower = current.batteryPowerKw?.toDouble()
            val previousTemp = previous.outsideTempCelsius?.toDouble()
            val currentTemp = current.outsideTempCelsius?.toDouble()
            if (previousSpeed == null || currentSpeed == null ||
                previousPower == null || currentPower == null ||
                previousTemp == null || currentTemp == null ||
                !previousSpeed.isFinite() || !currentSpeed.isFinite() ||
                !previousPower.isFinite() || !currentPower.isFinite() ||
                !previousTemp.isFinite() || !currentTemp.isFinite()
            ) {
                continue
            }
            val hours = durationMs / MILLIS_PER_HOUR
            val speed = (previousSpeed + currentSpeed) / 2.0
            val distanceKm = speed * hours
            if (!distanceKm.isFinite() || distanceKm <= 0.0) continue
            val temperature = (previousTemp + currentTemp) / 2.0
            val prediction = model.predict(speed, temperature)
            val predictedConsumption = prediction.value ?: continue
            val predictedUncertainty = prediction.uncertainty ?: continue
            val modelled = predictedConsumption * distanceKm / 100.0
            val uncertainty = predictedUncertainty * distanceKm / 100.0
            val measured = (max(0.0, previousPower) + max(0.0, currentPower)) / 2.0 * hours
            val context = climateContext(previous, current)

            tractionKwh += modelled
            tractionUncertaintyKwh += uncertainty
            coveredMeasuredKwh += measured
            groups.getValue(context).add(previous, current, measured - modelled, uncertainty)
        }
        val coveredIntervals = groups.values.sumOf { it.intervalCount }
        if (coveredIntervals == 0) {
            return EnergyAttributionResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }

        val residuals = ResidualContext.entries.mapNotNull { context ->
            groups.getValue(context).build(context)
        }
        val discrepancy = totalConsumed - coveredMeasuredKwh
        val netBatteryEnergy = totalConsumed - regeneration
        val reconciledNet = tractionKwh + residuals.sumOf { it.estimate.valueKwh } +
            discrepancy - regeneration
        return EnergyAttributionResult.Ready(
            EnergyAttribution(
                totalConsumedKwh = Provenanced.derived(totalConsumed),
                modelledTraction = AttributedEnergyEstimate(
                    tractionKwh,
                    tractionUncertaintyKwh,
                ),
                residuals = residuals,
                measuredRegenerationKwh = Provenanced.derived(regeneration),
                netBatteryEnergyKwh = Provenanced.derived(netBatteryEnergy),
                unmodelledDiscrepancyKwh = Provenanced.derived(discrepancy),
                reconciliationErrorKwh = netBatteryEnergy - reconciledNet,
            ),
        )
    }

    private fun climateContext(previous: TripSample, current: TripSample): ResidualContext {
        val states = listOf(climateActive(previous), climateActive(current))
        return when {
            states.any { it == true } -> ResidualContext.CLIMATE_ACTIVE
            states.all { it == false } -> ResidualContext.CLIMATE_INACTIVE
            else -> ResidualContext.CLIMATE_UNKNOWN
        }
    }

    private fun climateActive(sample: TripSample): Boolean? = when {
        sample.climatePowerOn == true || sample.climateAcOn == true ||
            sample.climateFanLevel?.let { it > 0 } == true -> true
        sample.climatePowerOn == false -> false
        else -> null
    }

    private class ResidualAccumulator {
        var valueKwh = 0.0
        var uncertaintyKwh = 0.0
        var intervalCount = 0
        private var fanTotal = 0.0
        private var fanCount = 0
        private var temperatureDeltaTotal = 0.0
        private var temperatureDeltaCount = 0

        fun add(
            previous: TripSample,
            current: TripSample,
            residualKwh: Double,
            residualUncertaintyKwh: Double,
        ) {
            valueKwh += residualKwh
            uncertaintyKwh += residualUncertaintyKwh
            intervalCount++
            listOf(previous.climateFanLevel, current.climateFanLevel).forEach { fan ->
                if (fan != null) {
                    fanTotal += fan
                    fanCount++
                }
            }
            listOf(previous, current).forEach { sample ->
                val cabin = sample.cabinTempCelsius?.toDouble()
                val outside = sample.outsideTempCelsius?.toDouble()
                if (cabin != null && outside != null && cabin.isFinite() && outside.isFinite()) {
                    temperatureDeltaTotal += abs(cabin - outside)
                    temperatureDeltaCount++
                }
            }
        }

        fun build(context: ResidualContext): ResidualAttribution? {
            if (intervalCount == 0) return null
            val estimate = AttributedEnergyEstimate(valueKwh, uncertaintyKwh)
            val finding = when {
                valueKwh < 0.0 -> ResidualFinding.NEGATIVE_MODEL_ERROR
                valueKwh <= uncertaintyKwh ->
                    ResidualFinding.NOT_DISTINGUISHABLE_FROM_ZERO
                else -> ResidualFinding.DISTINGUISHABLE
            }
            return ResidualAttribution(
                context = context,
                estimate = estimate,
                finding = finding,
                intervalCount = intervalCount,
                averageFanLevel = fanTotal.takeIf { fanCount > 0 }?.div(fanCount),
                averageCabinOutsideDeltaCelsius = temperatureDeltaTotal
                    .takeIf { temperatureDeltaCount > 0 }?.div(temperatureDeltaCount),
            )
        }
    }

    private const val MAX_SAMPLE_GAP_MS = 120_000L
    private const val MILLIS_PER_HOUR = 3_600_000.0
}
