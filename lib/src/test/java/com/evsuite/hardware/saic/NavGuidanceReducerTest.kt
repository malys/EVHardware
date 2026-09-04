package com.evsuite.hardware.saic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NavGuidanceReducerTest {

    private fun fold(code: Int, first: Int? = null, second: Int? = null, text: String? = null) =
        NavGuidanceReducer.fold(NavGuidance.EMPTY, code, first, second, text, atElapsedMs = 1_000L)

    @Test fun `remaining distance and time land in their own fields`() {
        val distance = fold(NavGuidanceReducer.TX_REMAINING_DISTANCE_CHANGE, first = 143)
        assertEquals(143, distance.remainingDistanceRaw)
        assertNull("distance must not be mistaken for a duration", distance.remainingMinutes)

        val minutes = fold(NavGuidanceReducer.TX_REMAINING_TIMES_CHANGE, first = 96)
        assertEquals(96, minutes.remainingMinutes)
        assertNull("duration must not be mistaken for a distance", minutes.remainingDistanceRaw)
    }

    @Test fun `guide infos keep the turn distance apart from the trip distance`() {
        val state = fold(
            NavGuidanceReducer.TX_GUIDE_INFOS_CHANGE,
            first = 7, second = 300, text = "D906",
        )
        assertEquals(7, state.nextTurnIcon)
        assertEquals(300, state.nextTurnDistanceRaw)
        assertEquals("D906", state.direction)
        // The whole point of the separation: 300 m to a turn is not 300 of anything to Alès.
        assertNull(state.remainingDistanceRaw)
    }

    @Test fun `an unknown transaction changes nothing at all`() {
        val before = NavGuidance.EMPTY.copy(remainingDistanceRaw = 143, events = 4)
        val after = NavGuidanceReducer.fold(before, code = 99, first = 1, atElapsedMs = 2_000L)
        assertSame(before, after)
    }

    @Test fun `folding accumulates a trip without losing earlier fields`() {
        var state = NavGuidance.EMPTY
        state = NavGuidanceReducer.fold(
            state, NavGuidanceReducer.TX_GUIDE_STATUS_CHANGE, first = 1, atElapsedMs = 10L,
        )
        state = NavGuidanceReducer.fold(
            state, NavGuidanceReducer.TX_REMAINING_DISTANCE_CHANGE, first = 143, atElapsedMs = 20L,
        )
        state = NavGuidanceReducer.fold(
            state, NavGuidanceReducer.TX_REMAINING_TIMES_CHANGE, first = 96, atElapsedMs = 30L,
        )
        state = NavGuidanceReducer.fold(
            state, NavGuidanceReducer.TX_ROAD_INFO_CHANGE, text = "A61", atElapsedMs = 40L,
        )
        assertEquals(1, state.guideStatus)
        assertEquals(143, state.remainingDistanceRaw)
        assertEquals(96, state.remainingMinutes)
        assertEquals("A61", state.road)
        assertEquals(4, state.events)
        assertEquals(40L, state.updatedAtElapsedMs)
    }

    @Test fun `nothing seen yet is null everywhere rather than zero`() {
        assertNull(NavGuidance.EMPTY.remainingDistanceRaw)
        assertNull(NavGuidance.EMPTY.remainingMinutes)
        assertNull(NavGuidance.EMPTY.guideStatus)
        assertNull(NavGuidance.EMPTY.updatedAtElapsedMs)
        assertEquals(0, NavGuidance.EMPTY.events)
    }

    @Test fun `the known transaction set matches the decoded callbacks`() {
        assertEquals(setOf(1, 2, 3, 12, 13), NavGuidanceReducer.KNOWN_TRANSACTIONS)
    }
}

class TransactionCensusTest {

    @Test fun `every code is counted, decoded or not`() {
        val census = TransactionCensus()
        census.record(NavGuidanceReducer.TX_REMAINING_DISTANCE_CHANGE)
        census.record(NavGuidanceReducer.TX_REMAINING_DISTANCE_CHANGE)
        census.record(41)
        assertEquals(mapOf(13 to 2, 41 to 1), census.snapshot())
        assertEquals(0, census.beyondCeiling)
    }

    @Test fun `a code past the ceiling is separated rather than dropped`() {
        val census = TransactionCensus(ceiling = 16)
        census.record(99)
        census.record(-1)
        assertEquals(emptyMap<Int, Int>(), census.snapshot())
        assertEquals(2, census.beyondCeiling)
    }

    @Test fun `a shifted map shows as traffic on undecoded codes`() {
        // What an inserted method one revision earlier would look like: everything this build
        // decodes goes quiet, and the codes one above it carry the traffic instead.
        val census = TransactionCensus()
        listOf(2, 3, 4, 13, 14).forEach(census::record)
        val seen = census.snapshot().keys
        val decoded = NavGuidanceReducer.KNOWN_TRANSACTIONS
        assertEquals(setOf(4, 14), seen - decoded)
    }

    @Test fun `clearing forgets the previous capture`() {
        val census = TransactionCensus()
        census.record(13)
        census.record(999)
        census.clear()
        assertEquals(emptyMap<Int, Int>(), census.snapshot())
        assertEquals(0, census.beyondCeiling)
    }
}

class NavGuidanceDistanceUnitTest {

    /** The 2026-09-04 capture: 9788 remaining, 25 minutes, "Rue de la Fontaine". */
    private val seen = NavGuidance(remainingDistanceRaw = 9788, remainingMinutes = 25)

    @Test fun `a metres generation reports kilometres`() {
        val km = seen.remainingDistanceKm(com.evsuite.hardware.FirmwareInfo.Gen.SWI68)!!
        assertEquals(9.788, km, 1e-9)
        // The reading that makes it metres: 9.788 km in 25 minutes is a plausible town route.
        assertEquals(23.5, km / (25.0 / 60.0), 0.1)
    }

    @Test fun `an unlisted generation claims no kilometres at all`() {
        com.evsuite.hardware.FirmwareInfo.Gen.entries
            .filterNot { it == com.evsuite.hardware.FirmwareInfo.Gen.SWI68 }
            .forEach { assertNull("$it", seen.remainingDistanceKm(it)) }
    }

    @Test fun `no raw distance means no kilometres`() {
        assertNull(
            NavGuidance.EMPTY.remainingDistanceKm(com.evsuite.hardware.FirmwareInfo.Gen.SWI68)
        )
    }
}
