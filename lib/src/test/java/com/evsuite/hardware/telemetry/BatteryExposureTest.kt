package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryExposureTest {

    @Test fun `a night ending at eighty five counts every one of its hours as high`() {
        val report = BatteryExposure().analyse(
            listOf(entry(0L, 90f), entry(10 * HOUR, 85f))
        )!!

        assertEquals(10.0, report.hoursAboveHighSoc, 1e-6)
        assertEquals(0.0, report.hoursBelowLowSoc, 1e-6)
        assertEquals(10.0, report.observedHours, 1e-6)
    }

    @Test fun `a segment that crosses the threshold is split where it crosses`() {
        // 90 % to 70 % over ten hours: half of the fall is above 80 %, so half the hours are.
        val report = BatteryExposure().analyse(
            listOf(entry(0L, 90f), entry(10 * HOUR, 70f))
        )!!

        assertEquals(5.0, report.hoursAboveHighSoc, 1e-6)
        assertEquals(80.0, report.meanSocPercent, 1e-6)
    }

    @Test fun `the charge spent is counted in equivalent full cycles`() {
        val report = BatteryExposure().analyse(
            listOf(
                entry(0L, 100f, odometerKm = 10_000f),
                entry(DAY, 20f, odometerKm = 10_400f),
                entry(DAY + 4 * HOUR, 100f, odometerKm = 10_400f),
                entry(2 * DAY, 20f, odometerKm = 10_800f),
            )
        )!!

        assertEquals(1.6, report.equivalentFullCycles, 1e-6)
        assertEquals(800.0 / 1.6, report.kmPerEquivalentCycle!!, 1e-6)
    }

    @Test fun `a rise is one session however many entries it wrote`() {
        val report = BatteryExposure().analyse(
            listOf(
                entry(0L, 20f),
                entry(HOUR, 45f),
                entry(2 * HOUR, 70f),
                entry(3 * HOUR, 80f),
                entry(DAY, 30f),
            )
        )!!

        val session = report.sessions.single()
        assertEquals(20.0, session.startSocPercent, 1e-6)
        assertEquals(80.0, session.endSocPercent, 1e-6)
        assertEquals(60.0, session.gainedPercent, 1e-6)
        assertEquals(20.0, session.meanPercentPerHour, 1e-6)
        assertEquals(25.0, session.peakPercentPerHour, 1e-6)
    }

    @Test fun `the rate is what tells a wall box from a fast charger`() {
        val slow = BatteryExposure().analyse(
            listOf(entry(0L, 30f), entry(8 * HOUR, 90f), entry(DAY, 40f))
        )!!.sessions.single()
        val fast = BatteryExposure().analyse(
            listOf(entry(0L, 30f), entry(HOUR / 2, 80f), entry(DAY, 40f))
        )!!.sessions.single()

        assertTrue(slow.meanPercentPerHour < 10.0)
        assertTrue(fast.meanPercentPerHour > 50.0)
    }

    @Test fun `outside temperature is carried, and stays outside temperature`() {
        val report = BatteryExposure().analyse(
            listOf(entry(0L, 30f, temp = 2f), entry(2 * HOUR, 70f, temp = 4f))
        )!!

        assertEquals(4.0, report.sessions.single().meanOutsideTempCelsius!!, 1e-6)
    }

    @Test fun `a ledger with nothing to compare is not a habit`() {
        assertNull(BatteryExposure().analyse(listOf(entry(0L, 50f))))
        assertNull(BatteryExposure().analyse(emptyList()))
    }

    @Test fun `hours below the low threshold are counted too`() {
        val report = BatteryExposure().analyse(
            listOf(entry(0L, 10f), entry(5 * HOUR, 5f))
        )!!

        assertEquals(5.0, report.hoursBelowLowSoc, 1e-6)
        assertEquals(0.0, report.hoursAboveHighSoc, 1e-6)
    }

    private fun entry(atMs: Long, soc: Float, odometerKm: Float? = null, temp: Float? = null) =
        BatteryLedgerEntry(
            atMs = atMs,
            socPercent = soc,
            odometerKm = odometerKm,
            outsideTempCelsius = temp,
        )

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
