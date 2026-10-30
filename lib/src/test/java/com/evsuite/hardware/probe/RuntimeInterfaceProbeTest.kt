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
        val writes = calls.filterValues { it.code in RuntimeInterfaceProbe.POWER_WRITES }
        assertEquals(emptyMap<String, RuntimeInterfaceProbe.Call>(), writes)
    }

    @Test
    fun `the property survey names no setter`() {
        val writes = RuntimeInterfaceProbe.VEHICLE_PROPERTY_CALLS
            .filterValues { it.code in RuntimeInterfaceProbe.PROPERTY_WRITES }
        assertEquals(emptyMap<String, RuntimeInterfaceProbe.Call>(), writes)
    }

    @Test
    fun `the charging survey never starts, stops or limits a charge`() {
        val writes = RuntimeInterfaceProbe.CHARGING_CALLS
            .filterValues { it.code in RuntimeInterfaceProbe.CHARGING_WRITES }
        assertEquals(emptyMap<String, RuntimeInterfaceProbe.Call>(), writes)
    }

    @Test
    fun `every property read carries the id it is asking for`() {
        val idless = RuntimeInterfaceProbe.VEHICLE_PROPERTY_CALLS.filterValues { it.args.size != 1 }
        assertEquals(emptyMap<String, RuntimeInterfaceProbe.Call>(), idless)
    }

    @Test
    fun `vehicle speed is read as a float`() {
        assertEquals(
            RuntimeInterfaceProbe.ReadAs.FLOAT,
            RuntimeInterfaceProbe.EVS_CALLS.getValue("getVehicleSpeed").readAs,
        )
    }

    @Test
    fun `each config getter has its own transaction code`() {
        val calls = RuntimeInterfaceProbe.CONFIG_CALLS
        assertEquals(calls.size, calls.values.map { it.code }.toSet().size)
    }

    @Test
    fun `the component map keeps the manifest's words and says when it stopped listing`() {
        val rows = (0 until RuntimeInterfaceProbe.MAX_COMPONENT_SERVICES + 3).map {
            RuntimeInterfaceProbe.ServiceRow("com.saicmotor.x.Service$it", it == 0, null)
        }
        val entry = RuntimeInterfaceProbe.componentEntry("com.saicmotor.x", rows)
        assertEquals("componentMap:com.saicmotor.x", entry.name)
        assertEquals(BindVerdict.ANSWERED, entry.verdict)
        assertEquals((rows.size).toString(), entry.values["services"])
        assertEquals("exported=true permission=null", entry.values["com.saicmotor.x.Service0"])
        assertEquals("3", entry.values["truncated"])
        assertEquals(RuntimeInterfaceProbe.MAX_COMPONENT_SERVICES + 2, entry.values.size)
    }

    @Test
    fun `a package that declares no service was still read`() {
        val entry = RuntimeInterfaceProbe.componentEntry("com.saicmotor.y", emptyList())
        assertEquals(BindVerdict.ANSWERED, entry.verdict)
        assertEquals(mapOf("services" to "0"), entry.values)
    }

    @Test
    fun `the head unit serial leaves the car as a length, never as its value`() {
        assertEquals(
            "redacted (15 chars)",
            RuntimeInterfaceProbe.redactSerial("getProductSerialNum", " 7XXXXXXXXXXXXXX"),
        )
        assertEquals(null, RuntimeInterfaceProbe.redactSerial("getProductSerialNum", null))
        assertEquals("11-95-8-58-1", RuntimeInterfaceProbe.redactSerial("getSoftwarePartNum", "11-95-8-58-1"))
    }
}
