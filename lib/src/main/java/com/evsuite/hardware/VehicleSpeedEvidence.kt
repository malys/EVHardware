package com.evsuite.hardware

/**
 * Which speed conversion produced a recorded distance.
 *
 * A trip distance is the integral of speed, so it inherits whatever the speed conversion was
 * at the time — and that conversion has already been wrong once. Every trip recorded before
 * [KMH_DIRECT_V2] on a generation that reports km/h carries a distance 3.6 times too long, and
 * nothing in the stored number says so: 8.46 km for a 2.4 km route reads exactly like 8.46 km
 * for an 8.46 km route.
 *
 * [BatteryPowerEvidence] exists for the same reason on the energy side. This is its
 * counterpart for distance, so a model can refuse a trip whose distance it cannot vouch for
 * instead of quietly training on it.
 */
data class VehicleSpeedEvidence(
    val firmware: FirmwareInfo.Gen,
    val conversionVersion: Int,
) {
    init {
        require(firmware != FirmwareInfo.Gen.UNKNOWN) { "speed evidence names a known firmware" }
        require(conversionVersion > 0) { "speed conversion version is positive" }
    }

    /**
     * True when this distance was produced by the conversion currently believed correct.
     *
     * The generation is a parameter rather than a read of [FirmwareInfo.getGeneration] so the
     * check can be exercised off a vehicle. A correctness gate that only runs on a car is a
     * gate nobody has tested.
     */
    fun matchesCurrent(generation: FirmwareInfo.Gen = FirmwareInfo.getGeneration()): Boolean =
        conversionVersion == CURRENT && firmware == generation

    companion object {
        /** `PERF_VEHICLE_SPEED` read as m/s and multiplied by 3.6, as AAOS specifies. */
        const val MPS_TIMES_3_6_V1 = 1

        /** The property read as the km/h it already carries, per generation evidence. */
        const val KMH_DIRECT_V2 = 2

        /** What a distance recorded now is produced by. */
        const val CURRENT = KMH_DIRECT_V2

        /**
         * The evidence for a distance recorded right now, or null on an unknown firmware —
         * where no claim about the conversion can honestly be made.
         */
        fun current(): VehicleSpeedEvidence? =
            FirmwareInfo.getGeneration()
                .takeIf { it != FirmwareInfo.Gen.UNKNOWN }
                ?.let { VehicleSpeedEvidence(it, CURRENT) }
    }
}
