package com.evsuite.hardware.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The before/after capture and the SOC survey call getters by reflection on vendor objects, in a
 * car with a driver in it. The one thing that must never happen is a call that changes something,
 * so the selection is pinned here, off the car, where a wrong answer is a failing test rather
 * than a setting that moved.
 */
class VendorSurfaceRulesTest {

    @Suppress("unused")
    class FakeVendorClient {
        fun getAutoHighBeamSetting(): Int = 1
        fun isAutoHighBeamEnabled(): Boolean = true
        fun getBatterySoc(): Float = 63f
        fun hasRainSensor(): Boolean = false
        fun getVersionName(): String = "x"
        fun getStatusBoxed(): Int? = 2

        fun setAutoHighBeamSetting(value: Int) = Unit
        fun getAndResetCounter(): Int = 0
        fun getInstance(): FakeVendorClient = this
        fun getService(): Any = Any()
        fun getListenerCount(): Int = 0
        fun getLevel(area: Int): Int = area
        fun requestState(): Int = 0
        fun getter(): Int = 0
        fun gettable(): Int = 0
        fun getValues(): IntArray = intArrayOf()
        fun startCharging(): Boolean = true

        companion object {
            @JvmStatic fun getStaticValue(): Int = 0
        }
    }

    private val names = VendorSurfaceRules.readableGetters(FakeVendorClient::class.java).map { it.name }

    @Test
    fun `value getters are read, including Setting and Enabled names`() {
        assertEquals(
            listOf(
                "getAutoHighBeamSetting", "getBatterySoc", "getStatusBoxed", "getVersionName",
                "hasRainSensor", "isAutoHighBeamEnabled",
            ),
            names,
        )
    }

    @Test
    fun `nothing that acts, takes an argument or returns an object is called`() {
        listOf(
            "setAutoHighBeamSetting", "getAndResetCounter", "getInstance", "getService",
            "getListenerCount", "getLevel", "requestState", "getter", "gettable", "getValues",
            "startCharging", "getStaticValue", "getClass", "hashCode", "toString",
        ).forEach { assertFalse(it, it in names) }
    }

    @Test
    fun `keywords narrow the survey, case-insensitively`() {
        val soc = VendorSurfaceRules.readableGetters(FakeVendorClient::class.java, listOf("SOC", "battery"))
        assertEquals(listOf("getBatterySoc"), soc.map { it.name })
    }

    @Test
    fun `camel-case words split on case and underscore`() {
        assertEquals(listOf("and", "reset", "counter"), VendorSurfaceRules.words("AndResetCounter"))
        assertEquals(listOf("high", "beam", "setting"), VendorSurfaceRules.words("High_BeamSetting"))
    }

    @Test
    fun `the diff lists changed, appeared and vanished values, and nothing else`() {
        val before = mapOf("a" to "1", "b" to "2", "gone" to "x")
        val after = mapOf("a" to "1", "b" to "3", "new" to "y")
        assertEquals(
            listOf(
                VendorSurfaceRules.Change("b", "2", "3"),
                VendorSurfaceRules.Change("gone", "x", null),
                VendorSurfaceRules.Change("new", null, "y"),
            ),
            VendorSurfaceRules.diff(before, after),
        )
        assertTrue(VendorSurfaceRules.diff(before, before).isEmpty())
    }

    @Test
    fun `an empty diff says why it may be empty`() {
        val text = VendorSurfaceRules.formatDiff(emptyList(), 10, 10)
        assertTrue(text.contains("0 changed"))
        assertTrue(text.contains("nothing changed"))
        val one = VendorSurfaceRules.formatDiff(listOf(VendorSurfaceRules.Change("k", null, "1")), 1, 2)
        assertTrue(one.contains("k: — → 1"))
    }

    @Test
    fun `arrays print their content`() {
        assertEquals("[1, 2]", VendorSurfaceRules.render(intArrayOf(1, 2)))
        assertEquals("[0.5]", VendorSurfaceRules.render(floatArrayOf(0.5f)))
        assertEquals("null", VendorSurfaceRules.render(null))
    }
}
