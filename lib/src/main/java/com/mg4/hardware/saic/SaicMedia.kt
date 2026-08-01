package com.mg4.hardware.saic

import android.content.Context

/**
 * The head unit's radio, as its own radio app drives it.
 *
 * Source: `apks/radio_eh32_eu_p` — `RadioOptionManager` (bind target, from
 * `RadioConstants.SERVICE_PACKAGE` / `SERVICE_ACTION`), `IRadioAppService` (transaction
 * codes) and `RadioType` (the band values).
 */
object SaicRadio {

    private const val PACKAGE = "com.saicmotor.service.radio"
    private const val ACTION = "com.saicmotor.service.radio.radioservice"
    private const val DESCRIPTOR = "com.saicmotor.sdk.radio.IRadioAppService"

    private const val TX_TUNE = 8
    private const val TX_START_ACTIVITY = 26
    private const val TX_SRC_PLAY = 27

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

    /** Tunes a frequency in kHz (FM 87500–108000) and makes radio the source. */
    fun tune(band: Int, frequencyKhz: Int): Boolean {
        if (!SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_TUNE, band, frequencyKhz)) return false
        return play()
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
 * Source: `apks/btcall_eh32_eu_p` — `BtCallManager` (bind target, from
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
 * Source: `apks/saicvoiceservice_overseas_eh32` — `VoiceAnnounceModel` (component name and
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
    fun speak(text: String, interrupt: Boolean = false, tag: String = "com.mg4.tasker"): Boolean =
        SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_PROMPT, text, interrupt, tag)

    fun stop(): Boolean = SaicAidl.callVoid(service.binder(), DESCRIPTOR, TX_STOP)
}
