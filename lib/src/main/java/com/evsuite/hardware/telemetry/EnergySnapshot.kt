package com.evsuite.hardware.telemetry

import com.evsuite.hardware.FirmwareInfo

/** One immutable, nullable, read-only view of the vehicle's energy state. */
data class EnergySnapshot(
    val timestampMs: Long,
    val firmware: FirmwareInfo.Gen,
    val socPercent: Float?,
    val rangeKm: Float?,
    val speedKmh: Float?,
    /** Positive means energy leaves the battery; negative means charging or regeneration. */
    val batteryPowerKw: Float?,
    val outsideTempCelsius: Float?,
    val cabinTempCelsius: Float?,
    val batteryTempCelsius: Float?,
    val batteryEnergyKwh: Float?,
    val batteryCapacityKwh: Float?,
    val odometerKm: Float?,
    val chargePortConnected: Boolean?,
    val chargingStatus: Int?,
    val parked: Boolean?,
    val climate: ClimateSnapshot,
    val tirePressures: TirePressureSnapshot,
) {
    val hasVehicleData: Boolean
        get() = socPercent != null || rangeKm != null || speedKmh != null ||
            batteryPowerKw != null || outsideTempCelsius != null || cabinTempCelsius != null ||
            batteryTempCelsius != null || batteryEnergyKwh != null || batteryCapacityKwh != null ||
            odometerKm != null || chargePortConnected != null || chargingStatus != null ||
            parked != null || climate.hasData || tirePressures.hasData
}

data class TirePressureSnapshot(
    val frontLeftKpa: Float?,
    val frontRightKpa: Float?,
    val rearLeftKpa: Float?,
    val rearRightKpa: Float?,
) {
    val hasData: Boolean
        get() = frontLeftKpa != null || frontRightKpa != null ||
            rearLeftKpa != null || rearRightKpa != null
}

data class ClimateSnapshot(
    val powerOn: Boolean?,
    val acOn: Boolean?,
    val autoOn: Boolean?,
    val econOn: Boolean?,
    val recirculationOn: Boolean?,
    val fanLevel: Int?,
    val fanLevelMax: Int?,
    val driverTargetCelsius: Float?,
    val passengerTargetCelsius: Float?,
) {
    val hasData: Boolean
        get() = powerOn != null || acOn != null || autoOn != null || econOn != null ||
            recirculationOn != null || fanLevel != null || fanLevelMax != null ||
            driverTargetCelsius != null || passengerTargetCelsius != null
}
