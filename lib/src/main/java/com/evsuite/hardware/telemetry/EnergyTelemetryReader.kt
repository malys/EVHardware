package com.evsuite.hardware.telemetry

import android.content.Context
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.saic.SaicCharging
import com.evsuite.hardware.saic.SaicClimate
import com.evsuite.hardware.saic.SaicHub

/** Injectable signal seam: production uses [EvHardwareEnergySignalSource], tests use a fake. */
interface EnergySignalSource {
    fun firmware(): FirmwareInfo.Gen
    fun socPercent(): Float?
    fun rangeKm(): Float?
    fun speedKmh(): Float?
    fun batteryPowerKw(): Float?
    fun outsideTempCelsius(): Float?
    fun cabinTempCelsius(): Float?
    fun batteryTempCelsius(): Float?
    fun batteryEnergyKwh(): Float?
    fun batteryCapacityKwh(): Float?
    fun odometerKm(): Float?
    fun chargePortConnected(): Boolean?
    fun chargingStatus(): Int?
    fun parked(): Boolean?
    fun climate(): ClimateSnapshot
    fun tirePressures(): TirePressureSnapshot
}

class EvHardwareEnergySignalSource(context: Context) : EnergySignalSource {
    init {
        val appContext = context.applicationContext
        EVHardware.init(appContext)
        SaicHub.connect(appContext)
    }

    override fun firmware() = FirmwareInfo.getGeneration()
    override fun socPercent() =
        SaicCharging.stateOfChargePercent() ?: EVHardware.getVendorBatterySocPercent()
    override fun rangeKm() =
        SaicCharging.rangeKm()?.toFloat()
            ?: EVHardware.getStandardRangeKm()
            ?: EVHardware.getVendorRangeKm()?.toFloat()
    override fun speedKmh() = EVHardware.getVehicleSpeedKmh()
    override fun batteryPowerKw() = EVHardware.getBatteryPowerKw()
    override fun outsideTempCelsius() =
        SaicClimate.outsideTempCelsius() ?: EVHardware.getOutsideTempCelsius()
    override fun cabinTempCelsius() = EVHardware.getCabinTemperatureCelsius()
    override fun batteryTempCelsius() = EVHardware.getBatteryTemperatureCelsius()
    override fun batteryEnergyKwh() = EVHardware.getBatteryEnergyKwh()
    override fun batteryCapacityKwh() = EVHardware.getBatteryCapacityKwh()
    override fun odometerKm() = EVHardware.getOdometerKm()
    override fun chargePortConnected() = EVHardware.isChargePortConnected()
    override fun chargingStatus() = SaicCharging.chargingStatus()
    override fun parked() = EVHardware.isVehicleInPark()
    override fun tirePressures() = TirePressureSnapshot(
        frontLeftKpa = EVHardware.getTirePressureKpa(EVHardware.Wheel.FRONT_LEFT),
        frontRightKpa = EVHardware.getTirePressureKpa(EVHardware.Wheel.FRONT_RIGHT),
        rearLeftKpa = EVHardware.getTirePressureKpa(EVHardware.Wheel.REAR_LEFT),
        rearRightKpa = EVHardware.getTirePressureKpa(EVHardware.Wheel.REAR_RIGHT),
    )
    override fun climate() = ClimateSnapshot(
        powerOn = SaicClimate.powerOn(),
        acOn = SaicClimate.acOn(),
        autoOn = SaicClimate.autoOn(),
        econOn = SaicClimate.econOn(),
        recirculationOn = SaicClimate.recirculationOn(),
        fanLevel = SaicClimate.fanLevel(),
        fanLevelMax = SaicClimate.fanLevelMax(),
        driverTargetCelsius = SaicClimate.driverTemp()?.toFloat()
            ?: EVHardware.getTemperatureSetCelsius(),
        passengerTargetCelsius = SaicClimate.passengerTemp()?.toFloat(),
    )
}

/** Builds a coherent snapshot from one shared source; every unavailable signal remains null. */
class EnergyTelemetryReader(private val source: EnergySignalSource) {
    constructor(context: Context) : this(EvHardwareEnergySignalSource(context))

    fun read(nowMs: Long = System.currentTimeMillis()) = EnergySnapshot(
        timestampMs = nowMs,
        firmware = source.firmware(),
        socPercent = source.socPercent()?.takeIf { it.isFinite() && it in 0f..100f },
        rangeKm = source.rangeKm()?.takeIf { it.isFinite() && it >= 0f },
        speedKmh = source.speedKmh()?.takeIf { it.isFinite() && it >= 0f },
        batteryPowerKw = source.batteryPowerKw()?.takeIf { it.isFinite() },
        outsideTempCelsius = source.outsideTempCelsius()?.takeIf { it.isFinite() },
        cabinTempCelsius = source.cabinTempCelsius()?.takeIf { it.isFinite() },
        batteryTempCelsius = source.batteryTempCelsius()?.takeIf { it.isFinite() },
        batteryEnergyKwh = source.batteryEnergyKwh()?.takeIf { it.isFinite() && it >= 0f },
        batteryCapacityKwh = source.batteryCapacityKwh()?.takeIf { it.isFinite() && it > 0f },
        odometerKm = source.odometerKm()?.takeIf { it.isFinite() && it >= 0f },
        chargePortConnected = source.chargePortConnected(),
        chargingStatus = source.chargingStatus(),
        parked = source.parked(),
        climate = source.climate(),
        tirePressures = source.tirePressures(),
    )
}
