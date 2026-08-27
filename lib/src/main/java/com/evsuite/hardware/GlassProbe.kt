package com.evsuite.hardware

import com.evsuite.hardware.saic.SaicVehicleControl
import com.evsuite.hardware.saic.SaicVehicleControl.Window
import kotlin.math.abs

/**
 * Finds out which glass command opens a window and which closes it, by trying them.
 *
 * The window actions ship refused because nothing on the firmware says what the eight
 * commands in `0..`[SaicVehicleControl.WINDOW_COMMAND_MAX] do: the vendor service accepts a
 * value, drops what it does not like, and reports the same success either way. No amount of
 * reading settles it. Sending one and watching the position read back does.
 *
 * ## What it does
 *
 * Commands are tried one at a time, with the position read before and after each. Two passes,
 * because a single one cannot see both directions: from a closed window only an *opening*
 * command moves anything, and a closing command looks identical to a command that does
 * nothing. The second pass retries exactly those — the window is open by then, so a closing
 * command now shows. It stops as soon as both directions are known.
 *
 * ## What it refuses
 *
 * Standstill is re-checked before **every** command, not once at the start: a sweep takes
 * half a minute, and a window that opens as the car pulls away is the accident this whole
 * gate exists to prevent. An unreadable position ends it too — without the read-back there is
 * no evidence, only a command sent into the dark, which is the situation this exists to get
 * out of.
 *
 * It puts the glass back where it found it when it can, and says so when it could not.
 */
object GlassProbe {

    /**
     * How much the position must change to count as movement, in percent.
     *
     * The read is a percentage that jitters by a point or so while the motor settles;
     * anything smaller than this would let noise pass for proof.
     */
    const val MOVEMENT_PERCENT = 3

    /** Time given to a window to finish travelling before the position is read back. */
    const val SETTLE_MS = 3_000L

    /** The car, or a stand-in for it in tests. */
    interface Glass {
        fun position(window: Window): Int?
        fun send(window: Window, command: Int): Boolean
    }

    /** One command sent, with the position on either side of it. */
    data class Attempt(val command: Int, val before: Int, val after: Int) {
        val delta: Int get() = after - before
        val moved: Boolean get() = abs(delta) >= MOVEMENT_PERCENT
    }

    enum class Refusal {
        /** The car was moving, or its speed was unreadable. */
        NOT_STOPPED,

        /** Ignition is not in RUN: the windows have no power and prove nothing. */
        IGNITION_NOT_RUN,

        /** The position stopped answering — no read, no evidence. */
        UNREADABLE,

        /** Every command was sent and the glass never budged. */
        NOTHING_MOVED,

        /** One direction found, the other never appeared. */
        ONE_DIRECTION_ONLY,
    }

    data class Result(
        val window: Window,
        val attempts: List<Attempt>,
        val openCommand: Int?,
        val closeCommand: Int?,
        val refusal: Refusal?,
        /** True when the glass ended where the probe found it. */
        val restored: Boolean,
    ) {
        val proven: Boolean get() = openCommand != null && closeCommand != null
    }

    /**
     * Runs the sweep on [window]. Blocking, and long — call it off the main thread.
     *
     * Every dependency is a parameter so the sequence itself can be tested without a car:
     * what makes this worth testing is the pass logic and the refusals, not the AIDL call.
     */
    fun run(
        window: Window,
        glass: Glass = VehicleGlass,
        stopped: () -> Boolean = { VehicleWriteGate.decideNow() == VehicleWriteGate.Decision.ALLOWED },
        ignitionRun: () -> Boolean = { EVHardware.getCurrentIgnitionState() == EVHardware.CarIgnitionItem.RUN },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Result {
        val attempts = mutableListOf<Attempt>()
        fun result(open: Int?, close: Int?, refusal: Refusal?, restored: Boolean = false) =
            Result(window, attempts.toList(), open, close, refusal, restored)

        if (!ignitionRun()) return result(null, null, Refusal.IGNITION_NOT_RUN)
        if (!stopped()) return result(null, null, Refusal.NOT_STOPPED)
        val start = glass.position(window) ?: return result(null, null, Refusal.UNREADABLE)

        var open: Int? = null
        var close: Int? = null

        outer@ for (pass in 0..1) {
            for (command in 0..SaicVehicleControl.WINDOW_COMMAND_MAX) {
                if (open != null && close != null) break@outer
                // A command already shown to move the glass has nothing left to tell us, and
                // re-sending it would only travel the window again.
                if (command == open || command == close) continue

                if (!stopped()) return result(open, close, Refusal.NOT_STOPPED)
                val before = glass.position(window) ?: return result(open, close, Refusal.UNREADABLE)
                // A command the service refuses outright is not evidence of anything: it was
                // never sent, so it is left out of the record rather than logged as inert.
                if (!glass.send(window, command)) continue
                sleep(SETTLE_MS)
                val after = glass.position(window) ?: return result(open, close, Refusal.UNREADABLE)

                val attempt = Attempt(command, before, after)
                attempts += attempt
                AppLogger.i(
                    TAG,
                    "pass $pass ${window.name} command $command: $before% → $after% " +
                        if (attempt.moved) "(moved ${attempt.delta})" else "(no movement)"
                )
                when {
                    attempt.delta >= MOVEMENT_PERCENT -> if (open == null) open = command
                    attempt.delta <= -MOVEMENT_PERCENT -> if (close == null) close = command
                }
            }
        }

        val restored = restore(window, glass, start, close, open, sleep)
        val refusal = when {
            open != null && close != null -> null
            open == null && close == null -> Refusal.NOTHING_MOVED
            else -> Refusal.ONE_DIRECTION_ONLY
        }
        return result(open, close, refusal, restored)
    }

    /**
     * Puts the glass back, when a command in the needed direction is known.
     *
     * Best effort by nature: the commands travel the window to an end, so "back where it was"
     * means closed again after a probe that started closed — which is the normal case, and the
     * one that matters. Anything else is reported as not restored rather than papered over,
     * because a window left open is something the driver has to be told about.
     */
    private fun restore(
        window: Window,
        glass: Glass,
        start: Int,
        close: Int?,
        open: Int?,
        sleep: (Long) -> Unit,
    ): Boolean {
        val now = glass.position(window) ?: return false
        if (abs(now - start) < MOVEMENT_PERCENT) return true
        val command = if (now > start) close else open
        if (command == null) return false
        glass.send(window, command)
        sleep(SETTLE_MS)
        val settled = glass.position(window) ?: return false
        return abs(settled - start) < MOVEMENT_PERCENT
    }

    /** The real car. */
    object VehicleGlass : Glass {
        override fun position(window: Window): Int? = SaicVehicleControl.windowPercent(window)
        override fun send(window: Window, command: Int): Boolean =
            SaicVehicleControl.setWindow(window, command)
    }

    private const val TAG = "EV_GLASS"
}
