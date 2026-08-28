package com.evsuite.hardware.telemetry

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream

/** A stored trip: always its totals, and its track for as long as there is room for it. */
data class StoredTrip(
    val summary: EnergyTripSummary,
    val samples: List<TripSample>? = null,
)

/**
 * The history file's envelope.
 *
 * The first version of this file was a bare JSON array of summaries, with no room to say what
 * it was. Trips now carry a sample track, and the track will change again as models arrive, so
 * the file states its own version and a reader can decide whether it understands it.
 */
data class TripHistoryFile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val trips: List<StoredTrip>,
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
}

/**
 * Bounded app-private history, replaced atomically after every successful write.
 *
 * Three bounds apply, in the order that keeps the most meaning per byte:
 *  • at most [maxTrips] trips, newest first;
 *  • at most [maxBytes] on disk, reached by dropping sample tracks oldest-first — a trip
 *    keeps its totals even when its track is evicted, because the totals are what the driver
 *    reads and the track is what a model would like;
 *  • only then, if a single trip still does not fit, by dropping the oldest trips.
 *
 * A file this build cannot read is quarantined rather than deleted. It is the only copy of
 * whatever it holds, and a future build — or a person — may be able to read it.
 */
class EnergyTripHistoryStore(
    private val target: File,
    private val maxTrips: Int = 200,
    private val maxBytes: Int = 512 * 1024,
    private val gson: Gson = Gson(),
) {
    /** Trips with whatever tracks survived eviction, newest first. */
    fun read(): List<StoredTrip> {
        if (!target.exists()) return emptyList()
        val text = runCatching { target.readText() }.getOrNull() ?: return emptyList()
        return parse(text) ?: run {
            quarantine()
            emptyList()
        }
    }

    fun readSummaries(): List<EnergyTripSummary> = read().map { it.summary }

    fun append(summary: EnergyTripSummary, samples: List<TripSample> = emptyList()): Boolean {
        val entry = StoredTrip(summary, samples.takeIf { it.isNotEmpty() })
        val updated = bound(listOf(entry) + read())
        return write(TripHistoryFile(trips = updated))
    }

    /** Removes exactly one trip identified by its recording start, preserving every other entry. */
    fun deleteTrip(startedAtMs: Long): Boolean {
        val trips = read()
        val index = trips.indexOfFirst { it.summary.startedAtMs == startedAtMs }
        if (index < 0) return false
        val updated = trips.toMutableList().also { it.removeAt(index) }
        return write(TripHistoryFile(trips = updated))
    }

    /** Atomically replaces the history with a valid empty v2 envelope. */
    fun clear(): Boolean = write(TripHistoryFile(trips = emptyList()))

    /**
     * Reads both shapes: the v2 envelope, and the v1 bare array that shipped before it.
     *
     * Returns null when the text is neither — a truncated write, or a file from a build that
     * knows something this one does not. The caller quarantines it; nothing is deleted, and
     * nothing is guessed at.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun parse(text: String): List<StoredTrip>? {
        if (text.trimStart().startsWith("[")) return parseLegacy(text)
        val envelope = runCatching { gson.fromJson(text, HistoryEnvelope::class.java) }
            .getOrNull() ?: return null
        if (envelope.schemaVersion != TripHistoryFile.SCHEMA_VERSION) return null
        // Gson writes straight through Kotlin's nullability, so a truncated or hand-edited file
        // can hold a trip with no summary at all. The comparison is not senseless here.
        return envelope.trips.orEmpty().filter { it != null && it.summary != null }
    }

    /** The shape that shipped first: a bare array of summaries, with no envelope and no track. */
    private fun parseLegacy(text: String): List<StoredTrip>? = runCatching {
        val type = object : TypeToken<List<EnergyTripSummary>>() {}.type
        gson.fromJson<List<EnergyTripSummary>>(text, type)?.map { StoredTrip(it) }
    }.getOrNull()

    private data class HistoryEnvelope(
        val schemaVersion: Int = 0,
        val trips: List<StoredTrip>? = null,
    )

    /** The unreadable file keeps its contents under a new name; the history starts again. */
    private fun quarantine() {
        val kept = File(target.parentFile, "${target.name}.quarantine.${System.currentTimeMillis()}")
        if (!target.renameTo(kept)) {
            runCatching { target.copyTo(kept, overwrite = true) }
        }
    }

    private fun bound(trips: List<StoredTrip>): List<StoredTrip> {
        var candidate = trips.take(maxTrips.coerceAtLeast(1))
        // Tracks first: a trip without its track is still a trip, a missing trip is not.
        while (sizeOf(candidate) > maxBytes && candidate.any { it.samples != null }) {
            val oldest = candidate.indexOfLast { it.samples != null }
            candidate = candidate.toMutableList().also {
                it[oldest] = it[oldest].copy(samples = null)
            }
        }
        while (sizeOf(candidate) > maxBytes && candidate.size > 1) {
            candidate = candidate.dropLast(1)
        }
        return candidate
    }

    private fun sizeOf(trips: List<StoredTrip>): Int =
        gson.toJson(TripHistoryFile(trips = trips)).toByteArray(Charsets.UTF_8).size

    private fun write(file: TripHistoryFile): Boolean {
        val bytes = gson.toJson(file).toByteArray(Charsets.UTF_8)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (temp.renameTo(target)) true else {
                temp.delete()
                false
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }
}
