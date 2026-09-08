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
 * **Two forms, because the car answers one of them.** `geo:` is the platform's own answer and
 * every Android map understands it; on SWI68 nothing declares a filter for it, so two drives
 * produced a map that opened at its default view with the destination left behind. [poi] is the
 * other form — the points as the head unit's own adapter carries them, sent by
 * [SaicNavGuidance.startNavFromEvRoute]. Both are built here, and only here, because the format
 * is the part that can be wrong and the part a JVM test can prove right without a head unit.
 *
 * **What `geo:` cannot do.** It carries one point. There are no waypoints in it, so a route
 * planned *through* somewhere cannot be pinned by handing over its endpoint — the caller hands
 * over the charging stop instead, which is the leg the forecast is about, and says so on screen.
 * The adapter form has a pathway list and does not have that limit.
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

    /**
     * One point of a route, in the shape the adapter's own `EVRoutPoiInfo` bean carries:
     * latitude, longitude, name. Nothing else fits in it, which is the whole of what leaves.
     */
    data class Poi(val latitude: Double, val longitude: Double, val name: String)

    /**
     * A validated point, or null when the coordinates are not a place on Earth.
     *
     * The same range check as [geoUri] and for the same reason — two doubles of one type, and a
     * swap that routes to another continent while looking like an ordinary number.
     *
     * The name is *not* sanitised the way a URI label is. This one is a parcel field, not part
     * of a URI, so a parenthesis or an ampersand cannot end it early and stripping them would
     * only mangle the name the driver reads on the map. What is removed is what a text field
     * has no business carrying: control characters, and runs of whitespace that would make two
     * different names look alike. An empty name falls back to the coordinates, because
     * `EVRoutPoiInfo` has nowhere to put "unnamed" and a map showing a blank label is worse
     * than one showing a point.
     */
    fun poi(latitude: Double, longitude: Double, label: String? = null): Poi? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude < -90.0 || latitude > 90.0) return null
        if (longitude < -180.0 || longitude > 180.0) return null
        val name = label.orEmpty()
            .take(MAX_LABEL_CHARS)
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(SPACES, " ")
            .trim()
            .ifEmpty { String.format(Locale.ROOT, "%.6f,%.6f", latitude, longitude) }
        return Poi(latitude, longitude, name)
    }

    /**
     * The pathway list, capped.
     *
     * A charging plan has one stop today and may have three; a list long enough to matter to a
     * binder transaction is a list this app never built. The cap is what keeps a malformed plan
     * from becoming a parcel the adapter has to fan out to every listener it holds a lock over.
     */
    fun pathway(stops: List<Poi>): List<Poi> = stops.take(MAX_PATHWAY_POINTS)

    /** More waypoints than a charging plan has ever produced, and few enough to stay a message. */
    const val MAX_PATHWAY_POINTS = 8
}
