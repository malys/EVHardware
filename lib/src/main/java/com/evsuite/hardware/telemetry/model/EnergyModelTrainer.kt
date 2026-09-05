package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.UnavailableReason
import kotlin.math.abs
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
        val evidence = CarPropertyEvidence.batteryPowerEvidence(firmware)
            ?: return EnergyModelTrainingResult.Unavailable(UnavailableReason.UNVALIDATED_FIRMWARE)
        return fit(trips, evidence)
    }

    internal fun fit(
        trips: List<StoredTrip>,
        evidence: BatteryPowerEvidence,
    ): EnergyModelTrainingResult {
        val equations = LeastSquares3()
        var minSpeed = Double.POSITIVE_INFINITY
        var maxSpeed = Double.NEGATIVE_INFINITY
        var minTemp = Double.POSITIVE_INFINITY
        var maxTemp = Double.NEGATIVE_INFINITY
        val count = forEachUsable(trips, evidence) { speed, temp, consumption ->
            equations.add(speed, temp, consumption)
            minSpeed = minOf(minSpeed, speed)
            maxSpeed = maxOf(maxSpeed, speed)
            minTemp = minOf(minTemp, temp)
            maxTemp = maxOf(maxTemp, temp)
        }
        if (count < MIN_TRAINING_SAMPLES || maxSpeed - minSpeed < MIN_SPEED_SPAN_KMH ||
            maxTemp - minTemp < MIN_TEMPERATURE_SPAN_CELSIUS
        ) {
            return EnergyModelTrainingResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }

        val coefficients = equations.solve()
            ?: return EnergyModelTrainingResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        val rolling = coefficients[0]
        val aero = coefficients[1]
        val thermal = coefficients[2]
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

    private inline fun forEachUsable(
        trips: List<StoredTrip>,
        evidence: BatteryPowerEvidence,
        block: (speedKmh: Double, outsideTempCelsius: Double, consumption: Double) -> Unit,
    ): Int {
        var accepted = 0
        for (trip in trips) {
            if (trip.summary.batteryPowerEvidence != evidence) continue
            for (sample in trip.samples.orEmpty()) {
                val speed = sample.speedKmh?.toDouble()
                val power = sample.batteryPowerKw?.toDouble()
                val temp = sample.outsideTempCelsius?.toDouble()
                if (speed == null || power == null || temp == null ||
                    !speed.isFinite() || speed !in MIN_SPEED_KMH..MAX_SPEED_KMH ||
                    !power.isFinite() || power <= 0.0 ||
                    !temp.isFinite() || temp !in MIN_TEMP_CELSIUS..MAX_TEMP_CELSIUS
                ) {
                    continue
                }
                val consumption = power * 100.0 / speed
                if (!consumption.isFinite() || consumption !in 0.0..MAX_CONSUMPTION_KWH_PER_100_KM) {
                    continue
                }
                block(speed, temp, consumption)
                accepted++
                if (accepted == maxSamples) return accepted
            }
        }
        return accepted
    }

    companion object {
        const val MIN_TRAINING_SAMPLES = 60
        const val MAX_TRAINING_SAMPLES = 50_000
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
