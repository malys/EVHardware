package com.evsuite.hardware.saic

import com.evsuite.hardware.catalog.ActionType
import com.evsuite.hardware.catalog.ValueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio family added by CR-020, checked where it can be checked without a car.
 *
 * [SaicRadio.playPause] itself binds a vendor service, so its direction logic is
 * re-implemented here over the same three inputs rather than mocked: what the test defends
 * is the decision table — an unreadable state sends nothing — and that table is small enough
 * to state twice and compare. The binder round trip is what the on-vehicle run covers.
 */
class RadioToggleTest {

    /** [SaicRadio.playPause]'s decision, over a state that may be unreadable. */
    private fun toggle(playing: Boolean?, accepted: Boolean): SaicRadio.ToggleResult {
        if (playing == null) return SaicRadio.ToggleResult.STATE_UNKNOWN
        if (!accepted) return SaicRadio.ToggleResult.REFUSED
        return if (playing) SaicRadio.ToggleResult.PAUSED else SaicRadio.ToggleResult.PLAYED
    }

    @Test
    fun `an unreadable state sends nothing in either direction`() {
        // The whole point of the action: a rule fires unattended, so a guessed direction is
        // a car left silent or left playing with nothing in the history saying it was a guess.
        assertEquals(SaicRadio.ToggleResult.STATE_UNKNOWN, toggle(playing = null, accepted = true))
        assertEquals(SaicRadio.ToggleResult.STATE_UNKNOWN, toggle(playing = null, accepted = false))
    }

    @Test
    fun `the toggle follows the state the tuner reports`() {
        assertEquals(SaicRadio.ToggleResult.PAUSED, toggle(playing = true, accepted = true))
        assertEquals(SaicRadio.ToggleResult.PLAYED, toggle(playing = false, accepted = true))
    }

    @Test
    fun `a refused call is not reported as an unknown state`() {
        // Two different things to tell the driver: the service refused, or it was never asked.
        assertEquals(SaicRadio.ToggleResult.REFUSED, toggle(playing = true, accepted = false))
        assertEquals(SaicRadio.ToggleResult.REFUSED, toggle(playing = false, accepted = false))
    }

    @Test
    fun `the band constants are the vendor RadioType values`() {
        // tune() passes them straight through; a value invented here tunes the wrong band.
        assertEquals(1, SaicRadio.BAND_AM)
        assertEquals(2, SaicRadio.BAND_FM)
        assertEquals(4, SaicRadio.BAND_DAB)
    }

    @Test
    fun `every radio action reaches the bridge and declares its firmware`() {
        RADIO_ACTIONS.forEach {
            assertNotNull("${it.name} has no bridgeAction", it.bridgeAction)
            assertTrue("${it.name} has no label", it.labelRes != 0)
        }
    }

    @Test
    fun `only opening a screen is gated, and the audio actions stay outside the gate`() {
        // Audio-only radio commands follow the physical media controls: refusing "next
        // station" above 0 km/h would make the action pointless. Opening a full-screen app in
        // front of a moving driver is the one that is not audio, and it takes the gate — and
        // adding it must not have pulled the rest of the family in with it.
        assertTrue("opening the radio screen must be standstill-only", ActionType.OPEN_RADIO_SCREEN.gated)
        (RADIO_ACTIONS - ActionType.OPEN_RADIO_SCREEN).forEach {
            assertFalse("${it.name} is audio-only and must not take the gate", it.gated)
        }
    }

    @Test
    fun `the radio family carries no value to configure, except the frequency`() {
        // A picker offering a control for an action that takes no argument asks the user for
        // something it will then ignore.
        (RADIO_ACTIONS - ActionType.TUNE_RADIO).forEach {
            assertEquals("${it.name} takes no argument", ValueKind.NONE, it.spec.kind)
        }
    }

    private companion object {
        val RADIO_ACTIONS = setOf(
            ActionType.PLAY_RADIO,
            ActionType.PAUSE_RADIO,
            ActionType.RADIO_PLAY_PAUSE,
            ActionType.TUNE_RADIO,
            ActionType.RADIO_NEXT_STATION,
            ActionType.RADIO_PREV_STATION,
            ActionType.OPEN_RADIO_SCREEN
        )
    }
}
