package com.evsuite.hardware.probe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the survey has to get right off the car: the three verdicts stay distinct, and the
 * harvested transaction table has no collisions.
 *
 * And the one thing that must never be true: no transaction the survey calls may be one that
 * changes something. The power interface carries a shutdown request two codes away from a
 * getter, and a survey is run on a car with a driver in it.
 *
 * None of this can be checked on the vehicle, because on the vehicle a wrong answer looks like a
 * finding. A silent binder reported as ANSWERED would put an empty row in the survey that reads
 * like "this interface has nothing to say", and two option getters sharing a code would put a
 * value under the wrong name — both would survive the drive and mislead whoever reads the
 * capture afterwards.
 */
class RuntimeInterfaceProbeTest {

    @Test
    fun `no binder is absent, not denied`() {
        assertEquals(
            BindVerdict.ABSENT,
            RuntimeInterfaceProbe.verdictFor(reached = false, answers = listOf(null)),
        )
    }

    @Test
    fun `a binder that answers nothing is denied, not answered empty`() {
        assertEquals(
            BindVerdict.DENIED,
            RuntimeInterfaceProbe.verdictFor(reached = true, answers = listOf(null, null, null)),
        )
    }

    @Test
    fun `one answer among many is enough to call it alive`() {
        assertEquals(
            BindVerdict.ANSWERED,
            RuntimeInterfaceProbe.verdictFor(reached = true, answers = listOf(null, "0", null)),
        )
    }

    @Test
    fun `a reached binder with nothing asked of it is denied`() {
        assertEquals(
            BindVerdict.DENIED,
            RuntimeInterfaceProbe.verdictFor(reached = true, answers = emptyList()),
        )
    }

    @Test
    fun `the survey never names a transaction that changes something`() {
        val calls = RuntimeInterfaceProbe.POWER_CALLS
        val writes = calls.filterValues { it in RuntimeInterfaceProbe.POWER_WRITES }
        assertEquals(emptyMap<String, Int>(), writes)
    }

    @Test
    fun `each config getter has its own transaction code`() {
        val calls = RuntimeInterfaceProbe.CONFIG_CALLS
        assertEquals(calls.size, calls.values.toSet().size)
    }
}
