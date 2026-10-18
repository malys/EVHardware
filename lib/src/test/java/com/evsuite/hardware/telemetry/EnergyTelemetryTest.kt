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

    @Test fun `one pedal reaches the track as the car's own code, and an unknown code stays unknown`() {
        val signals = FakeSignals(80f, null, null).apply { mode = 9; regen = 6 }
        val sample = TripSample.of(EnergyTelemetryReader(signals).read(1_000L))

        assertEquals(6, sample.regenLevelRaw)
        assertEquals(com.evsuite.hardware.model.RegenLevel.ONE_PEDAL,
            com.evsuite.hardware.model.RegenLevel.ofRaw(sample.regenLevelRaw))
        assertEquals(9, sample.driveModeRaw)
        assertNull(com.evsuite.hardware.model.DriveMode.ofRaw(sample.driveModeRaw))
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

    @Test fun `a trip started on the factory clock is dated from the corrected one`() {
        val factory = 1_546_300_800_000L
        val synced = 1_792_323_000_000L
        val trip = EnergyTripAccumulator(factory, 80f)
        trip.add(snapshot(factory, 30f, 5f))
        trip.add(snapshot(factory + 1_000L, 30f, 5f))
        trip.add(snapshot(synced, 30f, 5f))
        trip.add(snapshot(synced + 1_000L, 30f, 5f))
        val result = trip.snapshot(synced + 1_000L)

        assertEquals(2_000L, result.durationMs)
        assertEquals(synced - 1_000L, result.startedAtMs)
    }

    @Test fun `lifting off into regeneration is not a hard stop`() {
        val trip = EnergyTripAccumulator(0L, 80f)
        // 7 km/h a second is about 1.9 m/s², what regeneration on High does with no brake pedal.
        listOf(50f, 43f, 36f, 29f, 22f, 15f).forEachIndexed { second, speed ->
            trip.add(snapshot(second * 1_000L, speed, -10f))
        }
        val result = trip.snapshot(5_000L)

        assertEquals(0, result.harshBrakings)
        assertEquals(0L, result.harshMs)
    }

    @Test fun `a hard stretch counts once, braking and accelerating apart, at the live rate`() {
        val trip = EnergyTripAccumulator(0L, 80f)
        listOf(0f, 30f, 60f, 60f, 40f, 20f, 20f, 20f, 0f).forEachIndexed { second, speed ->
            trip.add(
                snapshot(second * 1_000L, speed, 5f)
                    .copy(driveModeRaw = if (second < 6) 4 else 2, regenLevelRaw = 6)
            )
        }
        val result = trip.snapshot(8_000L)

        assertEquals(1, result.harshAccelerations)
        assertEquals(2, result.harshBrakings)
        assertEquals(8_000L, result.movingMs)
        assertEquals(5_000L, result.harshMs)
        assertEquals(4, result.driveModeRaw)
        assertEquals(6, result.regenLevelRaw)
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
        var mode: Int? = null
        var regen: Int? = null
        override fun driveModeRaw() = mode
        override fun regenLevelRaw() = regen
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
        assertNull(
            "a model must not train on an unversioned distance",
            trip.modellableDistanceKm(com.evsuite.hardware.FirmwareInfo.Gen.SWI68),
        )
    }

    @Test fun `a distance from the 3_6x conversion is not modellable`() {
        val trip = summary(
            com.evsuite.hardware.VehicleSpeedEvidence(
                com.evsuite.hardware.FirmwareInfo.Gen.SWI68,
                com.evsuite.hardware.VehicleSpeedEvidence.MPS_TIMES_3_6_V1,
            )
        )
        assertEquals(8.46, trip.recordedDistanceKm!!, 1e-9)
        assertNull(trip.modellableDistanceKm(com.evsuite.hardware.FirmwareInfo.Gen.SWI68))
    }
}
