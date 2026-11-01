package com.evsuite.hardware.telemetry.model

import kotlin.math.abs

/**
 * The normal-equations fit both consumption models are built on.
 *
 * `constant + a × speed² + b × |outside − comfort|` is the shape of both models, with
 * `c ÷ speed` — the auxiliary load, which costs a fixed power whatever the speed and therefore
 * more per kilometre the slower the car goes. The arithmetic has no opinion
 * about units or about which terms are in play; it lives here so there is one of it: a second
 * copy would drift from this one on the day someone fixes a pivot and only remembers one file.
 *
 * O(1) working memory, O(n) in samples. Call from a worker thread.
 */
internal class LeastSquares(private val terms: List<Term>) {
    enum class Term { CONSTANT, SPEED_SQUARED, INVERSE_SPEED, TEMPERATURE }

    private val size = terms.size
    private val matrix = Array(size) { DoubleArray(size) }
    private val vector = DoubleArray(size)

    init {
        require(terms.isNotEmpty() && terms.first() == Term.CONSTANT) { "constant first" }
    }

    fun add(speedKmh: Double, outsideTempCelsius: Double, consumption: Double) {
        val values = DoubleArray(size) { value(terms[it], speedKmh, outsideTempCelsius) }
        for (row in 0 until size) {
            vector[row] += values[row] * consumption
            for (column in 0 until size) matrix[row][column] += values[row] * values[column]
        }
    }

    /**
     * The coefficients in [terms] order, or null when the points do not determine them.
     *
     * Fewer terms is not a lesser fit, it is a different claim: a driver who has only ever
     * recorded trips at one outside temperature has no evidence about temperature, and a
     * thermal coefficient invented from that would be read as knowledge.
     */
    fun solve(): DoubleArray? {
        val augmented = Array(size) { row ->
            DoubleArray(size + 1) { column -> if (column == size) vector[row] else matrix[row][column] }
        }
        for (column in 0 until size) {
            val pivot = (column until size).maxBy { abs(augmented[it][column]) }
            if (abs(augmented[pivot][column]) < MIN_PIVOT) return null
            val swap = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = swap
            val divisor = augmented[column][column]
            for (index in column..size) augmented[column][index] /= divisor
            for (row in 0 until size) {
                if (row == column) continue
                val factor = augmented[row][column]
                for (index in column..size) augmented[row][index] -= factor * augmented[column][index]
            }
        }
        return DoubleArray(size) { augmented[it][size] }
    }

    companion object {
        private const val MIN_PIVOT = 1e-9

        fun value(term: Term, speedKmh: Double, outsideTempCelsius: Double): Double = when (term) {
            Term.CONSTANT -> 1.0
            Term.SPEED_SQUARED -> speedKmh * speedKmh
            Term.INVERSE_SPEED -> 1.0 / speedKmh
            Term.TEMPERATURE -> abs(outsideTempCelsius - EnergyModel.COMFORT_TEMP_CELSIUS)
        }
    }
}
