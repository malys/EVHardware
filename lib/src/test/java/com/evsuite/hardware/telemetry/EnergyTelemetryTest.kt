package com.evsuite.hardware.telemetry

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.FirmwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class EnergyTelemetryTest {
    @Test fun `trip carries the exact evidence used for power integration`() {
        val evidence = BatteryPowerEvidence(
            FirmwareInfo.Gen.SWI68,
            BatteryPowerEvidence.OUTPUT_POSITIVE_MW_V1,
        )
        val trip = EnergyTripAccumulator(0L, 80f, batteryPowerEvidence = evidence)
        trip.add(snapshot(0L, 50f, 10f))
        trip.add(snapshot(1_000L, 50f, 10f))

        assertEquals(evidence, trip.snapshot(1_000L).batteryPowerEvidence)
    }

    @Test fun `reader keeps unknowns null and rejects invalid physical values`() {
        val source = FakeSignals(soc = 120f, range = -1f, power = 18f)
        val snapshot = EnergyTelemetryReader(source).read(42L)
        assertEquals(42L, snapshot.timestampMs)
        assertNull(snapshot.socPercent)
        assertNull(snapshot.rangeKm)
        assertEquals(18f, snapshot.batteryPowerKw)
    }

    @Test fun `candidate probes exist only in the explicit evidence read`() {
        val source = FakeSignals(soc = 80f, range = 200f, power = 18f)
        source.probes = listOf(
            TelemetryEvidenceProbe(
                TelemetryEvidenceRecorder.CANDIDATE_CURRENT_BATTERY_CAPACITY_WH,
                61_700.0,
            )
        )
        val reader = EnergyTelemetryReader(source)

        val normal = reader.read(41L)
        val evidence = reader.readEvidence(42L)

        assertEquals(41L, normal.timestampMs)
        assertEquals(42L, evidence.snapshot.timestampMs)
        assertEquals(61_700.0, evidence.probes.single().value!!, 0.0)
    }

    @Test fun `non finite candidate values fail closed`() {
        val source = FakeSignals(soc = null, range = null, power = null)
        source.probes = listOf(TelemetryEvidenceProbe("candidate.invalid", Double.NaN))

        assertNull(EnergyTelemetryReader(source).readEvidence(42L).probes.single().value)
    }

    @Test fun `trip integrates distance consumption and regeneration`() {
        val trip = EnergyTripAccumulator(0L, 80f)
        trip.add(snapshot(0L, 60f, 18f))
        trip.add(snapshot(1_000L, 60f, 18f))
        trip.add(snapshot(2_000L, 60f, -6f))
        trip.add(snapshot(3_000L, 60f, -6f))
        val result = trip.snapshot(3_000L)
        assertEquals(0.05, result.distanceKm, 0.000001)
        assertTrue(result.distanceAvailable == true)
        assertEquals(0.05, result.recordedDistanceKm!!, 0.000001)
        assertEquals(0.006666, result.consumedKwh!!, 0.000001)
        assertEquals(0.001666, result.regeneratedKwh!!, 0.000001)
    }

    @Test fun `trip keeps distance unknown without a usable speed interval`() {
        val trip = EnergyTripAccumulator(0L, 80f)
        trip.add(snapshot(0L, null, 18f))
        trip.add(snapshot(1_000L, null, 18f))

        val result = trip.snapshot(1_000L)

        assertFalse(result.distanceAvailable == true)
        assertNull(result.recordedDistanceKm)
        assertNull(result.averageConsumptionKwhPer100Km)
    }

    @Test fun `a suspended sampler never inflates trip duration`() {
        val trip = EnergyTripAccumulator(0L, 80f)
        trip.add(snapshot(0L, 60f, 18f))
        trip.add(snapshot(1_000L, 60f, 18f))
        trip.add(snapshot(600_000L, 60f, 18f)) // ten minutes with the dashboard hidden
        val result = trip.snapshot(600_000L)
        assertEquals(1_000L, result.durationMs)
        assertEquals(600_000L, result.endedAtMs)
        assertEquals(0.016666, result.distanceKm, 0.000001)
    }

    @Test fun `history is bounded newest first and leaves no temp file`() {
        val directory = Files.createTempDirectory("energy-history").toFile()
        val target = directory.resolve("trips.json")
        val store = EnergyTripHistoryStore(target, maxTrips = 2)
        assertEquals(true, store.append(summary(1L)))
        assertEquals(true, store.append(summary(2L)))
        assertEquals(true, store.append(summary(3L)))
        assertEquals(listOf(3L, 2L), store.readSummaries().map { it.startedAtMs })
        assertEquals(emptyList<String>(), directory.list()?.filter { it.endsWith(".tmp") })
    }

    private fun snapshot(at: Long, speed: Float?, power: Float) = EnergySnapshot(
        timestampMs = at, firmware = FirmwareInfo.Gen.SWI68, socPercent = 80f,
        rangeKm = null, speedKmh = speed, batteryPowerKw = power,
        outsideTempCelsius = null, cabinTempCelsius = null, batteryTempCelsius = null,
        batteryEnergyKwh = null, batteryCapacityKwh = null, odometerKm = null,
        chargePortConnected = null, chargingStatus = null, parked = null,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )

    private fun summary(start: Long) = EnergyTripSummary(
        start, start + 1, 1, 0.0, null, null, null, null
    )

    private class FakeSignals(
        private val soc: Float?,
        private val range: Float?,
        private val power: Float?,
    ) : EnergySignalSource {
        var probes: List<TelemetryEvidenceProbe> = emptyList()
        override fun firmware() = FirmwareInfo.Gen.SWI68
        override fun socPercent() = soc
        override fun rangeKm() = range
        override fun speedKmh(): Float? = null
        override fun batteryPowerKw() = power
        override fun outsideTempCelsius(): Float? = null
        override fun cabinTempCelsius(): Float? = null
        override fun batteryTempCelsius(): Float? = null
        override fun batteryEnergyKwh(): Float? = null
        override fun batteryCapacityKwh(): Float? = null
        override fun odometerKm(): Float? = null
        override fun chargePortConnected(): Boolean? = null
        override fun chargingStatus(): Int? = null
        override fun parked(): Boolean? = null
        override fun climate() =
            ClimateSnapshot(null, null, null, null, null, null, null, null, null)
        override fun tirePressures() = TirePressureSnapshot(null, null, null, null)
        override fun evidenceProbes() = probes
    }
}

class TripDistanceEvidenceTest {

    private fun summary(evidence: com.evsuite.hardware.VehicleSpeedEvidence?) = EnergyTripSummary(
        startedAtMs = 0L,
        endedAtMs = 1_000L,
        durationMs = 1_000L,
        distanceKm = 8.46,
        startSocPercent = 60f,
        endSocPercent = 59f,
        consumedKwh = null,
        regeneratedKwh = null,
        distanceAvailable = true,
        speedEvidence = evidence,
    )

    @Test fun `a legacy distance stays reported but is not modellable`() {
        val trip = summary(evidence = null)
        assertEquals(8.46, trip.recordedDistanceKm!!, 1e-9)
        assertNull("a model must not train on an unversioned distance", trip.modellableDistanceKm)
    }

    @Test fun `a distance from the 3_6x conversion is not modellable`() {
        val trip = summary(
            com.evsuite.hardware.VehicleSpeedEvidence(
                com.evsuite.hardware.FirmwareInfo.Gen.SWI68,
                com.evsuite.hardware.VehicleSpeedEvidence.MPS_TIMES_3_6_V1,
            )
        )
        assertEquals(8.46, trip.recordedDistanceKm!!, 1e-9)
        assertNull(trip.modellableDistanceKm)
    }
}
