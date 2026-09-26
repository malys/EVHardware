package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.UnavailableReason
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed interface EnergyModelTrainingResult {
    data class Ready(val model: EnergyModel) : EnergyModelTrainingResult
    data class Unavailable(val reason: UnavailableReason) : EnergyModelTrainingResult
}

/** Bounded O(n), O(1)-working-memory least-squares fit. Call from a worker thread. */
class EnergyModelTrainer(
    private val maxSamples: Int = MAX_TRAINING_SAMPLES,
    private val maxResidualRmse: Double = MAX_RESIDUAL_RMSE_KWH_PER_100_KM,
) {
    init {
        require(maxSamples >= MIN_TRAINING_SAMPLES)
        require(maxResidualRmse.isFinite() && maxResidualRmse > 0.0)
    }

    /** Production entry point: the empty pre-CP-003 catalogue makes training fail closed. */
    fun train(
        trips: List<StoredTrip>,
        firmware: FirmwareInfo.Gen,
    ): EnergyModelTrainingResult {
        if (firmware == FirmwareInfo.Gen.UNKNOWN) {
            return EnergyModelTrainingResult.Unavailable(UnavailableReason.UNSUPPORTED_FIRMWARE)
        }
        val evidence = CarPropertyEvidence.powerModelEvidence(firmware)
            ?: return EnergyModelTrainingResult.Unavailable(UnavailableReason.UNVALIDATED_FIRMWARE)
        return fit(trips, evidence)
    }

    internal fun fit(
        trips: List<StoredTrip>,
        evidence: BatteryPowerEvidence,
    ): EnergyModelTrainingResult {
        // Both fits are accumulated in the one pass; the temperature span picks which to solve.
        val equations = LeastSquares3()
        val narrowed = LeastSquares3(2)
        var minSpeed = Double.POSITIVE_INFINITY
        var maxSpeed = Double.NEGATIVE_INFINITY
        var minTemp = Double.POSITIVE_INFINITY
        var maxTemp = Double.NEGATIVE_INFINITY
        val count = forEachUsable(trips, evidence) { speed, temp, consumption ->
            equations.add(speed, temp, consumption)
            narrowed.add(speed, temp, consumption)
            minSpeed = minOf(minSpeed, speed)
            maxSpeed = maxOf(maxSpeed, speed)
            minTemp = minOf(minTemp, temp)
            maxTemp = maxOf(maxTemp, temp)
        }
        if (count < MIN_TRAINING_SAMPLES || maxSpeed - minSpeed < MIN_SPEED_SPAN_KMH) {
            return EnergyModelTrainingResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }

        // As in SocConsumptionFitter: one kind of weather is no evidence about weather, so two
        // coefficients are fitted and the narrow envelope refuses the cold instead of guessing.
        val features = if (maxTemp - minTemp >= MIN_TEMPERATURE_SPAN_CELSIUS) 3 else 2
        val coefficients = (if (features == 3) equations else narrowed).solve()
            ?: return EnergyModelTrainingResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        val rolling = coefficients[0]
        val aero = coefficients[1]
        val thermal = if (features == 3) coefficients[2] else 0.0
        if (rolling <= 0.0 || aero < 0.0 || thermal < 0.0 ||
            coefficients.any { !it.isFinite() }
        ) {
            return EnergyModelTrainingResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }

        var squaredError = 0.0
        val residualCount = forEachUsable(trips, evidence) { speed, temp, consumption ->
            val prediction = rolling + aero * speed * speed +
                thermal * abs(temp - EnergyModel.COMFORT_TEMP_CELSIUS)
            val error = consumption - prediction
            squaredError += error * error
        }
        val rmse = sqrt(squaredError / residualCount)
        if (!rmse.isFinite() || rmse > maxResidualRmse) {
            return EnergyModelTrainingResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }

        val model = EnergyModel(
            evidence = evidence,
            rollingKwhPer100Km = rolling,
            aeroKwhPer100KmPerSpeedSquared = aero,
            thermalKwhPer100KmPerDegree = thermal,
            residualRmseKwhPer100Km = rmse,
            sampleCount = count,
            envelope = EnergyModelEnvelope(minSpeed, maxSpeed, minTemp, maxTemp),
        )
        return EnergyModelTrainingResult.Ready(model)
    }

    /**
     * Calls [block] once per kilometre of driving, with its mean speed, mean temperature and
     * gross consumption, and returns how many kilometres it reported.
     *
     * A 5-second sample is not a consumption. The one taken while pulling away from a light
     * costs several times the one taken cruising, and a fit made per sample read that spread
     * as model error and refused town driving for good. Over a kilometre the accelerations
     * average into what the road actually cost, which is also what the attribution predicts.
     * Standing still is skipped without ending the window: it has no speed to be modelled at.
     * A hole in the track does end it, as in SocConsumptionFitter, so no window spans
     * kilometres nobody recorded.
     */
    private fun forEachUsable(
        trips: List<StoredTrip>,
        evidence: BatteryPowerEvidence,
        block: (speedKmh: Double, outsideTempCelsius: Double, consumption: Double) -> Unit,
    ): Int {
        var accepted = 0
        for (trip in trips) {
            if (trip.summary.batteryPowerEvidence != evidence) continue
            val samples = trip.samples.orEmpty()
            var km = 0.0
            var hours = 0.0
            var kwh = 0.0
            var tempSum = 0.0
            var tempCount = 0
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                val durationMs = current.atMs - previous.atMs
                val speed = mean(previous.speedKmh, current.speedKmh)
                val previousPower = previous.batteryPowerKw?.toDouble()
                val currentPower = current.batteryPowerKw?.toDouble()
                val temp = mean(previous.outsideTempCelsius, current.outsideTempCelsius)
                if (speed == null || temp == null || previousPower == null ||
                    currentPower == null || !previousPower.isFinite() ||
                    !currentPower.isFinite() || durationMs !in 1..MAX_SAMPLE_GAP_MS
                ) {
                    km = 0.0
                    hours = 0.0
                    kwh = 0.0
                    tempSum = 0.0
                    tempCount = 0
                    continue
                }
                if (speed < MIN_SPEED_KMH) continue
                val intervalHours = durationMs / MILLIS_PER_HOUR
                km += speed * intervalHours
                hours += intervalHours
                // Gross, as the attribution measures it: regeneration is a separate ledger line.
                kwh += (max(0.0, previousPower) + max(0.0, currentPower)) / 2.0 * intervalHours
                tempSum += temp
                tempCount++
                if (km < WINDOW_KM) continue
                val meanSpeed = km / hours
                val meanTemp = tempSum / tempCount
                val consumption = kwh * 100.0 / km
                km = 0.0
                hours = 0.0
                kwh = 0.0
                tempSum = 0.0
                tempCount = 0
                if (meanSpeed !in MIN_SPEED_KMH..MAX_SPEED_KMH ||
                    meanTemp !in MIN_TEMP_CELSIUS..MAX_TEMP_CELSIUS ||
                    consumption !in 0.0..MAX_CONSUMPTION_KWH_PER_100_KM
                ) {
                    continue
                }
                block(meanSpeed, meanTemp, consumption)
                accepted++
                if (accepted == maxSamples) return accepted
            }
        }
        return accepted
    }

    private fun mean(a: Float?, b: Float?): Double? {
        val first = a?.toDouble()?.takeIf { it.isFinite() } ?: return null
        val second = b?.toDouble()?.takeIf { it.isFinite() } ?: return null
        return (first + second) / 2.0
    }

    companion object {
        /** Kilometre windows, not samples: a dozen, as SocConsumptionFitter asks segments. */
        const val MIN_TRAINING_SAMPLES = 12
        const val MAX_TRAINING_SAMPLES = 20_000
        const val WINDOW_KM = 1.0
        private const val MAX_SAMPLE_GAP_MS = 120_000L
        private const val MILLIS_PER_HOUR = 3_600_000.0
        const val MAX_RESIDUAL_RMSE_KWH_PER_100_KM = 12.0
        private const val MIN_SPEED_KMH = 10.0
        private const val MAX_SPEED_KMH = 180.0
        private const val MIN_TEMP_CELSIUS = -40.0
        private const val MAX_TEMP_CELSIUS = 60.0
        private const val MIN_SPEED_SPAN_KMH = 20.0
        private const val MIN_TEMPERATURE_SPAN_CELSIUS = 5.0
        private const val MAX_CONSUMPTION_KWH_PER_100_KM = 100.0
    }
}
