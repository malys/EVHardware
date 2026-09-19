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
) {
    /** Stationary as far as the odometer is concerned. A missing odometer is not proof. */
    val plugged: Boolean get() = movedKm != null && movedKm <= STATIONARY_TOLERANCE_KM

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
    }
}

/** Every charge the ledger holds, and the two questions the set of them settles. */
data class ChargeEnergyReport(
    val charges: List<ChargeEnergy>,
    val packSign: ChargePackSign,
    val counterBehaviour: ChargeCounterBehaviour,
    val chargingStatuses: List<Int>,
) {
    /** The most recent charge that stood still, which is the one a driver means by "my charge". */
    val lastPluggedCharge: ChargeEnergy? get() = charges.lastOrNull { it.plugged }

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
                " sign=${charge.sign.name}"
        }
        return lines
    }

    private fun format(value: Double?): String =
        if (value == null) "unavailable" else String.format(Locale.ROOT, "%.2f", value)

    companion object {
        /** A bundle is bounded; the newest charges are the ones a question is asked about. */
        const val MAX_DESCRIBED_CHARGES = 8
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
