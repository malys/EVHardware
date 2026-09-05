package com.evsuite.hardware.telemetry

import com.evsuite.hardware.FirmwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRangeEstimatorTest {
    private val estimator = AdaptiveRangeEstimator()

    @Test fun `cold start is unavailable because consumption history is insufficient`() {
        val estimate = estimator.estimate(snapshot(energyKwh = 40f), null, emptyList())

        assertNull(estimate.value)
        assertEquals(Provenance.UNAVAILABLE, estimate.provenance)
        assertEquals(UnavailableReason.INSUFFICIENT_SAMPLES, estimate.reason)
    }

    @Test fun `steady consumption matches the analytic range`() {
        val estimate = estimator.estimate(
            snapshot(energyKwh = 40f),
            trip(20.0, endedAtMs = 3L),
            listOf(trip(20.0, 2L), trip(20.0, 1L)),
        )

        assertEquals(Provenance.ESTIMATED, estimate.provenance)
        assertEquals(200.0, estimate.value!!, 1e-9)
        // Trips that happened to agree do not make the model certain: the band falls back to
        // its floor rather than to zero, which would read as a measurement.
        assertEquals(20.0, estimate.uncertainty!!, 1e-9)
    }

    @Test fun `an estimate never claims a band narrower than the model's floor`() {
        val estimate = estimator.estimate(
            snapshot(energyKwh = 40f), null,
            listOf(trip(20.0, 3L), trip(20.0, 2L), trip(20.0, 1L)),
        )

        assertEquals(
            estimate.value!! * AdaptiveRangeEstimator.MIN_RELATIVE_UNCERTAINTY,
            estimate.uncertainty!!,
            1e-9,
        )
        assertTrue(estimate.uncertainty!! > 0.0)
    }

    @Test fun `a momentarily unreadable power signal does not blank a settled estimate`() {
        val history = listOf(trip(20.0, 3L), trip(20.0, 2L), trip(20.0, 1L))
        val withPower = snapshot(energyKwh = 40f)
        val withoutPower = withPower.copy(batteryPowerKw = null)

        assertEquals(Provenance.ESTIMATED, estimator.estimate(withPower, null, history).provenance)
        // The capability was established a sample ago and the car has not lost it since; the
        // estimate depends on stored trips and pack charge, neither of which moved.
        val next = estimator.estimate(withoutPower, null, history)
        assertEquals(Provenance.ESTIMATED, next.provenance)
        assertEquals(200.0, next.value!!, 1e-9)
    }

    @Test fun `volatile consumption widens the band without moving the estimate`() {
        val steady = estimator.estimate(
            snapshot(energyKwh = 40f), null,
            listOf(trip(20.0, 3L), trip(20.0, 2L), trip(20.0, 1L)),
        )
        val volatile = estimator.estimate(
            snapshot(energyKwh = 40f), null,
            listOf(trip(10.0, 3L), trip(20.0, 2L), trip(30.0, 1L)),
        )

        assertEquals(steady.value!!, volatile.value!!, 1e-9)
        assertTrue(volatile.uncertainty!! > steady.uncertainty!!)
        assertEquals(100.0, volatile.uncertainty!!, 1e-9)
    }

    @Test fun `soc and capacity provide usable energy when direct energy is absent`() {
        val estimate = estimator.estimate(
            snapshot(energyKwh = null, soc = 50f, capacityKwh = 64f), null,
            listOf(trip(16.0, 3L), trip(16.0, 2L), trip(16.0, 1L)),
        )

        assertEquals(200.0, estimate.value!!, 1e-9)
    }

    @Test fun `firmware without a current battery power signal never estimates`() {
        val estimate = estimator.estimate(
            snapshot(energyKwh = 40f, powerKw = null), null,
            listOf(trip(20.0, 3L), trip(20.0, 2L), trip(20.0, 1L)),
            UnavailableReason.UNSUPPORTED_FIRMWARE,
        )

        assertNull(estimate.value)
        assertEquals(UnavailableReason.UNSUPPORTED_FIRMWARE, estimate.reason)
    }

    @Test fun `only the bounded newest history window affects the fit`() {
        val estimate = AdaptiveRangeEstimator(maxConsumptionSamples = 3).estimate(
            snapshot(energyKwh = 40f), null,
            listOf(
                trip(20.0, 30L), trip(20.0, 20L), trip(20.0, 10L),
                trip(100.0, 1L),
            ),
        )

        assertEquals(200.0, estimate.value!!, 1e-9)
        assertEquals(20.0, estimate.uncertainty!!, 1e-9)
    }

    private fun trip(consumption: Double, endedAtMs: Long): EnergyTripSummary {
        val distanceKm = 10.0
        return EnergyTripSummary(
            startedAtMs = 0L,
            endedAtMs = endedAtMs,
            durationMs = 1_000L,
            distanceKm = distanceKm,
            startSocPercent = null,
            endSocPercent = null,
            consumedKwh = consumption * distanceKm / 100.0,
            regeneratedKwh = 0.0,
            distanceAvailable = true,
        )
    }

    private fun snapshot(
        energyKwh: Float?,
        soc: Float? = null,
        capacityKwh: Float? = null,
        powerKw: Float? = 10f,
    ) = EnergySnapshot(
        timestampMs = 0L,
        firmware = FirmwareInfo.Gen.SWI68,
        socPercent = soc,
        rangeKm = null,
        speedKmh = 50f,
        batteryPowerKw = powerKw,
        outsideTempCelsius = null,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        batteryEnergyKwh = energyKwh,
        batteryCapacityKwh = capacityKwh,
        odometerKm = null,
        chargePortConnected = null,
        chargingStatus = null,
        parked = null,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )
}
