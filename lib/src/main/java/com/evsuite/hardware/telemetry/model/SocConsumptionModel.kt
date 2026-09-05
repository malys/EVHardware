package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleSpeedEvidence
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.UnavailableReason
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Driver-specific consumption in **percent of charge per 100 km**.
 *
 * [EnergyModel] answers the same question in kWh and cannot be trained on a car that does not
 * publish battery power — which is this car. `CarPropertyEvidence` records the evidence and
 * the conclusion: SWI68 publishes no battery power, so the kWh fit can never train, and an
 * arrival forecast here has to come from state of charge instead.
 *
 * **The unit is the point.** Converting a state of charge into kWh needs a pack capacity, and
 * on this vehicle the capacity is a specification sheet, not a measurement — an unmeasured
 * multiplier in front of every number the model produces. Fitting in percent removes it
 * entirely: state of charge is published by the car, distance is the speed integral the app
 * already gates on [VehicleSpeedEvidence], and every consumer of this — the charging-stop
 * plan, the reserve, the route what-if — already thinks in percent. Nothing is converted, so
 * nothing has to be assumed.
 *
 * It is not an SWI68 workaround. Every EV publishes a state of charge, so this fit works on
 * generations that publish power too; [EnergyModel] remains for the energy breakdown, which
 * genuinely needs to split kWh and has nothing to split without power.
 *
 * Same shape as [EnergyModel]: `rolling + aero × speed² + thermal × |outside − 20 °C|`, every
 * prediction structurally [Provenanced] as estimated, and nothing predicted outside the
 * envelope the fit actually saw.
 */
data class SocConsumptionModel(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** The distance conversion behind every segment this was fitted from. */
    val speedEvidence: VehicleSpeedEvidence,
    val rollingPercentPer100Km: Double,
    val aeroPercentPer100KmPerSpeedSquared: Double,
    val thermalPercentPer100KmPerDegree: Double,
    val residualRmsePercentPer100Km: Double,
    val segmentCount: Int,
    val envelope: EnergyModelEnvelope,
) {
    /** Percent of charge per 100 km at this speed and this outside temperature. */
    fun predict(speedKmh: Double, outsideTempCelsius: Double): Provenanced<Double> {
        if (!isValid() || !envelope.contains(speedKmh, outsideTempCelsius)) {
            return Provenanced.unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
        val value = rollingPercentPer100Km +
            aeroPercentPer100KmPerSpeedSquared * speedKmh * speedKmh +
            thermalPercentPer100KmPerDegree * abs(outsideTempCelsius - EnergyModel.COMFORT_TEMP_CELSIUS)
        if (!value.isFinite() || value <= 0.0) {
            return Provenanced.unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
        val band = max(
            CONFIDENCE_MULTIPLIER * residualRmsePercentPer100Km,
            value * EnergyModel.MIN_RELATIVE_UNCERTAINTY,
        )
        return Provenanced.estimated(value, band)
    }

    internal fun isValid(): Boolean =
        schemaVersion == SCHEMA_VERSION &&
            rollingPercentPer100Km.isFinite() && rollingPercentPer100Km > 0.0 &&
            aeroPercentPer100KmPerSpeedSquared.isFinite() &&
            aeroPercentPer100KmPerSpeedSquared >= 0.0 &&
            thermalPercentPer100KmPerDegree.isFinite() &&
            thermalPercentPer100KmPerDegree >= 0.0 &&
            residualRmsePercentPer100Km.isFinite() && residualRmsePercentPer100Km >= 0.0 &&
            segmentCount >= SocConsumptionFitter.MIN_SEGMENTS && envelope.isValid()

    companion object {
        const val SCHEMA_VERSION = 1
        private const val CONFIDENCE_MULTIPLIER = 1.96
    }
}

sealed interface SocConsumptionFitResult {
    data class Ready(val model: SocConsumptionModel) : SocConsumptionFitResult
    data class Unavailable(val reason: UnavailableReason) : SocConsumptionFitResult
}

/**
 * Turns recorded tracks into the segments a percent-per-100-km fit can be built from.
 *
 * State of charge is a coarse signal — a step is around 1 % of the pack, which is four or five
 * kilometres of motorway — so a per-sample reading says nothing and a fit built on one would
 * be fitting quantisation. Segments are therefore accumulated until the charge has actually
 * moved by [minSocDropPercent], and only then does one training point exist: the distance the
 * speed integral covered, the mean temperature over it, and the charge it cost.
 *
 * A hole in the track ends the current segment rather than being bridged. A sampler suspended
 * in a tunnel or by the driver leaving the screen loses distance it never recorded, and a
 * segment spanning that hole would charge the missing kilometres' energy to the ones on either
 * side of it.
 *
 * Bounded and allocation-light: one pass per trip, one small object per emitted segment, and
 * a cap on how many segments a fit will look at.
 */
class SocConsumptionFitter(
    private val minSocDropPercent: Double = MIN_SOC_DROP_PERCENT,
    private val maxSegments: Int = MAX_SEGMENTS,
    private val maxResidualRmse: Double = MAX_RESIDUAL_RMSE_PERCENT_PER_100_KM,
) {
    /** One training point: what a stretch of road cost, and the conditions it cost it in. */
    internal data class Segment(
        val distanceKm: Double,
        val meanSpeedKmh: Double,
        val meanOutsideTempCelsius: Double,
        val socDropPercent: Double,
    ) {
        val percentPer100Km: Double get() = socDropPercent * 100.0 / distanceKm
    }

    fun fit(
        trips: List<StoredTrip>,
        generation: FirmwareInfo.Gen = FirmwareInfo.getGeneration(),
    ): SocConsumptionFitResult {
        if (generation == FirmwareInfo.Gen.UNKNOWN) {
            return SocConsumptionFitResult.Unavailable(UnavailableReason.UNSUPPORTED_FIRMWARE)
        }
        // Built from the passed generation rather than read from the device, so the gate can
        // be exercised on the JVM. A correctness gate that only runs on a car is untested.
        val evidence = VehicleSpeedEvidence(generation, VehicleSpeedEvidence.CURRENT)

        val segments = ArrayList<Segment>()
        for (trip in trips) {
            // The distance is the speed integral, so a trip recorded under a conversion that
            // is no longer believed correct contributes nothing. CP-036's 3.6x error is
            // invisible in the stored number and would be fitted as a real consumption.
            if (trip.summary.speedEvidence?.matchesCurrent(generation) != true) continue
            collect(trip.samples.orEmpty(), segments)
            if (segments.size >= maxSegments) break
        }
        if (segments.size < MIN_SEGMENTS) {
            return SocConsumptionFitResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }

        val minSpeed = segments.minOf { it.meanSpeedKmh }
        val maxSpeed = segments.maxOf { it.meanSpeedKmh }
        val minTemp = segments.minOf { it.meanOutsideTempCelsius }
        val maxTemp = segments.maxOf { it.meanOutsideTempCelsius }
        if (maxSpeed - minSpeed < MIN_SPEED_SPAN_KMH) {
            return SocConsumptionFitResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }

        // A driver who has only recorded one kind of weather has no evidence about weather.
        // Fitting two coefficients is the honest answer there; the envelope keeps the narrow
        // temperature range, so this model refuses the cold instead of guessing at it.
        val features = if (maxTemp - minTemp >= MIN_TEMPERATURE_SPAN_CELSIUS) 3 else 2
        val equations = LeastSquares3(features)
        for (segment in segments) {
            equations.add(
                segment.meanSpeedKmh,
                segment.meanOutsideTempCelsius,
                segment.percentPer100Km,
            )
        }
        val coefficients = equations.solve()
            ?: return SocConsumptionFitResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        val rolling = coefficients[0]
        val aero = coefficients[1]
        val thermal = if (features == 3) coefficients[2] else 0.0
        if (rolling <= 0.0 || aero < 0.0 || thermal < 0.0 || coefficients.any { !it.isFinite() }) {
            return SocConsumptionFitResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }

        var squaredError = 0.0
        for (segment in segments) {
            val prediction = rolling +
                aero * segment.meanSpeedKmh * segment.meanSpeedKmh +
                thermal * abs(
                    segment.meanOutsideTempCelsius - EnergyModel.COMFORT_TEMP_CELSIUS
                )
            val error = segment.percentPer100Km - prediction
            squaredError += error * error
        }
        val rmse = sqrt(squaredError / segments.size)
        if (!rmse.isFinite() || rmse > maxResidualRmse) {
            return SocConsumptionFitResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }

        val model = SocConsumptionModel(
            speedEvidence = evidence,
            rollingPercentPer100Km = rolling,
            aeroPercentPer100KmPerSpeedSquared = aero,
            thermalPercentPer100KmPerDegree = thermal,
            residualRmsePercentPer100Km = rmse,
            segmentCount = segments.size,
            envelope = EnergyModelEnvelope(minSpeed, maxSpeed, minTemp, maxTemp),
        )
        return if (model.isValid()) {
            SocConsumptionFitResult.Ready(model)
        } else {
            SocConsumptionFitResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
    }

    /**
     * Walks one track, emitting a segment each time the charge has moved enough to divide by.
     *
     * Internal rather than private so the segmentation can be tested on its own: what this
     * refuses to build a segment from is most of what makes the fit trustworthy.
     */
    internal fun collect(samples: List<TripSample>, out: MutableList<Segment>) {
        var startSoc: Double? = null
        var distanceKm = 0.0
        var hours = 0.0
        var tempSum = 0.0
        var tempCount = 0

        fun reset(soc: Double?) {
            startSoc = soc
            distanceKm = 0.0
            hours = 0.0
            tempSum = 0.0
            tempCount = 0
        }

        for (index in samples.indices) {
            val sample = samples[index]
            val soc = sample.socPercent?.toDouble()?.takeIf { it.isFinite() }
            if (index == 0) {
                reset(soc)
                continue
            }
            val previous = samples[index - 1]
            val previousSpeed = previous.speedKmh?.toDouble()
            val speed = sample.speedKmh?.toDouble()
            val durationMs = sample.atMs - previous.atMs
            if (previousSpeed == null || speed == null ||
                !previousSpeed.isFinite() || !speed.isFinite() ||
                previousSpeed < 0.0 || speed < 0.0 ||
                durationMs !in 1..MAX_SAMPLE_GAP_MS
            ) {
                // Distance across the hole is unknown, and a segment that spanned it would
                // charge the missing kilometres to the ones on either side.
                reset(soc)
                continue
            }
            val intervalHours = durationMs / MILLIS_PER_HOUR
            distanceKm += (previousSpeed + speed) / 2.0 * intervalHours
            hours += intervalHours
            sample.outsideTempCelsius?.toDouble()?.takeIf { it.isFinite() }?.let {
                tempSum += it
                tempCount++
            }

            val start = startSoc
            if (soc == null) continue
            if (start == null) {
                reset(soc)
                continue
            }
            // Charging, or a pack rebalancing upward: the segment is no longer a drive.
            if (soc > start) {
                reset(soc)
                continue
            }
            val drop = start - soc
            if (drop < minSocDropPercent) continue
            emit(distanceKm, hours, tempSum, tempCount, drop)?.let(out::add)
            reset(soc)
            if (out.size >= maxSegments) return
        }
    }

    private fun emit(
        distanceKm: Double,
        hours: Double,
        tempSum: Double,
        tempCount: Int,
        dropPercent: Double,
    ): Segment? {
        if (tempCount == 0 || hours <= 0.0 || distanceKm < MIN_SEGMENT_KM) return null
        val meanSpeed = distanceKm / hours
        val meanTemp = tempSum / tempCount
        if (meanSpeed !in MIN_SPEED_KMH..MAX_SPEED_KMH) return null
        if (meanTemp !in MIN_TEMP_CELSIUS..MAX_TEMP_CELSIUS) return null
        val segment = Segment(distanceKm, meanSpeed, meanTemp, dropPercent)
        if (segment.percentPer100Km !in 0.0..MAX_CONSUMPTION_PERCENT_PER_100_KM) return null
        return segment
    }

    companion object {
        /** Below this the drop is quantisation, not consumption. */
        const val MIN_SOC_DROP_PERCENT = 2.0

        /** Three coefficients from a dozen points is already generous. */
        const val MIN_SEGMENTS = 12
        const val MAX_SEGMENTS = 5_000
        const val MAX_RESIDUAL_RMSE_PERCENT_PER_100_KM = 12.0
        const val MIN_SEGMENT_KM = 2.0

        private const val MIN_SPEED_KMH = 10.0
        private const val MAX_SPEED_KMH = 180.0
        private const val MIN_TEMP_CELSIUS = -40.0
        private const val MAX_TEMP_CELSIUS = 60.0
        private const val MIN_SPEED_SPAN_KMH = 20.0
        private const val MIN_TEMPERATURE_SPAN_CELSIUS = 5.0

        /** A tenth of the pack every ten kilometres is not a car, it is a broken record. */
        private const val MAX_CONSUMPTION_PERCENT_PER_100_KM = 100.0
        private const val MAX_SAMPLE_GAP_MS = 120_000L
        private const val MILLIS_PER_HOUR = 3_600_000.0
    }
}
