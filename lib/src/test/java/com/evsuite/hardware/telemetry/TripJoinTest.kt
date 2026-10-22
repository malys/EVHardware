package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TripJoinTest {
    private val minute = 60_000L

    @Test fun `a short stop with no charge joins on save`() {
        val store = EnergyTripHistoryStore(File(Files.createTempDirectory("join").toFile(), "trips.json"))
        assertTrue(store.append(trip(0L, 20 * minute, 80f, 75f), listOf(sample(0L))))

        val stored = store.appendOrJoin(trip(25 * minute, 45 * minute, 75f, 70f), listOf(sample(25 * minute)))

        assertEquals(0L, stored)
        val joined = store.read().single()
        assertEquals(45 * minute, joined.summary.endedAtMs)
        // The five-minute stop is in neither the recorded time nor the distance.
        assertEquals(40 * minute, joined.summary.durationMs)
        assertEquals(20.0, joined.summary.distanceKm, 1e-9)
        assertEquals(80f, joined.summary.startSocPercent)
        assertEquals(70f, joined.summary.endSocPercent)
        assertEquals(4.0, joined.summary.consumedKwh!!, 1e-9)
        assertEquals(2, joined.samples!!.size)
    }

    @Test fun `a long stop or a charge keeps two trips`() {
        val store = EnergyTripHistoryStore(File(Files.createTempDirectory("join").toFile(), "trips.json"))
        store.append(trip(0L, 20 * minute, 80f, 75f))
        store.appendOrJoin(trip(40 * minute, 60 * minute, 75f, 70f))
        assertEquals(2, store.read().size)

        store.appendOrJoin(trip(62 * minute, 80 * minute, 90f, 85f))
        assertEquals(3, store.read().size)
    }

    @Test fun `an unread charge level never joins on its own`() {
        val earlier = trip(0L, 20 * minute, 80f, null)
        val later = trip(22 * minute, 40 * minute, 75f, 70f)
        assertFalse(TripJoin.shouldAutoJoin(earlier, later))
        assertTrue(TripJoin.canJoin(earlier, later))
    }

    @Test fun `the driver joins across any stop, but not across a charge`() {
        val store = EnergyTripHistoryStore(File(Files.createTempDirectory("join").toFile(), "trips.json"))
        store.append(trip(0L, 20 * minute, 80f, 75f))
        store.append(trip(120 * minute, 140 * minute, 75f, 70f))
        store.append(trip(600 * minute, 620 * minute, 95f, 90f))

        assertNull(store.joinWithPrevious(600 * minute))
        assertEquals(0L, store.joinWithPrevious(120 * minute))
        assertEquals(listOf(600 * minute, 0L), store.readSummaries().map { it.startedAtMs })
        assertNull(store.joinWithPrevious(0L))
    }

    @Test fun `a missing figure on either side stays missing`() {
        val joined = TripJoin.join(
            StoredTrip(trip(0L, minute, 80f, 79f)),
            StoredTrip(trip(2 * minute, 3 * minute, 79f, 78f).copy(consumedKwh = null, harshBrakings = null)),
        ).summary
        assertNull(joined.consumedKwh)
        assertNull(joined.regeneratedKwh)
        assertNull(joined.batteryPowerEvidence)
        assertNull(joined.harshBrakings)
    }

    private fun trip(start: Long, end: Long, from: Float, to: Float?) = EnergyTripSummary(
        startedAtMs = start, endedAtMs = end, durationMs = end - start,
        distanceKm = 10.0, startSocPercent = from, endSocPercent = to,
        consumedKwh = 2.0, regeneratedKwh = 0.3, distanceAvailable = true,
        harshBrakings = 1,
    )

    private fun sample(at: Long) = TripSample(
        atMs = at, speedKmh = 90f, batteryPowerKw = 18f, socPercent = 70f,
        outsideTempCelsius = 12f, cabinTempCelsius = 21f, batteryTempCelsius = 24f,
        climatePowerOn = true, climateAcOn = false, climateFanLevel = 3,
    )
}
