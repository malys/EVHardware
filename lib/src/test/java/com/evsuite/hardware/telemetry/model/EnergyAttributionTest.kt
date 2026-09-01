package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.EnergyTripSummary
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.TripSample
import com.evsuite.hardware.telemetry.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyAttributionTest {
    private val evidence = BatteryPowerEvidence(
        FirmwareInfo.Gen.SWI68,
        BatteryPowerEvidence.OUTPUT_POSITIVE_MW_V1,
    )
    private val model = EnergyModel(
        evidence = evidence,
        rollingKwhPer100Km = 10.0,
        aeroKwhPer100KmPerSpeedSquared = 0.0,
        thermalKwhPer100KmPerDegree = 0.0,
        residualRmseKwhPer100Km = 0.1,
        sampleCount = 120,
        envelope = EnergyModelEnvelope(90.0, 110.0, 5.0, 15.0),
    )

    @Test fun `known traction and active auxiliary load reconcile inside the band`() {
        val result = ready(calculate(powerKw = 12f, climateOn = true, consumedKwh = 12.0))
        val active = result.residuals.single()

        assertEquals(10.0, result.modelledTraction.valueKwh, 1e-6)
        assertEquals(2.0, active.estimate.valueKwh, 1e-6)
        assertEquals(ResidualContext.CLIMATE_ACTIVE, active.context)
        assertEquals(ResidualFinding.DISTINGUISHABLE, active.finding)
        assertTrue(2.0 in active.estimate.bandLowKwh..active.estimate.bandHighKwh)
        assertEquals(11.5, result.netBatteryEnergyKwh.value!!, 1e-9)
        assertEquals(0.0, result.reconciliationErrorKwh, 1e-9)
    }

    @Test fun `residual inside model noise is not distinguishable from zero`() {
        val result = ready(calculate(powerKw = 10.2f, climateOn = true, consumedKwh = 10.2))

        assertEquals(
            ResidualFinding.NOT_DISTINGUISHABLE_FROM_ZERO,
            result.residuals.single().finding,
        )
    }

    @Test fun `negative residual is surfaced as model error and never clamped`() {
        val result = ready(calculate(powerKw = 9f, climateOn = false, consumedKwh = 9.0))
        val inactive = result.residuals.single()

        assertEquals(ResidualContext.CLIMATE_INACTIVE, inactive.context)
        assertEquals(ResidualFinding.NEGATIVE_MODEL_ERROR, inactive.finding)
        assertEquals(-1.0, inactive.estimate.valueKwh, 1e-6)
        assertEquals(0.0, result.reconciliationErrorKwh, 1e-9)
    }

    @Test fun `missing model evidence stays unavailable`() {
        val result = EnergyAttributionCalculator.calculate(trip(12f, true, 12.0), null)

        assertEquals(
            UnavailableReason.MODEL_NOT_TRAINED,
            (result as EnergyAttributionResult.Unavailable).reason,
        )
    }

    private fun calculate(powerKw: Float, climateOn: Boolean, consumedKwh: Double) =
        EnergyAttributionCalculator.calculate(trip(powerKw, climateOn, consumedKwh), model)

    private fun trip(powerKw: Float, climateOn: Boolean, consumedKwh: Double): StoredTrip {
        val samples = (0..60).map { minute ->
            TripSample(
                atMs = minute * 60_000L,
                speedKmh = 100f,
                batteryPowerKw = powerKw,
                socPercent = 80f,
                outsideTempCelsius = 10f,
                cabinTempCelsius = 20f,
                batteryTempCelsius = null,
                climatePowerOn = climateOn,
                climateAcOn = climateOn,
                climateFanLevel = if (climateOn) 4 else 0,
            )
        }
        return StoredTrip(
            summary = EnergyTripSummary(
                startedAtMs = 0L,
                endedAtMs = 3_600_000L,
                durationMs = 3_600_000L,
                distanceKm = 100.0,
                startSocPercent = 80f,
                endSocPercent = 60f,
                consumedKwh = consumedKwh,
                regeneratedKwh = 0.5,
                distanceAvailable = true,
                batteryPowerEvidence = evidence,
            ),
            samples = samples,
        )
    }

    private fun ready(result: EnergyAttributionResult): EnergyAttribution =
        (result as EnergyAttributionResult.Ready).attribution
}
