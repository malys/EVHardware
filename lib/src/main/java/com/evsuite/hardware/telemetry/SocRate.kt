package com.evsuite.hardware.telemetry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How much state of charge a kilometre costs.
 *
 * The obvious route to an arrival forecast runs through the energy model: kWh/100 km, times a
 * distance, divided by a pack capacity. On this vehicle it is a dead end — the model trains on
 * energy integrated from battery power, and `EV_INSTANTANEOUS_CHARGE_RATE` publishes nothing.
 *
 * State of charge per kilometre needs no energy unit at all. Both of its inputs read on a car
 * where battery power does not, which is why the forecast is built on it instead.
 */
data class SocRate(
    /** Percentage points of charge per kilometre. Always positive for a car that is driving. */
    val percentPerKm: Double,
    /** Half-width of the band on [percentPerKm], in the same unit. */
    val uncertaintyPercentPerKm: Double,
    val source: Source,
    /** Trips behind the figure, or 0 when it came from the vehicle's own range. */
    val sampleCount: Int = 0,
) {
    enum class Source {
        /**
         * State of charge divided by the vehicle's own remaining range. Available from the
         * first reading, and only as good as the car's own estimate.
         */
        VEHICLE_RANGE,

        /** Measured across recorded trips: what this driver actually spends per kilometre. */
        TRIP_HISTORY,
    }
}

/** Builds a [SocRate] from whichever evidence exists, preferring what the driver has driven. */
object SocRateEstimator {

    /**
     * How wrong the vehicle's own range estimate may be.
     *
     * The car publishes a range and no error bar. It is a model like any other — it reacts to
     * recent driving, temperature and its own assumptions — so a fifth is a stated assumption
     * rather than a measurement, and it is deliberately wide. Trip history replaces it with a
     * band that was actually observed.
     */
    const val VEHICLE_RANGE_RELATIVE_UNCERTAINTY = 0.20

    /** Below this a trip's own state-of-charge resolution dominates whatever it measured. */
    const val MIN_TRIP_DISTANCE_KM = 1.0

    /** Fewer than this and the spread across trips is not a band, it is noise. */
    const val MIN_TRIPS = 3

    /** A floor on the observed spread, so a run of similar trips cannot imply false precision. */
    const val MIN_TRIP_RELATIVE_UNCERTAINTY = 0.10

    /**
     * The rate implied by the vehicle's own range reading.
     *
     * @param socPercent the vehicle's state of charge, 0..100.
     * @param rangeKm the vehicle's own remaining range.
     */
    fun fromVehicleRange(socPercent: Double?, rangeKm: Double?): SocRate? {
        if (socPercent == null || rangeKm == null) return null
        if (!socPercent.isFinite() || !rangeKm.isFinite()) return null
        if (socPercent !in 0.0..100.0 || rangeKm <= 0.0) return null
        val rate = socPercent / rangeKm
        if (!rate.isFinite() || rate <= 0.0) return null
        return SocRate(
            percentPerKm = rate,
            uncertaintyPercentPerKm = rate * VEHICLE_RANGE_RELATIVE_UNCERTAINTY,
            source = SocRate.Source.VEHICLE_RANGE,
        )
    }

    /**
     * The rate this driver has actually spent, across trips whose distance can be vouched for.
     *
     * Only trips carrying [EnergyTripSummary.modellableDistanceKm] count: a distance recorded
     * under the old speed conversion is 3.6 times too long, and averaging it in would make the
     * car look three and a half times more efficient than it is.
     *
     * Charging stops are excluded by construction — a trip whose charge went up is not a trip
     * whose consumption means anything.
     */
    fun fromTrips(
        trips: List<EnergyTripSummary>,
        generation: com.evsuite.hardware.FirmwareInfo.Gen =
            com.evsuite.hardware.FirmwareInfo.getGeneration(),
    ): SocRate? {
        val rates = trips.mapNotNull { trip ->
            val km = trip.modellableDistanceKm(generation)?.takeIf { it >= MIN_TRIP_DISTANCE_KM }
                ?: return@mapNotNull null
            val start = trip.startSocPercent ?: return@mapNotNull null
            val end = trip.endSocPercent ?: return@mapNotNull null
            val spent = (start - end).toDouble()
            if (spent <= 0.0) return@mapNotNull null
            (spent / km).takeIf { it.isFinite() && it > 0.0 }
        }
        if (rates.size < MIN_TRIPS) return null
        val mean = rates.average()
        if (!mean.isFinite() || mean <= 0.0) return null
        val variance = rates.sumOf { (it - mean) * (it - mean) } / (rates.size - 1)
        val observed = sqrt(variance)
        return SocRate(
            percentPerKm = mean,
            uncertaintyPercentPerKm = maxOf(observed, abs(mean) * MIN_TRIP_RELATIVE_UNCERTAINTY),
            source = SocRate.Source.TRIP_HISTORY,
            sampleCount = rates.size,
        )
    }

    /** Trip history when there is enough of it, the vehicle's own range otherwise. */
    fun best(
        socPercent: Double?,
        rangeKm: Double?,
        trips: List<EnergyTripSummary>,
        generation: com.evsuite.hardware.FirmwareInfo.Gen =
            com.evsuite.hardware.FirmwareInfo.getGeneration(),
    ): SocRate? = fromTrips(trips, generation) ?: fromVehicleRange(socPercent, rangeKm)
}
