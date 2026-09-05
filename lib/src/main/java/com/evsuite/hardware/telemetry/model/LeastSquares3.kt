package com.evsuite.hardware.telemetry.model

import kotlin.math.abs

/**
 * The three-coefficient normal-equations fit both consumption models are built on.
 *
 * `constant + a × speed² + b × |outside − comfort|` is the same shape whether the left-hand
 * side is kWh per 100 km or percent of charge per 100 km, and the arithmetic that solves it
 * has no opinion about units. It lives here so there is one of it: a second copy would drift
 * from this one on the day someone fixes a pivot and only remembers one file.
 *
 * O(1) working memory, O(n) in samples. Call from a worker thread.
 */
internal class LeastSquares3(private val features: Int = FEATURES) {
    private val matrix = Array(features) { DoubleArray(features) }
    private val vector = DoubleArray(features)

    init {
        require(features in 2..FEATURES) { "two or three features" }
    }

    fun add(speedKmh: Double, outsideTempCelsius: Double, consumption: Double) {
        val speedSquared = speedKmh * speedKmh
        val temperatureOffset = abs(outsideTempCelsius - EnergyModel.COMFORT_TEMP_CELSIUS)
        for (row in 0 until features) {
            val rowValue = feature(row, speedSquared, temperatureOffset)
            vector[row] += rowValue * consumption
            for (column in 0 until features) {
                matrix[row][column] += rowValue *
                    feature(column, speedSquared, temperatureOffset)
            }
        }
    }

    private fun feature(index: Int, speedSquared: Double, temperatureOffset: Double) =
        when (index) {
            0 -> 1.0
            1 -> speedSquared
            else -> temperatureOffset
        }

    /**
     * The coefficients, or null when the points do not determine them.
     *
     * Two features is not a lesser fit, it is a different claim: a driver who has only ever
     * recorded trips at one outside temperature has no evidence about temperature, and a
     * thermal coefficient invented from that would be read as knowledge. The envelope keeps
     * the narrow range, so such a model refuses the cold rather than guessing at it.
     */
    fun solve(): DoubleArray? {
        val augmented = Array(features) { row ->
            DoubleArray(features + 1) { column ->
                if (column == features) vector[row] else matrix[row][column]
            }
        }
        for (column in 0 until features) {
            val pivot = (column until features).maxBy { abs(augmented[it][column]) }
            if (abs(augmented[pivot][column]) < MIN_PIVOT) return null
            val swap = augmented[column]
            augmented[column] = augmented[pivot]
            augmented[pivot] = swap
            val divisor = augmented[column][column]
            for (index in column..features) augmented[column][index] /= divisor
            for (row in 0 until features) {
                if (row == column) continue
                val factor = augmented[row][column]
                for (index in column..features) {
                    augmented[row][index] -= factor * augmented[column][index]
                }
            }
        }
        return DoubleArray(features) { augmented[it][features] }
    }

    private companion object {
        const val FEATURES = 3
        const val MIN_PIVOT = 1e-9
    }
}
