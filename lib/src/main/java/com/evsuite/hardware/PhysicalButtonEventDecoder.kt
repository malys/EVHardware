package com.evsuite.hardware

import com.evsuite.hardware.catalog.SnapshotKeys

/**
 * Decodes the head unit's two hard-key broadcasts.
 *
 * They number their keys in two different spaces, so a code only means something together
 * with the broadcast it came on ([Source]):
 *
 * - `com.saic.keyevent.hardkey.report` carries Android key codes. 5/23/24/25/87/88/164/286/287
 *   come from R69 EOL's PhysicalKeysBroadcastReceiver; 287 is also VrSpeechService's
 *   VOICE_KEY_CODE. 17 and 18 are firmware aliases already observed by EVProfile.
 * - `com.android.systemui.ACTION_HARD_KEY_EVENT` carries SAIC's own numbering: 1/2 volume,
 *   3 mute, 4/5 previous/next (mediaservice HardKeyMonitor, radioservice ReceiveUtils),
 *   6–10 the instrument-cluster pad (engineermodeservice KeyEventReceiver, `_IPK`), 16 phone
 *   (btcallservice).
 *
 * The same physical press arrives on both. Read as one space, SystemUI's 5 (next track) was
 * decoded as the report's 5 (phone), and a phone press arriving twice read as a double.
 * So SystemUI keys the report already carries are dropped, and only the cluster pad — which
 * the report does not carry — is read from SystemUI.
 *
 * A key neither list names is still reported, as an unknown key with its own [Event.keyId],
 * so a driver can bind a button this table has not met yet.
 */
class PhysicalButtonEventDecoder {
    enum class Source { HARDKEY_REPORT, SYSTEM_UI }

    enum class Button(
        /** On the report broadcast. */
        val codes: Set<Int>,
        val shortKey: String,
        val longKey: String,
        /** On the SystemUI broadcast, for keys the report does not carry. */
        val systemUiCodes: Set<Int> = emptySet(),
    ) {
        PHONE(setOf(5), SnapshotKeys.KEY_PHONE_SHORT, SnapshotKeys.KEY_PHONE_LONG),
        UP(emptySet(), SnapshotKeys.KEY_UP_SHORT, SnapshotKeys.KEY_UP_LONG, setOf(6)),
        DOWN(emptySet(), SnapshotKeys.KEY_DOWN_SHORT, SnapshotKeys.KEY_DOWN_LONG, setOf(7)),
        OK(emptySet(), SnapshotKeys.KEY_OK_SHORT, SnapshotKeys.KEY_OK_LONG, setOf(8)),
        LEFT(emptySet(), SnapshotKeys.KEY_LEFT_SHORT, SnapshotKeys.KEY_LEFT_LONG, setOf(9)),
        RIGHT(emptySet(), SnapshotKeys.KEY_RIGHT_SHORT, SnapshotKeys.KEY_RIGHT_LONG, setOf(10)),
        SOURCE(setOf(110), SnapshotKeys.KEY_SOURCE_SHORT, SnapshotKeys.KEY_SOURCE_LONG),
        CENTER(setOf(23), SnapshotKeys.KEY_CENTER_SHORT, SnapshotKeys.KEY_CENTER_LONG),
        VOLUME_UP(setOf(24), SnapshotKeys.KEY_VOLUME_UP_SHORT, SnapshotKeys.KEY_VOLUME_UP_LONG),
        VOLUME_DOWN(setOf(25), SnapshotKeys.KEY_VOLUME_DOWN_SHORT, SnapshotKeys.KEY_VOLUME_DOWN_LONG),
        MEDIA_NEXT(setOf(87), SnapshotKeys.KEY_MEDIA_NEXT_SHORT, SnapshotKeys.KEY_MEDIA_NEXT_LONG),
        MEDIA_PREVIOUS(setOf(88), SnapshotKeys.KEY_MEDIA_PREVIOUS_SHORT, SnapshotKeys.KEY_MEDIA_PREVIOUS_LONG),
        MUTE(setOf(164), SnapshotKeys.KEY_MUTE_SHORT, SnapshotKeys.KEY_MUTE_LONG),
        STAR_LEFT(setOf(17), SnapshotKeys.KEY_STAR_LEFT_SHORT, SnapshotKeys.KEY_STAR_LEFT_LONG),
        STAR_RIGHT(setOf(286, 18), SnapshotKeys.KEY_STAR_RIGHT_SHORT, SnapshotKeys.KEY_STAR_RIGHT_LONG),
        ASSISTANT(setOf(287), SnapshotKeys.KEY_ASSISTANT_SHORT, SnapshotKeys.KEY_ASSISTANT_LONG);

        /** The number a rule stores for this button — the first code, as rules always stored. */
        val id: Int get() = (codes + systemUiCodes).first()

        companion object {
            fun byId(id: Int): Button? = entries.firstOrNull { it.id == id }
        }
    }
    enum class Press { SHORT, LONG, DOUBLE }

    /**
     * One decoded press. [keyId] is [Button.id] for a known button, and an [unknownKeyId]
     * otherwise.
     */
    data class Event(val keyId: Int, val press: Press) {
        val button: Button? get() = Button.byId(keyId)
        val value: String get() = "${keyName(keyId)}:${press.name}"
        fun readings(): Map<String, Any> = mapOf(SnapshotKeys.KEY_PHYSICAL_BUTTON_EVENT to value)
    }
    private enum class State { DOWN, LONG_REPORTED }
    private val states = mutableMapOf<Int, State>()

    /**
     * How long a second press has to arrive within to count as a double.
     *
     * The stock steering controls use the same window, and it is short on purpose: longer
     * would make two deliberate single presses merge into one double.
     */
    private val lastShortAt = mutableMapOf<Int, Long>()

    /**
     * @param atMillis when the event happened. A monotonic default, and JVM-only so the
     *   decoder stays testable without a device — the caller may pass its own clock.
     */
    fun accept(
        keyCode: Int,
        down: Boolean,
        longPress: Boolean,
        atMillis: Long = System.nanoTime() / 1_000_000,
        source: Source = Source.HARDKEY_REPORT,
    ): Event? {
        val key = keyId(source, keyCode) ?: return null
        return when {
            down && longPress && states[key] != State.LONG_REPORTED -> {
                states[key] = State.LONG_REPORTED
                // A long press ends the pairing: the release that follows must not become the
                // second half of a double the driver never made.
                lastShortAt.remove(key)
                Event(key, Press.LONG)
            }
            down -> { states.putIfAbsent(key, State.DOWN); null }
            states.remove(key) == State.DOWN -> {
                val previous = lastShortAt[key]
                if (previous != null && atMillis - previous <= DOUBLE_TAP_MS) {
                    // Consumed, so three presses read as one double and one single rather than
                    // as two overlapping doubles.
                    lastShortAt.remove(key)
                    Event(key, Press.DOUBLE)
                } else {
                    lastShortAt[key] = atMillis
                    Event(key, Press.SHORT)
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

        /** SystemUI codes for keys the report broadcast already carries — see the class doc. */
        private val SYSTEM_UI_DUPLICATES = setOf(1, 2, 3, 4, 5, 16)
        private const val UNKNOWN_REPORT_BASE = 10_000
        private const val UNKNOWN_SYSTEM_UI_BASE = 20_000

        /** Kept apart from every [Button.id] and from the other broadcast's codes. */
        fun unknownKeyId(source: Source, keyCode: Int): Int = keyCode + when (source) {
            Source.HARDKEY_REPORT -> UNKNOWN_REPORT_BASE
            Source.SYSTEM_UI -> UNKNOWN_SYSTEM_UI_BASE
        }

        /** Where an unknown key id came from, as `(source, code)` — null for a known button. */
        fun unknownKey(keyId: Int): Pair<Source, Int>? = when {
            Button.byId(keyId) != null -> null
            keyId >= UNKNOWN_SYSTEM_UI_BASE -> Source.SYSTEM_UI to keyId - UNKNOWN_SYSTEM_UI_BASE
            keyId >= UNKNOWN_REPORT_BASE -> Source.HARDKEY_REPORT to keyId - UNKNOWN_REPORT_BASE
            else -> null
        }

        fun keyName(keyId: Int): String = Button.byId(keyId)?.name ?: "KEY_$keyId"

        private fun keyId(source: Source, keyCode: Int): Int? {
            if (keyCode < 0) return null
            return when (source) {
                Source.HARDKEY_REPORT -> Button.entries.firstOrNull { keyCode in it.codes }?.id
                Source.SYSTEM_UI -> {
                    if (keyCode in SYSTEM_UI_DUPLICATES) return null
                    Button.entries.firstOrNull { keyCode in it.systemUiCodes }?.id
                }
            } ?: unknownKeyId(source, keyCode)
        }
    }
}
