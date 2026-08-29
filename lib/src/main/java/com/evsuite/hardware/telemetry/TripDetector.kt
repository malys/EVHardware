package com.evsuite.hardware.telemetry

/**
 * Pure automatic-trip state machine. It observes telemetry but never owns recording or storage.
 *
 * Speed is the mandatory signal. Park/charge confirmation is used at the end when either signal
 * can answer; a firmware with no confirmation signals may fall back to the long standstill timer.
 */
class TripDetector {
    enum class State { IDLE, ARMED, RECORDING, ENDING }
    enum class Event { START, STOP }

    data class Result(val state: State, val event: Event? = null)

    @Volatile
    var state: State = State.IDLE
        private set

    private var stateSinceMs: Long? = null
    private var lastTimestampMs: Long? = null

    @Synchronized
    fun add(snapshot: EnergySnapshot): Result {
        val timestampMs = snapshot.timestampMs
        val previousTimestamp = lastTimestampMs
        if (previousTimestamp != null && timestampMs <= previousTimestamp) {
            // A clock discontinuity invalidates every debounce window. Never end a recording
            // because time moved backwards; return it to the state that needs fresh evidence.
            state = if (state == State.RECORDING || state == State.ENDING) {
                State.RECORDING
            } else {
                State.IDLE
            }
            stateSinceMs = null
        }
        lastTimestampMs = timestampMs

        val speedKmh = snapshot.speedKmh?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }
        if (speedKmh == null) {
            // Missing speed can neither prove motion nor prove a stop. Drop partial debounce
            // evidence, preserve an active recording, and leave manual control available.
            state = if (state == State.RECORDING || state == State.ENDING) {
                State.RECORDING
            } else {
                State.IDLE
            }
            stateSinceMs = null
            return Result(state)
        }

        return when (state) {
            State.IDLE -> {
                if (speedKmh >= START_SPEED_KMH) transition(State.ARMED, timestampMs)
                Result(state)
            }
            State.ARMED -> {
                if (speedKmh < START_SPEED_KMH) {
                    transition(State.IDLE, timestampMs)
                    Result(state)
                } else if (elapsed(timestampMs) >= START_DEBOUNCE_MS) {
                    transition(State.RECORDING, timestampMs)
                    Result(state, Event.START)
                } else {
                    Result(state)
                }
            }
            State.RECORDING -> {
                if (speedKmh <= STOP_SPEED_KMH) transition(State.ENDING, timestampMs)
                Result(state)
            }
            State.ENDING -> {
                when {
                    speedKmh > STOP_SPEED_KMH -> {
                        transition(State.RECORDING, timestampMs)
                        Result(state)
                    }
                    elapsed(timestampMs) >= STOP_DEBOUNCE_MS && endConfirmed(snapshot) -> {
                        transition(State.IDLE, timestampMs)
                        Result(state, Event.STOP)
                    }
                    else -> Result(state)
                }
            }
        }
    }

    /** Keeps detector state aligned when the parked-only manual control starts a trip. */
    @Synchronized
    fun markRecording() {
        state = State.RECORDING
        stateSinceMs = null
    }

    /** Used after a manual stop or when automatic detection is disabled. */
    @Synchronized
    fun reset() {
        state = State.IDLE
        stateSinceMs = null
        lastTimestampMs = null
    }

    private fun transition(next: State, timestampMs: Long) {
        state = next
        stateSinceMs = timestampMs
    }

    private fun elapsed(timestampMs: Long): Long =
        stateSinceMs?.let { (timestampMs - it).coerceAtLeast(0L) } ?: 0L

    private fun endConfirmed(snapshot: EnergySnapshot): Boolean {
        val confirmationAvailable = snapshot.parked != null || snapshot.chargePortConnected != null
        return !confirmationAvailable || snapshot.parked == true || snapshot.chargePortConnected == true
    }

    companion object {
        /** Ignores parking creep and noisy zero readings; 5 km/h is unambiguous road motion. */
        const val START_SPEED_KMH = 5.0

        /** Five consecutive seconds reject a single stale or bouncing speed sample. */
        const val START_DEBOUNCE_MS = 5_000L

        /** Separate 1 km/h stop threshold provides hysteresis after a trip has started. */
        const val STOP_SPEED_KMH = 1.0

        /** Longer than the ticket's 90-second traffic stop while still practical at arrival. */
        const val STOP_DEBOUNCE_MS = 120_000L
    }
}
