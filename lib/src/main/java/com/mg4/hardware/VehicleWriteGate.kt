package com.mg4.hardware

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * [T-904] Policy: a vehicle setting write is permitted **only when stopped** (0 km/h).
 *
 * Changing AEB, ELK, ACC/TJA or the drive mode while moving alters how the car behaves
 * under the driver. The rule is therefore: 0 km/h or refuse — and refuse ALSO when the
 * speed is unreadable (fail closed), because an unknown speed could be any speed.
 *
 * The one exception to failing closed is **park**. A car in P is not moving, whatever the
 * speedometer failed to say, and the gear arrives from the vendor service on a different
 * path from the AOSP speed property — so when the speed says nothing, the gear can still
 * say standstill. It is never allowed to contradict a speed that *did* read: see [decide].
 *
 * Comfort writes (seat/steering heating via CarHvacManager) are NOT affected: they do not
 * change road behaviour. Setters that are gated carry [RequiresStandstill].
 *
 * This object lives in the shared `mg4-hardware` module, compiled into both MG4Control and
 * MG4Tasker. It carries no dependency on either app's resources: the refusal message shown
 * to the driver comes from [messageProvider], which an app may set to its own localized
 * strings; the English fallback keeps the module self-contained.
 */
object VehicleWriteGate {

    private const val TAG = "MG4_GATE"

    /** Anti-spam on the user message: at most one refusal toast per second. */
    private const val TOAST_THROTTLE_MS = 1_000L

    @Volatile
    private var lastToastMs = 0L

    /**
     * Optional localized message source. An app sets this at init to surface its own
     * strings; when null, the English fallback below is used. Returning null suppresses the
     * toast for that decision.
     */
    @Volatile
    var messageProvider: ((Decision) -> String?)? = null

    enum class Decision {
        /** Vehicle stopped — write allowed. */
        ALLOWED,
        /** Vehicle moving — write refused. */
        REFUSED_MOVING,
        /** Speed unreadable — write refused (fail closed). */
        REFUSED_UNKNOWN_SPEED
    }

    /** Highest threshold an app may set. Above this the gate stops being a gate. */
    const val MAX_ALLOWED_THRESHOLD_KMH = 50f

    /**
     * Speed up to which a write is allowed, in km/h. Zero — standstill only — is the default
     * and the only value that needs no justification.
     *
     * Raising it is a deliberate choice by the app that sets it: the vehicle itself refuses
     * some of these settings while moving, so a higher threshold does not make a write
     * succeed, it only stops this gate from being the one that says no. Values outside
     * 0…[MAX_ALLOWED_THRESHOLD_KMH] are clamped, and an unreadable speed still fails closed
     * whatever the threshold is.
     */
    @Volatile
    var allowUpToKmh: Float = 0f
        set(value) {
            field = value.coerceIn(0f, MAX_ALLOWED_THRESHOLD_KMH)
            AppLogger.i(TAG, "write threshold set to $field km/h")
        }

    /**
     * Pure decision from a speed in km/h, [speedKmh] null when unreadable.
     *
     * A negative speed is treated as unreadable: the VHAL does not produce a negative speed
     * moving forward, and an aberrant value must never open the gate.
     */
    fun decide(speedKmh: Float?): Decision = decide(speedKmh, allowUpToKmh)

    /**
     * [decide] with the threshold passed in, so the rule is testable without global state.
     *
     * [parked] is a second, independent standstill signal — the gear, read from the vendor
     * service rather than from `PERF_VEHICLE_SPEED`. It **only rescues the unreadable case**.
     * Park is a mechanical guarantee that the car is not moving, so when the speed says
     * nothing at all it is better evidence than none; but when the two disagree — park while
     * the speedometer reads 30 — the disagreement itself is the reason to refuse. Rescuing a
     * REFUSED_MOVING would mean a stale or wrong gear could unlock writes at speed, which is
     * the one outcome this gate exists to prevent.
     */
    fun decide(speedKmh: Float?, allowUpToKmh: Float, parked: Boolean? = null): Decision {
        val bySpeed = when {
            speedKmh == null || speedKmh.isNaN() -> Decision.REFUSED_UNKNOWN_SPEED
            speedKmh < 0f                        -> Decision.REFUSED_UNKNOWN_SPEED
            speedKmh <= allowUpToKmh.coerceIn(0f, MAX_ALLOWED_THRESHOLD_KMH) -> Decision.ALLOWED
            else                                 -> Decision.REFUSED_MOVING
        }
        if (bySpeed == Decision.REFUSED_UNKNOWN_SPEED && parked == true) return Decision.ALLOWED
        return bySpeed
    }

    /**
     * The decision for the vehicle as it is right now — what [allow] enforces, exposed so a
     * caller can report the same verdict it is about to get instead of guessing at it.
     *
     * The gear is read only when the speed came back unreadable. That keeps the normal path
     * at one property read: the extra binder round trip happens in the case that was going
     * to be refused anyway.
     */
    fun decideNow(): Decision {
        val bySpeed = decide(MG4Hardware.getVehicleSpeedKmh(), allowUpToKmh)
        if (bySpeed != Decision.REFUSED_UNKNOWN_SPEED) return bySpeed
        return decide(null, allowUpToKmh, parked = MG4Hardware.isVehicleInPark())
    }

    /**
     * True if the [operation] write is allowed now. On refusal it logs and notifies the
     * user — a silent refusal would make the setting look applied when it was not.
     */
    fun allow(operation: String): Boolean {
        val decision = decideNow()
        if (decision == Decision.ALLOWED) return true

        AppLogger.w(TAG, "Write refused ($operation): $decision")
        notifyUser(decision)
        return false
    }

    private fun notifyUser(decision: Decision) {
        val context: Context = MG4Hardware.appContext() ?: return
        val now = System.currentTimeMillis()
        if (now - lastToastMs < TOAST_THROTTLE_MS) return
        lastToastMs = now

        val message = messageProvider?.invoke(decision) ?: defaultMessage(decision) ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun defaultMessage(decision: Decision): String? = when (decision) {
        Decision.REFUSED_MOVING        -> "Setting change refused: vehicle is moving"
        Decision.REFUSED_UNKNOWN_SPEED -> "Setting change refused: speed unreadable"
        Decision.ALLOWED               -> null
    }
}
