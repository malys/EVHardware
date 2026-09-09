package com.evsuite.hardware.saic

/**
 * A trip driven one leg at a time, because the command that starts guidance carries one point.
 *
 * **Why a chain exists at all.** A plan with a charging stop is a stop and then a destination, and
 * nothing on this head unit has been seen to drive both from one handover.
 * [SaicNavGuidance.goTo] takes a single point by construction. [SaicNavGuidance.startNavFromEvRoute]
 * takes a pathway and a destination and, on SWI68 on 2026-09-09, is the channel that actually
 * starts guidance — but whether Telenav drives *through* the pathway or straight to the last point
 * is not known, and no drive with a charging stop has happened yet to say. The chain covers both:
 * if the map drives the whole route, guidance never stops early and this sends nothing.
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

    /** Consecutive readings with no guidance — the grace before a start, the settle after one. */
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
        // One false reading is not an arrival. The drive of 2026-09-09 16:34 watched a route
        // handed over through `startNavFromEVRout` run for twenty-five minutes while the adapter
        // stopped answering a remaining distance seven minutes in and its notification listener
        // heard nothing at all. Whether the guiding flag itself is that shaky is not yet known,
        // and the cost of being wrong is a car sent onward from a charger it is still driving to.
        // Consecutive readings cost seconds; a genuine arrival stays false for the rest of the day.
        if (++silentTicks < SETTLE_TICKS) return Step.Wait
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

        /** Consecutive readings with no guidance before a leg that was running counts as over. */
        const val SETTLE_TICKS = 3
    }
}
