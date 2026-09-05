package com.evsuite.hardware.saic

import java.util.Locale

/**
 * A destination, in the one form every Android navigation app has agreed to understand.
 *
 * **This is the library's first write towards the car.** Everything else here reads: the hub,
 * the nav adapter, the guidance getters all answer questions and change nothing. This builds a
 * string that will move the screen a driver is about to follow at 130 km/h, and it is separated
 * from the sending on purpose — the format is the part that can be wrong, and the part a JVM
 * test can prove right without a head unit.
 *
 * **Why an intent rather than the OEM's own hook.** `IMapNotificationListener` carries
 * `startNavFromEVRout`, which is exactly what this is for, but reaching it means registering on
 * the channel the head unit uses to *command* the navigation app — impersonating a navigation
 * provider to send one destination. `geo:` is the platform's own answer, it is one line, and if
 * the head unit answers it there is nothing left to decompile. CP-056's Q9 says which.
 *
 * **What it cannot do.** `geo:` carries one point. There are no waypoints in it, so a route
 * planned *through* somewhere cannot be pinned by handing over its endpoint — the caller hands
 * over the charging stop instead, which is the leg the forecast is about, and says so on screen.
 */
object NavigationHandoff {

    /**
     * The destination as a `geo:` URI, or null when the coordinates are not a place on Earth.
     *
     * Refusing out-of-range coordinates is not defensive padding: latitude and longitude are two
     * doubles of the same type and a swap routes to the wrong continent while looking like an
     * ordinary number the whole way. Half the swaps land outside ±90 and this catches those.
     *
     * [Locale.ROOT] because a head unit set to French formats 43.5 as `43,5`, and a comma is a
     * field separator in this URI. The destination would parse, silently, as somewhere else.
     */
    fun geoUri(latitude: Double, longitude: Double, label: String? = null): String? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude < -90.0 || latitude > 90.0) return null
        if (longitude < -180.0 || longitude > 180.0) return null
        val point = String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude)
        val name = label?.let(::sanitise)?.takeIf { it.isNotEmpty() }
        // The `q=` form is what carries a name; the leading point is what an app that ignores
        // `q=` still shows. Both are the same place, so neither reading is wrong.
        return if (name == null) "geo:$point?q=$point" else "geo:$point?q=$point($name)"
    }

    /**
     * A label reduced to what cannot change the meaning of the URI around it.
     *
     * The name comes from a geocoder or a charger dataset — text this project did not write and
     * cannot constrain. A parenthesis, an ampersand or a percent in it would end the label early
     * or introduce a parameter, so the allowlist is letters, digits, spaces and hyphens, and
     * everything else becomes a space. Accented letters are kept: a French place name without
     * them is a different name.
     */
    private fun sanitise(label: String): String =
        label.take(MAX_LABEL_CHARS)
            .map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else ' ' }
            .joinToString("")
            .trim()
            .replace(SPACES, "%20")

    /** Long enough for "Ionity Béziers Est", short enough not to be a payload. */
    private const val MAX_LABEL_CHARS = 48

    private val SPACES = Regex(" +")
}
