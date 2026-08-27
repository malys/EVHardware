package com.evsuite.hardware

import android.content.Context
import com.evsuite.hardware.catalog.ActionType

/**
 * What this car was *observed* to do with a glass command.
 *
 * [ActionType.writeProven] is a claim about the catalogue: nobody has ever seen one of the
 * eight window commands move a window, so the actions ship refused everywhere. That claim is
 * true of the project, not of a given car — and it can only ever be settled on a car, by
 * sending a command and watching the position read back change.
 *
 * [GlassProbe] does exactly that, and what it saw is recorded here. From then on this car
 * knows which command opens and which closes, and the window actions stop being refused
 * **on this car only**: the evidence never leaves it, and it is dropped as soon as the
 * firmware generation changes, since a command proven on one generation proves nothing about
 * another.
 *
 * Loaded once at [EVHardware.init] and cached, so the catalogue predicate stays a pure
 * property read — the editors and the diagnostic ask it for every entry they draw.
 */
object GlassEvidence {

    private const val PREFS = "ev_settings"
    private const val KEY_OPEN = "glass_open_command"
    private const val KEY_CLOSE = "glass_close_command"
    private const val KEY_GEN = "glass_proof_generation"
    private const val KEY_AT = "glass_proof_at"

    private const val NONE = -1

    /** The actions this evidence unlocks — the ones [ActionType.writeProven] holds back. */
    val ACTIONS: Set<ActionType> = setOf(
        ActionType.SET_WINDOWS,
        ActionType.SET_WINDOW_DRIVER,
        ActionType.SET_WINDOW_PASSENGER,
        ActionType.SET_WINDOW_REAR_LEFT,
        ActionType.SET_WINDOW_REAR_RIGHT,
    )

    @Volatile
    private var cached: Proof? = null

    /**
     * @param generation the firmware the commands were observed on. Evidence from another
     *   generation is ignored rather than deleted: a car that is rolled back to its previous
     *   firmware finds its proof again.
     */
    data class Proof(
        val openCommand: Int,
        val closeCommand: Int,
        val generation: String,
        val observedAtMillis: Long,
    )

    /** The proof for the firmware now running, or null while nothing has been observed. */
    val proof: Proof? get() = cached?.takeIf { it.generation == FirmwareInfo.getGeneration().name }

    val isProven: Boolean get() = proof != null

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val open = prefs.getInt(KEY_OPEN, NONE)
        val close = prefs.getInt(KEY_CLOSE, NONE)
        val gen = prefs.getString(KEY_GEN, null)
        cached = if (open == NONE || close == NONE || gen == null) null else {
            Proof(open, close, gen, prefs.getLong(KEY_AT, 0L))
        }
        AppLogger.i(TAG, "glass evidence: ${cached ?: "none"}")
    }

    /**
     * Records what the probe watched happen. Both directions or nothing: a car that can be
     * opened and not closed is worse than one the actions never touch.
     */
    fun record(context: Context, openCommand: Int, closeCommand: Int) {
        val gen = FirmwareInfo.getGeneration().name
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_OPEN, openCommand)
            .putInt(KEY_CLOSE, closeCommand)
            .putString(KEY_GEN, gen)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        cached = Proof(openCommand, closeCommand, gen, System.currentTimeMillis())
        AppLogger.i(TAG, "glass evidence recorded: open=$openCommand close=$closeCommand on $gen")
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_OPEN).remove(KEY_CLOSE).remove(KEY_GEN).remove(KEY_AT)
            .apply()
        cached = null
    }

    /** Test seam: sets the cache without touching storage. */
    internal fun setForTest(proof: Proof?) { cached = proof }

    private const val TAG = "EV_GLASS"
}

/**
 * Whether writing this action has been shown to do something *here*.
 *
 * [ActionType.writeProven] answers it for every car at once, from what the project knows.
 * This answers it for the car the code is running on, which is the only place the glass
 * question could ever be settled. Everything user-facing — the picker, the diagnostic, the
 * executor — asks this one rather than the raw property.
 */
val ActionType.effectProven: Boolean
    get() = writeProven || (this in GlassEvidence.ACTIONS && GlassEvidence.isProven)
