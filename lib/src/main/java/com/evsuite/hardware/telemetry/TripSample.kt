package com.evsuite.hardware.telemetry

/**
 * One moment of a trip, kept so the trip can be re-examined later.
 *
 * A summary cannot be un-summarised. Every model this project will grow — what a slower
 * motorway speed would have saved, what the climate system took, what the state of charge
 * will be on arrival — is fitted from the *shape* of past trips, and a shape that was never
 * recorded cannot be recovered from totals. So the track starts being written before there
 * is anything to fit, because the alternative is a model with no history to learn from.
 *
 * Every field is nullable and stays that way: a car that publishes no pack temperature
 * stores no pack temperature, rather than a column of zeros that a fit would happily
 * believe.
 */
data class TripSample(
    val atMs: Long,
    val speedKmh: Float?,
    val batteryPowerKw: Float?,
    val socPercent: Float?,
    val outsideTempCelsius: Float?,
    val cabinTempCelsius: Float?,
    val batteryTempCelsius: Float?,
    val climatePowerOn: Boolean?,
    val climateAcOn: Boolean?,
    val climateFanLevel: Int?,
) {
    companion object {
        fun of(snapshot: EnergySnapshot) = TripSample(
            atMs = snapshot.timestampMs,
            speedKmh = snapshot.speedKmh,
            batteryPowerKw = snapshot.batteryPowerKw,
            socPercent = snapshot.socPercent,
            outsideTempCelsius = snapshot.outsideTempCelsius,
            cabinTempCelsius = snapshot.cabinTempCelsius,
            batteryTempCelsius = snapshot.batteryTempCelsius,
            climatePowerOn = snapshot.climate.powerOn,
            climateAcOn = snapshot.climate.acOn,
            climateFanLevel = snapshot.climate.fanLevel,
        )
    }
}

/**
 * Collects a trip's track at a bounded resolution and a bounded length.
 *
 * The sampler reads at 1 Hz because the dashboard wants a live number; the track does not
 * need that, and storing it would cost fourteen thousand samples for a four-hour drive. It
 * keeps one sample per [intervalMs] instead.
 *
 * When a drive outlasts even that, the track is decimated rather than truncated: every
 * second sample is dropped and the interval doubles. A long trip keeps its whole shape at a
 * coarser resolution, where cutting the tail would keep the first hours and silently discard
 * the motorway stretch that a model most wants to see.
 */
class TripSampleTrack(
    private val startIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {
    private val samples = ArrayList<TripSample>()
    private var intervalMs = startIntervalMs
    private var lastKeptMs: Long? = null

    fun add(snapshot: EnergySnapshot) {
        val last = lastKeptMs
        if (last != null && snapshot.timestampMs - last < intervalMs) return
        samples.add(TripSample.of(snapshot))
        lastKeptMs = snapshot.timestampMs
        if (samples.size >= maxSamples) decimate()
    }

    fun samples(): List<TripSample> = samples.toList()

    /**
     * Halves the track in place and doubles the interval, keeping first and last.
     *
     * The last sample is kept explicitly rather than by parity. Decimation is triggered by the
     * sample that has just been added, and on an even-sized track that sample sits at an odd
     * index — taking every second one from the start would throw away the newest moment of the
     * trip at every doubling, which is the one a model is least able to do without.
     */
    private fun decimate() {
        val newest = samples.lastIndex
        var write = 0
        for (read in samples.indices) {
            if (read % 2 == 0 && read != newest) samples[write++] = samples[read]
        }
        samples[write++] = samples[newest]
        while (samples.size > write) samples.removeAt(samples.size - 1)
        intervalMs *= 2
    }

    companion object {
        /** One sample per five seconds: the resolution a consumption fit actually needs. */
        const val DEFAULT_INTERVAL_MS = 5_000L
        /** About five and a half hours at the default interval before the first decimation. */
        const val DEFAULT_MAX_SAMPLES = 4_096
    }
}
