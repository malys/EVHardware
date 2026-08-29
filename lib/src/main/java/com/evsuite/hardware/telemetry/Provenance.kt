package com.evsuite.hardware.telemetry

/**
 * What a number on an energy screen actually is.
 *
 * A dashboard that shows a vehicle's state of charge next to a model's opinion of the
 * remaining range is showing two different kinds of claim in the same typeface. The MG4
 * publishes no per-consumer energy counters, so anything past traction totals — how much
 * the climate system took, what a slower motorway speed would have saved, the state of
 * charge on arrival — can only ever be inferred. Those figures are worth showing, and they
 * are not measurements.
 *
 * The distinction is a type rather than a convention because conventions are kept by
 * whoever wrote the line last. A screen renders a [Provenanced] value, and the class it
 * carries decides how it is drawn.
 */
enum class Provenance {
    /** Read from the vehicle, with the VHAL standing behind it (see `CarPropertyEvidence`). */
    MEASURED,

    /** Arithmetic on measured values only — kWh/100 km from power and speed, and nothing else. */
    DERIVED,

    /** A model's output. Always carries an uncertainty band; never rendered like a measurement. */
    ESTIMATED,

    /** No value, and a reason why. Rendered as an em dash, never as zero. */
    UNAVAILABLE,
}

/** Why a value is missing. The screen says which of these it is instead of only "—". */
enum class UnavailableReason {
    /** The firmware generation does not offer this signal at all. */
    UNSUPPORTED_FIRMWARE,

    /** A read path exists, but no on-vehicle evidence confirms its unit, sign or cadence yet. */
    UNVALIDATED_FIRMWARE,

    /** Declared by the VHAL and not published right now: an unavailable status, or a null read. */
    SIGNAL_ABSENT,

    /** A derived or estimated value whose inputs have not accumulated enough samples. */
    INSUFFICIENT_SAMPLES,

    /** A speed-normalised value is undefined or unstable below its documented speed floor. */
    SPEED_TOO_LOW,

    /** An estimator with no fit to predict from. */
    MODEL_NOT_TRAINED,
}

/**
 * A value, what kind of claim it is, and — for an estimate — how wrong it might be.
 *
 * Construction is validated rather than trusted: an [Provenance.ESTIMATED] value cannot be
 * built without a band, and an [Provenance.UNAVAILABLE] one cannot carry a number. That is
 * the whole point of the class. The constructor enforces those rules, so it is safe to call
 * directly, but the factories below read better at the call site and pick the right reason
 * when a signal turns out to be missing.
 *
 * @param uncertainty half-width of the band, in the value's own unit: 14.2 ± 1.6 kWh/100 km.
 */
data class Provenanced<T : Any>(
    val value: T?,
    val provenance: Provenance,
    val uncertainty: Double? = null,
    val reason: UnavailableReason? = null,
) {
    init {
        when (provenance) {
            Provenance.UNAVAILABLE -> {
                require(value == null) { "an unavailable value carries no number" }
                require(reason != null) { "an unavailable value must say why" }
                require(uncertainty == null) { "an unavailable value has no band" }
            }
            Provenance.ESTIMATED -> {
                require(value != null) { "an estimate with no value is unavailable instead" }
                require(uncertainty != null) { "an estimate must carry its uncertainty band" }
                require(uncertainty.isFinite() && uncertainty >= 0.0) {
                    "an uncertainty band is finite and non-negative, was $uncertainty"
                }
                require(reason == null) { "an available value has no unavailability reason" }
            }
            Provenance.MEASURED, Provenance.DERIVED -> {
                require(value != null) { "a missing reading is unavailable instead" }
                require(uncertainty == null) {
                    "only an estimate carries a band; the vehicle publishes no error bars"
                }
                require(reason == null) { "an available value has no unavailability reason" }
            }
        }
    }

    val isAvailable: Boolean get() = provenance != Provenance.UNAVAILABLE

    /** The band's lower and upper edge, or null when there is no band. */
    val bandLow: Double? get() = boundsOrNull()?.first
    val bandHigh: Double? get() = boundsOrNull()?.second

    private fun boundsOrNull(): Pair<Double, Double>? {
        val centre = (value as? Number)?.toDouble() ?: return null
        val half = uncertainty ?: return null
        return centre - half to centre + half
    }

    companion object {
        /** A vehicle reading. A null becomes unavailable for [absent], never a zero. */
        fun <T : Any> measured(
            value: T?,
            absent: UnavailableReason = UnavailableReason.SIGNAL_ABSENT,
        ): Provenanced<T> =
            if (value == null) unavailable(absent)
            else Provenanced(value, Provenance.MEASURED)

        /** Arithmetic on measured inputs. Prefer the [derive] overloads, which propagate gaps. */
        fun <T : Any> derived(
            value: T?,
            absent: UnavailableReason = UnavailableReason.INSUFFICIENT_SAMPLES,
        ): Provenanced<T> =
            if (value == null) unavailable(absent)
            else Provenanced(value, Provenance.DERIVED)

        /** A model output. The band is not optional. */
        fun <T : Any> estimated(
            value: T?,
            uncertainty: Double,
            absent: UnavailableReason = UnavailableReason.MODEL_NOT_TRAINED,
        ): Provenanced<T> =
            if (value == null) unavailable(absent)
            else Provenanced(value, Provenance.ESTIMATED, uncertainty = uncertainty)

        fun <T : Any> unavailable(reason: UnavailableReason): Provenanced<T> =
            Provenanced(null, Provenance.UNAVAILABLE, reason = reason)

        /**
         * Derives from one measured input, carrying its gap forward.
         *
         * A derivation over a missing input is not a smaller number, it is no number: the
         * consumption of a car whose speed is unreadable is unknown, not zero. The reason
         * travels with the gap so the screen can still explain itself.
         */
        fun <A : Any, R : Any> derive(
            a: Provenanced<A>,
            transform: (A) -> R?,
        ): Provenanced<R> {
            requireNotEstimated(a)
            val value = a.value ?: return unavailable(a.reasonOrAbsent())
            return derived(transform(value))
        }

        /** Derives from two inputs; the first unavailable one decides the reason. */
        fun <A : Any, B : Any, R : Any> derive(
            a: Provenanced<A>,
            b: Provenanced<B>,
            transform: (A, B) -> R?,
        ): Provenanced<R> {
            requireNotEstimated(a)
            requireNotEstimated(b)
            val first = a.value ?: return unavailable(a.reasonOrAbsent())
            val second = b.value ?: return unavailable(b.reasonOrAbsent())
            return derived(transform(first, second))
        }

        /**
         * A derivation whose input is itself an estimate is an estimate, and its band has to
         * be computed by whoever knows the model — not silently dropped here.
         */
        private fun requireNotEstimated(input: Provenanced<*>) {
            require(input.provenance != Provenance.ESTIMATED) {
                "deriving from an estimate loses its band; propagate the band explicitly"
            }
        }

        private fun Provenanced<*>.reasonOrAbsent(): UnavailableReason =
            reason ?: UnavailableReason.SIGNAL_ABSENT
    }
}
