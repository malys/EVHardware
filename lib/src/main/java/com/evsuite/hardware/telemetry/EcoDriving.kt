package com.evsuite.hardware.telemetry

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.model.SocConsumptionFitter
import com.evsuite.hardware.telemetry.model.SocConsumptionModel
import java.util.Locale

/** How this drive compares with what the driver's own fitted history expected of it. */
enum class EcoBand {
    /** Below the expectation by more than the fit's own band. */
    BETTER,

    /** Inside the band. A fit that does not know much says this often, and should. */
    TYPICAL,

    /** Above the expectation by more than the band. */
    WORSE,
}

/**
 * The one thing worth saying, chosen from the two the recorded signals can measure.
 *
 * The climate system is deliberately not here. Cabin state is passive state — an on/off flag and
 * a fan level — and turning either into kilowatts is exactly what `AGENTS.md` forbids. The cabin's
 * share is a fitted number that only exists after the trip, where `EnergyAttribution` produces it.
 */
enum class EcoLever {
    /**
     * Drag rises with the square of speed, and the fitted model carries that term, so the saving
     * is arithmetic on a fit this car trained rather than a rule of thumb.
     */
    CRUISE_SPEED,

    /**
     * Time spent accelerating hard. Measured from the speed channel alone, which matters: on a
     * firmware where battery power is derived rather than measured this lever is still a
     * measurement.
     */
    STEADINESS,
}

/**
 * One piece of advice, with the number that earned it.
 *
 * Exactly one of the two number pairs is populated, decided by [lever]. There is no common
 * currency between "14 % less at 110" and "a fifth of this drive was hard acceleration", and
 * inventing one to rank them would be inventing the more important of the two figures.
 */
data class EcoAdvice(
    val lever: EcoLever,
    /** [EcoLever.CRUISE_SPEED]: what the fit says the slower speed costs, as a percentage saved. */
    val savingPercent: Double? = null,
    val fromSpeedKmh: Double? = null,
    val toSpeedKmh: Double? = null,
    /** [EcoLever.STEADINESS]: the share of moving time spent above the acceleration threshold. */
    val harshSharePercent: Double? = null,
)

/**
 * What this drive is costing, against what this driver's own history expected it to cost.
 *
 * Everything here is in **percent of charge per 100 km**, never kilowatt-hours, and that is a
 * decision rather than a convenience: `EnergySnapshot.batteryPowerKw`'s sign convention is still
 * unvalidated (RI-002, CP-073), so every kWh figure in this app inherits an open question. Charge
 * and speed are the two signals CP-003 actually proved on this car, and a verdict the driver is
 * asked to act on is built on those.
 */
data class EcoVerdict(
    /**
     * The verdict, and — as its band — the width of the model's own `TYPICAL` zone in percent
     * per 100 km: how far [observedPercentPer100Km] would have had to move to say something
     * else. A fit that knows little carries a wide one and says `TYPICAL` for almost any drive,
     * which is the honest answer from a fit that knows little.
     */
    val band: Provenanced<EcoBand>,
    val observedPercentPer100Km: Double? = null,
    val expectedPercentPer100Km: Double? = null,
    /** Signed: negative is below the expectation, which is the good direction. */
    val deltaPercent: Double? = null,
    val meanSpeedKmh: Double? = null,
    val advice: EcoAdvice? = null,
) {
    /** For the diagnostic bundle: the verdict, its inputs, and any refusal, on one line each. */
    fun describe(): List<String> {
        val lines = ArrayList<String>()
        lines += "band=" + (band.value?.name ?: "unavailable(${band.reason?.name})")
        lines += "observed_percent_per_100km=${format(observedPercentPer100Km)}"
        lines += "expected_percent_per_100km=${format(expectedPercentPer100Km)}"
        lines += "delta_percent=${format(deltaPercent)}"
        lines += "mean_speed_kmh=${format(meanSpeedKmh)}"
        val advice = advice
        lines += if (advice == null) {
            "advice=none"
        } else {
            "advice=${advice.lever.name}" +
                " saving_percent=${format(advice.savingPercent)}" +
                " from_speed_kmh=${format(advice.fromSpeedKmh)}" +
                " to_speed_kmh=${format(advice.toSpeedKmh)}" +
                " harsh_share_percent=${format(advice.harshSharePercent)}"
        }
        return lines
    }

    private fun format(value: Double?): String =
        if (value == null) "unavailable" else String.format(Locale.ROOT, "%.2f", value)
}

/**
 * The eco verdict and its one piece of advice, live on the dashboard and replayed after the trip.
 *
 * **One analyzer, two callers.** The dashboard feeds it the 1 Hz speed channel as it arrives; the
 * trip review replays a stored [TripSample] track through the same instance. That is the whole
 * reason it lives here rather than in the app: the number the driver saw at 90 km/h and the number
 * they read afterwards come out of the same code, and cannot disagree.
 *
 * **It costs nothing to run.** No new sampling, no vehicle call, no allocation per frame beyond one
 * bounded deque of speeds. The verdict is arithmetic over a trip summary the recorder already keeps
 * and a model CP-052 already trained.
 *
 * **It refuses rather than guesses.** No fit, a speed outside the envelope the fit was trained in,
 * too little distance to divide by, too small a charge drop to read against a gauge that moves in
 * whole percent — each of those is an [UnavailableReason] the rest of the app already renders, not
 * a number with a shrug attached.
 */
class EcoDrivingMonitor(
    private val windowMs: Long = WINDOW_MS,
    private val harshAccelerationMs2: Double = HARSH_ACCELERATION_MS2,
    private val maxSamples: Int = MAX_WINDOW_SAMPLES,
) {
    private data class Speed(val atMs: Long, val kmh: Double)

    private val window = ArrayDeque<Speed>()

    init {
        require(windowMs > 0L) { "the lever window must be positive" }
        require(harshAccelerationMs2 > 0.0) { "the acceleration threshold must be positive" }
        require(maxSamples > 1) { "a window of one sample measures nothing" }
    }

    /** Feed one frame. A null or negative speed is a gap in the window, never a zero. */
    fun add(atMs: Long, speedKmh: Float?) {
        if (window.lastOrNull()?.let { atMs <= it.atMs } == true) reset()
        val speed = speedKmh?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
        if (speed != null) window.addLast(Speed(atMs, speed))
        while (window.size > maxSamples) window.removeFirst()
        while (window.size > 1 && atMs - window.first().atMs > windowMs) window.removeFirst()
    }

    fun reset() = window.clear()

    /** Replays a stored track, so a finished trip is judged by the same code that judged it live. */
    fun replay(samples: List<TripSample>): EcoDrivingMonitor {
        reset()
        samples.forEach { add(it.atMs, it.speedKmh) }
        return this
    }

    /**
     * The share of moving time spent accelerating hard, whatever the cruise lever had to say.
     *
     * Live, the two levers compete for one line and the cruise lever wins when it has a measured
     * saving. A trip review has no such contest: the motorway question is answered there by
     * `SpeedWhatIfCalculator` from the trip's own intervals, and this is the other half.
     */
    fun steadiness(): EcoAdvice? = steadinessAdvice()

    /**
     * @param trip the trip so far, live, or the finished one. Its charge drop and its distance are
     *   what the verdict is measured from.
     * @param outsideTempCelsius the fit's second input; without it the model cannot be asked.
     */
    fun verdict(
        model: SocConsumptionModel?,
        trip: EnergyTripSummary?,
        outsideTempCelsius: Float?,
        generation: FirmwareInfo.Gen = FirmwareInfo.getGeneration(),
    ): EcoVerdict {
        val advice = advice(model, outsideTempCelsius)
        if (model == null) return refuse(UnavailableReason.MODEL_NOT_TRAINED, advice)

        // A distance the current speed conversion did not produce may carry a 3.6x error, and a
        // verdict built on it would be wrong by the same factor without saying so.
        val distanceKm = trip?.modellableDistanceKm(generation)
        val start = trip?.startSocPercent?.toDouble()
        val end = trip?.endSocPercent?.toDouble()
        val hours = trip?.durationMs?.let { it / MILLIS_PER_HOUR }
        if (distanceKm == null || distanceKm < SocConsumptionFitter.MIN_SEGMENT_KM ||
            start == null || end == null || hours == null || hours <= 0.0
        ) {
            return refuse(UnavailableReason.INSUFFICIENT_SAMPLES, advice)
        }
        // The gauge moves in whole percent. Below the fitter's own floor the quantisation is
        // larger than the thing being measured.
        val drop = start - end
        if (drop < SocConsumptionFitter.MIN_SOC_DROP_PERCENT) {
            return refuse(UnavailableReason.INSUFFICIENT_SAMPLES, advice)
        }

        val meanSpeedKmh = distanceKm / hours
        val temp = outsideTempCelsius?.toDouble()
            ?: return refuse(UnavailableReason.SIGNAL_ABSENT, advice, meanSpeedKmh)
        val expected = model.predict(meanSpeedKmh, temp)
        val expectedValue = expected.value
            ?: return refuse(
                expected.reason ?: UnavailableReason.MODEL_NOT_TRAINED, advice, meanSpeedKmh
            )

        val observed = drop / distanceKm * PER_100_KM
        // Compared against the fit's *own* band rather than a threshold invented here: a fit with
        // a wide residual says TYPICAL more often, which is the correct behaviour for a fit that
        // does not know much yet.
        val band = expected.uncertainty ?: 0.0
        val verdict = when {
            observed < expectedValue - band -> EcoBand.BETTER
            observed > expectedValue + band -> EcoBand.WORSE
            else -> EcoBand.TYPICAL
        }
        return EcoVerdict(
            band = Provenanced.estimated(verdict, band),
            observedPercentPer100Km = observed,
            expectedPercentPer100Km = expectedValue,
            deltaPercent = (observed - expectedValue) / expectedValue * PERCENT,
            meanSpeedKmh = meanSpeedKmh,
            advice = advice,
        )
    }

    private fun refuse(
        reason: UnavailableReason,
        advice: EcoAdvice?,
        meanSpeedKmh: Double? = null,
    ) = EcoVerdict(
        band = Provenanced.unavailable(reason),
        meanSpeedKmh = meanSpeedKmh,
        advice = advice,
    )

    /**
     * At most one lever, and by precedence rather than by a ranking that would have to invent a
     * comparison. The two are near-disjoint in practice anyway: cruise speed cannot fire below
     * [CRUISE_MIN_KMH], and a drive spent above it is not a drive spent accelerating.
     */
    private fun advice(model: SocConsumptionModel?, outsideTempCelsius: Float?): EcoAdvice? =
        cruiseAdvice(model, outsideTempCelsius) ?: steadinessAdvice()

    private fun cruiseAdvice(
        model: SocConsumptionModel?,
        outsideTempCelsius: Float?,
    ): EcoAdvice? {
        if (model == null) return null
        val temp = outsideTempCelsius?.toDouble() ?: return null
        val from = windowMeanSpeedKmh() ?: return null
        if (from < CRUISE_MIN_KMH) return null
        val to = from - CRUISE_STEP_KMH
        val at = model.predict(from, temp).value ?: return null
        val slower = model.predict(to, temp).value ?: return null
        val saving = (at - slower) / at * PERCENT
        if (!saving.isFinite() || saving < MIN_ADVICE_SAVING_PERCENT) return null
        return EcoAdvice(
            lever = EcoLever.CRUISE_SPEED,
            savingPercent = saving,
            fromSpeedKmh = from,
            toSpeedKmh = to,
        )
    }

    private fun steadinessAdvice(): EcoAdvice? {
        var movingMs = 0L
        var harshMs = 0L
        window.zipWithNext { previous, current ->
            val deltaMs = current.atMs - previous.atMs
            // Beyond the gap this app allows itself to integrate over, the missing minutes are
            // unknown motion. Reading them as one enormous step would invent an acceleration
            // out of the app having been asleep.
            if (deltaMs !in 1..MAX_GAP_MS) return@zipWithNext
            if (current.kmh <= 0.0 && previous.kmh <= 0.0) return@zipWithNext
            movingMs += deltaMs
            val acceleration = (current.kmh - previous.kmh) / KMH_PER_MS2 / (deltaMs / 1000.0)
            if (acceleration >= harshAccelerationMs2) harshMs += deltaMs
        }
        if (movingMs < MIN_LEVER_WINDOW_MS) return null
        val share = harshMs.toDouble() / movingMs * PERCENT
        if (share < MIN_HARSH_SHARE_PERCENT) return null
        return EcoAdvice(lever = EcoLever.STEADINESS, harshSharePercent = share)
    }

    private fun windowMeanSpeedKmh(): Double? {
        if (window.size < 2) return null
        var weightedMs = 0L
        var distanceKmh = 0.0
        window.zipWithNext { previous, current ->
            val deltaMs = current.atMs - previous.atMs
            if (deltaMs !in 1..MAX_GAP_MS) return@zipWithNext
            weightedMs += deltaMs
            distanceKmh += (previous.kmh + current.kmh) / 2.0 * deltaMs
        }
        if (weightedMs < MIN_LEVER_WINDOW_MS) return null
        return distanceKmh / weightedMs
    }

    companion object {
        /**
         * The whole of a finished drive, rather than the last three minutes of a live one.
         *
         * A review that judged steadiness on the final window would grade the driver on their
         * arrival, and the bound that keeps the live window cheap has no purpose over a track
         * that is already on the disk.
         */
        fun forReplay() = EcoDrivingMonitor(windowMs = Long.MAX_VALUE, maxSamples = Int.MAX_VALUE)

        /** Long enough that a single roundabout is not a driving style. */
        const val WINDOW_MS = 180_000L

        /**
         * Brisk rather than emergency. Eco-driving practice puts the useful line around here, and
         * the number is a calibration knob rather than physics: a heavier car, a different tyre or
         * a driver who simply disagrees moves it, and nothing else in this class assumes its value.
         */
        const val HARSH_ACCELERATION_MS2 = 1.5

        /** Below this the levers are measuring a traffic light, not a drive. */
        const val MIN_LEVER_WINDOW_MS = 60_000L

        /** Under this share, saying anything would be nagging about noise. */
        const val MIN_HARSH_SHARE_PERCENT = 12.0

        /** Motorway, where the square term is worth more than everything else on this list. */
        const val CRUISE_MIN_KMH = 95.0

        /** The step the advice proposes: large enough to matter, small enough to be plausible. */
        const val CRUISE_STEP_KMH = 10.0

        /** A saving smaller than this is not worth a line the driver has to read. */
        const val MIN_ADVICE_SAVING_PERCENT = 3.0

        /** The app's own integration rule: past five seconds, the motion in between is unknown. */
        const val MAX_GAP_MS = 5_000L

        /** Three minutes at 1 Hz, with room for a faster caller. */
        const val MAX_WINDOW_SAMPLES = 1_024

        private const val MILLIS_PER_HOUR = 3_600_000.0
        private const val PER_100_KM = 100.0
        private const val PERCENT = 100.0

        /** 1 m/s² is 3.6 km/h per second. */
        private const val KMH_PER_MS2 = 3.6
    }
}
