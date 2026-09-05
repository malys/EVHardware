package com.evsuite.hardware.telemetry

import kotlin.math.max
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
    /**
     * Whether this vehicle has ever published battery power to this instance.
     *
     * The capability question is "can this firmware measure power at all", and the answer does
     * not change between two polls. Asking the live reading instead made a single unreadable
     * sample blank an estimate computed from stored trips and the pack's charge — neither of
     * which had moved — so the figure flickered for a reason that had nothing to do with it.
     */
    private var powerEverPublished = false

    init {
        require(minimumConsumptionSamples >= 2) { "at least two samples are needed for spread" }
        require(maxConsumptionSamples >= minimumConsumptionSamples) {
            "the range window must hold the minimum sample count"
        }
    }

    /**
     * Estimates remaining range without replacing [EnergySnapshot.rangeKm].
     *
     * Battery power is the capability gate, latched rather than sampled: a firmware that has
     * never published it must not reuse another firmware's stored history to claim an adaptive
     * range, but one that published it a second ago has not lost the ability since.
     */
    fun estimate(
        snapshot: EnergySnapshot,
        currentTrip: EnergyTripSummary?,
        recentTrips: List<EnergyTripSummary>,
        missingReason: UnavailableReason = UnavailableReason.SIGNAL_ABSENT,
    ): Provenanced<Double> {
        if (snapshot.batteryPowerKw?.isFinite() == true) powerEverPublished = true
        if (!powerEverPublished) return Provenanced.unavailable(missingReason)

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
        val observedBandKm = estimatedKm * consumptionSpread / meanConsumption
        if (!observedBandKm.isFinite()) return Provenanced.unavailable(missingReason)
        // A floor, because the spread of at most eight of one driver's trips is not the model's
        // error. Trips that happened to agree produce a band of exactly zero, and "266.7 ± 0.0
        // km" reads as a measurement — the one thing Provenance exists to stop an estimate
        // doing. The floor is what the model cannot claim to know better than.
        val rangeUncertaintyKm = max(observedBandKm, estimatedKm * MIN_RELATIVE_UNCERTAINTY)
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

        /**
         * The narrowest band this model is allowed to claim, as a fraction of the estimate.
         *
         * Eight trips of one driver do not measure the error of extrapolating to a whole pack:
         * terrain, load, weather and speed all sit outside the fit. Ten percent is the width
         * below which the figure would invite more trust than the method can carry.
         */
        const val MIN_RELATIVE_UNCERTAINTY = 0.10
    }
}
