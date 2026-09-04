package com.evsuite.hardware.telemetry

/**
 * The state of charge on arrival, and how wrong it might be.
 *
 * An arrival-SOC point estimate is how drivers get stranded, so this never returns one: the
 * value always carries a band, and the band always widens with distance because the rate it
 * rests on is itself uncertain. Where the band grows wider than [MAX_BAND_PERCENT] the
 * forecast is refused outright rather than shown wide — a number spanning half the pack tells
 * a driver nothing while looking like it does.
 *
 * A negative arrival figure is deliberately not clamped. It means the destination is beyond
 * what the charge covers, which is the single most useful thing this can say.
 */
object ArrivalSocForecast {

    /**
     * Widest band worth showing, in points of charge.
     *
     * Past this the honest answer is "not enough evidence", and saying so is more use than a
     * figure the driver has to discount themselves.
     */
    const val MAX_BAND_PERCENT = 15.0

    /**
     * @param socPercent the vehicle's current state of charge, 0..100.
     * @param remainingDistanceKm distance still to cover, from the head unit's navigation.
     * @param rate what a kilometre costs, from [SocRateEstimator].
     */
    fun of(
        socPercent: Double?,
        remainingDistanceKm: Double?,
        rate: SocRate?,
    ): Provenanced<Double> {
        if (socPercent == null || !socPercent.isFinite() || socPercent !in 0.0..100.0) {
            return Provenanced.unavailable(UnavailableReason.SIGNAL_ABSENT)
        }
        if (remainingDistanceKm == null || !remainingDistanceKm.isFinite() ||
            remainingDistanceKm < 0.0
        ) {
            return Provenanced.unavailable(UnavailableReason.SIGNAL_ABSENT)
        }
        if (rate == null) {
            return Provenanced.unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
        val arrival = socPercent - rate.percentPerKm * remainingDistanceKm
        // The band comes from the rate alone: the distance is the head unit's own figure and
        // the current charge is a vehicle reading, so neither contributes an error bar here.
        val band = rate.uncertaintyPercentPerKm * remainingDistanceKm
        if (!arrival.isFinite() || !band.isFinite()) {
            return Provenanced.unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }
        if (band > MAX_BAND_PERCENT) {
            return Provenanced.unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }
        return Provenanced.estimated(arrival, uncertainty = band)
    }

    /**
     * How far the charge reaches at this rate, for the same inputs.
     *
     * The complement of [of]: an arrival figure answers "will I get there", this one answers
     * "how far does this get me", and a driver deciding whether to stop wants both.
     */
    fun rangeAtRateKm(socPercent: Double?, rate: SocRate?): Provenanced<Double> {
        if (socPercent == null || !socPercent.isFinite() || socPercent !in 0.0..100.0) {
            return Provenanced.unavailable(UnavailableReason.SIGNAL_ABSENT)
        }
        if (rate == null || rate.percentPerKm <= 0.0) {
            return Provenanced.unavailable(UnavailableReason.MODEL_NOT_TRAINED)
        }
        val km = socPercent / rate.percentPerKm
        // A rate known to ±x% puts the distance between soc/(rate+x) and soc/(rate-x); the
        // near edge is the half-width worth quoting, because overstating range is the danger.
        val worst = socPercent / (rate.percentPerKm + rate.uncertaintyPercentPerKm)
        val band = km - worst
        if (!km.isFinite() || !band.isFinite() || band < 0.0) {
            return Provenanced.unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }
        return Provenanced.estimated(km, uncertainty = band)
    }
}
