package com.evsuite.hardware.saic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole of [NavLegChain]'s behaviour, on the JVM, because the alternative is a drive.
 *
 * Every case here is a sequence of answers to one question — "is the map guiding" — and what the
 * chain does with them. That is all the class knows, so that is all a test needs to give it.
 */
class NavLegChainTest {

    private val stop = NavigationHandoff.Poi(43.343, 3.215, "Ionity Beziers Est")
    private val destination = NavigationHandoff.Poi(43.5583, 1.5333, "Auzielle")

    private fun chain() = NavLegChain(listOf(stop, destination))

    @Test fun `the second leg goes out when guidance for the first one ends`() {
        val chain = chain()
        assertEquals(NavLegChain.Step.Wait, chain.tick(guiding = true))
        assertEquals(NavLegChain.Step.Wait, chain.tick(guiding = true))
        assertEquals(
            NavLegChain.Step.Send(destination, number = 2, count = 2),
            chain.tick(guiding = false),
        )
    }

    @Test fun `nothing goes out while the first leg is still being driven`() {
        val chain = chain()
        repeat(50) { assertEquals(NavLegChain.Step.Wait, chain.tick(guiding = true)) }
    }

    @Test fun `a leg that never starts is abandoned rather than chained onto`() {
        val chain = chain()
        repeat(NavLegChain.GIVE_UP_TICKS - 1) {
            assertEquals(NavLegChain.Step.Wait, chain.tick(guiding = false))
        }
        assertEquals(NavLegChain.Step.Done(arrived = false), chain.tick(guiding = false))
    }

    @Test fun `guidance appearing late still counts, and the grace restarts with it`() {
        val chain = chain()
        repeat(NavLegChain.GIVE_UP_TICKS - 1) { chain.tick(guiding = false) }
        assertEquals(NavLegChain.Step.Wait, chain.tick(guiding = true))
        assertEquals(
            NavLegChain.Step.Send(destination, number = 2, count = 2),
            chain.tick(guiding = false),
        )
    }

    @Test fun `the leg handed over gets its own grace before it is given up on`() {
        val chain = chain()
        chain.tick(guiding = true)
        chain.tick(guiding = false)
        // The car was just sent somewhere; the map has not come up yet. That silence is not a
        // refusal until the grace runs out again, and it must run out again in full.
        repeat(NavLegChain.GIVE_UP_TICKS - 1) {
            assertEquals(NavLegChain.Step.Wait, chain.tick(guiding = false))
        }
        assertEquals(NavLegChain.Step.Done(arrived = false), chain.tick(guiding = false))
    }

    @Test fun `the last leg ending ends the chain, and every later tick says so`() {
        val chain = chain()
        chain.tick(guiding = true)
        chain.tick(guiding = false)
        chain.tick(guiding = true)
        assertEquals(NavLegChain.Step.Done(arrived = true), chain.tick(guiding = false))
        assertEquals(NavLegChain.Step.Done(arrived = true), chain.tick(guiding = true))
    }

    @Test fun `three legs are driven one after the other, in order`() {
        val second = NavigationHandoff.Poi(44.128, 4.081, "Ales")
        val chain = NavLegChain(listOf(stop, second, destination))
        chain.tick(guiding = true)
        assertEquals(
            NavLegChain.Step.Send(second, number = 2, count = 3),
            chain.tick(guiding = false),
        )
        chain.tick(guiding = true)
        assertEquals(
            NavLegChain.Step.Send(destination, number = 3, count = 3),
            chain.tick(guiding = false),
        )
        chain.tick(guiding = true)
        assertEquals(NavLegChain.Step.Done(arrived = true), chain.tick(guiding = false))
    }

    @Test fun `a trip with one point has nothing to chain`() {
        val chain = NavLegChain(listOf(destination))
        assertTrue(chain.tick(guiding = true) is NavLegChain.Step.Done)
    }

    @Test fun `an empty chain is over before it starts`() {
        assertEquals(
            NavLegChain.Step.Done(arrived = true),
            NavLegChain(emptyList()).tick(guiding = false),
        )
    }

    @Test fun `the legs are copied, so the caller's list cannot change the trip`() {
        val legs = mutableListOf(stop, destination)
        val chain = NavLegChain(legs)
        legs.clear()
        chain.tick(guiding = true)
        assertEquals(
            NavLegChain.Step.Send(destination, number = 2, count = 2),
            chain.tick(guiding = false),
        )
    }
}
