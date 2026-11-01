package com.evsuite.hardware.telemetry.model

import com.evsuite.hardware.BatteryPowerEvidence
import com.evsuite.hardware.CarPropertyEvidence
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.telemetry.StoredTrip
import com.evsuite.hardware.telemetry.UnavailableReason
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

sealed interface EnergyModelTrainingResult {
    data class Ready(val model: EnergyModel) : EnergyModelTrainingResult
    data class Unavailable(val reason: UnavailableReason) : EnergyModelTrainingResult
}

/** Bounded O(n), O(1)-working-memory least-squares fit. Call from a worker thread. */
class EnergyModelTrainer(
    private val maxSamples: Int = MAX_TRAINING_SAMPLES,
    private val maxResidualRmse: Double = MAX_RESIDUAL_RMSE_KWH_PER_100_KM,
) {
    init {
        require(maxSamples >= MIN_TRAINING_SAMPLES)
        require(maxResidualRmse.isFinite() && maxResidualRmse > 0.0)
    }

    /** Production entry point: the empty pre-CP-003 catalogue makes training fail closed. */
    fun train(
        trips: List<StoredTrip>,
        firmware: FirmwareInfo.Gen,
    ): EnergyModelTrainingResult {
        if (firmware == FirmwareInfo.Gen.UNKNOWN) {
            return EnergyModelTrainingResult.Unavailable(UnavailableReason.UNSUPPORTED_FIRMWARE)
        }
        val evidence = CarPropertyEvidence.powerModelEvidence(firmware)
            ?: return EnergyModelTrainingResult.Unavailable(UnavailableReason.UNVALIDATED_FIRMWARE)
        return fit(trips, evidence)
    }

    internal fun fit(
        trips: List<StoredTrip>,
        evidence: BatteryPowerEvidence,
    ): EnergyModelTrainingResult = fitExplained(trips, evidence).first

    /**
     * One line for a diagnostic bundle, in [SocConsumptionFitter.describe]'s shape: the reason
     * code alone cannot say which of four gates refused, and the 2026-10-30 refusal could not be
     * replayed off the car because only trip totals left it.
     *
     * Diagnostic only: it runs the fit a second time, so call it from a probe, not a screen.
     */
    fun describe(trips: List<StoredTrip>, firmware: FirmwareInfo.Gen): String {
        if (firmware == FirmwareInfo.Gen.UNKNOWN) return "firmware=unknown"
        val evidence = CarPropertyEvidence.powerModelEvidence(firmware) ?: return "firmware=unvalidated"
        val used = trips.count { it.summary.batteryPowerEvidence == evidence && !it.samples.isNullOrEmpty() }
        return "used=$used/${trips.size} " + fitExplained(trips, evidence).second
    }

    /**
     * The fit's own training points, `[km/h, °C, kWh/100 km]`, so a refusal can be replayed off
     * the car as [SocConsumptionFitter.segments] lets the charge model's be. Diagnostic only.
     */
    fun windows(trips: List<StoredTrip>, firmware: FirmwareInfo.Gen): List<DoubleArray> {
        if (firmware == FirmwareInfo.Gen.UNKNOWN) return emptyList()
        val evidence = CarPropertyEvidence.powerModelEvidence(firmware) ?: return emptyList()
        val out = ArrayList<DoubleArray>()
        forEachUsable(trips, evidence) { speed, temp, consumption -> out.add(doubleArrayOf(speed, temp, consumption)) }
        return out
    }

    /** The fit, and the line [describe] prints for it. */
    private fun fitExplained(
        trips: List<StoredTrip>,
        evidence: BatteryPowerEvidence,
    ): Pair<EnergyModelTrainingResult, String> {
        var minSpeed = Double.POSITIVE_INFINITY
        var maxSpeed = Double.NEGATIVE_INFINITY
        var minTemp = Double.POSITIVE_INFINITY
        var maxTemp = Double.NEGATIVE_INFINITY
        val count = forEachUsable(trips, evidence) { speed, temp, _ ->
            minSpeed = minOf(minSpeed, speed)
            maxSpeed = maxOf(maxSpeed, speed)
            minTemp = minOf(minTemp, temp)
            maxTemp = maxOf(maxTemp, temp)
        }
        val gates = String.format(
            Locale.ROOT, "windows=%d/%d span=%.0f/%.0f km/h", count, MIN_TRAINING_SAMPLES,
            if (count > 0) maxSpeed - minSpeed else 0.0, MIN_SPEED_SPAN_KMH,
        )
        if (count < MIN_TRAINING_SAMPLES || maxSpeed - minSpeed < MIN_SPEED_SPAN_KMH) {
            return EnergyModelTrainingResult.Unavailable(UnavailableReason.INSUFFICIENT_SAMPLES) to gates
        }

        // As in SocConsumptionFitter: one kind of weather is no evidence about weather, so the
        // thermal term is not offered and the narrow envelope refuses the cold instead of guessing.
        // Every coefficient is physical, so the auxiliary and thermal terms are each tried in and
        // out and the least-error fit with no negative coefficient wins. Gross consumption counts
        // the energy of pulling away and not the regeneration that returns it, so town kilometres
        // cost more than open-road ones: without the ÷ speed term, the car's 2026-11-01 fit read
        // that as a negative aero and refused.
        val weather = maxTemp - minTemp >= MIN_TEMPERATURE_SPAN_CELSIUS
        val optional = listOfNotNull(
            LeastSquares.Term.INVERSE_SPEED,
            LeastSquares.Term.TEMPERATURE.takeIf { weather },
        )
        val candidates = optional.indices.fold(listOf(emptyList<LeastSquares.Term>())) { sets, at ->
            sets + sets.map { it + optional[at] }
        }.sortedByDescending { it.size }
        var best: Pair<DoubleArray, List<LeastSquares.Term>>? = null
        var bestError = Double.POSITIVE_INFINITY
        // Fullest first, so the refusal left is the base model's: the one a reader can act on.
        var refusal = "singular"
        for (extra in candidates) {
            val terms = BASE_TERMS + extra
            val equations = LeastSquares(terms)
            forEachUsable(trips, evidence) { speed, temp, consumption -> equations.add(speed, temp, consumption) }
            val coefficients = equations.solve()
            val refused = when {
                coefficients == null -> "singular"
                coefficients.any { !it.isFinite() } -> "nonfinite"
                coefficients[0] <= 0.0 -> "rolling"
                else -> terms.indices.drop(1).firstOrNull { coefficients[it] < 0.0 }?.let { name(terms[it]) }
            }
            if (refused != null) {
                refusal = refused
                continue
            }
            var squaredError = 0.0
            forEachUsable(trips, evidence) { speed, temp, consumption ->
                val error = consumption - predict(terms, checkNotNull(coefficients), speed, temp)
                squaredError += error * error
            }
            if (squaredError < bestError) {
                bestError = squaredError
                best = checkNotNull(coefficients) to terms
            }
        }
        val (coefficients, terms) = best ?: return EnergyModelTrainingResult.Unavailable(
            UnavailableReason.MODEL_NOT_TRAINED,
        ) to "$gates fit=refused($refusal)"
        fun coefficient(term: LeastSquares.Term) = terms.indexOf(term).let { if (it < 0) 0.0 else coefficients[it] }
        val rolling = coefficients[0]
        val aero = coefficient(LeastSquares.Term.SPEED_SQUARED)
        val aux = coefficient(LeastSquares.Term.INVERSE_SPEED)
        val thermal = coefficient(LeastSquares.Term.TEMPERATURE)
        val solved = String.format(
            Locale.ROOT, "%s features=%d rolling=%.2f aero=%.6f aux=%.1f thermal=%.3f",
            gates, terms.size, rolling, aero, aux, thermal,
        )

        val rmse = sqrt(bestError / count)
        val residual = String.format(Locale.ROOT, "rmse=%.2f/%.1f", rmse, maxResidualRmse)
        if (!rmse.isFinite() || rmse > maxResidualRmse) {
            return EnergyModelTrainingResult.Unavailable(UnavailableReason.MODEL_NOT_TRAINED) to
                "$solved fit=refused($residual)"
        }

        val model = EnergyModel(
            evidence = evidence,
            rollingKwhPer100Km = rolling,
            aeroKwhPer100KmPerSpeedSquared = aero,
            thermalKwhPer100KmPerDegree = thermal,
            auxKwhPer100KmTimesKmh = aux,
            residualRmseKwhPer100Km = rmse,
            sampleCount = count,
            envelope = EnergyModelEnvelope(minSpeed, maxSpeed, minTemp, maxTemp),
        )
        return EnergyModelTrainingResult.Ready(model) to "$solved fit=ready $residual"
    }

    /**
     * Calls [block] once per kilometre of driving, with its mean speed, mean temperature and
     * gross consumption, and returns how many kilometres it reported.
     *
     * A 5-second sample is not a consumption. The one taken while pulling away from a light
     * costs several times the one taken cruising, and a fit made per sample read that spread
     * as model error and refused town driving for good. Over a kilometre the accelerations
     * average into what the road actually cost, which is also what the attribution predicts.
     * Standing still is skipped without ending the window: it has no speed to be modelled at.
     * A hole in the track does end it, as in SocConsumptionFitter, so no window spans
     * kilometres nobody recorded.
     */
    private fun forEachUsable(
        trips: List<StoredTrip>,
        evidence: BatteryPowerEvidence,
        block: (speedKmh: Double, outsideTempCelsius: Double, consumption: Double) -> Unit,
    ): Int {
        var accepted = 0
        for (trip in trips) {
            if (trip.summary.batteryPowerEvidence != evidence) continue
            val samples = trip.samples.orEmpty()
            var km = 0.0
            var hours = 0.0
            var kwh = 0.0
            var tempSum = 0.0
            var tempCount = 0
            for (index in 1 until samples.size) {
                val previous = samples[index - 1]
                val current = samples[index]
                val durationMs = current.atMs - previous.atMs
                val speed = mean(previous.speedKmh, current.speedKmh)
                val previousPower = previous.batteryPowerKw?.toDouble()
                val currentPower = current.batteryPowerKw?.toDouble()
                val temp = mean(previous.outsideTempCelsius, current.outsideTempCelsius)
                if (speed == null || temp == null || previousPower == null ||
                    currentPower == null || !previousPower.isFinite() ||
                    !currentPower.isFinite() || durationMs !in 1..MAX_SAMPLE_GAP_MS
                ) {
                    km = 0.0
                    hours = 0.0
                    kwh = 0.0
                    tempSum = 0.0
                    tempCount = 0
                    continue
                }
                if (speed < MIN_SPEED_KMH) continue
                val intervalHours = durationMs / MILLIS_PER_HOUR
                km += speed * intervalHours
                hours += intervalHours
                // Gross, as the attribution measures it: regeneration is a separate ledger line.
                kwh += (max(0.0, previousPower) + max(0.0, currentPower)) / 2.0 * intervalHours
                tempSum += temp
                tempCount++
                if (km < WINDOW_KM) continue
                val meanSpeed = km / hours
                val meanTemp = tempSum / tempCount
                val consumption = kwh * 100.0 / km
                km = 0.0
                hours = 0.0
                kwh = 0.0
                tempSum = 0.0
                tempCount = 0
                if (meanSpeed !in MIN_SPEED_KMH..MAX_SPEED_KMH ||
                    meanTemp !in MIN_TEMP_CELSIUS..MAX_TEMP_CELSIUS ||
                    consumption !in 0.0..MAX_CONSUMPTION_KWH_PER_100_KM
                ) {
                    continue
                }
                block(meanSpeed, meanTemp, consumption)
                accepted++
                if (accepted == maxSamples) return accepted
            }
        }
        return accepted
    }

    private fun predict(terms: List<LeastSquares.Term>, coefficients: DoubleArray, speed: Double, temp: Double) =
        terms.indices.sumOf { coefficients[it] * LeastSquares.value(terms[it], speed, temp) }

    private fun name(term: LeastSquares.Term) = when (term) {
        LeastSquares.Term.CONSTANT -> "rolling"
        LeastSquares.Term.SPEED_SQUARED -> "aero"
        LeastSquares.Term.INVERSE_SPEED -> "aux"
        LeastSquares.Term.TEMPERATURE -> "thermal"
    }

    private fun mean(a: Float?, b: Float?): Double? {
        val first = a?.toDouble()?.takeIf { it.isFinite() } ?: return null
        val second = b?.toDouble()?.takeIf { it.isFinite() } ?: return null
        return (first + second) / 2.0
    }

    companion object {
        private val BASE_TERMS = listOf(LeastSquares.Term.CONSTANT, LeastSquares.Term.SPEED_SQUARED)
        /** Kilometre windows, not samples: a dozen, as SocConsumptionFitter asks segments. */
        const val MIN_TRAINING_SAMPLES = 12
        const val MAX_TRAINING_SAMPLES = 20_000
        const val WINDOW_KM = 1.0
        private const val MAX_SAMPLE_GAP_MS = 120_000L
        private const val MILLIS_PER_HOUR = 3_600_000.0
        const val MAX_RESIDUAL_RMSE_KWH_PER_100_KM = 12.0
        private const val MIN_SPEED_KMH = 10.0
        private const val MAX_SPEED_KMH = 180.0
        private const val MIN_TEMP_CELSIUS = -40.0
        private const val MAX_TEMP_CELSIUS = 60.0
        private const val MIN_SPEED_SPAN_KMH = 20.0
        private const val MIN_TEMPERATURE_SPAN_CELSIUS = 5.0
        private const val MAX_CONSUMPTION_KWH_PER_100_KM = 100.0
    }
}
