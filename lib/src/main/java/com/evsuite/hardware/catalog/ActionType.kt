package com.evsuite.hardware.catalog

import androidx.annotation.StringRes
import com.evsuite.hardware.R
import com.evsuite.hardware.catalog.SnapshotKeys
import com.evsuite.hardware.catalog.VehicleEnums
import com.evsuite.hardware.SupportedOn
import com.evsuite.hardware.FirmwareGen.SWI131
import com.evsuite.hardware.FirmwareGen.SWI132
import com.evsuite.hardware.FirmwareGen.SWI133
import com.evsuite.hardware.FirmwareGen.SWI165
import com.evsuite.hardware.FirmwareGen.SWI68
import com.evsuite.hardware.FirmwareGen.SWI69
import com.evsuite.hardware.catalog.ValueSpec.Companion.number

/** Grouping in the action picker. */
enum class ActionGroup(@StringRes val labelRes: Int) {
    PROFILE(R.string.group_profile),
    DRIVING(R.string.group_driving),
    COMFORT(R.string.group_comfort),
    CLIMATE(R.string.group_climate),
    ENERGY(R.string.group_energy),
    AUDIO(R.string.group_audio),
    ADAS(R.string.group_adas),
    SYSTEM(R.string.group_system)
}

/**
 * Catalogue of executable actions.
 *
 * [bridgeAction] is the identifier sent to `TaskerBridgeService.applyAction`. It is null
 * for actions handled locally by EVTasker (launch an app, notify), which never touch the
 * vehicle and therefore have no business in the bridge.
 *
 * [gated] marks writes that change road behaviour. EVProfile refuses them while the car
 * is moving or when its speed is unreadable. The editor shows the mark so the user knows
 * up front that such an action only applies when stopped, instead of discovering a
 * refusal in the history afterwards.
 *
 * The [SupportedOn] annotation declares firmware support (from EVProfile routing); it
 * drives the generated README matrix and the editor's runtime filter. Local actions carry
 * no annotation — they are firmware-independent.
 *
 * Climate and charging writes go through the SAIC vendor services
 * (`com.evsuite.hardware.saic`), not through AOSP property ids. That distinction is the reason
 * they exist at all: the AOSP climate ids EVHardware reads are standard ids that no MG4
 * confirmed, so writing them would have been a guess. The vendor calls
 * are the ones the car's own HVAC and charging screens make.
 *
 * Window and door-lock writes go through `vehiclecontrol` on the same hub. The glass carries
 * a **command** in 0..7, not a position: the service reads back a percentage but accepts
 * nothing above 7 on the way in, and drops what it does not accept without saying so (see
 * `SaicVehicleControl`). Which command moves which way is not established by anything on the
 * firmware, so every glass write takes the standstill gate — an unknown direction cannot be
 * the one that is safe at speed, and every glass write is [writeProven] `= false` until one
 * command is observed to move a window. The electric tailgate stays out — the launcher defines its
 * OPEN and CLOSE as the same value, so it is a pulse whose direction depends on state this
 * cannot read.
 *
 * Also deliberately absent:
 *   • `VEHICLE_POWER_OFF` — cutting the vehicle must stay an explicit human gesture.
 *   • `SET_SOUND_FIELD`   — EVHardware.getSoundFieldType() always returns -1, so the
 *                           state is never readable back and a rule's effect would be
 *                           unverifiable.
 */
enum class ActionType(
    @StringRes val labelRes: Int,
    val group: ActionGroup,
    val spec: ValueSpec,
    val bridgeAction: String?,
    val gated: Boolean = false,
    /**
     * Snapshot key holding what this action controls, right now, when the car can report it.
     *
     * The editor opens the control on that value instead of on the bottom of its range. A
     * brightness slider that starts at 5% invites the driver to confirm 5% by accident; one
     * that starts where the screen already is makes the rule an edit of the present state,
     * which is what the user is thinking about. Null where nothing reads it back.
     */
    val currentKey: String? = null,
    /**
     * False when nothing establishes that this write does what its label says.
     *
     * Firmware support and effect are two different questions. `@SupportedOn` says the
     * property exists on this generation; this says whether writing it has ever been shown
     * to move anything. The glass is the case that made the distinction necessary: the
     * service accepts a command in 0..7, no head-unit application sends one, and which of
     * the eight raises a window is written down nowhere — so a write returns success while
     * the glass stays where it was.
     *
     * An unproven action is offered nowhere and executed nowhere: EVTasker's diagnostic
     * blocks it, the rule editor does not list it, and the executor refuses it rather than
     * report a success it cannot back. Flip the flag back to true in the same commit that
     * records the evidence — a command sent, the position read back, the glass observed to
     * have moved.
     */
    val writeProven: Boolean = true
) {

    // ── Profile ──────────────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    APPLY_PROFILE(
        R.string.act_apply_profile, ActionGroup.PROFILE,
        ValueSpec(ValueKind.PROFILE), bridgeAction = "APPLY_PROFILE", gated = true
    ),
    /**
     * Opens EVProfile's profile picker and leaves the choice to the driver.
     *
     * The counterpart of [APPLY_PROFILE], for the rules that must not decide alone: arriving
     * at a charger, the profile to apply depends on what the driver is about to do, which no
     * condition reads. Gated like the profile it leads to — EVProfile refuses to put the
     * picker in front of a moving driver, so offering it without the mark would promise a
     * dialog that never appears.
     */
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SHOW_PROFILE_PICKER(
        R.string.act_show_profile_picker, ActionGroup.PROFILE,
        ValueSpec.NONE, bridgeAction = "SHOW_PROFILE_PICKER", gated = true
    ),

    // ── Driving (gated) ──────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_DRIVE_MODE(
        R.string.act_drive_mode, ActionGroup.DRIVING,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.DRIVE_MODES),
        "SET_DRIVE_MODE", gated = true, currentKey = SnapshotKeys.KEY_DRIVE_MODE
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_REGEN_LEVEL(
        R.string.act_regen, ActionGroup.DRIVING,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.REGEN_LEVELS),
        "SET_REGEN_LEVEL", gated = true, currentKey = SnapshotKeys.KEY_REGEN_LEVEL
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ONE_PEDAL(
        R.string.act_one_pedal, ActionGroup.DRIVING,
        ValueSpec.BOOL, "SET_ONE_PEDAL", gated = true
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ENERGY_SAVING(
        R.string.act_energy_saving, ActionGroup.DRIVING,
        ValueSpec.BOOL, "SET_ENERGY_SAVING", gated = true, currentKey = SnapshotKeys.KEY_ENERGY_SAVING
    ),

    // ── Comfort (not gated: does not alter road behaviour) ───────────────────
    @SupportedOn(SWI133, SWI68, SWI165)
    SET_SEAT_HEAT_LEFT(
        R.string.act_seat_heat_l, ActionGroup.COMFORT,
        number(0, 3), "SET_SEAT_HEAT_LEFT", currentKey = SnapshotKeys.KEY_SEAT_HEAT_L
    ),
    @SupportedOn(SWI133, SWI68, SWI165)
    SET_SEAT_HEAT_RIGHT(
        R.string.act_seat_heat_r, ActionGroup.COMFORT,
        number(0, 3), "SET_SEAT_HEAT_RIGHT", currentKey = SnapshotKeys.KEY_SEAT_HEAT_R
    ),
    @SupportedOn(SWI133, SWI68, SWI165)
    SET_STEERING_HEAT(
        R.string.act_steering_heat, ActionGroup.COMFORT,
        ValueSpec.BOOL, "SET_STEERING_HEAT", currentKey = SnapshotKeys.KEY_STEERING_HEAT
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_SCREEN_BRIGHTNESS(
        R.string.act_brightness, ActionGroup.COMFORT,
        number(VehicleEnums.BRIGHTNESS_MIN, VehicleEnums.BRIGHTNESS_MAX, R.string.unit_percent),
        "SET_SCREEN_BRIGHTNESS", currentKey = SnapshotKeys.KEY_BRIGHTNESS
    ),

    // ── Climate (vendor service — com.evsuite.hardware.saic.SaicClimate) ─────────
    // These are the calls the car's own HVAC screen makes, so what they do is not in doubt.
    // Not gated: changing the cabin temperature does not alter road behaviour, and a rule
    // that pre-heats on the motorway is a legitimate one.
    //
    // Two paths behind one name: the vendor hub on SWI68/SWI165, and `carapi`'s CarHvacClient
    // on the A9 generations, which have no such hub (`A9Climate`). The exceptions are ECON and
    // the passenger's own target — the A9 client exposes neither, so those two stay where they
    // were rather than being offered on a car that would refuse them.
    @SupportedOn(SWI68, SWI165, SWI69, SWI131, SWI132)
    SET_CLIMATE_POWER(
        R.string.act_climate_power, ActionGroup.CLIMATE,
        ValueSpec.BOOL, "SET_CLIMATE_POWER", currentKey = SnapshotKeys.KEY_CLIMATE_ON
    ),
    @SupportedOn(SWI68, SWI165, SWI69, SWI131, SWI132)
    SET_CABIN_TEMP(
        R.string.act_cabin_temp, ActionGroup.CLIMATE,
        number(VehicleEnums.CABIN_TEMP_MIN, VehicleEnums.CABIN_TEMP_MAX, R.string.unit_celsius),
        "SET_CABIN_TEMP", currentKey = SnapshotKeys.KEY_TEMPERATURE_SET
    ),
    /**
     * The passenger side's target, where [SET_CABIN_TEMP] is the driver's.
     *
     * A second action rather than a zone argument on the first: the driver's target is the
     * one every existing rule sets, and giving it a zone would have made every saved rule
     * carry a value it never chose.
     */
    @SupportedOn(SWI68, SWI165)
    SET_PASSENGER_TEMP(
        R.string.act_passenger_temp, ActionGroup.CLIMATE,
        number(VehicleEnums.CABIN_TEMP_MIN, VehicleEnums.CABIN_TEMP_MAX, R.string.unit_celsius),
        "SET_PASSENGER_TEMP", currentKey = SnapshotKeys.KEY_PASSENGER_TEMP
    ),
    @SupportedOn(SWI68, SWI165, SWI69, SWI131, SWI132)
    SET_AC(
        R.string.act_ac, ActionGroup.CLIMATE,
        ValueSpec.BOOL, "SET_AC", currentKey = SnapshotKeys.KEY_AC_ON
    ),
    @SupportedOn(SWI68, SWI165)
    SET_ECON(
        R.string.act_econ, ActionGroup.CLIMATE,
        ValueSpec.BOOL, "SET_ECON", currentKey = SnapshotKeys.KEY_ECON
    ),
    @SupportedOn(SWI68, SWI165, SWI69, SWI131, SWI132)
    SET_CLIMATE_AUTO(
        R.string.act_climate_auto, ActionGroup.CLIMATE,
        ValueSpec.BOOL, "SET_CLIMATE_AUTO", currentKey = SnapshotKeys.KEY_HVAC_AUTO
    ),
    @SupportedOn(SWI68, SWI165, SWI69, SWI131, SWI132)
    SET_RECIRCULATION(
        R.string.act_recirc, ActionGroup.CLIMATE,
        ValueSpec.BOOL, "SET_RECIRCULATION", currentKey = SnapshotKeys.KEY_RECIRC
    ),
    @SupportedOn(SWI68, SWI165, SWI69, SWI131, SWI132)
    SET_FAN_LEVEL(
        R.string.act_fan_level, ActionGroup.CLIMATE,
        number(0, VehicleEnums.FAN_LEVEL_MAX), "SET_FAN_LEVEL", currentKey = SnapshotKeys.KEY_FAN_SPEED
    ),
    @SupportedOn(SWI68, SWI165, SWI69, SWI131, SWI132)
    SET_FRONT_DEFROST(
        R.string.act_front_defrost, ActionGroup.CLIMATE,
        ValueSpec.BOOL, "SET_FRONT_DEFROST", currentKey = SnapshotKeys.KEY_FRONT_DEFROST
    ),
    @SupportedOn(SWI68, SWI165, SWI69, SWI131, SWI132)
    SET_REAR_DEFROST(
        R.string.act_rear_defrost, ActionGroup.CLIMATE,
        ValueSpec.BOOL, "SET_REAR_DEFROST", currentKey = SnapshotKeys.KEY_REAR_DEFROST
    ),

    /**
     * Sends one glass command to all four windows.
     *
     * **The value is a command in 0..7, not a position.** The service accepts nothing above 7
     * and drops what it will not accept in silence, so the percentage this used to carry was
     * discarded on every write above 7 while the history said the action had been applied.
     * The reading stays a percentage — [SnapshotKeys.KEY_WINDOW_PERCENT] is what the car
     * reports back — which is what makes a command identifiable: send one, read the position.
     *
     * Which command opens and which closes is not written down anywhere on the firmware and
     * no head-unit application sends one, so the whole action is standstill-gated: a write
     * whose direction is unknown is not a write to allow at speed.
     */
    @SupportedOn(SWI68, SWI165)
    SET_WINDOWS(
        R.string.act_windows, ActionGroup.CLIMATE,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.WINDOW_COMMANDS), "SET_WINDOWS",
        currentKey = null,
        gated = true, writeProven = false
    ),
    /**
     * One window's glass command — the counterpart of [SET_WINDOWS], same 0..7 scale.
     *
     * Both are worth having. Commanding the glass as one gesture belongs in one action that
     * cannot leave three answered and one forgotten; addressing the driver's window alone
     * cannot be written with an all-or-nothing call.
     */
    @SupportedOn(SWI68, SWI165)
    SET_WINDOW_DRIVER(
        R.string.act_window_driver, ActionGroup.CLIMATE,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.WINDOW_COMMANDS), "SET_WINDOW_DRIVER",
        currentKey = null,
        gated = true, writeProven = false
    ),
    @SupportedOn(SWI68, SWI165)
    SET_WINDOW_PASSENGER(
        R.string.act_window_passenger, ActionGroup.CLIMATE,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.WINDOW_COMMANDS), "SET_WINDOW_PASSENGER",
        currentKey = null,
        gated = true, writeProven = false
    ),
    @SupportedOn(SWI68, SWI165)
    SET_WINDOW_REAR_LEFT(
        R.string.act_window_rear_left, ActionGroup.CLIMATE,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.WINDOW_COMMANDS), "SET_WINDOW_REAR_LEFT",
        currentKey = null,
        gated = true, writeProven = false
    ),
    @SupportedOn(SWI68, SWI165)
    SET_WINDOW_REAR_RIGHT(
        R.string.act_window_rear_right, ActionGroup.CLIMATE,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.WINDOW_COMMANDS), "SET_WINDOW_REAR_RIGHT",
        currentKey = null,
        gated = true, writeProven = false
    ),
    @SupportedOn(SWI68, SWI165)
    SET_DOOR_LOCK(
        R.string.act_door_lock, ActionGroup.CLIMATE,
        ValueSpec.BOOL, "SET_DOOR_LOCK", currentKey = SnapshotKeys.KEY_DOORS_LOCKED
    ),

    // ── Energy (vendor service — com.evsuite.hardware.saic.SaicCharging) ─────────
    @SupportedOn(SWI68, SWI165)
    SET_CHARGE_LIMIT(
        R.string.act_charge_limit, ActionGroup.ENERGY,
        number(VehicleEnums.CHARGE_LIMIT_MIN, VehicleEnums.CHARGE_LIMIT_MAX, R.string.unit_percent),
        "SET_CHARGE_LIMIT", currentKey = SnapshotKeys.KEY_CHARGE_LIMIT
    ),
    @SupportedOn(SWI68, SWI165)
    SET_CHARGING_ENABLED(
        R.string.act_charging_enabled, ActionGroup.ENERGY,
        ValueSpec.BOOL, "SET_CHARGING_ENABLED"
    ),
    @SupportedOn(SWI68, SWI165)
    SET_CHARGE_SCHEDULE(
        R.string.act_charge_schedule, ActionGroup.ENERGY,
        ValueSpec.BOOL, "SET_CHARGE_SCHEDULE", currentKey = SnapshotKeys.KEY_CHARGE_SCHEDULE
    ),
    /**
     * The scheduled charging window. A range rather than two actions: start and stop are
     * four separate vehicle signals, and setting only half of them leaves the car with a
     * window nobody intended.
     */
    @SupportedOn(SWI68, SWI165)
    SET_CHARGE_WINDOW(
        R.string.act_charge_window, ActionGroup.ENERGY,
        ValueSpec(ValueKind.TIME_RANGE), "SET_CHARGE_WINDOW"
    ),
    @SupportedOn(SWI68, SWI165)
    SET_BATTERY_PREHEAT(
        R.string.act_battery_preheat, ActionGroup.ENERGY,
        ValueSpec.BOOL, "SET_BATTERY_PREHEAT", currentKey = SnapshotKeys.KEY_BATTERY_PREHEAT
    ),

    // ── Audio ────────────────────────────────────────────────────────────────
    // Everything below the media volume goes through the SAIC `caradapter` audio helper,
    // which EVHardware binds only on the A9 platform (SWI69 / SWI131 / SWI132). These
    // entries used to be annotated SWI133 + SWI132, read off `hasAudioControl()` — but that
    // predicate describes the door-volume feature, a different thing. The effect was an
    // action offered on SWI133, where the helper is never bound and the write silently
    // returns false, and hidden on SWI69 / SWI131, where it works.
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_MEDIA_VOLUME(
        R.string.act_media_volume, ActionGroup.AUDIO,
        ValueSpec.dynamicNumber(0, VehicleEnums.MEDIA_VOLUME_FALLBACK_MAX),
        "SET_MEDIA_VOLUME", currentKey = SnapshotKeys.KEY_MEDIA_VOLUME
    ),

    /**
     * Moves the volume by a number of steps instead of setting one.
     *
     * The two are not interchangeable. A target is what a rule wants at ignition — "start the
     * day at 12". A step is what a *button* wants, and what a rule wants when it must not
     * discard what the driver chose: "two quieter when the phone rings" keeps their setting,
     * where a target throws it away. Negative goes down, and either end of the range is
     * reported as done rather than refused.
     */
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    ADJUST_MEDIA_VOLUME(
        R.string.act_adjust_media_volume, ActionGroup.AUDIO,
        number(-VehicleEnums.MEDIA_VOLUME_FALLBACK_MAX, VehicleEnums.MEDIA_VOLUME_FALLBACK_MAX),
        "ADJUST_MEDIA_VOLUME"
    ),
    @SupportedOn(SWI69, SWI131, SWI132)
    SET_AUDIO_BALANCE(
        R.string.act_balance, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_LEVEL_MIN, VehicleEnums.AUDIO_LEVEL_MAX),
        "SET_AUDIO_BALANCE"
    ),
    @SupportedOn(SWI69, SWI131, SWI132)
    SET_AUDIO_FADER(
        R.string.act_fader, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_LEVEL_MIN, VehicleEnums.AUDIO_LEVEL_MAX),
        "SET_AUDIO_FADER"
    ),
    @SupportedOn(SWI69, SWI131, SWI132)
    SET_TONE_CONTROL(
        R.string.act_tone, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_LEVEL_MIN, VehicleEnums.AUDIO_LEVEL_MAX),
        "SET_TONE_CONTROL"
    ),
    @SupportedOn(SWI69, SWI131, SWI132)
    SET_BOSE_SOUND_TYPE(
        R.string.act_bose, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_TYPE_MIN, VehicleEnums.AUDIO_TYPE_MAX),
        "SET_BOSE_SOUND_TYPE"
    ),
    @SupportedOn(SWI69, SWI131, SWI132)
    SET_3D_EFFECT(
        R.string.act_3d, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_TYPE_MIN, VehicleEnums.AUDIO_TYPE_MAX),
        "SET_3D_EFFECT"
    ),
    @SupportedOn(SWI69, SWI131, SWI132)
    SET_SPEED_VOLUME(
        R.string.act_speed_volume, ActionGroup.AUDIO,
        number(VehicleEnums.AUDIO_TYPE_MIN, VehicleEnums.AUDIO_TYPE_MAX),
        "SET_SPEED_VOLUME"
    ),

    /**
     * Makes radio the current audio source, resuming the last station.
     *
     * Nothing to configure: `srcPlayRadio` takes no argument, and it resumes the last
     * station. [TUNE_RADIO] is the one to use when the station matters.
     *
     * It resumes rather than opening the radio screen: a rule firing at ignition wants the
     * sound, not a screen thrown in front of the driver.
     *
     * Vendor service, so it carries firmware support like any other vehicle entry — the
     * radio is not an ordinary Android app that could be launched instead.
     */
    @SupportedOn(SWI68, SWI165)
    PLAY_RADIO(
        R.string.act_play_radio, ActionGroup.AUDIO,
        ValueSpec.NONE, "PLAY_RADIO"
    ),

    /**
     * Tunes a station, leaving the audio source alone — [PLAY_RADIO] is what starts playback.
     *
     * A free-text frequency rather than a band picker plus a slider: the driver knows their
     * station as "103.5", and an FM slider covering 87.5–108.0 in 50 kHz steps is 410
     * positions to drag past on a touchscreen. The text is parsed leniently — "103.5",
     * "FM 103.5", "103,5", "1080 AM" all land — and a value that parses to nothing is
     * reported as unsupported with what was typed, so the history names the typo.
     *
     * Bands are AM (522–1620 kHz, 9 kHz steps) and FM (87.5–108.0 MHz, 50 kHz steps), from
     * `RadioConstants.AM_RANGE` / `FM_RANGE`. DAB is out: `tuneDab` takes a service and
     * ensemble id, not a frequency, so there is nothing here for a driver to type.
     */
    @SupportedOn(SWI68, SWI165)
    TUNE_RADIO(
        R.string.act_tune_radio, ActionGroup.AUDIO,
        ValueSpec(ValueKind.TEXT, hintRes = R.string.value_radio_hint), "TUNE_RADIO"
    ),

    /**
     * Steps through the tuner's stations, on whichever band it is already on.
     *
     * This is what reaches **DAB**, which [TUNE_RADIO] cannot: a DAB service is addressed by
     * ensemble and service id, so there is no frequency for a driver to type. Stepping asks
     * for neither — the tuner's own list is the right one — which makes "next station" the
     * one radio action that works on all three bands.
     *
     * Separate from [ActionType.MEDIA_CONTROL] on purpose. That one skips a *track* on
     * whatever owns the audio; this one changes *station* whether or not the radio is the
     * current source, which is a different intent and a different service call.
     */
    @SupportedOn(SWI68, SWI165)
    RADIO_NEXT_STATION(
        R.string.act_radio_next_station, ActionGroup.AUDIO,
        ValueSpec.NONE, "RADIO_NEXT_STATION"
    ),

    @SupportedOn(SWI68, SWI165)
    RADIO_PREV_STATION(
        R.string.act_radio_prev_station, ActionGroup.AUDIO,
        ValueSpec.NONE, "RADIO_PREV_STATION"
    ),

    /**
     * Puts the tuner on a band — AM, FM or **DAB**.
     *
     * The one radio action that reaches DAB deliberately. [TUNE_RADIO] cannot: it takes a
     * frequency a driver typed, and a DAB *service* is addressed by ensemble and service id,
     * so there is nothing to type. A DAB **block**, on the other hand, is a real frequency in
     * Band III, which is what makes the band reachable at all.
     *
     * No band-only call in the vendor service has been observed, and a guessed transaction
     * code is never sent to a vehicle. `SaicRadio.selectBand` composes established calls
     * instead — read the current band from `RadioBean`, then `tune` into the requested one,
     * since `tune` carries the band as its first argument. It lands on the station this car
     * was last heard on for that band, or steps to the first one the tuner lists when it has
     * never been there.
     *
     * Audio-only, so it is not gated: changing band is what the wheel's source button already
     * does at speed.
     */
    @SupportedOn(SWI68, SWI165)
    SELECT_RADIO_BAND(
        R.string.act_select_radio_band, ActionGroup.AUDIO,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.RADIO_BANDS), "SELECT_RADIO_BAND"
    ),

    /**
     * Silences the tuner — `srcPauseRadio`, the counterpart of [PLAY_RADIO].
     *
     * Not [MEDIA_CONTROL]'s play/pause, which addresses whichever source owns the audio and
     * would therefore stop Bluetooth when Bluetooth is the one playing. This one names the
     * radio, so a rule that silences the news on arrival silences the news.
     *
     * It mutes rather than handing the audio focus back: the vendor service only abandons
     * focus when it loses it, so whatever played before the radio does not resume on its own.
     * Silence is the most the service offers, and the label says no more than that.
     */
    @SupportedOn(SWI68, SWI165)
    PAUSE_RADIO(
        R.string.act_pause_radio, ActionGroup.AUDIO,
        ValueSpec.NONE, "PAUSE_RADIO"
    ),

    /**
     * Toggles the tuner between playing and silent, on the state the tuner reports.
     *
     * One entry for a driver who has one button and wants the radio specifically — again not
     * [MEDIA_CONTROL], which follows the current source wherever it went.
     *
     * The direction is read, never assumed. `AudioManager.isMusicActive` is **false while the
     * radio plays**, its stream not being the music one, so the obvious substitute would send
     * "play" to a playing radio forever; `SaicRadio.isPlaying` reads `RadioBean.state`
     * instead. When even that cannot be read the action sends nothing and reports why: a
     * media shortcut can be pressed a second time, an unattended rule cannot, and a toggle
     * that guessed wrong leaves the car silent — or playing — with nothing saying the
     * direction was invented.
     */
    @SupportedOn(SWI68, SWI165)
    RADIO_PLAY_PAUSE(
        R.string.act_radio_play_pause, ActionGroup.AUDIO,
        ValueSpec.NONE, "RADIO_PLAY_PAUSE"
    ),

    /**
     * Brings the radio screen up — `startRadioActivity`, a different intent from playing.
     *
     * The one radio action that is not audio-only, and the only one that is [gated]. Skipping
     * a station changes what comes out of the speakers; this replaces what is on the screen,
     * and a full-screen app thrown in front of a driver at 110 km/h takes their eyes off the
     * road for as long as it takes to understand what happened. It follows the standstill
     * gate for the same reason the glass does, refusal on an unreadable speed included —
     * which is the gate the rest of the audio family is deliberately outside of, and stays
     * outside of.
     */
    @SupportedOn(SWI68, SWI165)
    OPEN_RADIO_SCREEN(
        R.string.act_open_radio_screen, ActionGroup.AUDIO,
        ValueSpec.NONE, "OPEN_RADIO_SCREEN", gated = true
    ),

    // ── ADAS (gated) ─────────────────────────────────────────────────────────
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_AEB_ENABLED(
        R.string.act_aeb, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_AEB_ENABLED", gated = true, currentKey = SnapshotKeys.KEY_AEB_ENABLED
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_AEB_MODE(
        R.string.act_aeb_mode, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.AEB_MODES),
        "SET_AEB_MODE", gated = true, currentKey = SnapshotKeys.KEY_AEB_MODE
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_AEB_SENSITIVITY(
        R.string.act_aeb_sensitivity, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.SENSITIVITIES),
        "SET_AEB_SENSITIVITY", gated = true, currentKey = SnapshotKeys.KEY_AEB_SENSITIVITY
    ),

    /**
     * Electronic stability control.
     *
     * Gated like every driving write, and refused by EVHardware for a second reason of its
     * own: the write is a **toggle** driven by a read, so it only runs on an ignition known to
     * be in RUN and on three agreeing readings. Getting in while the cluster is still dark,
     * the property does not yet reflect reality, and aiming at ON from a false OFF turns off
     * an ESC that was on — silently. A rule that wants it off is simply honoured a moment
     * later, once the car is awake.
     */
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ESC(
        R.string.act_esc, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_ESC", gated = true, currentKey = SnapshotKeys.KEY_ESC
    ),

    /**
     * The drowsiness warning — UDW, the one the car raises when the driving gets unsteady.
     *
     * Deliberately the UDW switch and not the camera-based DMS one: both exist, their labels
     * read alike, and writing the camera one changed nothing visible.
     */
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_DROWSINESS(
        R.string.act_drowsiness, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_DROWSINESS", gated = true, currentKey = SnapshotKeys.KEY_DROWSINESS
    ),

    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_DROWSINESS_SENSITIVITY(
        R.string.act_drowsiness_sensitivity, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.SENSITIVITIES),
        "SET_DROWSINESS_SENSITIVITY", gated = true,
        currentKey = SnapshotKeys.KEY_DROWSINESS_SENSITIVITY
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ELK_MODE(
        R.string.act_elk_mode, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.ELK_MODES),
        "SET_ELK_MODE", gated = true, currentKey = SnapshotKeys.KEY_ELK_MODE
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ELK_SENSITIVITY(
        R.string.act_elk_sensitivity, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.SENSITIVITIES),
        "SET_ELK_SENSITIVITY", gated = true, currentKey = SnapshotKeys.KEY_ELK_SENSITIVITY
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_ACC_TJA_MODE(
        R.string.act_acc_tja, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.ACC_TJA_MODES),
        "SET_ACC_TJA_MODE", gated = true, currentKey = SnapshotKeys.KEY_ACC_TJA_MODE
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_LIMITER_MODE(
        R.string.act_limiter, ActionGroup.ADAS,
        ValueSpec(ValueKind.ENUM, options = VehicleEnums.LIMITER_MODES),
        "SET_LIMITER_MODE", gated = true, currentKey = SnapshotKeys.KEY_LIMITER_MODE
    ),
    @SupportedOn(SWI133, SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_TSR(
        R.string.act_tsr, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_TSR", gated = true, currentKey = SnapshotKeys.KEY_TSR
    ),
    @SupportedOn(SWI133, SWI132)
    SET_OVERSPEED_ALARM(
        R.string.act_overspeed, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_OVERSPEED_ALARM", gated = true, currentKey = SnapshotKeys.KEY_OVERSPEED_ALARM
    ),
    @SupportedOn(SWI133, SWI132)
    SET_SPEED_LIMIT_TONE(
        R.string.act_speed_limit_tone, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_SPEED_LIMIT_TONE", gated = true, currentKey = SnapshotKeys.KEY_SPEED_LIMIT_TONE
    ),
    @SupportedOn(SWI132, SWI68, SWI69, SWI131, SWI165)
    SET_SOUND_WARNING(
        R.string.act_sound_warning, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_SOUND_WARNING", gated = true, currentKey = SnapshotKeys.KEY_SOUND_WARNING
    ),
    @SupportedOn(SWI132)
    SET_LAS_WARNING_SOUND(
        R.string.act_las_sound, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_LAS_WARNING_SOUND"
    ),
    @SupportedOn(SWI132)
    SET_LAS_WARNING_VIBRATION(
        R.string.act_las_vibration, ActionGroup.ADAS,
        ValueSpec.BOOL, "SET_LAS_WARNING_VIBRATION"
    ),

    // ── System (local, no vehicle access, firmware-independent) ──────────────
    LAUNCH_APP(
        R.string.act_launch_app, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.APP), bridgeAction = null
    ),
    SHOW_NOTIFICATION(
        R.string.act_notify, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.TEXT), bridgeAction = null
    ),
    /**
     * Speaks the text through the platform TTS engine. Local like the two above: nothing
     * is written to the vehicle, so no firmware annotation and no bridge action.
     */
    SPEAK_TEXT(
        R.string.act_speak, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.TEXT), bridgeAction = null
    ),
    /**
     * Hands a destination to whatever navigation app the head unit has, through the standard
     * `geo:` intent. Local and firmware-independent: no vendor service is involved, and the
     * MG4's navigation is not part of the SAIC vehicle SDK.
     *
     * [Action.text] is an address or "latitude,longitude".
     */
    NAVIGATE_TO(
        R.string.act_navigate, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.DESTINATION), bridgeAction = null
    ),
    /**
     * Puts a yes/no question on screen and stops the rule there when the answer is no.
     *
     * The only action whose result decides whether the rest of the rule runs. That is the
     * point of it: a rule that opens the windows or unlocks the doors on arrival is right
     * most of the time and wrong the once, and the driver is the only one who knows which
     * this is. Placed first, it turns an automatic rule into a proposed one.
     *
     * `Action.text` is the question and `Action.number` how many seconds it waits. No answer
     * within that time counts as no — the rest of the rule needs a deliberate yes, not a
     * driver who walked away. `0` means [ASK_CONFIRM_DEFAULT_SECONDS]: rules saved before the
     * wait was configurable carry no value, and the field they never set must not read as an
     * instant refusal.
     */
    ASK_CONFIRM(
        R.string.act_ask_confirm, ActionGroup.SYSTEM,
        ValueSpec(
            ValueKind.CONFIRM,
            min = 5, max = 60,
            unitRes = R.string.unit_second, hintRes = R.string.act_ask_confirm_hint
        ),
        bridgeAction = null
    ),
    /**
     * Sends a text message through the paired phone.
     *
     * Same reasoning as [CALL_NUMBER], one profile further out: the head unit has no SIM, so
     * there is nothing here to send from. The vendor hands-free service does not help either
     * — its `IBtCall` interface is calls only, with no message transaction to address — so
     * the message goes out over the Bluetooth Message Access Profile, which the car's own
     * Bluetooth settings manage as "MAP Client". Whether that profile is up is a bind, not a
     * table: the action reports itself unsupported on a car or a phone that does not carry it.
     *
     * `Action.text` is the number, `Action.displayName` the contact it was picked from, and
     * `Action.payload` the message.
     *
     * Not standstill-gated, for the reason a call is not: it writes nothing to the vehicle,
     * and the message was written when the rule was, not at the wheel.
     */
    SEND_SMS(
        R.string.act_send_sms, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.SMS, hintRes = R.string.act_send_sms_hint), bridgeAction = null
    ),
    /**
     * Turns another of the user's rules on, or off.
     *
     * The cheapest thing in the catalogue that changes what rules can express: a rule that
     * only applies during a trip is one rule enabling a second at departure and disabling it
     * on arrival, with no state to store and nothing new to evaluate. `Action.text` is the
     * target rule's id, and a rule that no longer exists is reported as unsupported — never
     * silently skipped, because a chain whose middle link vanished is exactly what a user
     * needs told.
     *
     * A rule cannot disable itself into a state it can never leave: the switch is the same one
     * the rule list shows, so the user can always put it back.
     */
    ENABLE_RULE(
        R.string.act_enable_rule, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.RULE), bridgeAction = null
    ),
    DISABLE_RULE(
        R.string.act_disable_rule, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.RULE), bridgeAction = null
    ),
    /**
     * Play/pause, next or previous — sent to the source that is actually playing.
     *
     * Not a media key, which is the implementation this started as. The car publishes one
     * Android media session (`com.android.bluetooth`), so the key either reaches nothing or
     * reaches Bluetooth, wakes a phone that was not playing and **changes the audio source**
     * while the radio was on. `SaicMediaPlayer` asks the vendor service which source owns the
     * audio and commands that source's own player, falling back to media sessions on the A9
     * generations, which have no such service.
     *
     * The values are unchanged (85/87/88, the Android key codes): saved rules store the
     * number, and repurposing them would silently redefine every existing rule.
     */
    MEDIA_CONTROL(
        R.string.act_media_control, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.ENUM, options = MediaCommand.OPTIONS), bridgeAction = null
    ),
    /**
     * The head unit's own radios.
     *
     * Turning Bluetooth off ends the hands-free link, and with it every Bluetooth condition —
     * a rule that switches it off and then asks which phone is on board gets "unreadable",
     * which is the truth.
     */
    SET_BLUETOOTH(
        R.string.act_set_bluetooth, ActionGroup.SYSTEM,
        ValueSpec.BOOL, bridgeAction = null
    ),
    SET_WIFI(
        R.string.act_set_wifi, ActionGroup.SYSTEM,
        ValueSpec.BOOL, bridgeAction = null
    ),
    /**
     * Calls an HTTP(S) endpoint. [Action.flag] carries the verb — false for GET, true for
     * POST — and [Action.payload] is the POST body, ignored for GET.
     */
    WEBHOOK(
        R.string.act_webhook, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.WEBHOOK), bridgeAction = null
    ),

    /**
     * Waits, in seconds, before the rule's next action runs.
     *
     * A rule's actions are executed one after the other, so a rule that switches the climate
     * on and then sets the fan level asks for the second value while the car is still acting
     * on the first — and the vehicle answers from the state it had. This is the pause between
     * the two, placed by the user where they know one write needs to land before the next.
     *
     * Bounded to a minute: the wait holds the cycle thread, and a rule that pauses longer
     * than the ignition transition it reacts to is a rule whose later actions may never run.
     */
    DELAY(
        R.string.act_delay, ActionGroup.SYSTEM,
        number(1, 60, R.string.unit_second), bridgeAction = null
    ),

    /**
     * Calls a number through the car's own hands-free stack.
     *
     * A vehicle action despite looking like a phone one: the head unit has no SIM and no
     * dialer, so the call is placed by the vendor Bluetooth service on the paired handset.
     * `ACTION_CALL` would find nothing to handle it.
     */
    @SupportedOn(SWI68, SWI165)
    CALL_NUMBER(
        R.string.act_call, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.CONTACT), "CALL_NUMBER"
    ),
    /**
     * Kept only so rules saved by releases that exposed a separate contact action still load.
     * New rules use [CALL_NUMBER], whose editor accepts either a number or a contact.
     */
    @Deprecated("Compatibility alias for saved rules; use CALL_NUMBER")
    @SupportedOn(SWI68, SWI165)
    CALL_CONTACT(
        R.string.act_call_contact, ActionGroup.SYSTEM,
        ValueSpec(ValueKind.CONTACT), "CALL_CONTACT"
    );

    companion object {
        @Suppress("DEPRECATION")
        fun byGroup(): Map<ActionGroup, List<ActionType>> =
            entries.filterNot { it == CALL_CONTACT }.groupBy { it.group }

        /**
         * The wait [ASK_CONFIRM] uses when its action carries none.
         *
         * The spec's own floor (5 s) is not zero: a question that closes before it can be
         * read is a refusal dressed as a choice. Its ceiling (60 s) holds a rule's cycle for
         * at most a minute, the same order as the delay budget the engine already enforces.
         */
        const val ASK_CONFIRM_DEFAULT_SECONDS = 10
    }
}

/**
 * What [ActionType.MEDIA_CONTROL] sends.
 *
 * The values are the platform's own media key codes, so the runner has nothing to translate
 * and a wrong constant cannot become a different key by accident. Only the three a driver
 * asks a rule for: stop and rewind are the wheel's job, not a rule's.
 */
object MediaCommand {
    const val PLAY_PAUSE = 85
    const val NEXT = 87
    const val PREVIOUS = 88

    val OPTIONS = listOf(
        EnumOption(PLAY_PAUSE, R.string.media_play_pause),
        EnumOption(NEXT, R.string.media_next),
        EnumOption(PREVIOUS, R.string.media_previous)
    )
}
