package com.evsuite.hardware.saic

/**
 * What the head unit's navigation stack last said about the trip in progress.
 *
 * Every field is nullable and every field is raw. The adapter service logs these values
 * without naming a unit, and no unit has been proven on a vehicle yet, so nothing here is
 * converted, scaled or rounded — a kilometre read as a metre is the mistake that turns an
 * arrival forecast into a stranding. Consumers must treat the distances as unvalidated until
 * an on-vehicle run says otherwise, which is what CP-040 exists to collect.
 *
 * Absence is absence: a field that never arrived stays null rather than becoming zero.
 */
data class NavGuidance(
    /** Raw `guideStatusChange` code. The mapping to "guiding"/"idle" is not yet proven. */
    val guideStatus: Int? = null,
    /** Raw `remainingDistanceChange` value — distance to destination, unit unproven. */
    val remainingDistanceRaw: Int? = null,
    /** Raw `remainingTimesChange` value. The service logs it as `remainingMinutes`. */
    val remainingMinutes: Int? = null,
    /** Raw `guideInfosChange` distance — to the next manoeuvre, not to the destination. */
    val nextTurnDistanceRaw: Int? = null,
    /** Raw `guideInfosChange` icon code. */
    val nextTurnIcon: Int? = null,
    /** `guideInfosChange` direction text, as the navigation app worded it. */
    val direction: String? = null,
    /** `roadInfoChange` text. */
    val road: String? = null,
    /** How many callbacks have been folded in. The liveness signal a capture needs. */
    val events: Int = 0,
    /** Monotonic time of the last accepted callback, or null before the first one. */
    val updatedAtElapsedMs: Long? = null,
) {
    companion object {
        val EMPTY = NavGuidance()
    }
}

/**
 * Folds one adapter-service callback into [NavGuidance].
 *
 * Pure and Android-free so the transaction map is testable on the JVM: the binder shell does
 * the parcel reading, this decides what the numbers mean. Transaction codes come from
 * `IGeneralNotificationListener` in `saicadapterservice_overseas_eh32` (R69 EH32); an
 * unrecognised code leaves the state untouched rather than guessing at its payload.
 */
object NavGuidanceReducer {

    const val TX_GUIDE_STATUS_CHANGE = 1
    const val TX_GUIDE_INFOS_CHANGE = 2
    const val TX_ROAD_INFO_CHANGE = 3
    const val TX_REMAINING_TIMES_CHANGE = 12
    const val TX_REMAINING_DISTANCE_CHANGE = 13

    /** Every code this decoder claims to understand. Anything else is left alone. */
    val KNOWN_TRANSACTIONS = setOf(
        TX_GUIDE_STATUS_CHANGE,
        TX_GUIDE_INFOS_CHANGE,
        TX_ROAD_INFO_CHANGE,
        TX_REMAINING_TIMES_CHANGE,
        TX_REMAINING_DISTANCE_CHANGE,
    )

    /**
     * @param first the callback's first int argument, when it has one.
     * @param second the second int argument — only `guideInfosChange` carries one.
     * @param text the string argument, for the two callbacks that carry one.
     * @return the new state, or [state] unchanged when [code] is not one this understands.
     */
    fun fold(
        state: NavGuidance,
        code: Int,
        first: Int? = null,
        second: Int? = null,
        text: String? = null,
        atElapsedMs: Long,
    ): NavGuidance {
        val folded = when (code) {
            TX_GUIDE_STATUS_CHANGE -> state.copy(guideStatus = first)
            // icon, then distance to the next manoeuvre, then the direction wording.
            TX_GUIDE_INFOS_CHANGE -> state.copy(
                nextTurnIcon = first,
                nextTurnDistanceRaw = second,
                direction = text,
            )
            TX_ROAD_INFO_CHANGE -> state.copy(road = text)
            TX_REMAINING_TIMES_CHANGE -> state.copy(remainingMinutes = first)
            TX_REMAINING_DISTANCE_CHANGE -> state.copy(remainingDistanceRaw = first)
            else -> return state
        }
        return folded.copy(events = state.events + 1, updatedAtElapsedMs = atElapsedMs)
    }
}
