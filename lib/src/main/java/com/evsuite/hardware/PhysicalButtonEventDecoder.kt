package com.evsuite.hardware

import com.evsuite.hardware.catalog.SnapshotKeys

/**
 * Decodes `com.saic.keyevent.hardkey.report` payloads.
 *
 * Codes 5/23/24/25/87/88/164/286/287 come from R69 EOL's
 * PhysicalKeysBroadcastReceiver; 287 is also named VOICE_KEY_CODE by VrSpeechService.
 * Codes 17 and 18 are firmware aliases already observed by EVProfile.
 */
class PhysicalButtonEventDecoder {
    enum class Button(val codes: Set<Int>, val shortKey: String, val longKey: String) {
        PHONE(setOf(5, 16), SnapshotKeys.KEY_PHONE_SHORT, SnapshotKeys.KEY_PHONE_LONG),
        UP(setOf(6), SnapshotKeys.KEY_UP_SHORT, SnapshotKeys.KEY_UP_LONG),
        DOWN(setOf(7), SnapshotKeys.KEY_DOWN_SHORT, SnapshotKeys.KEY_DOWN_LONG),
        OK(setOf(8), SnapshotKeys.KEY_OK_SHORT, SnapshotKeys.KEY_OK_LONG),
        LEFT(setOf(9), SnapshotKeys.KEY_LEFT_SHORT, SnapshotKeys.KEY_LEFT_LONG),
        RIGHT(setOf(10), SnapshotKeys.KEY_RIGHT_SHORT, SnapshotKeys.KEY_RIGHT_LONG),
        SOURCE(setOf(110), SnapshotKeys.KEY_SOURCE_SHORT, SnapshotKeys.KEY_SOURCE_LONG),
        CENTER(setOf(23), SnapshotKeys.KEY_CENTER_SHORT, SnapshotKeys.KEY_CENTER_LONG),
        VOLUME_UP(setOf(24), SnapshotKeys.KEY_VOLUME_UP_SHORT, SnapshotKeys.KEY_VOLUME_UP_LONG),
        VOLUME_DOWN(setOf(25), SnapshotKeys.KEY_VOLUME_DOWN_SHORT, SnapshotKeys.KEY_VOLUME_DOWN_LONG),
        MEDIA_NEXT(setOf(87), SnapshotKeys.KEY_MEDIA_NEXT_SHORT, SnapshotKeys.KEY_MEDIA_NEXT_LONG),
        MEDIA_PREVIOUS(setOf(88), SnapshotKeys.KEY_MEDIA_PREVIOUS_SHORT, SnapshotKeys.KEY_MEDIA_PREVIOUS_LONG),
        MUTE(setOf(164), SnapshotKeys.KEY_MUTE_SHORT, SnapshotKeys.KEY_MUTE_LONG),
        STAR_LEFT(setOf(17), SnapshotKeys.KEY_STAR_LEFT_SHORT, SnapshotKeys.KEY_STAR_LEFT_LONG),
        STAR_RIGHT(setOf(286, 18), SnapshotKeys.KEY_STAR_RIGHT_SHORT, SnapshotKeys.KEY_STAR_RIGHT_LONG),
        ASSISTANT(setOf(287), SnapshotKeys.KEY_ASSISTANT_SHORT, SnapshotKeys.KEY_ASSISTANT_LONG)
    }
    enum class Press { SHORT, LONG, DOUBLE }
    data class Event(val button: Button, val press: Press) {
        val value: String get() = "${button.name}:${press.name}"
        fun readings(): Map<String, Any> = mapOf(SnapshotKeys.KEY_PHYSICAL_BUTTON_EVENT to value)
    }
    private enum class State { DOWN, LONG_REPORTED }
    private val states = mutableMapOf<Button, State>()

    /**
     * How long a second press has to arrive within to count as a double.
     *
     * The stock steering controls use the same window, and it is short on purpose: longer
     * would make two deliberate single presses merge into one double.
     */
    private val lastShortAt = mutableMapOf<Button, Long>()

    /**
     * @param atMillis when the event happened. A monotonic default, and JVM-only so the
     *   decoder stays testable without a device — the caller may pass its own clock.
     */
    fun accept(
        keyCode: Int,
        down: Boolean,
        longPress: Boolean,
        atMillis: Long = System.nanoTime() / 1_000_000,
    ): Event? {
        val button = Button.entries.firstOrNull { keyCode in it.codes } ?: return null
        return when {
            down && longPress && states[button] != State.LONG_REPORTED -> {
                states[button] = State.LONG_REPORTED
                // A long press ends the pairing: the release that follows must not become the
                // second half of a double the driver never made.
                lastShortAt.remove(button)
                Event(button, Press.LONG)
            }
            down -> { states.putIfAbsent(button, State.DOWN); null }
            states.remove(button) == State.DOWN -> {
                val previous = lastShortAt[button]
                if (previous != null && atMillis - previous <= DOUBLE_TAP_MS) {
                    // Consumed, so three presses read as one double and one single rather than
                    // as two overlapping doubles.
                    lastShortAt.remove(button)
                    Event(button, Press.DOUBLE)
                } else {
                    lastShortAt[button] = atMillis
                    Event(button, Press.SHORT)
                }
            }
            else -> null
        }
    }

    companion object {
        /**
         * The double-tap window, in milliseconds.
         *
         * A [Press.SHORT] is emitted as soon as the button is released, so a double tap
         * produces a SHORT and then a DOUBLE. Holding every single press back for this long
         * would tax the common case to serve the rare one; suppressing the leading SHORT is
         * the caller's business, and only worth doing when a rule is actually waiting on the
         * double — see EVTasker's vehicle service.
         */
        const val DOUBLE_TAP_MS = 300L
    }
}
