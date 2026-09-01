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
