package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateOfHealthTest {

    @Test fun `six discharges on the car's own counters recover the capacity`() {
        val result = StateOfHealthEstimator()
            .estimate(counterLedger(windows = 6, capacityKwh = 60.0), CAPACITY_WHEN_NEW)

        val ready = result as StateOfHealthResult.Ready
        assertEquals(60.0, ready.estimate.usableCapacityKwh.value!!, 0.2)
        assertEquals(
            60.0 * 100.0 / CAPACITY_WHEN_NEW,
            ready.estimate.stateOfHealthPercent.value!!,
            0.5,
        )
        assertEquals(SohEnergySource.VEHICLE_COUNTERS, ready.estimate.source)
        assertEquals(6, ready.estimate.windowCount)
        assertEquals(Provenance.ESTIMATED, ready.estimate.stateOfHealthPercent.provenance)
        assertNotNull(ready.estimate.stateOfHealthPercent.uncertainty)
    }

    @Test fun `five discharges refuse rather than estimate`() {
        val result = StateOfHealthEstimator()
            .estimate(counterLedger(windows = 5, capacityKwh = 60.0), CAPACITY_WHEN_NEW)

        val unavailable = result as StateOfHealthResult.Unavailable
        assertEquals(UnavailableReason.INSUFFICIENT_SAMPLES, unavailable.reason)
        assertEquals(5, unavailable.windowsSeen)
    }

    @Test fun `the band is never tighter than the counter's own whole kilowatt-hour`() {
        val ready = StateOfHealthEstimator()
            .estimate(counterLedger(windows = 8, capacityKwh = 60.0), CAPACITY_WHEN_NEW)
                as StateOfHealthResult.Ready

        // Identical windows have no spread at all, so a floor is all that is left — and for the
        // car's own counter the coarser of the two is its whole-kWh step, not the charge gauge.
        val expected = StateOfHealthEstimator.COUNTER_STEP_KWH * 100.0 / 60.0
        assertEquals(expected, ready.estimate.usableCapacityKwh.uncertainty!!, 1e-6)
        assertTrue(expected > 60.0 * StateOfHealthEstimator.SOC_STEP_PERCENT / 60.0)
    }

    @Test fun `an integral has no step of its own, so the gauge is its floor`() {
        val ready = StateOfHealthEstimator()
            .estimate(packPairLedger(windows = 8, capacityKwh = 58.0), CAPACITY_WHEN_NEW)
                as StateOfHealthResult.Ready

        val expected = 58.0 * StateOfHealthEstimator.SOC_STEP_PERCENT / 60.0
        assertEquals(expected, ready.estimate.usableCapacityKwh.uncertainty!!, 1e-3)
    }

    @Test fun `a window under the minimum drop contributes nothing`() {
        val entries = listOf(
            entry(day(0), 60f, consumed = 0f),
            entry(day(1), 40f, consumed = 12.0f),
        )
        assertTrue(StateOfHealthEstimator().windows(entries).isEmpty())
    }

    @Test fun `a charge splits the window it falls in`() {
        val entries = listOf(
            entry(day(0), 95f, consumed = 0f),
            entry(day(1), 35f, consumed = 36f),
            entry(day(1) + HOUR, 90f, consumed = 0f),
            entry(day(2), 30f, consumed = 36f),
        )
        val windows = StateOfHealthEstimator().windows(entries)
        assertEquals(2, windows.size)
        assertTrue(windows.all { it.socDropPercent == 60.0 })
    }

    @Test fun `a counter that went backwards is a charge the gauge denied, and is refused`() {
        val entries = listOf(
            entry(day(0), 95f, consumed = 30f),
            entry(day(1), 35f, consumed = 4f),
        )
        assertTrue(StateOfHealthEstimator().windows(entries).isEmpty())
    }

    @Test fun `the car's counters outrank this app's integral even when the integral has more windows`() {
        val entries = ArrayList<BatteryLedgerEntry>()
        entries += packPairLedger(windows = 9, capacityKwh = 52.0)
        entries += counterLedger(windows = 6, capacityKwh = 60.0, startDay = 40)

        val ready = StateOfHealthEstimator().estimate(entries, CAPACITY_WHEN_NEW)
                as StateOfHealthResult.Ready
        assertEquals(SohEnergySource.VEHICLE_COUNTERS, ready.estimate.source)
        assertEquals(60.0, ready.estimate.usableCapacityKwh.value!!, 0.2)
    }

    @Test fun `the pack pair answers when the car keeps no counters`() {
        val ready = StateOfHealthEstimator()
            .estimate(packPairLedger(windows = 6, capacityKwh = 58.0), CAPACITY_WHEN_NEW)
                as StateOfHealthResult.Ready

        assertEquals(SohEnergySource.PACK_PAIR_INTEGRAL, ready.estimate.source)
        assertEquals(58.0, ready.estimate.usableCapacityKwh.value!!, 0.2)
    }

    @Test fun `an integral that covered less ground than the odometer is refused`() {
        val entries = listOf(
            packEntry(day(0), 95f, packKwh = 0.0, odometerKm = 10_000f, integratedKm = 0.0),
            // The car drove 300 km; the app was awake for 200 of them, so a third of the
            // energy is missing and the capacity would come out a third too small.
            packEntry(day(1), 35f, packKwh = 34.8, odometerKm = 10_300f, integratedKm = 200.0),
        )
        assertTrue(StateOfHealthEstimator().windows(entries).isEmpty())
    }

    @Test fun `a cold window is excluded and counted`() {
        val warm = counterLedger(windows = 6, capacityKwh = 60.0)
        val cold = counterLedger(windows = 2, capacityKwh = 48.0, startDay = 40, tempCelsius = -4f)

        val ready = StateOfHealthEstimator().estimate(warm + cold, CAPACITY_WHEN_NEW)
                as StateOfHealthResult.Ready
        assertEquals(6, ready.estimate.windowCount)
        assertEquals(2, ready.estimate.coldWindowsExcluded)
        assertEquals(60.0, ready.estimate.usableCapacityKwh.value!!, 0.2)
    }

    @Test fun `a trend needs a history long enough to have one`() {
        val short = StateOfHealthEstimator()
            .estimate(counterLedger(windows = 6, capacityKwh = 60.0), CAPACITY_WHEN_NEW)
                as StateOfHealthResult.Ready
        assertNull(short.estimate.trendPercentPoints)

        val fading = ArrayList<BatteryLedgerEntry>()
        fading += counterLedger(windows = 6, capacityKwh = 60.0, startDay = 0)
        fading += counterLedger(windows = 6, capacityKwh = 57.0, startDay = 120)
        val long = StateOfHealthEstimator().estimate(fading, CAPACITY_WHEN_NEW)
                as StateOfHealthResult.Ready
        // Three kilowatt-hours lost out of a 61,7 kWh pack is a little under five points.
        assertEquals(-4.9, long.estimate.trendPercentPoints!!, 0.6)
    }

    @Test fun `a capacity nobody declared is refused rather than divided by`() {
        val result = StateOfHealthEstimator()
            .estimate(counterLedger(windows = 6, capacityKwh = 60.0), 0.0)
        assertTrue(result is StateOfHealthResult.Unavailable)
    }

    /** One window per cycle: full to 35 %, then a charge back up, exactly as a week looks. */
    private fun counterLedger(
        windows: Int,
        capacityKwh: Double,
        startDay: Int = 0,
        tempCelsius: Float = 18f,
    ): List<BatteryLedgerEntry> {
        val entries = ArrayList<BatteryLedgerEntry>()
        repeat(windows) { index ->
            val start = day(startDay + index * 2)
            val drop = 60.0
            entries += entry(start, 95f, consumed = 0f, temp = tempCelsius)
            entries += entry(
                start + DAY,
                35f,
                consumed = (capacityKwh * drop / 100.0).toFloat(),
                temp = tempCelsius,
            )
        }
        return entries
    }

    private fun packPairLedger(
        windows: Int,
        capacityKwh: Double,
        startDay: Int = 0,
    ): List<BatteryLedgerEntry> {
        val entries = ArrayList<BatteryLedgerEntry>()
        var cumulativeKwh = 0.0
        var cumulativeKm = 0.0
        repeat(windows) { index ->
            val start = day(startDay + index * 2)
            entries += packEntry(start, 95f, cumulativeKwh, 10_000f + cumulativeKm.toFloat(), cumulativeKm)
            cumulativeKwh += capacityKwh * 0.60
            cumulativeKm += 300.0
            entries += packEntry(
                start + DAY, 35f, cumulativeKwh, 10_000f + cumulativeKm.toFloat(), cumulativeKm,
            )
        }
        return entries
    }

    private fun entry(
        atMs: Long,
        soc: Float,
        consumed: Float,
        regenerated: Float = 0f,
        temp: Float = 18f,
    ) = BatteryLedgerEntry(
        atMs = atMs,
        socPercent = soc,
        outsideTempCelsius = temp,
        vehicleConsumedKwh = consumed,
        vehicleRegeneratedKwh = regenerated,
    )

    private fun packEntry(
        atMs: Long,
        soc: Float,
        packKwh: Double,
        odometerKm: Float,
        integratedKm: Double,
    ) = BatteryLedgerEntry(
        atMs = atMs,
        socPercent = soc,
        odometerKm = odometerKm,
        outsideTempCelsius = 18f,
        packEnergyKwh = packKwh,
        integratedDistanceKm = integratedKm,
    )

    private fun day(index: Int): Long = index * DAY

    private companion object {
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        const val CAPACITY_WHEN_NEW = 61.7
    }
}
