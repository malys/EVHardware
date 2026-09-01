package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.Provenanced
import com.evsuite.hardware.telemetry.UnavailableReason
import kotlin.math.abs
import kotlin.math.max

/** Exact speed and temperature region observed by one fit. */
data class EnergyModelEnvelope(
    val minSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val minOutsideTempCelsius: Double,
    val maxOutsideTempCelsius: Double,
) {
    fun contains(speedKmh: Double, outsideTempCelsius: Double): Boolean =
        speedKmh.isFinite() && outsideTempCelsius.isFinite() &&
            speedKmh in minSpeedKmh..maxSpeedKmh &&
            outsideTempCelsius in minOutsideTempCelsius..maxOutsideTempCelsius

    internal fun isValid(): Boolean =
        minSpeedKmh.isFinite() && maxSpeedKmh.isFinite() && minSpeedKmh > 0.0 &&
            minSpeedKmh <= maxSpeedKmh &&
            minOutsideTempCelsius.isFinite() && maxOutsideTempCelsius.isFinite() &&
            minOutsideTempCelsius <= maxOutsideTempCelsius
}

/**
 * Driver-specific consumption fit in kWh/100 km.
 *
 * `rolling + aero × speed² + thermal × |outside - 20 °C|` is intentionally the whole model.
 * Grade is absent until CP-031 decides on a trustworthy input, and HVAC attribution remains a
 * residual owned by CP-033. Every prediction is structurally [Provenanced] as estimated.
 */
data class EnergyModel(
    val schemaVersion: Int = SCHEMA_VERSION,
    val evidence: BatteryPowerEvidence,
    val rollingKwhPer100Km: Double,
    val aeroKwhPer100KmPerSpeedSquared: Double,
    val thermalKwhPer100KmPerDegree: Double,
    val residualRmseKwhPer100Km: Double,
    val sampleCount: Int,
    val envelope: EnergyModelEnvelope,
) {
    fun predict(speedKmh: Double, outsideTempCelsius: Double): Provenanced<Double> {
        if (!isValid() || !envelope.contains(speedKmh, outsideTempCelsius)) {
            return Provenanced.unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
        val value = rollingKwhPer100Km +
            aeroKwhPer100KmPerSpeedSquared * speedKmh * speedKmh +
            thermalKwhPer100KmPerDegree * abs(outsideTempCelsius - COMFORT_TEMP_CELSIUS)
        if (!value.isFinite() || value <= 0.0) {
            return Provenanced.unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
        val band = max(
            CONFIDENCE_MULTIPLIER * residualRmseKwhPer100Km,
            value * MIN_RELATIVE_UNCERTAINTY,
        )
        return Provenanced.estimated(value, band)
    }

    internal fun isValid(): Boolean =
        schemaVersion == SCHEMA_VERSION &&
            evidence.firmware != FirmwareInfo.Gen.UNKNOWN && evidence.conversionVersion > 0 &&
            rollingKwhPer100Km.isFinite() && rollingKwhPer100Km > 0.0 &&
            aeroKwhPer100KmPerSpeedSquared.isFinite() &&
            aeroKwhPer100KmPerSpeedSquared >= 0.0 &&
            thermalKwhPer100KmPerDegree.isFinite() && thermalKwhPer100KmPerDegree >= 0.0 &&
            residualRmseKwhPer100Km.isFinite() && residualRmseKwhPer100Km >= 0.0 &&
            sampleCount >= EnergyModelTrainer.MIN_TRAINING_SAMPLES && envelope.isValid()

    companion object {
        const val SCHEMA_VERSION = 1
        const val COMFORT_TEMP_CELSIUS = 20.0
        const val MIN_RELATIVE_UNCERTAINTY = 0.05
        private const val CONFIDENCE_MULTIPLIER = 1.96
    }
}
