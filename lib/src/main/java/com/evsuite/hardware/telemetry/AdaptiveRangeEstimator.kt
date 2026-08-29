package com.evsuite.hardware.telemetry

import kotlin.math.sqrt

/**
 * A deliberately small range model fitted only from the driver's recent completed averages.
 *
 * Each trip contributes one observation so a long motorway drive does not silently erase every
 * other driving context. The newest observations win, the current trip is considered newest,
 * and the fixed-size window keeps both memory and model history explicit.
 */
class AdaptiveRangeEstimator(
    private val maxConsumptionSamples: Int = MAX_CONSUMPTION_SAMPLES,
    private val minimumConsumptionSamples: Int = MINIMUM_CONSUMPTION_SAMPLES,
) {
    init {
        require(minimumConsumptionSamples >= 2) { "at least two samples are needed for spread" }
        require(maxConsumptionSamples >= minimumConsumptionSamples) {
            "the range window must hold the minimum sample count"
        }
    }

    /**
     * Estimates remaining range without replacing [EnergySnapshot.rangeKm].
     *
     * A current power reading is the capability gate. A firmware that cannot publish battery
     * power must never reuse another firmware's stored history to claim an adaptive range.
     */
    fun estimate(
        snapshot: EnergySnapshot,
        currentTrip: EnergyTripSummary?,
        recentTrips: List<EnergyTripSummary>,
        missingReason: UnavailableReason = UnavailableReason.SIGNAL_ABSENT,
    ): Provenanced<Double> {
        if (snapshot.batteryPowerKw?.isFinite() != true) {
            return Provenanced.unavailable(missingReason)
        }

        val samples = consumptionSamples(currentTrip, recentTrips)
        if (samples.size < minimumConsumptionSamples) {
            return Provenanced.unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }

        val usableEnergyKwh = usableEnergy(snapshot)
            ?: return Provenanced.unavailable(missingReason)
        val meanConsumption = samples.average()
        val estimatedKm = usableEnergyKwh * 100.0 / meanConsumption
        if (!estimatedKm.isFinite()) return Provenanced.unavailable(missingReason)

        // Sample standard deviation describes the consumption observations we actually saw.
        // First-order propagation through range = energy * 100 / consumption converts that
        // spread to the symmetric kilometre half-band Provenanced exposes.
        val variance = samples.sumOf { sample ->
            val delta = sample - meanConsumption
            delta * delta
        } / (samples.size - 1).toDouble()
        val consumptionSpread = sqrt(variance)
        val rangeUncertaintyKm = estimatedKm * consumptionSpread / meanConsumption
        if (!rangeUncertaintyKm.isFinite()) return Provenanced.unavailable(missingReason)
        return Provenanced.estimated(estimatedKm, rangeUncertaintyKm)
    }

    private fun consumptionSamples(
        currentTrip: EnergyTripSummary?,
        recentTrips: List<EnergyTripSummary>,
    ): List<Double> {
        val current = currentTrip?.averageConsumptionKwhPer100Km
            ?.takeIf(::isUsableConsumption)
        val history = recentTrips.asSequence()
            .sortedByDescending { it.endedAtMs }
            .mapNotNull { it.averageConsumptionKwhPer100Km }
            .filter(::isUsableConsumption)
        return sequenceOf(current).filterNotNull()
            .plus(history)
            .take(maxConsumptionSamples)
            .toList()
    }

    private fun usableEnergy(snapshot: EnergySnapshot): Double? {
        snapshot.batteryEnergyKwh?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { return it }
        val soc = snapshot.socPercent?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        val capacity = snapshot.batteryCapacityKwh?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return capacity * soc / 100.0
    }

    private fun isUsableConsumption(value: Double): Boolean = value.isFinite() && value > 0.0

    companion object {
        /** Current trip plus the seven most recent valid stored-trip averages. */
        const val MAX_CONSUMPTION_SAMPLES = 8

        /** Three observations permit a centre and a non-degenerate observed spread. */
        const val MINIMUM_CONSUMPTION_SAMPLES = 3
    }
}
