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
    enum class Press { SHORT, LONG }
    data class Event(val button: Button, val press: Press) {
        val value: String get() = "${button.name}:${press.name}"
        fun readings(): Map<String, Any> = mapOf(SnapshotKeys.KEY_PHYSICAL_BUTTON_EVENT to value)
    }
    private enum class State { DOWN, LONG_REPORTED }
    private val states = mutableMapOf<Button, State>()
    fun accept(keyCode: Int, down: Boolean, longPress: Boolean): Event? {
        val button = Button.entries.firstOrNull { keyCode in it.codes } ?: return null
        return when {
            down && longPress && states[button] != State.LONG_REPORTED -> {
                states[button] = State.LONG_REPORTED; Event(button, Press.LONG)
            }
            down -> { states.putIfAbsent(button, State.DOWN); null }
            states.remove(button) == State.DOWN -> Event(button, Press.SHORT)
            else -> null
        }
    }
}
