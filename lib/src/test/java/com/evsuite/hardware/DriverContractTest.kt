package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverContractTest {

    @Test
    fun `a name is trimmed and stripped of control characters`() {
        assertEquals("Alice", DriverContract.normalizeName("  Al\nice\u0000 "))
    }

    @Test
    fun `an empty or oversized name is refused`() {
        assertNull(DriverContract.normalizeName(null))
        assertNull(DriverContract.normalizeName("   "))
        assertNull(DriverContract.normalizeName("x".repeat(DriverContract.MAX_NAME_LENGTH + 1)))
        assertEquals(24, DriverContract.normalizeName("x".repeat(24))?.length)
    }

    @Test
    fun `a driver set during the trip or just before it belongs to it`() {
        val start = 1_000_000L
        val end = 2_000_000L
        assertTrue(DriverContract.belongsToTrip(start + 20_000, start, end))
        assertTrue(DriverContract.belongsToTrip(start - DriverContract.ACCEPT_BEFORE_START_MS, start, end))
        assertTrue(DriverContract.belongsToTrip(end, start, end))
    }

    @Test
    fun `a driver set long before or after the trip does not`() {
        val start = 1_000_000L
        val end = 2_000_000L
        assertFalse(DriverContract.belongsToTrip(start - DriverContract.ACCEPT_BEFORE_START_MS - 1, start, end))
        assertFalse(DriverContract.belongsToTrip(end + 1, start, end))
    }
}
