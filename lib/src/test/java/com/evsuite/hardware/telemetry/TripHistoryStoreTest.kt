package com.evsuite.hardware.telemetry

import com.evsuite.hardware.FirmwareInfo
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TripHistoryStoreTest {

    @Test fun `a v1 file opens without losing a trip`() {
        val directory = tempDirectory()
        val target = File(directory, "trips.json")
        // Exactly what the previous build wrote: a bare array of summaries, no envelope.
        target.writeText(Gson().toJson(listOf(summary(2L), summary(1L))))

        val store = EnergyTripHistoryStore(target)
        assertEquals(listOf(2L, 1L), store.readSummaries().map { it.startedAtMs })
        assertTrue(store.read().all { it.samples == null })
    }

    @Test fun `a v1 file is rewritten as v2 on the next append`() {
        val directory = tempDirectory()
        val target = File(directory, "trips.json")
        target.writeText(Gson().toJson(listOf(summary(1L))))

        val store = EnergyTripHistoryStore(target)
        assertTrue(store.append(summary(2L), listOf(sample(0L))))
        assertTrue(target.readText().contains("\"schemaVersion\":2"))
        assertEquals(listOf(2L, 1L), store.readSummaries().map { it.startedAtMs })
    }

    @Test fun `a truncated file is quarantined, not deleted, and does not throw`() {
        val directory = tempDirectory()
        val target = File(directory, "trips.json")
        target.writeText("{\"schemaVersion\":2,\"trips\":[{\"summ")

        val store = EnergyTripHistoryStore(target)
        assertEquals(emptyList<StoredTrip>(), store.read())
        val quarantined = directory.listFiles()?.filter { it.name.contains("quarantine") }
        assertEquals(1, quarantined?.size)
        assertTrue(quarantined!!.single().readText().startsWith("{"))
    }

    @Test fun `a file from a newer schema is quarantined rather than misread`() {
        val directory = tempDirectory()
        val target = File(directory, "trips.json")
        target.writeText("{\"schemaVersion\":99,\"trips\":[]}")

        val store = EnergyTripHistoryStore(target)
        assertEquals(emptyList<StoredTrip>(), store.read())
        assertEquals(1, directory.listFiles()?.count { it.name.contains("quarantine") })
    }

    @Test fun `a null that was never measured comes back null, not zero`() {
        val directory = tempDirectory()
        val store = EnergyTripHistoryStore(File(directory, "trips.json"))
        val bare = TripSample(
            atMs = 10L, speedKmh = 42f, batteryPowerKw = null, socPercent = null,
            outsideTempCelsius = null, cabinTempCelsius = null, batteryTempCelsius = null,
            climatePowerOn = null, climateAcOn = null, climateFanLevel = null,
        )
        assertTrue(store.append(summary(1L), listOf(bare)))

        val restored = store.read().single().samples!!.single()
        assertEquals(42f, restored.speedKmh)
        assertNull(restored.batteryPowerKw)
        assertNull(restored.batteryTempCelsius)
        assertNull(restored.climateAcOn)
        assertNull(restored.climateFanLevel)
    }

    @Test fun `tracks are evicted before trips are`() {
        val directory = tempDirectory()
        // Small enough that two full tracks cannot both fit, large enough for one and for
        // every summary. One sample serialises to roughly 200 bytes.
        val store = EnergyTripHistoryStore(File(directory, "trips.json"), maxBytes = 3_000)
        val track = (0 until 10).map { sample(it * 5_000L) }

        assertTrue(store.append(summary(1L), track))
        assertTrue(store.append(summary(2L), track))
        assertTrue(store.append(summary(3L), track))

        val stored = store.read()
        assertEquals(listOf(3L, 2L, 1L), stored.map { it.summary.startedAtMs })
        // The newest trip keeps its track; the oldest lost theirs first.
        assertNotNull(stored.first().samples)
        assertNull(stored.last().samples)
    }

    @Test fun `deleting one trip preserves the other summaries and tracks`() {
        val directory = tempDirectory()
        val store = EnergyTripHistoryStore(File(directory, "trips.json"))
        assertTrue(store.append(summary(1L), listOf(sample(1L))))
        assertTrue(store.append(summary(2L), listOf(sample(2L))))
        assertTrue(store.append(summary(3L), listOf(sample(3L))))

        assertTrue(store.deleteTrip(2L))

        val remaining = store.read()
        assertEquals(listOf(3L, 1L), remaining.map { it.summary.startedAtMs })
        assertEquals(listOf(3L, 1L), remaining.map { it.samples!!.single().atMs })
    }

    @Test fun `deleting an unknown trip leaves the file unchanged`() {
        val directory = tempDirectory()
        val target = File(directory, "trips.json")
        val store = EnergyTripHistoryStore(target)
        assertTrue(store.append(summary(1L)))
        val before = target.readText()

        assertTrue(!store.deleteTrip(99L))
        assertEquals(before, target.readText())
    }

    @Test fun `clearing writes a valid empty v2 history`() {
        val directory = tempDirectory()
        val target = File(directory, "trips.json")
        val store = EnergyTripHistoryStore(target)
        assertTrue(store.append(summary(1L), listOf(sample(1L))))

        assertTrue(store.clear())

        assertEquals(emptyList<StoredTrip>(), store.read())
        assertTrue(target.readText().contains("\"schemaVersion\":2"))
    }

    @Test fun `the file stays under its byte bound across a month of trips`() {
        val directory = tempDirectory()
        val target = File(directory, "trips.json")
        val store = EnergyTripHistoryStore(target, maxBytes = 64 * 1024)
        val track = (0 until 500).map { sample(it * 5_000L) }

        repeat(30) { day -> assertTrue(store.append(summary(day.toLong()), track)) }

        assertTrue("history grew to ${target.length()} bytes", target.length() <= 64 * 1024)
        assertEquals(30, store.read().size)
    }

    @Test fun `a four hour trip is decimated rather than truncated`() {
        val track = TripSampleTrack(startIntervalMs = 5_000L, maxSamples = 64)
        // Four hours at 1 Hz: 14 400 reads, of which the track would keep 2 880 at 5 s.
        repeat(14_400) { second -> track.add(snapshot(at = second * 1_000L)) }

        val samples = track.samples()
        assertTrue("kept ${samples.size}", samples.size <= 64)
        // The last hour is still represented: decimation halves everywhere, it does not cut a tail.
        assertTrue(samples.last().atMs > 3 * 3_600_000L)
        assertEquals(0L, samples.first().atMs)
    }

    @Test fun `the track keeps one sample per interval, not one per read`() {
        val track = TripSampleTrack(startIntervalMs = 5_000L, maxSamples = 4_096)
        repeat(60) { second -> track.add(snapshot(at = second * 1_000L)) }

        // 0, 5, 10 … 55 seconds: twelve samples for sixty reads.
        assertEquals(12, track.samples().size)
    }

    @Test fun `a recorded trip carries the track it was integrated from`() {
        EnergyTripSession.stop(0L)
        EnergyTripSession.start(snapshot(at = 0L))
        repeat(20) { second -> EnergyTripSession.add(snapshot(at = second * 1_000L)) }

        val recorded = EnergyTripSession.stop(20_000L)
        assertNotNull(recorded)
        assertTrue(recorded!!.samples.isNotEmpty())
        assertEquals(0L, recorded.summary.startedAtMs)
    }

    private fun tempDirectory(): File =
        Files.createTempDirectory("trip-history").toFile()

    private fun summary(start: Long) = EnergyTripSummary(
        startedAtMs = start, endedAtMs = start + 1_000L, durationMs = 1_000L,
        distanceKm = 1.0, startSocPercent = 80f, endSocPercent = 79f,
        consumedKwh = 0.2, regeneratedKwh = 0.05,
    )

    private fun sample(at: Long) = TripSample(
        atMs = at, speedKmh = 90f, batteryPowerKw = 18f, socPercent = 70f,
        outsideTempCelsius = 12f, cabinTempCelsius = 21f, batteryTempCelsius = 24f,
        climatePowerOn = true, climateAcOn = false, climateFanLevel = 3,
    )

    private fun snapshot(at: Long) = EnergySnapshot(
        timestampMs = at, firmware = FirmwareInfo.Gen.SWI68, socPercent = 70f,
        rangeKm = null, speedKmh = 90f, batteryPowerKw = 18f,
        outsideTempCelsius = null, cabinTempCelsius = null, batteryTempCelsius = null,
        batteryEnergyKwh = null, batteryCapacityKwh = null, odometerKm = null,
        chargePortConnected = null, chargingStatus = null, parked = null,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )
}
