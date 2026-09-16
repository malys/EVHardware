package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationDriftTest {

    @Test fun `a charge to full last week settles it`() {
        val entries = listOf(
            entry(day(0), 98f),
            // The odometer moves, because the charge was spent driving: a step is only a step
            // where the car went nowhere.
            entry(day(3), 60f, odometerKm = 10_200f),
        )
        val report = CalibrationDrift().analyse(entries, nowMs = day(7))!!

        assertEquals(CalibrationVerdict.SETTLED, report.verdict)
        assertEquals(day(0), report.lastFullChargeAtMs)
        assertEquals(7.0, report.daysSinceFullCharge!!, 1e-9)
        assertEquals(0.38, report.equivalentCyclesSinceFullCharge, 1e-6)
    }

    @Test fun `ninety days without a full charge asks for one`() {
        val entries = listOf(
            entry(day(0), 98f),
            entry(day(50), 60f, odometerKm = 10_300f),
            entry(day(95), 55f, odometerKm = 10_400f),
        )
        val report = CalibrationDrift().analyse(entries, nowMs = day(100))!!

        assertEquals(CalibrationVerdict.RE_ANCHOR_SUGGESTED, report.verdict)
        assertEquals(100.0, report.daysSinceFullCharge!!, 1e-9)
    }

    @Test fun `a history that never saw a full charge is not silently settled`() {
        val entries = listOf(entry(day(0), 80f), entry(day(1), 60f, odometerKm = 10_100f))

        assertEquals(
            CalibrationVerdict.RE_ANCHOR_SUGGESTED,
            CalibrationDrift().analyse(entries, nowMs = day(2))!!.verdict,
        )
    }

    @Test fun `between the two it watches`() {
        val entries = listOf(entry(day(0), 98f), entry(day(40), 70f))

        assertEquals(
            CalibrationVerdict.WATCH,
            CalibrationDrift().analyse(entries, nowMs = day(45))!!.verdict,
        )
    }

    @Test fun `a parked car that lost four points overnight is one unexplained step`() {
        val entries = listOf(
            entry(day(0), 98f),
            entry(day(1), 70f, odometerKm = 10_100f),
            entry(day(1) + 8 * HOUR, 66f, odometerKm = 10_100f),
        )
        val report = CalibrationDrift().analyse(entries, nowMs = day(2))!!

        assertEquals(1, report.steps.size)
        assertEquals(4.0, report.steps.single().dropPercent, 1e-6)
        assertTrue(report.steps.single().parked)
        assertEquals(CalibrationVerdict.RE_ANCHOR_SUGGESTED, report.verdict)
    }

    @Test fun `a fortnight of standing still is self-discharge, not a step`() {
        val entries = listOf(
            entry(day(0), 98f, odometerKm = 10_000f),
            entry(day(14), 85f, odometerKm = 10_000f),
        )
        assertTrue(CalibrationDrift().analyse(entries, nowMs = day(15))!!.steps.isEmpty())
    }

    @Test fun `a drop with the odometer moving is a drive, whatever the app sampled`() {
        val entries = listOf(
            entry(day(0), 98f, odometerKm = 10_000f),
            entry(day(0) + HOUR, 80f, odometerKm = 10_090f),
        )
        assertTrue(CalibrationDrift().analyse(entries, nowMs = day(1))!!.steps.isEmpty())
    }

    @Test fun `without an odometer at both ends nothing is called a step`() {
        val entries = listOf(
            entry(day(0), 98f, odometerKm = null),
            entry(day(0) + HOUR, 90f, odometerKm = null),
        )
        assertTrue(CalibrationDrift().analyse(entries, nowMs = day(1))!!.steps.isEmpty())
    }

    @Test fun `the estimate's band is carried as the spread, in points`() {
        val estimate = StateOfHealthEstimate(
            usableCapacityKwh = Provenanced.estimated(60.0, 1.0),
            stateOfHealthPercent = Provenanced.estimated(97.2, 1.6),
            source = SohEnergySource.VEHICLE_COUNTERS,
            windowCount = 6,
            coldWindowsExcluded = 0,
            spanDays = 30.0,
            spanKm = 1_800.0,
            trendPercentPoints = null,
        )
        val report = CalibrationDrift()
            .analyse(listOf(entry(day(0), 98f), entry(day(1), 60f)), day(2), estimate)!!

        assertEquals(3.2, report.estimateSpreadPercentPoints!!, 1e-9)
    }

    @Test fun `one entry is not a history`() {
        assertNull(CalibrationDrift().analyse(listOf(entry(day(0), 90f)), nowMs = day(1)))
    }

    private fun entry(atMs: Long, soc: Float, odometerKm: Float? = 10_000f) =
        BatteryLedgerEntry(atMs = atMs, socPercent = soc, odometerKm = odometerKm, parked = true)

    private fun day(index: Int): Long = index * DAY

    private companion object {
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
    }
}
