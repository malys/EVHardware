package com.evsuite.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmGlassTest {

    @Test fun `hold stops pulsing and releases glass when gate closes`() {
        var now = 0L
        var checks = 0
        val writes = mutableListOf<Pair<Int, Int>>()

        val result = VsmGlass.runHold(
            targets = listOf(0, 1),
            command = VsmGlass.Command.UP,
            durationMs = 1_000L,
            nowMs = { now },
            allowed = { ++checks == 1 },
            write = { area, command -> writes += area to command; true },
            sleep = { now += it },
        )

        assertFalse(result)
        assertEquals(2, checks)
        assertEquals(
            listOf(
                0 to VsmGlass.Command.UP,
                1 to VsmGlass.Command.UP,
                0 to VsmGlass.Command.STOP,
                1 to VsmGlass.Command.STOP,
            ),
            writes,
        )
    }

    @Test fun `hold releases glass when first pulse is refused`() {
        val writes = mutableListOf<Pair<Int, Int>>()

        val result = VsmGlass.runHold(
            targets = listOf(2),
            command = VsmGlass.Command.UP,
            durationMs = 1_000L,
            nowMs = { 0L },
            allowed = { false },
            write = { area, command -> writes += area to command; true },
            sleep = {},
        )

        assertFalse(result)
        assertEquals(listOf(2 to VsmGlass.Command.STOP), writes)
    }

    @Test fun `hold completes while gate remains open`() {
        var now = 0L
        val writes = mutableListOf<Pair<Int, Int>>()

        val result = VsmGlass.runHold(
            targets = listOf(3),
            command = VsmGlass.Command.UP,
            durationMs = VsmGlass.PULSE_MS * 2,
            nowMs = { now },
            allowed = { true },
            write = { area, command -> writes += area to command; true },
            sleep = { now += it },
        )

        assertTrue(result)
        assertEquals(
            listOf(
                3 to VsmGlass.Command.UP,
                3 to VsmGlass.Command.UP,
                3 to VsmGlass.Command.STOP,
            ),
            writes,
        )
    }
}
