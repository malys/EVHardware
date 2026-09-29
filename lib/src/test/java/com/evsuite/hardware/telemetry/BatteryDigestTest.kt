package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BatteryDigestTest {

    private fun day(
        day: String,
        health: Double? = 94.0,
        band: Double? = 3.0,
        calibration: CalibrationVerdict? = CalibrationVerdict.SETTLED,
    ) = BatteryDigest(
        day = day,
        healthPercent = health,
        healthBandPercent = band,
        healthWindows = if (health == null) 0 else 5,
        calibration = calibration,
        daysSinceFullCharge = 4.0,
        hoursAboveHighSoc = 10.0,
        equivalentFullCycles = 12.0,
        chargeCount = 7,
    )

    @Test
    fun `movement inside the band is not a change`() {
        assertTrue(BatteryDigest.changes(day("2026-09-28"), day("2026-09-29", health = 92.0)).isEmpty())
    }

    @Test
    fun `movement past the band is one change`() {
        val change = BatteryDigest.changes(day("2026-09-28"), day("2026-09-29", health = 90.0)).single()
        assertEquals(DigestChange.HealthMoved(94.0, 90.0, 3.0), change)
    }

    @Test
    fun `a band that narrows by a point is said, less is rounding`() {
        val narrowed = BatteryDigest.changes(day("2026-09-28"), day("2026-09-29", band = 2.0))
        assertEquals(listOf(DigestChange.HealthBandNarrowed(3.0, 2.0, 94.0)), narrowed)
        assertTrue(BatteryDigest.changes(day("2026-09-28"), day("2026-09-29", band = 2.5)).isEmpty())
    }

    @Test
    fun `the first estimate is news, and the first day has nothing to compare`() {
        val first = BatteryDigest.changes(day("2026-09-28", health = null, band = null), day("2026-09-29"))
        assertEquals(listOf(DigestChange.HealthBandNarrowed(null, 3.0, 94.0)), first)
        assertTrue(BatteryDigest.changes(null, day("2026-09-29")).isEmpty())
    }

    @Test
    fun `a calibration verdict that changes is a change`() {
        val change = BatteryDigest.changes(
            day("2026-09-28"),
            day("2026-09-29", calibration = CalibrationVerdict.WATCH),
        ).single() as DigestChange.CalibrationChanged
        assertEquals(CalibrationVerdict.WATCH, change.to)
    }

    @Test
    fun `the store keeps one record a day, a bounded year, and returns the day before`() {
        val dir = Files.createTempDirectory("digest").toFile()
        val store = BatteryDigestStore(File(dir, BatteryDigestStore.FILE_NAME), maxDays = 2)
        assertNull(store.upsert(day("2026-09-27")))
        assertEquals("2026-09-27", store.upsert(day("2026-09-28"))?.day)
        assertEquals("2026-09-27", store.upsert(day("2026-09-28", health = 80.0))?.day)
        store.upsert(day("2026-09-29"))
        assertEquals(listOf("2026-09-28", "2026-09-29"), store.read().map { it.day })
        assertEquals(80.0, store.read().first().healthPercent!!, 1e-9)
        dir.deleteRecursively()
    }

    @Test
    fun `an unreadable file is set aside, not trusted`() {
        val dir = Files.createTempDirectory("digest").toFile()
        val file = File(dir, BatteryDigestStore.FILE_NAME).apply { writeText("{not json") }
        assertTrue(BatteryDigestStore(file).read().isEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.contains("quarantine") })
        dir.deleteRecursively()
    }
}
