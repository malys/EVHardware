package com.evsuite.hardware

/**
 * The driver EVTasker publishes and other suite apps read (CP-098).
 *
 * EVTasker decides who is driving — a rule's [com.evsuite.hardware.catalog.ActionType.SET_DRIVER],
 * usually on the driver's phone joining hands-free — and serves it from a read-only content
 * provider. A reader such as EVChargePilot only asks, and files a trip under the answer. Both
 * ends take every name and rule from here, so publisher and reader cannot drift.
 *
 * Kept free of Android types so the two rules below run on the JVM.
 */
object DriverContract {

    const val AUTHORITY = "com.evsuite.tasker.driver"
    const val URI_CURRENT = "content://$AUTHORITY/current"

    /**
     * Guards the provider. Declared `signature` in BOTH manifests: on API 28 a client
     * installed before the app that defines a permission is not granted it until it is
     * reinstalled, so the reader declares the same permission itself (allowed, same key).
     */
    const val PERMISSION_READ = "com.evsuite.tasker.permission.READ_DRIVER"

    const val COLUMN_NAME = "name"
    const val COLUMN_SET_AT_MS = "set_at_ms"

    const val MAX_NAME_LENGTH = 24

    /**
     * How long before a trip starts a driver set may still be accepted. The rule fires on
     * the phone joining hands-free, which can happen while the car is still being readied;
     * anything older is a leftover from a cycle the ignition clear did not reach.
     */
    const val ACCEPT_BEFORE_START_MS = 60_000L

    /** The name as stored and shown: trimmed, no control characters, 1..[MAX_NAME_LENGTH]; else null. */
    fun normalizeName(raw: String?): String? {
        val name = raw?.filterNot { it.isISOControl() }?.trim() ?: return null
        return name.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
    }

    /**
     * Whether a driver set at [setAtMs] belongs to the trip running [startMs]..[endMs].
     * A driver set after the trip ended belongs to the next one.
     */
    fun belongsToTrip(setAtMs: Long, startMs: Long, endMs: Long): Boolean =
        setAtMs in (startMs - ACCEPT_BEFORE_START_MS)..endMs
}
