package com.evsuite.hardware.telemetry

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Which way this app's own integral moved while charge was entering the pack.
 *
 * [EnergySnapshot.batteryPowerKw] declares positive as energy leaving the battery, so an
 * integral that **falls** during a charge is the declared convention holding. One that rises is
 * the same convention inverted, and it matters well beyond a sign on a dashboard: the state of
 * health estimator reads `PACK_PAIR_INTEGRAL` windows as energy that *left* the pack, so an
 * inverted source would hand it discharge windows with the sign of a charge and the estimator
 * would refuse every one of them.
 *
 * Nothing here corrects anything. RI-002 asks what the car actually does and this reports it.
 */
enum class ChargePackSign {
    /** The integral fell, which is the convention [EnergySnapshot.batteryPowerKw] declares. */
    ENERGY_FELL,

    /** The integral rose, so the property signs a charge the other way round. */
    ENERGY_ROSE,

    /** Readable and level: the pack pair said nothing moved while the gauge said it did. */
    FLAT,

    /** No charge was watched closely enough, or no pack reading was integrated at all. */
    UNREADABLE,

    /** Two charges answered differently, which settles nothing and must not be averaged. */
    CONTRADICTORY,
}

/**
 * What the car's own kWh counters did across a charge.
 *
 * `SaicCharging` transactions 75 and 77 are documented as *since the last charge*, so the
 * expected behaviour is [RESET] — and "expected" is the whole reason to measure it. A counter
 * that resets is what lets [StateOfHealthEstimator] tell one discharge window from the next; a
 * counter that never moves is a source that will never produce a window at all.
 */
enum class ChargeCounterBehaviour {
    /** A counter went backwards: the car cleared it, which is what a charge is said to do. */
    RESET,

    /**
     * A counter rose during the charge, which "since the last charge" does not predict.
     */
    ROSE,

    /** Read at both ends and unchanged. */
    STATIC,

    /** Never published on either end of any charge. */
    ABSENT,
}

/**
 * One charge session measured in energy rather than in points of charge.
 *
 * [BatteryExposure] already segments the ledger into sessions and says how fast each one moved
 * the gauge. This adds the half nothing reads today: how much energy went in, which way the
 * pack pair signed it, and what the car's own counters did while it happened.
 */
data class ChargeEnergy(
    val session: BatteryChargeSession,
    /**
     * Whether this app was sampling throughout.
     *
     * The ledger earns an entry every quarter of an hour whatever else happens, so a longer
     * silence than that is the app having been down — a head unit asleep on a charger overnight
     * is the ordinary case, not an edge one. An unwatched charge still gained the charge it
     * gained; what it cannot carry is a power, because the integral stopped moving while nobody
     * was integrating.
     */
    val watched: Boolean,
    val maxEntryGapMs: Long,
    /** Signed, as the integral moved. Negative is the declared convention; see [ChargePackSign]. */
    val packDeltaKwh: Double?,
    /** Magnitude over the wall clock, and only where the whole session was watched. */
    val meanPowerKw: Double?,
    val counterBehaviour: ChargeCounterBehaviour,
    val consumedDeltaKwh: Double?,
    val regeneratedDeltaKwh: Double?,
    /**
     * Odometer moved, when the car published one at both ends.
     *
     * A rise in charge is how a charge is detected here, and a long descent raises the charge
     * too. Distance is what tells the two apart, so a moving session is regeneration and is
     * never offered to the driver as "your last charge".
     */
    val movedKm: Double?,
    /** Every distinct value the charging status held, in the order the ledger saw them. */
    val chargingStatuses: List<Int>,
    /**
     * Pack power between each pair of watched ledger entries, which the recorder writes once
     * per point of charge: this is the charge curve, one step per point. Empty when unwatched.
     */
    val steps: List<ChargePowerStep> = emptyList(),
    /** Outside temperature over the session, named as such: the pack's own is unpublished. */
    val minOutsideTempCelsius: Double? = null,
    val maxOutsideTempCelsius: Double? = null,
) {
    /** The fastest watched step, measured; null when no step was watched. */
    val peakPowerKw: Double? get() = steps.maxOfOrNull { it.powerKw }

    /**
     * Whether this rise was a charge rather than regeneration.
     *
     * Three pieces of evidence, any one of them enough:
     * - the odometer stood still;
     * - the car reported a non-zero charging status during the rise (every drive recorded so far
     *   read zero throughout, 1 473 samples on SWI68);
     * - the rise is too large to be regeneration **and** nobody watched the car move during it.
     *   A head unit asleep on the charger is the ordinary overnight case: the ledger then holds
     *   the evening entry and the morning one, the odometer may have moved between them because
     *   the drive home was never recorded either, and requiring it to stand still is what made
     *   a whole night's charge read as a descent and vanish from the battery page.
     */
    val plugged: Boolean
        get() {
            val moved = movedKm
            if (moved != null && moved <= STATIONARY_TOLERANCE_KM) return true
            if (chargingStatuses.any { it != 0 }) return true
            return session.gainedPercent >= UNAMBIGUOUS_CHARGE_PERCENT && (moved == null || !watched)
        }

    val sign: ChargePackSign
        get() {
            val delta = packDeltaKwh
            if (!watched || delta == null) return ChargePackSign.UNREADABLE
            return when {
                delta <= -ChargeEnergyAnalyzer.MIN_ENERGY_KWH -> ChargePackSign.ENERGY_FELL
                delta >= ChargeEnergyAnalyzer.MIN_ENERGY_KWH -> ChargePackSign.ENERGY_ROSE
                else -> ChargePackSign.FLAT
            }
        }

    companion object {
        /** The odometer reads in whole kilometres, so anything under one is standing still. */
        const val STATIONARY_TOLERANCE_KM = 0.5

        /**
         * A rise no descent produces. Regeneration on the steepest ordinary pass returns a few
         * points; ten points of a 61,7 kWh pack is over 6 kWh of potential energy recovered.
         */
        const val UNAMBIGUOUS_CHARGE_PERCENT = 10.0
    }
}

/** One watched step of a charge: from one ledger entry to the next, usually one point. */
data class ChargePowerStep(
    val fromSocPercent: Double,
    val toSocPercent: Double,
    val durationMs: Long,
    /** Magnitude of the pack-pair integral's move over the step's wall clock. */
    val powerKw: Double,
) {
    val midSocPercent: Double get() = (fromSocPercent + toSocPercent) / 2.0
}

/** Time-weighted mean power over every watched step whose midpoint fell in one charge band. */
data class SocBandPower(
    val fromPercent: Int,
    val toPercent: Int,
    val meanPowerKw: Double,
    val hours: Double,
)

/** Every charge the ledger holds, and the two questions the set of them settles. */
data class ChargeEnergyReport(
    val charges: List<ChargeEnergy>,
    val packSign: ChargePackSign,
    val counterBehaviour: ChargeCounterBehaviour,
    val chargingStatuses: List<Int>,
) {
    /** The most recent charge that stood still, which is the one a driver means by "my charge". */
    val lastPluggedCharge: ChargeEnergy? get() = charges.lastOrNull { it.plugged }

    /** Every charge that was a charge, oldest first. */
    val pluggedCharges: List<ChargeEnergy> get() = charges.filter { it.plugged }

    /**
     * The charge curve across every watched charge: mean power per band of [bandPercent]
     * points of charge. This is where a taper shows — the pack accepting less as it fills —
     * and it is measured, one step per point, never modelled.
     */
    fun powerBySocBand(bandPercent: Int = SOC_BAND_PERCENT): List<SocBandPower> =
        pluggedCharges.flatMap { it.steps }
            .groupBy { (it.midSocPercent / bandPercent).toInt().coerceIn(0, 100 / bandPercent - 1) }
            .toSortedMap()
            .map { (band, steps) ->
                val ms = steps.sumOf { it.durationMs }.toDouble()
                SocBandPower(
                    fromPercent = band * bandPercent,
                    toPercent = (band + 1) * bandPercent,
                    meanPowerKw = steps.sumOf { it.powerKw * it.durationMs } / ms,
                    hours = ms / 3_600_000.0,
                )
            }

    val watchedCount: Int get() = charges.count { it.watched }

    /**
     * The report as lines, because the answer belongs in the diagnostic bundle rather than
     * only on a screen. Same reason [com.evsuite.hardware.telemetry.model.SocConsumptionFitter]
     * describes itself: a ticket is settled by a bundle a driver brought back, not by a UI.
     */
    fun describe(): List<String> {
        if (charges.isEmpty()) return listOf("charges=0")
        val lines = ArrayList<String>()
        lines += "charges=${charges.size} watched=$watchedCount"
        lines += "pack_pair_sign=${packSign.name}"
        lines += "vehicle_counters=${counterBehaviour.name}"
        lines += "charging_status_values=" + chargingStatuses.joinToString(",").ifEmpty { "none" }
        charges.takeLast(MAX_DESCRIBED_CHARGES).forEach { charge ->
            lines += "charge started_epoch_ms=${charge.session.startedAtMs}" +
                " gained_percent=${format(charge.session.gainedPercent)}" +
                " hours=${format(charge.session.durationHours)}" +
                " watched=${charge.watched}" +
                " max_entry_gap_ms=${charge.maxEntryGapMs}" +
                " pack_delta_kwh=${format(charge.packDeltaKwh)}" +
                " mean_power_kw=${format(charge.meanPowerKw)}" +
                " consumed_delta_kwh=${format(charge.consumedDeltaKwh)}" +
                " regenerated_delta_kwh=${format(charge.regeneratedDeltaKwh)}" +
                " moved_km=${format(charge.movedKm)}" +
                " counters=${charge.counterBehaviour.name}" +
                " sign=${charge.sign.name}" +
                " plugged=${charge.plugged}" +
                " peak_power_kw=${format(charge.peakPowerKw)}" +
                " steps=${charge.steps.size}"
        }
        powerBySocBand().forEach {
            lines += "soc_band ${it.fromPercent}-${it.toPercent} mean_power_kw=${format(it.meanPowerKw)}" +
                " hours=${format(it.hours)}"
        }
        return lines
    }

    private fun format(value: Double?): String =
        if (value == null) "unavailable" else String.format(Locale.ROOT, "%.2f", value)

    companion object {
        /** A bundle is bounded; the newest charges are the ones a question is asked about. */
        const val MAX_DESCRIBED_CHARGES = 8

        const val SOC_BAND_PERCENT = 10
    }
}

/**
 * The energy side of a charge, read from the ledger that is already being written.
 *
 * Nothing new is sampled and no new vehicle call is made. [BatteryLedgerRecorder] has been
 * recording the charge gauge, the car's counters, the integrated pack energy and the odometer
 * since CP-070; [BatteryExposure] already cuts that stream into charge sessions. What was
 * missing is a reader: the pack's health is estimated from discharges alone, so the energy
 * *entering* the pack — and with it the two facts RI-002 has been blocked on — was recorded and
 * never looked at.
 *
 * **Sessions are not re-segmented here.** They arrive from [BatteryExposure], so the two
 * screens can never disagree about what counts as a charge.
 */
class ChargeEnergyAnalyzer(
    private val maxWatchedGapMs: Long = MAX_WATCHED_GAP_MS,
) {
    fun analyse(
        entries: List<BatteryLedgerEntry>,
        sessions: List<BatteryChargeSession>,
    ): ChargeEnergyReport {
        val ordered = entries.sortedBy { it.atMs }
        val charges = sessions.map { measure(ordered, it) }
        return ChargeEnergyReport(
            charges = charges,
            packSign = consensusSign(charges),
            counterBehaviour = mostInformative(charges.map { it.counterBehaviour }),
            chargingStatuses = charges.flatMap { it.chargingStatuses }.distinct().sorted(),
        )
    }

    private fun measure(
        ordered: List<BatteryLedgerEntry>,
        session: BatteryChargeSession,
    ): ChargeEnergy {
        val slice = ordered.filter { it.atMs in session.startedAtMs..session.endedAtMs }
        var gap = 0L
        for (index in 1 until slice.size) gap = max(gap, slice[index].atMs - slice[index - 1].atMs)
        val watched = slice.size >= 2 && gap <= maxWatchedGapMs
        val packDelta = span(slice.mapNotNull { it.packEnergyKwh })
        val consumed = span(slice.mapNotNull { it.vehicleConsumedKwh?.toDouble() })
        val regenerated = span(slice.mapNotNull { it.vehicleRegeneratedKwh?.toDouble() })
        val hours = session.durationHours
        val temps = slice.mapNotNull { it.outsideTempCelsius?.toDouble() }
        return ChargeEnergy(
            session = session,
            watched = watched,
            maxEntryGapMs = gap,
            packDeltaKwh = packDelta,
            meanPowerKw = if (watched && packDelta != null && hours > 0.0) {
                abs(packDelta) / hours
            } else {
                null
            },
            counterBehaviour = behaviourOf(consumed, regenerated),
            consumedDeltaKwh = consumed,
            regeneratedDeltaKwh = regenerated,
            movedKm = span(slice.mapNotNull { it.odometerKm?.toDouble() }),
            chargingStatuses = slice.mapNotNull { it.chargingStatus }.distinct(),
            steps = if (watched) steps(slice) else emptyList(),
            minOutsideTempCelsius = temps.minOrNull(),
            maxOutsideTempCelsius = temps.maxOrNull(),
        )
    }

    /** Adjacent pairs that both carry the integral; a pair with none says nothing and is dropped. */
    private fun steps(slice: List<BatteryLedgerEntry>): List<ChargePowerStep> =
        slice.zipWithNext().mapNotNull { (a, b) ->
            val from = a.packEnergyKwh ?: return@mapNotNull null
            val to = b.packEnergyKwh ?: return@mapNotNull null
            val ms = b.atMs - a.atMs
            if (ms <= 0L || !from.isFinite() || !to.isFinite()) return@mapNotNull null
            ChargePowerStep(
                fromSocPercent = a.socPercent.toDouble(),
                toSocPercent = b.socPercent.toDouble(),
                durationMs = ms,
                powerKw = abs(to - from) / (ms / 3_600_000.0),
            )
        }

    /** Last reading minus first, or null when fewer than two of them were published. */
    private fun span(values: List<Double>): Double? {
        val finite = values.filter { it.isFinite() }
        return if (finite.size < 2) null else finite.last() - finite.first()
    }

    private fun behaviourOf(consumed: Double?, regenerated: Double?): ChargeCounterBehaviour {
        val deltas = listOfNotNull(consumed, regenerated)
        if (deltas.isEmpty()) return ChargeCounterBehaviour.ABSENT
        if (deltas.any { it <= -COUNTER_EPSILON_KWH }) return ChargeCounterBehaviour.RESET
        if (deltas.any { it >= COUNTER_EPSILON_KWH }) return ChargeCounterBehaviour.ROSE
        return ChargeCounterBehaviour.STATIC
    }

    /**
     * One answer from several charges, or an admission that there is not one.
     *
     * Two charges that signed the pack pair differently is not a figure to average: it says the
     * property means something this app has not understood, and reporting either half of it as
     * the answer would settle RI-002 wrongly.
     */
    private fun consensusSign(charges: List<ChargeEnergy>): ChargePackSign {
        val signs = charges.map { it.sign }
        val decided = signs.filter {
            it == ChargePackSign.ENERGY_FELL || it == ChargePackSign.ENERGY_ROSE
        }.distinct()
        return when {
            decided.size > 1 -> ChargePackSign.CONTRADICTORY
            decided.size == 1 -> decided.first()
            signs.contains(ChargePackSign.FLAT) -> ChargePackSign.FLAT
            else -> ChargePackSign.UNREADABLE
        }
    }

    /**
     * A reset seen once is a fact about the car; a static reading elsewhere only says that
     * charge did not end where the car clears its counters. So the set is reported by its most
     * informative member rather than refused as a contradiction.
     */
    private fun mostInformative(behaviours: List<ChargeCounterBehaviour>): ChargeCounterBehaviour =
        INFORMATIVENESS.firstOrNull { behaviours.contains(it) } ?: ChargeCounterBehaviour.ABSENT

    companion object {
        /**
         * How long the ledger may go quiet inside a charge before it stops being watched.
         *
         * Twice [BatteryLedgerRecorder.QUIET_INTERVAL_MS]: the recorder earns an entry every
         * quarter of an hour on its own, so a longer silence is this app having been down rather
         * than a slow charge. The margin covers a sampler that stopped for one interval.
         */
        const val MAX_WATCHED_GAP_MS = 2 * BatteryLedgerRecorder.QUIET_INTERVAL_MS

        /**
         * Below this a charge says nothing about the sign.
         *
         * A watched charge of any length moves the integral by kilowatt-hours; half of one is
         * comfortably below anything real and comfortably above the drift of a pack pair read
         * at rest.
         */
        const val MIN_ENERGY_KWH = 0.5

        /** The car's counters step by a whole kilowatt-hour, so anything above noise is a move. */
        const val COUNTER_EPSILON_KWH = 0.05

        private val INFORMATIVENESS = listOf(
            ChargeCounterBehaviour.RESET,
            ChargeCounterBehaviour.ROSE,
            ChargeCounterBehaviour.STATIC,
            ChargeCounterBehaviour.ABSENT,
        )
    }
}
