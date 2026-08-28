package com.evsuite.hardware.saic

import android.content.Context

/**
 * The head unit's radio, as its own radio app drives it.
 *
 * Binding contract: `RadioOptionManager` (bind target, from
 * `RadioConstants.SERVICE_PACKAGE` / `SERVICE_ACTION`), `IRadioAppService` (transaction
 * codes) and `RadioType` (the band values).
 */
object SaicRadio {

    private const val PACKAGE = "com.saicmotor.service.radio"
    private const val ACTION = "com.saicmotor.service.radio.radioservice"
    private const val DESCRIPTOR = "com.saicmotor.sdk.radio.IRadioAppService"

    private const val TX_TUNE = 8
    private const val TX_NEXT = 13
    private const val TX_PREVIOUS = 14
    private const val TX_CURRENT_INFO = 19
    private const val TX_START_ACTIVITY = 26
    private const val TX_SRC_PLAY = 27
    private const val TX_SRC_PAUSE = 28

    /** `RadioType`: 1 = AM, 2 = FM, 4 = DAB. */
    const val BAND_AM = 1
    const val BAND_FM = 2
    const val BAND_DAB = 4

    private val service = SaicService(PACKAGE, ACTION, "radio")

    val isAvailable: Boolean get() = service.isReady

    fun connect(context: Context) = service.connect(context)

    /**
     * Makes radio the current audio source, resuming the last station.
     *
     * `srcPlayRadio` rather than launching the activity: a rule that fires at ignition wants
     * the sound, not a screen thrown in front of whatever the driver was looking at.
     */
    fun play(): Boolean = SaicAidl.callBoolean(service.binder(), DESCRIPTOR, TX_SRC_PLAY) ?: false

    /** Brings the radio screen up — separate, because it is a different intent entirely. */
    fun openScreen(): Boolean =
        SaicAidl.callBoolean(service.binder(), DESCRIPTOR, TX_START_ACTIVITY) ?: false

    /**
     * Silences the radio and marks it stopped — `srcPauseRadio`, the counterpart of [play].
     *
     * It mutes the tuner rather than handing the audio focus back: the vendor service only
     * abandons focus when it loses it, so whatever was playing before the radio took over
     * does not resume on its own. Silence is the most this service offers.
     */
    fun pause(): Boolean = SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_SRC_PAUSE)

    /**
     * Tunes a frequency in kHz (FM 87500–108000), playing it or leaving it silent.
     *
     * [andPlay] is not optional decoration on top of the tune: `tune` itself calls
     * `AudioController.requestMuted(false)` inside the vendor service, which requests the
     * audio focus and unmutes the tuner, so the radio starts playing whether or not anyone
     * asked it to. A caller that wants the station set without the sound has to undo that,
     * which is why the choice is made here rather than left to a second call the caller can
     * forget: `andPlay = false` pauses the radio again as soon as it is tuned.
     */
    fun tune(band: Int, frequencyKhz: Int, andPlay: Boolean): Boolean {
        if (!SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_TUNE, band, frequencyKhz)) return false
        return if (andPlay) play() else pause()
    }

    /**
     * Steps to the next station of the band the tuner is already on.
     *
     * This is the one way to reach a **DAB** station from a rule: [tune] takes a frequency,
     * and a DAB service is addressed by ensemble and service id, not by one — so there is
     * nothing for a driver to type. Stepping needs neither, and the tuner's own list is
     * already the right one.
     */
    fun nextStation(): Boolean = SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_NEXT)

    fun previousStation(): Boolean = SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_PREVIOUS)

    /** What [playPause] did, or why it did nothing. */
    enum class ToggleResult {
        /** The tuner was silent and was asked to play. */
        PLAYED,

        /** The tuner was playing and was asked to stop. */
        PAUSED,

        /**
         * The tuner would not say whether it was playing, so nothing was sent.
         *
         * The distinction from [REFUSED] is the one a caller has to report: the service
         * refused nothing, it was never asked.
         */
        STATE_UNKNOWN,

        /** The state was read, and the play or pause call that followed was not accepted. */
        REFUSED
    }

    /**
     * Toggles the tuner between playing and silent, addressing the radio whatever owns the
     * audio right now.
     *
     * Deliberately not [SaicMediaPlayer]'s play/pause, which commands the *current source* —
     * that one silences Bluetooth when Bluetooth is playing, which is right for a media
     * shortcut and wrong for "toggle the radio".
     *
     * **Fails closed on an unreadable state.** [SaicMediaPlayer.radioCommand] treats a null
     * as playing, and is right to: a driver pressing a wheel button sees nothing happen and
     * presses again. A rule has no second press. Guessing wrong there leaves the car silent
     * on a morning commute, or playing at a hospital car park, with nothing in the history
     * saying the direction was invented — so an unreadable state sends nothing at all.
     */
    fun playPause(): ToggleResult {
        val playing = isPlaying() ?: return ToggleResult.STATE_UNKNOWN
        val ok = if (playing) pause() else play()
        return when {
            !ok -> ToggleResult.REFUSED
            playing -> ToggleResult.PAUSED
            else -> ToggleResult.PLAYED
        }
    }

    /**
     * Is the tuner playing? Null when the question could not be asked.
     *
     * `isPlaying()` in the vendor SDK is `getCurrentRadioInfo().getRadioState() == 1`, so the
     * `RadioBean` has to be unwound in its `writeToParcel` order: enable(byte), name(String),
     * rds(byte), cover(String), frequency(int), band(int), **state(int)**.
     *
     * Worth the coupling because the obvious substitute is wrong: `AudioManager.isMusicActive`
     * is **false while the radio plays**, its stream not being the music one. A play/pause
     * shortcut driven by it would send "play" to a radio that is already playing, forever.
     */
    fun isPlaying(): Boolean? =
        SaicAidl.callParcel(service.binder(), DESCRIPTOR, TX_CURRENT_INFO) { reply ->
            if (reply.readInt() == 0) return@callParcel null   // null bean: nothing to conclude
            reply.readByte(); reply.readString()               // enable, station name
            reply.readByte(); reply.readString()               // rds, cover art
            reply.readInt(); reply.readInt()                   // frequency, band
            reply.readInt() == 1
        }
}

/**
 * Placing a call over the car's own hands-free stack.
 *
 * Not `ACTION_CALL`: the head unit has no SIM and no dialer of its own — the call is placed
 * by the vehicle's Bluetooth hands-free service through the paired phone, which is what its
 * `placeCall` does. Going through the platform telephony intent would find nothing to handle
 * it.
 *
 * Binding contract: `BtCallManager` (bind target, from
 * `BtConstant.SERVICE_PACKAGE` / `SERVICE_ACTION`) and `IBtCall` (transaction codes).
 */
object SaicPhone {

    private const val PACKAGE = "com.saicmotor.service.btcall"
    private const val ACTION = "com.saicmotor.service.btcall.BtCallService"
    private const val DESCRIPTOR = "com.saicmotor.sdk.btcall.IBtCall"

    private const val TX_PLACE_CALL = 2
    private const val TX_GET_BLUETOOTH_STATE = 12
    private const val TX_GET_CONN_DEVICE_NAME = 14

    private val service = SaicService(PACKAGE, ACTION, "btcall")

    val isAvailable: Boolean get() = service.isReady

    fun connect(context: Context) = service.connect(context)

    /** @return true when the hands-free stack accepted the number. */
    fun placeCall(number: String): Boolean =
        SaicAidl.callBoolean(service.binder(), DESCRIPTOR, TX_PLACE_CALL, number) ?: false

    fun bluetoothState(): Int? = SaicAidl.callInt(service.binder(), DESCRIPTOR, TX_GET_BLUETOOTH_STATE)

    /** Name of the phone the car is paired with, for the diagnostic report. */
    fun connectedDeviceName(): String? =
        SaicAidl.callString(service.binder(), DESCRIPTOR, TX_GET_CONN_DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
}

/**
 * The vehicle's own voice — the one that says "left front tyre abnormality".
 *
 * This is what makes the "speak" action work on a head unit with no Android TTS engine
 * installed, which is the normal case: the car talks, but through a vendor service rather
 * than through `android.speech.tts`. Probing for a platform engine and finding none was
 * therefore the right answer to the wrong question.
 *
 * Bound by component name, not by action — the service declares no intent filter, and the
 * car's own voice app reaches it exactly this way.
 *
 * Binding contract: `VoiceAnnounceModel` (component name and
 * the call it makes) and `ITtsService` (descriptor, transaction codes).
 */
object SaicTts {

    private const val PACKAGE = "com.saicmotor.voicetts"
    private const val CLASS = "com.saicmotor.voicetts.TtsService"
    private const val DESCRIPTOR = "com.saicmotor.voicetts.ITtsService"

    private const val TX_PROMPT = 1
    private const val TX_STOP = 3

    private val service = SaicService.byComponent(PACKAGE, CLASS, "voicetts")

    val isAvailable: Boolean get() = service.isReady

    fun connect(context: Context) = service.connect(context)

    /**
     * Speaks [text].
     *
     * [interrupt] true stops whatever the car is currently announcing — false queues behind
     * it, which is what a rule message should do: a tyre warning outranks "profile applied".
     * The tag is the caller's package, as the vendor apps pass their own.
     */
    fun speak(text: String, interrupt: Boolean = false, tag: String = "com.evsuite.tasker"): Boolean =
        SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_PROMPT, text, interrupt, tag)

    fun stop(): Boolean = SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_STOP)
}
