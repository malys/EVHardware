package com.evsuite.hardware.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeEnergyTest {

    @Test fun `a watched charge carries its power and the sign the integral moved`() {
        // Three quarters of an hour at sixteen points an hour, with the pack pair integral
        // falling by 8,25 kWh: the declared convention — positive leaves the battery — holding
        // across a charge, and 11 kW of it.
        val entries = listOf(
            entry(0L, 40f, pack = 100.0, odometer = 10_000f),
            entry(15 * MINUTE, 44f, pack = 97.25, odometer = 10_000f),
            entry(30 * MINUTE, 48f, pack = 94.5, odometer = 10_000f),
            entry(45 * MINUTE, 52f, pack = 91.75, odometer = 10_000f),
        )

        val report = analyse(entries)

        assertEquals(1, report.charges.size)
        val charge = report.charges.single()
        assertTrue(charge.watched)
        assertTrue(charge.plugged)
        assertEquals(-8.25, charge.packDeltaKwh!!, 1e-6)
        assertEquals(11.0, charge.meanPowerKw!!, 1e-6)
        assertEquals(ChargePackSign.ENERGY_FELL, report.packSign)
        assertEquals(charge, report.lastPluggedCharge)
    }

    @Test fun `an integral that rises during a charge is reported as the other convention`() {
        val entries = listOf(
            entry(0L, 40f, pack = 10.0),
            entry(15 * MINUTE, 45f, pack = 17.0),
            entry(30 * MINUTE, 50f, pack = 24.0),
        )

        assertEquals(ChargePackSign.ENERGY_ROSE, analyse(entries).packSign)
    }

    @Test fun `two charges that disagree settle nothing`() {
        val entries = listOf(
            entry(0L, 40f, pack = 100.0),
            entry(15 * MINUTE, 45f, pack = 96.5),
            entry(30 * MINUTE, 50f, pack = 93.0),
            // A drive in between, so what follows is a second session rather than the same one.
            entry(45 * MINUTE, 30f, pack = 99.0),
            entry(60 * MINUTE, 35f, pack = 102.5),
            entry(75 * MINUTE, 40f, pack = 106.0),
        )

        assertEquals(ChargePackSign.CONTRADICTORY, analyse(entries).packSign)
    }

    @Test fun `a charge the app slept through carries no power and no sign`() {
        // The recorder earns an entry every quarter of an hour while it is running, so a
        // six-hour silence is the head unit having been off — and the integral did not move
        // during it, which would otherwise read as a charge that took no energy at all.
        val entries = listOf(
            entry(0L, 30f, pack = 100.0),
            entry(6 * HOUR, 90f, pack = 100.0),
        )

        val report = analyse(entries)
        val charge = report.charges.single()
        assertFalse(charge.watched)
        assertNull(charge.meanPowerKw)
        assertEquals(ChargePackSign.UNREADABLE, charge.sign)
        assertEquals(ChargePackSign.UNREADABLE, report.packSign)
    }

    @Test fun `a counter that goes backwards across a charge is the car resetting it`() {
        val entries = listOf(
            entry(0L, 40f, consumed = 12f, regenerated = 3f),
            entry(15 * MINUTE, 55f, consumed = 12f, regenerated = 3f),
            entry(30 * MINUTE, 70f, consumed = 0f, regenerated = 0f),
        )

        val report = analyse(entries)
        assertEquals(ChargeCounterBehaviour.RESET, report.counterBehaviour)
        assertEquals(-12.0, report.charges.single().consumedDeltaKwh!!, 1e-6)
    }

    @Test fun `counters read at both ends and unchanged are static, never absent`() {
        val entries = listOf(
            entry(0L, 40f, consumed = 12f, regenerated = 3f),
            entry(20 * MINUTE, 70f, consumed = 12f, regenerated = 3f),
        )

        assertEquals(ChargeCounterBehaviour.STATIC, analyse(entries).counterBehaviour)
    }

    @Test fun `a car with no counters at all says absent rather than static`() {
        val entries = listOf(entry(0L, 40f), entry(20 * MINUTE, 70f))

        assertEquals(ChargeCounterBehaviour.ABSENT, analyse(entries).counterBehaviour)
    }

    @Test fun `a reset seen once outranks a charge that ended before the reset`() {
        val entries = listOf(
            entry(0L, 40f, consumed = 9f),
            entry(15 * MINUTE, 60f, consumed = 9f),
            entry(30 * MINUTE, 30f, consumed = 14f),
            entry(45 * MINUTE, 70f, consumed = 0f),
        )

        assertEquals(ChargeCounterBehaviour.RESET, analyse(entries).counterBehaviour)
    }

    @Test fun `a rise while the odometer moves is regeneration, never the last charge`() {
        val entries = listOf(
            entry(0L, 40f, pack = 100.0, odometer = 10_000f),
            entry(20 * MINUTE, 48f, pack = 96.0, odometer = 10_020f),
        )

        val report = analyse(entries)
        val charge = report.charges.single()
        assertEquals(20.0, charge.movedKm!!, 1e-6)
        assertFalse(charge.plugged)
        assertNull(report.lastPluggedCharge)
        // The sign is still evidence: energy entering the pack is energy entering the pack.
        assertEquals(ChargePackSign.ENERGY_FELL, report.packSign)
    }

    @Test fun `the description names every question and stays one line per charge`() {
        val entries = listOf(
            entry(0L, 40f, pack = 100.0, odometer = 10_000f, status = 2),
            entry(20 * MINUTE, 55f, pack = 86.0, odometer = 10_000f, status = 2),
        )

        val lines = analyse(entries).describe()

        assertTrue(lines.any { it == "pack_pair_sign=ENERGY_FELL" })
        assertTrue(lines.any { it == "vehicle_counters=ABSENT" })
        assertTrue(lines.any { it == "charging_status_values=2" })
        assertEquals(1, lines.count { it.startsWith("charge ") })
    }

    @Test fun `an empty ledger describes itself without inventing a verdict`() {
        val report = ChargeEnergyAnalyzer().analyse(emptyList(), emptyList())

        assertEquals(ChargePackSign.UNREADABLE, report.packSign)
        assertEquals(ChargeCounterBehaviour.ABSENT, report.counterBehaviour)
        assertEquals(listOf("charges=0"), report.describe())
    }

    /** The sessions are the exposure report's, never re-segmented — the analyzer's own rule. */
    private fun analyse(entries: List<BatteryLedgerEntry>): ChargeEnergyReport {
        val sessions = BatteryExposure().analyse(entries)?.sessions.orEmpty()
        return ChargeEnergyAnalyzer().analyse(entries, sessions)
    }

    private fun entry(
        atMs: Long,
        soc: Float,
        pack: Double? = null,
        consumed: Float? = null,
        regenerated: Float? = null,
        odometer: Float? = null,
        status: Int? = null,
    ) = BatteryLedgerEntry(
        atMs = atMs,
        socPercent = soc,
        odometerKm = odometer,
        vehicleConsumedKwh = consumed,
        vehicleRegeneratedKwh = regenerated,
        packEnergyKwh = pack,
        chargingStatus = status,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
