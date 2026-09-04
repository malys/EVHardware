package com.evsuite.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateChangeLogTest {

    @Test fun `the first value always passes`() {
        assertTrue(StateChangeLog<Int>().accept(2))
    }

    @Test fun `a repeat is suppressed`() {
        val gate = StateChangeLog<Int>()
        assertTrue(gate.accept(2))
        assertFalse(gate.accept(2))
        assertFalse(gate.accept(2))
    }

    @Test fun `a transition passes and the repeats after it do not`() {
        val gate = StateChangeLog<Int>()
        gate.accept(2)
        assertTrue(gate.accept(4))
        assertFalse(gate.accept(4))
        assertTrue(gate.accept(2))
    }

    @Test fun `the ignition flood collapses to its transitions`() {
        // 10 Hz of "RUN/READY" either side of one real change, as the vehicle actually reports it.
        val gate = StateChangeLog<Int>()
        val stream = List(100) { 2 } + List(100) { 0 }
        assertEquals(2, stream.count { gate.accept(it) })
    }

    @Test fun `a first null still counts as a change`() {
        val gate = StateChangeLog<Int?>()
        assertTrue(gate.accept(null))
        assertFalse(gate.accept(null))
        assertTrue(gate.accept(1))
    }

    @Test fun `reset makes the next value log again`() {
        val gate = StateChangeLog<Int>()
        gate.accept(2)
        assertFalse(gate.accept(2))
        gate.reset()
        assertTrue(gate.accept(2))
    }

    private fun assertEquals(expected: Int, actual: Int) =
        org.junit.Assert.assertEquals(expected.toLong(), actual.toLong())
}
