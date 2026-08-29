package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.exp

class ConsumptionCalculatorTest {
    @Test fun `zero and low speed are unavailable rather than zero or infinity`() {
        val zero = ConsumptionCalculator.instantaneous(12f, 0f)
        val low = ConsumptionCalculator.instantaneous(12f, 4.9f)

        assertNull(zero.value)
        assertEquals(Provenance.UNAVAILABLE, zero.provenance)
        assertEquals(UnavailableReason.SPEED_TOO_LOW, zero.reason)
        assertNull(low.value)
        assertEquals(UnavailableReason.SPEED_TOO_LOW, low.reason)
    }

    @Test fun `a missing input propagates the stated reason`() {
        val missingPower = ConsumptionCalculator.instantaneous(null, 80f)
        val missingSpeed = ConsumptionCalculator.instantaneous(
            18f,
            null,
            UnavailableReason.UNSUPPORTED_FIRMWARE,
        )

        assertEquals(UnavailableReason.SIGNAL_ABSENT, missingPower.reason)
        assertEquals(UnavailableReason.UNSUPPORTED_FIRMWARE, missingSpeed.reason)
    }

    @Test fun `regeneration remains a negative instantaneous consumption`() {
        val value = ConsumptionCalculator.instantaneous(-18f, 90f)

        assertEquals(Provenance.DERIVED, value.provenance)
        assertEquals(-20.0, value.value!!, 1e-9)
    }

    @Test fun `dashboard value uses a five second exponential moving average`() {
        val calculator = ConsumptionCalculator()
        calculator.add(0L, 18f, 90f)

        val second = calculator.add(5_000L, 36f, 90f)
        val expected = 20.0 + (1.0 - exp(-1.0)) * (40.0 - 20.0)

        assertEquals(40.0, second.rawInstantaneous.value!!, 1e-9)
        assertEquals(expected, second.smoothedInstantaneous.value!!, 1e-9)
    }

    @Test fun `rolling average matches trip integration for the same samples`() {
        val calculator = ConsumptionCalculator()
        val accumulator = EnergyTripAccumulator(0L, 80f)
        val samples = listOf(
            snapshot(0L, 120f, 18f),
            snapshot(5_000L, 120f, 18f),
        )

        var reading: ConsumptionCalculator.Reading? = null
        samples.forEach {
            accumulator.add(it)
            reading = calculator.add(it)
        }

        assertEquals(
            accumulator.snapshot(5_000L).averageConsumptionKwhPer100Km!!,
            reading!!.rollingAverage.value!!,
            1e-9,
        )
    }

    @Test fun `rolling average drops samples outside its bounded window`() {
        val calculator = ConsumptionCalculator(rollingWindowMs = 2_000L)
        calculator.add(0L, 40f, 100f)
        calculator.add(1_000L, 40f, 100f)
        calculator.add(4_000L, 20f, 200f)

        val recent = calculator.add(6_000L, 20f, 200f)

        assertEquals(10.0, recent.rollingAverage.value!!, 1e-9)
    }

    private fun snapshot(atMs: Long, speedKmh: Float, powerKw: Float) = EnergySnapshot(
        timestampMs = atMs,
        firmware = com.evsuite.hardware.FirmwareInfo.Gen.SWI68,
        socPercent = 80f,
        rangeKm = null,
        speedKmh = speedKmh,
        batteryPowerKw = powerKw,
        outsideTempCelsius = null,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        batteryEnergyKwh = null,
        batteryCapacityKwh = null,
        odometerKm = null,
        chargePortConnected = null,
        chargingStatus = null,
        parked = null,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )
}
