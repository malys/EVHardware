package com.evsuite.hardware.saic

/**
 * Turns what a driver types into the (band, kHz) pair `IRadioAppService.tune` wants.
 *
 * Nobody types "87500". People type "103.5", and about as often "FM 103,5" or "1080 AM".
 * All of those name exactly one station, so all of them are accepted; anything that names
 * no station at all is rejected rather than guessed at, because tuning the wrong frequency
 * is a worse answer than saying the text made no sense.
 *
 * Android-free on purpose: this is the part worth testing, and it runs on the JVM.
 *
 * Bounds and steps follow the project's radio contract: `RadioConstants.AM_RANGE` /
 * `FM_RANGE`, and the tuning scale's own step (`NormalRadioInfoFragment`: 9 kHz on AM,
 * 50 kHz on FM).
 */
object RadioFrequency {

    const val AM_MIN_KHZ = 522
    const val AM_MAX_KHZ = 1620
    const val FM_MIN_KHZ = 87_500
    const val FM_MAX_KHZ = 108_000

    /** A tuned station: the band constant [SaicRadio] expects, and a frequency in kHz. */
    data class Station(val band: Int, val frequencyKhz: Int)

    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

    /**
     * @param text what the driver typed.
     * @param band the band the rule picked, or null when it picked none.
     * @return the station, or null when [text] names none on [band].
     *
     * The band is taken from the rule when it names one, then from the text when the text says
     * one, and inferred from the number otherwise — the two ranges do not overlap in any unit,
     * so "103.5" can only be FM and "1080" can only be AM.
     *
     * A band that disagrees with the text (`AM 103.5`, or a picker on AM over a typed "103.5")
     * is rejected rather than resolved: two things were said, and silently picking one of them
     * is how a rule ends up on a station nobody chose. Only AM and FM name a frequency at all,
     * so a [band] of DAB never parses — the caller reaches DAB by naming the band alone.
     */
    fun parse(text: String, band: Int? = null): Station? {
        val upper = text.uppercase()
        val typed = when {
            "FM" in upper -> SaicRadio.BAND_FM
            "AM" in upper -> SaicRadio.BAND_AM
            else -> null
        }
        // Both said, and not the same thing: the disagreement is the answer.
        if (band != null && typed != null && band != typed) return null
        val stated = band ?: typed
        val raw = NUMBER.find(upper)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null

        // Megahertz or kilohertz, both written by real people: 103.5, 103500, 1080, 1.08.
        val candidates = listOf(
            Station(SaicRadio.BAND_FM, Math.round(raw * 1000).toInt()),
            Station(SaicRadio.BAND_FM, Math.round(raw).toInt()),
            Station(SaicRadio.BAND_AM, Math.round(raw).toInt()),
            Station(SaicRadio.BAND_AM, Math.round(raw * 1000).toInt()),
        )
        return candidates.firstOrNull { it.inRange() && (stated == null || it.band == stated) }
    }

    private fun Station.inRange(): Boolean = when (band) {
        SaicRadio.BAND_FM -> frequencyKhz in FM_MIN_KHZ..FM_MAX_KHZ
        SaicRadio.BAND_AM -> frequencyKhz in AM_MIN_KHZ..AM_MAX_KHZ
        else -> false
    }
}
