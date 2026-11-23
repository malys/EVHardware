package com.evsuite.hardware.telemetry

/**
 * Acceleration over at least [baseMs], never over a single read (CP-099).
 *
 * The app reads the speed every 1000 ms while SWI68 publishes it about every 1088 ms, in bursts
 * as close as 62 ms apart. One read-to-read step then holds no update, one, or two, and the
 * acceleration it gives reads 0, about 9 % high, or double. Two seconds hold two updates almost
 * always, so the slope across them is the car's and not the sampler's. A hard stop lasts longer
 * than that, so it still shows; a one-read jolt mostly does not, and was mostly the sampler.
 *
 * A moving speed read twice to the 1/64 km/h is a read that missed its update (861 of 986 reads
 * changed on the 2026-11-21 capture): it is not kept as a point the slope starts from, where its
 * stale value would read the catch-up as half again too steep, and its own slope is unknown.
 */
class SpeedSlope(
    private val baseMs: Long = BASE_MS,
    private val maxGapMs: Long = EcoDrivingMonitor.MAX_GAP_MS,
) {
    private val recent = ArrayDeque<Pair<Long, Double>>()

    init {
        require(baseMs > 0L) { "the slope base must be positive" }
        require(maxGapMs > 0L) { "the sampling gap must be positive" }
    }

    /**
     * Feed one read; returns m/s² over the last [baseMs] or more, or null while less than that
     * has been read since the last gap. A null speed is a gap, never a zero.
     */
    fun add(atMs: Long, speedKmh: Double?): Double? {
        val last = recent.lastOrNull()
        if (last != null && atMs <= last.first) return null
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0.0 ||
            (last != null && atMs - last.first > maxGapMs)
        ) recent.clear()
        if (speedKmh == null || !speedKmh.isFinite() || speedKmh < 0.0) return null
        if (speedKmh > 0.0 && recent.lastOrNull()?.second == speedKmh) return null
        recent.addLast(atMs to speedKmh)
        while (recent.size > 2 && atMs - recent[1].first >= baseMs) recent.removeFirst()
        val (fromMs, fromKmh) = recent.first()
        val spanMs = atMs - fromMs
        if (spanMs < baseMs) return null
        return (speedKmh - fromKmh) / KMH_PER_MS2 / (spanMs / 1000.0)
    }

    fun reset() = recent.clear()

    companion object {
        /** Two updates of the car's speed, whatever the phase between it and the sampler. */
        const val BASE_MS = 2_000L

        /** 1 m/s² is 3.6 km/h per second. */
        const val KMH_PER_MS2 = 3.6
    }
}
