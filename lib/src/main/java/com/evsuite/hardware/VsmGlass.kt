package com.evsuite.hardware

import java.lang.reflect.Method

/**
 * The glass, through the VSM — the interface the car's own window switch goes through.
 *
 * EVHardware reached the windows through the vendor hub until now
 * ([com.evsuite.hardware.saic.SaicVehicleControl]), and that path is the whole reason the
 * glass actions shipped refused: its setter takes a value no application on the head unit
 * ever writes, so what the eight accepted values do was never more than a guess, and a value
 * the service drops looks exactly like one it obeys.
 *
 * The VSM carries the same glass under two methods whose meaning is settled by use:
 *
 *   • `setVehicleWindowStatus(area, command)` — the commands in [Command]
 *   • `getVehicleWindowValue(area)` — a position, 0 closed to [VALUE_MAX] open
 *
 * Areas are `0..3`: front left, front right, rear left, rear right.
 *
 * **Per generation.** VSM-based firmwares only. SWI68 and SWI165 reach the VSM through
 * `VehicleSettingManager`, SWI69, SWI131 and SWI132 through `CarVehicleSettingClient`, and
 * both expose these names. SWI133 is VPM-based and carries neither, so [isAvailable] answers
 * false there and the caller keeps the hub instead of writing into nothing.
 */
object VsmGlass {

    /** The scale `getVehicleWindowValue` answers on: 0 fully closed, 255 fully open. */
    const val VALUE_MAX = 255

    /** The largest command `setVehicleWindowStatus` carries. */
    const val COMMAND_MAX = 7

    /** How often a held command is re-sent while the glass travels. */
    const val PULSE_MS = 120L

    /** How long [hold] keeps a command down when the caller names no duration. */
    const val HOLD_MS = 5_000L

    /**
     * The commands whose effect is established.
     *
     * [UP] is a switch held down, not an order: the window travels while the command keeps
     * arriving and stops the instant it does not — which is why sending it once and waiting
     * looked exactly like a command that does nothing at all. [AUTO_UP] is the one-shot close
     * the door switch performs, and only the trims whose motors carry a position sensor obey
     * it; [hold] works on both, so it is what a close falls back to.
     *
     * No opening command is named here. Nothing on the head unit sends one, so any value
     * written down would be a guess — [GlassProbe] finds them on the car instead.
     */
    object Command {
        const val STOP = 0
        const val UP = 1
        const val AUTO_UP = 3
    }

    private val INT = Int::class.javaPrimitiveType!!
    private val AREAS = 0..3

    // Looked up once per VSM class rather than per call: a hold writes eight times a second,
    // and `getMethod` on every one of them is forty lookups for a single close.
    @Volatile private var owner: Class<*>? = null
    @Volatile private var setter: Method? = null
    @Volatile private var getter: Method? = null

    /** The VSM with its two methods resolved, or null when there is nothing to call. */
    @Synchronized
    private fun vsm(): Any? {
        val vsm = EVHardware.vsmInstance() ?: return null
        if (owner !== vsm.javaClass) {
            owner = vsm.javaClass
            setter = method(vsm, "setVehicleWindowStatus", INT, INT)
            getter = method(vsm, "getVehicleWindowValue", INT)
        }
        return vsm
    }

    private fun method(vsm: Any, name: String, vararg types: Class<*>): Method? = try {
        vsm.javaClass.getMethod(name, *types)
    } catch (e: Exception) {
        AppLogger.w(TAG, "  VSM: $name absent — ${e.javaClass.simpleName}")
        null
    }

    /** True when this firmware's VSM carries the glass setter. */
    val isAvailable: Boolean
        get() = FirmwareInfo.isVsmBased() && vsm() != null && setter != null

    /**
     * One window's position, 0 closed to [VALUE_MAX] open, or null when there is no reading.
     *
     * Null means the method is absent, the call threw, or the answer fell outside `0..`
     * [VALUE_MAX] — which is how the sensorless trims seen so far report having no position.
     *
     * **What this cannot rule out.** A motor that answers a constant *inside* the scale is
     * indistinguishable from one reporting a real position, from a single read, and 0 is the
     * obvious candidate for such a constant — it would read as "fully closed" on a window that
     * is wide open. Nothing here can settle that, and guessing at it would be the same mistake
     * the raw hub setter made. [GlassProbe] is what settles it on a car: it holds a command and
     * watches this value change, so a car with [GlassEvidence] recorded has been shown to
     * report positions that move. A car without it has not, and a caller deciding whether to
     * skip a close should weigh the reading accordingly.
     */
    fun value(area: Int): Int? {
        val vsm = vsm() ?: return null
        val get = getter ?: return null
        if (area !in AREAS) return null
        return try {
            (get.invoke(vsm, area) as? Number)?.toInt()?.takeIf { it in 0..VALUE_MAX }
        } catch (e: Exception) {
            AppLogger.w(TAG, "  VSM: getVehicleWindowValue($area) exc: ${e.message}")
            null
        }
    }

    /** The same position in percent — the scale the catalogue and the rules speak in. */
    fun percent(area: Int): Int? = value(area)?.let { it * 100 / VALUE_MAX }

    /** Sends [command] once to each of [areas]. One write, for the commands that are orders. */
    fun send(areas: List<Int>, command: Int): Boolean =
        gate(areas, command)?.let { targets -> targets.map { write(it, command) }.all { it } } ?: false

    /**
     * Holds [command] down on [areas] for [durationMs], then releases it with [Command.STOP].
     *
     * This is what [Command.UP] needs: it moves the glass only while it keeps arriving, so a
     * single write travels the window by whatever it manages before the next frame — nothing
     * a position read can see. A command that is an order rather than a switch is unharmed by
     * being repeated, which is what lets [GlassProbe] sweep both kinds the same way.
     *
     * Blocking for [durationMs] — call it off the main thread.
     *
     * The gate is re-checked before every pulse, not only before the first. Glass is
     * standstill-gated outright on this project (see the 1.6.0 note: with the direction of a
     * command unknown, an unknown direction is not one to allow at speed), and a hold that
     * checked once wrote for five more seconds after the car started moving — five seconds in
     * which the policy said no and the glass kept travelling. On refusal the command is
     * released with [Command.STOP] rather than simply abandoned, so the motor stops where it
     * is instead of continuing on the last command it received.
     */
    fun hold(
        areas: List<Int>,
        command: Int,
        durationMs: Long = HOLD_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Boolean {
        val targets = gate(areas, command) ?: return false
        return runHold(
            targets = targets,
            command = command,
            durationMs = durationMs,
            nowMs = System::currentTimeMillis,
            allowed = { VehicleWriteGate.allow("VSM setVehicleWindowStatus (hold)") },
            write = ::write,
            sleep = sleep,
        )
    }

    /** Hardware-free seam proving a moving/unreadable transition stops and releases the glass. */
    internal fun runHold(
        targets: List<Int>,
        command: Int,
        durationMs: Long,
        nowMs: () -> Long,
        allowed: () -> Boolean,
        write: (Int, Int) -> Boolean,
        sleep: (Long) -> Unit,
    ): Boolean {
        var ok = true
        val deadline = nowMs() + durationMs
        while (nowMs() < deadline) {
            if (!allowed()) {
                ok = false
                break
            }
            targets.forEach { if (!write(it, command)) ok = false }
            sleep(PULSE_MS)
        }
        targets.forEach { write(it, Command.STOP) }
        return ok
    }

    /** The areas worth writing to, or null when the call is refused before any write. */
    private fun gate(areas: List<Int>, command: Int): List<Int>? {
        if (command !in 0..COMMAND_MAX) return null
        val targets = areas.filter { it in AREAS }
        if (targets.isEmpty()) return null
        if (vsm() == null || setter == null) return null
        // [T-904] Vehicle write: allowed only when stopped, refused if speed unreadable.
        if (!VehicleWriteGate.allow("VSM setVehicleWindowStatus")) return null
        return targets
    }

    private fun write(area: Int, command: Int): Boolean {
        val vsm = EVHardware.vsmInstance() ?: return false
        val set = setter ?: return false
        return try {
            set.invoke(vsm, area, command)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "  VSM: setVehicleWindowStatus($area, $command) exc: ${e.message}")
            false
        }
    }

    private const val TAG = "EV_GLASS"
}
