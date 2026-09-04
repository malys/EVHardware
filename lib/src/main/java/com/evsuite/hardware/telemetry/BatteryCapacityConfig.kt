package com.evsuite.hardware.telemetry

/**
 * The pack size, as the driver declares it, because the vehicle will not say.
 *
 * `INFO_EV_BATTERY_CAPACITY` is declared and never published on the MG4, and deriving the
 * capacity from an energy total needs battery power, which is itself unvalidated. Without a
 * capacity there is no bridge between kWh and state of charge, and every energy model stops
 * at kWh/100 km. A declared figure is the only one available, so it is taken — and labelled
 * for exactly what it is.
 *
 * **Usable, not nameplate.** State of charge spans the usable window, not the gross pack: the
 * buffers at each end are outside it. An MG4 sold as a 64 kWh car does not put 64 kWh between
 * 0 % and 100 %, and using the nameplate figure would overstate every arrival forecast by the
 * size of those buffers, in the optimistic direction. The field asks for the usable figure and
 * nothing here converts one into the other, because the relationship is not published either.
 *
 * **Everything derived from this is [Provenance.ESTIMATED].** A dealer's state-of-health
 * reading is itself an estimate, the usable figure is a specification rather than a
 * measurement of this pack, and neither was taken today. [RELATIVE_UNCERTAINTY] carries that,
 * so a value built on a declared capacity can never be drawn like a vehicle reading.
 */
data class BatteryCapacityConfig(
    /** kWh between 0 % and 100 % state of charge on a healthy pack — the usable figure. */
    val usableCapacityKwhWhenNew: Double,
    /** State of health in percent, as measured by whoever measured it. */
    val stateOfHealthPercent: Double,
) {
    /** What 100 % state of charge is worth on this pack today. */
    val effectiveUsableKwh: Double
        get() = usableCapacityKwhWhenNew * stateOfHealthPercent / 100.0

    /**
     * The energy behind a state of charge.
     *
     * @param socPercent the vehicle's own reading, 0..100.
     */
    fun energyAtSocKwh(socPercent: Double?): Provenanced<Double> {
        if (socPercent == null || !socPercent.isFinite() || socPercent !in 0.0..100.0) {
            return Provenanced.unavailable(UnavailableReason.SIGNAL_ABSENT)
        }
        val kwh = effectiveUsableKwh * socPercent / 100.0
        return Provenanced.estimated(kwh, uncertainty = kwh * RELATIVE_UNCERTAINTY)
    }

    /** How many state-of-charge points an amount of energy is worth on this pack. */
    fun socPercentForEnergy(kwh: Double?): Provenanced<Double> {
        if (kwh == null || !kwh.isFinite() || effectiveUsableKwh <= 0.0) {
            return Provenanced.unavailable(UnavailableReason.INSUFFICIENT_SAMPLES)
        }
        val soc = kwh * 100.0 / effectiveUsableKwh
        return Provenanced.estimated(soc, uncertainty = kotlin.math.abs(soc) * RELATIVE_UNCERTAINTY)
    }

    companion object {
        /**
         * How wrong a declared capacity may reasonably be.
         *
         * It covers a dealer's state-of-health figure, a usable capacity taken from a
         * specification rather than from this pack, and the drift since either was taken. It
         * is deliberately not smaller: a narrow band on a number nobody measured today would
         * be a false precision, and arrival forecasts are where that gets someone stranded.
         */
        const val RELATIVE_UNCERTAINTY = 0.05

        private val CAPACITY_RANGE = 10.0..250.0
        private val HEALTH_RANGE = 50.0..100.0

        /**
         * A configuration, or null when the figures are not usable.
         *
         * Out-of-range input is refused rather than clamped: a clamped capacity is a number
         * the driver did not enter, silently standing in for the one they did.
         */
        fun of(usableCapacityKwhWhenNew: Double?, stateOfHealthPercent: Double?):
            BatteryCapacityConfig? {
            if (usableCapacityKwhWhenNew == null || stateOfHealthPercent == null) return null
            if (!usableCapacityKwhWhenNew.isFinite() || !stateOfHealthPercent.isFinite()) return null
            if (usableCapacityKwhWhenNew !in CAPACITY_RANGE) return null
            if (stateOfHealthPercent !in HEALTH_RANGE) return null
            return BatteryCapacityConfig(usableCapacityKwhWhenNew, stateOfHealthPercent)
        }
    }
}
