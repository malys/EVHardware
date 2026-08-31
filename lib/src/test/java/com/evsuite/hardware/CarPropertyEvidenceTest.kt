package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A property the firmware declares and never publishes answers with a zero that looks exactly
 * like a measurement. The status is the only thing that tells the two apart, so what counts as
 * evidence is pinned here rather than left to the reflection that cannot be unit-tested.
 */
class CarPropertyEvidenceTest {

    @Test
    fun `an available value is the measurement`() {
        assertEquals(21.5f, CarPropertyEvidence.accept(21.5f, CarPropertyEvidence.STATUS_AVAILABLE))
    }

    @Test
    fun `zero is a measurement when the vehicle stands behind it`() {
        assertEquals(0f, CarPropertyEvidence.accept(0f, CarPropertyEvidence.STATUS_AVAILABLE))
    }

    @Test
    fun `a declared but unpublished property is not a zero reading`() {
        assertNull(CarPropertyEvidence.accept(0f, CarPropertyEvidence.STATUS_UNAVAILABLE))
    }

    @Test
    fun `a faulty signal is not a reading either`() {
        assertNull(CarPropertyEvidence.accept(42f, CarPropertyEvidence.STATUS_ERROR))
    }

    @Test
    fun `an unknown status is refused rather than guessed`() {
        assertNull(CarPropertyEvidence.accept(42f, 7))
    }

    @Test
    fun `a null value stays null whatever the status claims`() {
        assertNull(CarPropertyEvidence.accept(null, CarPropertyEvidence.STATUS_AVAILABLE))
    }

    @Test
    fun `every status is named so the diagnostics report reads`() {
        assertEquals("available", CarPropertyEvidence.describe(CarPropertyEvidence.STATUS_AVAILABLE))
        assertEquals("unavailable", CarPropertyEvidence.describe(CarPropertyEvidence.STATUS_UNAVAILABLE))
        assertEquals("error", CarPropertyEvidence.describe(CarPropertyEvidence.STATUS_ERROR))
        assertEquals("status 7", CarPropertyEvidence.describe(7))
    }

    @Test
    fun `battery power remains unvalidated until vehicle evidence names a generation`() {
        FirmwareInfo.Gen.values().forEach { firmware ->
            assertFalse(
                firmware.name,
                CarPropertyEvidence.isValidated(
                    CarPropertyEvidence.Signal.BATTERY_POWER_KW,
                    firmware,
                ),
            )
            assertNull(CarPropertyEvidence.batteryPowerEvidence(firmware))
        }
    }

    @Test
    fun `battery temperature remains unvalidated until vehicle evidence names a generation`() {
        FirmwareInfo.Gen.values().forEach { firmware ->
            assertFalse(
                firmware.name,
                CarPropertyEvidence.isValidated(
                    CarPropertyEvidence.Signal.BATTERY_TEMPERATURE_CELSIUS,
                    firmware,
                ),
            )
        }
    }
}

/**
 * One unreadable property sampled every second filled the whole log buffer with the same line
 * and pushed every other entry out of it, so the throttle is part of the diagnostics being
 * usable at all.
 */
class ReadFailureLogTest {

    @Before
    fun reset() = ReadFailureLog.reset()

    @Test
    fun `the same failure is worth one line`() {
        assertTrue(ReadFailureLog.isNew("0x11600207", "unavailable"))
        assertFalse(ReadFailureLog.isNew("0x11600207", "unavailable"))
        assertFalse(ReadFailureLog.isNew("0x11600207", "unavailable"))
    }

    @Test
    fun `a failure that changes its story is worth another`() {
        assertTrue(ReadFailureLog.isNew("0x11600207", "unavailable"))
        assertTrue(ReadFailureLog.isNew("0x11600207", "PropertyNotAvailableException"))
        assertFalse(ReadFailureLog.isNew("0x11600207", "PropertyNotAvailableException"))
    }

    @Test
    fun `properties are throttled apart`() {
        assertTrue(ReadFailureLog.isNew("0x11600207", "unavailable"))
        assertTrue(ReadFailureLog.isNew("0x1160030e", "unavailable"))
    }

    @Test
    fun `a property that recovers reports its next failure`() {
        assertTrue(ReadFailureLog.isNew("0x1160030e", "unavailable"))
        ReadFailureLog.clear("0x1160030e")
        assertTrue(ReadFailureLog.isNew("0x1160030e", "unavailable"))
    }

    @Test
    fun `a new vehicle session starts from silence`() {
        assertTrue(ReadFailureLog.isNew("0x1160030e", "unavailable"))
        ReadFailureLog.reset()
        assertTrue(ReadFailureLog.isNew("0x1160030e", "unavailable"))
    }
}
