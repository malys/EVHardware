package com.evsuite.hardware.telemetry

import com.evsuite.hardware.FirmwareInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BatteryLedgerTest {

    @Test fun `entries round trip oldest first`() {
        val store = BatteryLedgerStore(File(tempDirectory(), BatteryLedgerStore.FILE_NAME))
        assertTrue(store.append(entry(atMs = 2_000L, soc = 70f)))
        assertTrue(store.append(entry(atMs = 1_000L, soc = 80f)))

        assertEquals(listOf(1_000L, 2_000L), store.read().map { it.atMs })
    }

    @Test fun `an unreadable file is quarantined and never read as an empty ledger twice`() {
        val directory = tempDirectory()
        val target = File(directory, BatteryLedgerStore.FILE_NAME)
        target.writeText("{\"schemaVersion\":1,\"entries\":[{\"atMs\":1,")

        val store = BatteryLedgerStore(target)
        assertTrue(store.read().isEmpty())
        assertFalse(target.exists())
        assertEquals(1, directory.listFiles()!!.count { it.name.contains("quarantine") })
    }

    @Test fun `a file from a schema this build does not know is not guessed at`() {
        val target = File(tempDirectory(), BatteryLedgerStore.FILE_NAME)
        target.writeText("{\"schemaVersion\":99,\"entries\":[{\"atMs\":1,\"socPercent\":50}]}")

        assertTrue(BatteryLedgerStore(target).read().isEmpty())
    }

    @Test fun `the newest entries survive the bound`() {
        val store = BatteryLedgerStore(File(tempDirectory(), BatteryLedgerStore.FILE_NAME), maxEntries = 3)
        repeat(5) { store.append(entry(atMs = it * 1_000L, soc = 90f - it)) }

        assertEquals(listOf(2_000L, 3_000L, 4_000L), store.read().map { it.atMs })
    }

    @Test fun `a sample with no charge reading earns nothing`() {
        val store = BatteryLedgerStore(File(tempDirectory(), BatteryLedgerStore.FILE_NAME))
        val recorder = BatteryLedgerRecorder(store)

        assertFalse(recorder.observe(snapshot(atMs = 0L, soc = null)))
        assertTrue(store.read().isEmpty())
    }

    @Test fun `an entry is earned by a point of charge, not by a sample`() {
        val store = BatteryLedgerStore(File(tempDirectory(), BatteryLedgerStore.FILE_NAME))
        val recorder = BatteryLedgerRecorder(store)

        assertTrue(recorder.observe(snapshot(atMs = 0L, soc = 80f)))
        assertFalse(recorder.observe(snapshot(atMs = 1_000L, soc = 79.5f)))
        assertTrue(recorder.observe(snapshot(atMs = 2_000L, soc = 79f)))

        assertEquals(listOf(80f, 79f), store.read().map { it.socPercent })
    }

    @Test fun `a charging state change earns an entry even at the same charge`() {
        val store = BatteryLedgerStore(File(tempDirectory(), BatteryLedgerStore.FILE_NAME))
        val recorder = BatteryLedgerRecorder(store)

        recorder.observe(snapshot(atMs = 0L, soc = 50f, chargingStatus = 0))
        assertTrue(recorder.observe(snapshot(atMs = 1_000L, soc = 50f, chargingStatus = 1)))
    }

    @Test fun `the integrals are cumulative and bounded by the same five seconds a trip uses`() {
        val store = BatteryLedgerStore(File(tempDirectory(), BatteryLedgerStore.FILE_NAME))
        val recorder = BatteryLedgerRecorder(store)

        recorder.observe(snapshot(atMs = 0L, soc = 80f, powerKw = 36f, speedKmh = 72f))
        // 36 kW and 72 km/h held for an hour, sampled every second, would be 36 kWh and 72 km;
        // one second of it is a thousandth of that.
        recorder.observe(snapshot(atMs = 1_000L, soc = 80f, powerKw = 36f, speedKmh = 72f))
        // A gap longer than five seconds is time the app did not watch: nothing is added.
        recorder.observe(snapshot(atMs = 60_000L, soc = 79f, powerKw = 36f, speedKmh = 72f))

        val last = store.read().last()
        assertEquals(0.01, last.packEnergyKwh!!, 1e-9)
        assertEquals(0.02, last.integratedDistanceKm!!, 1e-9)
    }

    @Test fun `a restart continues the counters instead of starting again at zero`() {
        val target = File(tempDirectory(), BatteryLedgerStore.FILE_NAME)
        val store = BatteryLedgerStore(target)
        val first = BatteryLedgerRecorder(store)
        first.observe(snapshot(atMs = 0L, soc = 80f, powerKw = 36f, speedKmh = 72f))
        first.observe(snapshot(atMs = 1_000L, soc = 79f, powerKw = 36f, speedKmh = 72f))
        val before = store.read().last().packEnergyKwh!!

        val afterRestart = BatteryLedgerRecorder(BatteryLedgerStore(target))
        afterRestart.observe(snapshot(atMs = 10_000L, soc = 78f, powerKw = 36f, speedKmh = 72f))
        afterRestart.observe(snapshot(atMs = 11_000L, soc = 77f, powerKw = 36f, speedKmh = 72f))

        assertTrue(store.read().last().packEnergyKwh!! > before)
        assertEquals(before + 0.01, store.read().last().packEnergyKwh!!, 1e-9)
    }

    @Test fun `the vehicle's own counters are carried through when the car keeps any`() {
        val store = BatteryLedgerStore(File(tempDirectory(), BatteryLedgerStore.FILE_NAME))
        BatteryLedgerRecorder(store).observe(
            snapshot(atMs = 0L, soc = 80f, consumedKwh = 4.5f, regeneratedKwh = 0.5f)
        )

        val entry = store.read().single()
        assertEquals(4.5f, entry.vehicleConsumedKwh!!, 0f)
        assertEquals(0.5f, entry.vehicleRegeneratedKwh!!, 0f)
    }

    @Test fun `a reader never sees an integral the app never integrated`() {
        val store = BatteryLedgerStore(File(tempDirectory(), BatteryLedgerStore.FILE_NAME))
        BatteryLedgerRecorder(store).observe(snapshot(atMs = 0L, soc = 80f, powerKw = null))

        assertNull(store.read().single().packEnergyKwh)
        assertNotNull(store.read().single().socPercent)
    }

    private fun entry(atMs: Long, soc: Float) = BatteryLedgerEntry(atMs = atMs, socPercent = soc)

    private fun snapshot(
        atMs: Long,
        soc: Float?,
        powerKw: Float? = null,
        speedKmh: Float? = null,
        chargingStatus: Int? = null,
        consumedKwh: Float? = null,
        regeneratedKwh: Float? = null,
    ) = EnergySnapshot(
        timestampMs = atMs,
        firmware = FirmwareInfo.Gen.SWI68,
        socPercent = soc,
        rangeKm = null,
        speedKmh = speedKmh,
        batteryPowerKw = powerKw,
        outsideTempCelsius = 18f,
        cabinTempCelsius = null,
        batteryTempCelsius = null,
        batteryEnergyKwh = null,
        batteryCapacityKwh = null,
        odometerKm = 20_000f,
        chargePortConnected = null,
        chargingStatus = chargingStatus,
        vehicleConsumedKwh = consumedKwh,
        vehicleRegeneratedKwh = regeneratedKwh,
        parked = true,
        climate = ClimateSnapshot(null, null, null, null, null, null, null, null, null),
        tirePressures = TirePressureSnapshot(null, null, null, null),
    )

    private fun tempDirectory(): File = Files.createTempDirectory("ledger").toFile()
}
