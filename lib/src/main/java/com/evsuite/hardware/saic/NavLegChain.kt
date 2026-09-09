package com.evsuite.hardware.saic

/**
 * A trip driven one leg at a time, because the command that starts guidance carries one point.
 *
 * **Why a chain exists at all.** [SaicNavGuidance.goTo] is the only call on this interface that
 * has ever been seen to *start* guidance, and it takes a single point. The route handoff takes a
 * pathway and a destination, and on SWI68 it draws them without driving them. So a plan with a
 * charging stop on the way cannot be handed over in one go: the car is sent to the stop, and the
 * destination is sent afterwards, when the first leg is over.
 *
 * **What "over" means here.** The navigation app's own flag — `isMapNavigating` — going false
 * after having been true for this leg. That is arrival in every ordinary case, and it is the only
 * signal available without a network request, a coordinate comparison or a second permission.
 * A driver who *cancels* guidance mid-leg produces the same signal, so the next leg starts under
 * them; they cancel that one too and the chain gives up. That is the known ceiling of this
 * design, and the alternative — comparing the car's position against the leg's — would put
 * coordinates on a background timer for a case the driver can undo with one tap.
 *
 * **Nothing here is persisted.** The chain lives in memory for the length of the drive. It holds
 * place names and coordinates, which is precisely why it is never written down: the followed-plan
 * store on the other side of this trip keeps none of that, and a chain on disk would be the same
 * data by another route. A process that dies loses the chain, and the driver hands over the
 * remaining leg from the screen, which is what they did before this class existed.
 *
 * Pure and Android-free: the caller does the binder reads and this decides what they mean.
 */
class NavLegChain(legs: List<NavigationHandoff.Poi>) {

    /** What the caller should do after a tick. */
    sealed interface Step {
        /** Nothing to do: the leg is still being driven, or guidance has yet to appear. */
        data object Wait : Step

        /**
         * Hand this point over — the previous leg ended.
         *
         * [number] and [count] are one-based and are the only things about a leg that may be
         * written to a probe: a name or a coordinate on a diagnostic file leaves the car.
         */
        data class Send(
            val poi: NavigationHandoff.Poi,
            val number: Int,
            val count: Int,
        ) : Step

        /** The chain is over and every later tick returns this. [arrived] tells why. */
        data class Done(val arrived: Boolean) : Step
    }

    private val legs = legs.toList()

    /** The leg the car was last sent to. Zero is the one the tap itself handed over. */
    private var index = 0

    /** Whether guidance has been seen running for [index]. Nothing chains before it has. */
    private var sawGuidance = false

    /** Consecutive ticks with no guidance while none has ever been seen for this leg. */
    private var silentTicks = 0

    private var done: Step.Done? = null

    /**
     * Folds one reading of the navigation app's flag.
     *
     * @param guiding what `isMapNavigating` answered. Do not call with a guess: a caller that
     *   could not reach the adapter should skip the tick entirely rather than pass false, or the
     *   grace below expires against a question that was never asked.
     */
    fun tick(guiding: Boolean): Step {
        done?.let { return it }
        if (legs.size < 2) return finish(arrived = true)
        if (guiding) {
            sawGuidance = true
            silentTicks = 0
            return Step.Wait
        }
        if (!sawGuidance) {
            // Guidance never started for this leg. Chaining onto a leg that was refused would
            // send the car to the destination while it is still standing at the charger.
            silentTicks++
            return if (silentTicks >= GIVE_UP_TICKS) finish(arrived = false) else Step.Wait
        }
        index++
        if (index > legs.lastIndex) return finish(arrived = true)
        sawGuidance = false
        silentTicks = 0
        return Step.Send(legs[index], number = index + 1, count = legs.size)
    }

    private fun finish(arrived: Boolean): Step.Done =
        Step.Done(arrived).also { done = it }

    companion object {
        /**
         * How many silent ticks before a leg that never started is abandoned.
         *
         * The caller ticks every few seconds, so this is a couple of minutes: long enough for a
         * navigation app to come up and start drawing, short enough that a chain armed by a tap
         * nobody followed does not sit waiting for the rest of the drive.
         */
        const val GIVE_UP_TICKS = 24
    }
}
