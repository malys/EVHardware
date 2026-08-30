# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Vehicle-validated telemetry is catalogued per signal and firmware generation.** Battery
  power starts unvalidated everywhere; CP-003 must add each generation only after its unit,
  scale, sign and cadence have on-vehicle evidence. Consumers can therefore distinguish an
  unproven signal from a property that is genuinely absent.

## [1.11.0] - 2026-08-29

### Added

- **A rule can put the tuner on a band, DAB included.** `SELECT_RADIO_BAND` is the answer to
  the one gap the radio work left: "tune the radio" takes a frequency a driver typed, and a DAB
  service is addressed by an ensemble and service id, so there was nothing to type and no way
  to ask for DAB. The action guesses no transaction code — the vendor service exposes no
  band-only call that anyone here has observed, and a wrong code on a vehicle binder does
  something unknown to a car. It composes calls that are already proven instead: read the
  current band from `RadioBean`, then `tune` into the requested one, since `tune` carries the
  band as its first argument. It lands on the station this car was last heard on for that band,
  and when it has never been there it steps to the first station the tuner lists rather than
  leaving the driver on an empty frequency. An unreadable current band sends nothing at all.

### Changed

- **The window actions ask to open or close, instead of carrying a raw command.** They used to
  take a number in 0..7 — the vendor service's own command range, which nothing in the app or
  the car explains to the person writing the rule. An on-vehicle capture showed what that
  costs: a rule asking for `7` ran five times, was reported as applied five times, and left the
  driver's window fully open throughout, because the service accepts a command it does not
  implement and drops it. Closed and open are the only two states the glass can be asked for,
  so those are the two the editor now offers.
  Which raw command reaches each is a property of the *car*, established by the window probe and
  stored per car, so it stays out of the catalogue and out of saved rules — a rule exported from
  one car must not carry that car's command numbers.

- **"Windows" is now "All windows".** It commands four windows at once, and the bare plural read
  as the family of window actions rather than the one that moves them together.

## [1.10.0] - 2026-08-29

### Fixed

- **A property the firmware declares and never publishes no longer reads as a measurement.**
  `CarPropertyManager.getFloatProperty` answers such a property with a plain zero, and nothing
  in the number says the vehicle never put a value behind it — an MG4 on SWI68 reporting 31 °C
  outside showed a 0 °C traction battery and a 0 kW pack current on the dashboard next to it.
  Reads now go through `getProperty`, whose `CarPropertyValue` carries the VHAL's status for
  the property, and only `STATUS_AVAILABLE` is treated as a reading; anything else stays null
  and reaches the screen as "unknown", which is what it is. A measured zero is still a zero.
  Runtimes that do not expose `getProperty` fall back to the typed getters unchanged, so no
  generation loses a property it was already answering.

### Added

- **Automatic trip boundaries are now one fail-closed telemetry state machine.**
  `TripDetector` requires five seconds above 5 km/h to start and two minutes below 1 km/h
  to end, using park or charge-port confirmation when those signals exist. Missing speed
  cancels partial evidence and can never start or stop a recording; all timers and threshold
  bounce behaviour are covered without Android or a vehicle.

- **Adaptive range is now an explicit estimate with evidence-sized uncertainty.**
  `AdaptiveRangeEstimator` fits up to eight recent trip-consumption averages, requires three
  usable observations, and converts their sample spread into a kilometre uncertainty band.
  It prefers measured usable energy, falls back to SOC times capacity, and refuses to estimate
  when the current firmware does not publish battery power; the vehicle's own range is untouched.

- **Consumption is now one shared, nullable derivation instead of UI arithmetic.**
  `ConsumptionCalculator` exposes raw and five-second exponentially smoothed instantaneous
  kWh/100 km plus a two-minute energy/distance average. It masks missing inputs and speeds
  below 5 km/h, preserves negative regeneration, rejects unobserved gaps over five seconds,
  and bounds its working set independently of trip length. Every output is provenance-labelled;
  the existing trip accumulator continues to integrate raw power and speed unchanged.

- **Trip history can be pruned without bypassing its atomic persistence boundary.**
  `EnergyTripHistoryStore` can remove one start-time-identified trip or replace the history with
  a valid empty v2 envelope; both operations preserve the same unique-temp-file rewrite used by
  appends, so a UI never deletes the only valid file before its replacement exists. The default
  summary bound is 200 trips; the existing byte ceiling still drops old sample tracks first.

- **A public post-AAOS 9 capacity signal can be tested without becoming product data.**
  `EV_CURRENT_BATTERY_CAPACITY` describes real-time usable capacity rather than the pack's nominal
  new capacity, but a public id does not prove that an MG4 firmware publishes it. The new
  status-aware, firmware-branched reader is therefore internal to explicit evidence capture,
  stays null when absent or invalid, and is carried beside — never inside — `EnergySnapshot`.
  [The candidate review](docs/energy-signal-candidates.md) records exact AOSP provenance and the
  negative search results for HVAC, battery-heater, accessory and cumulative energy counters.

- **Three radio behaviours the library could already perform are now things a rule can ask for.**
  `srcPauseRadio`, the tuner's own play state and `startRadioActivity` were implemented and
  unreachable: the catalogue offered play, tune and station stepping, so "silence the radio"
  had to be approximated with `MEDIA_CONTROL`, which addresses whichever source owns the audio
  and therefore stops Bluetooth when Bluetooth is the one playing. `PAUSE_RADIO`,
  `RADIO_PLAY_PAUSE` and `OPEN_RADIO_SCREEN` name the tuner instead. The toggle reads
  `RadioBean.state` rather than `AudioManager.isMusicActive`, which is **false while the radio
  plays** — a toggle driven by it would answer a playing radio with another "play", forever —
  and when even that cannot be read it sends nothing and says so: a wheel button can be pressed
  twice, an unattended rule cannot, and a guessed direction leaves the car silent or playing
  with nothing in the history admitting the direction was invented. Opening the radio screen is
  the one of the three that is not audio-only, so it takes the standstill gate, unreadable
  speed included; skipping a station does not, and did not gain it. Band selection and direct
  DAB service selection were considered and rejected — see
  [docs/radio-action-gap.md](docs/radio-action-gap.md), which records every candidate including
  the refused ones. DAB remains reachable through station stepping, which is what the head
  unit's own launcher calls.

- **A trip now keeps its shape, not only its totals.** A summary cannot be un-summarised, and
  every model this project will grow — what a slower motorway speed would have saved, what the
  climate system took, the state of charge on arrival — is fitted from the shape of past drives.
  `TripSampleTrack` records one sample per five seconds of speed, power, state of charge,
  temperatures and climate state, all nullable, so a car that publishes no pack temperature
  stores no pack temperature rather than a column of zeros a fit would believe. A drive that
  outlasts the track's length is decimated rather than truncated: every second sample goes and
  the interval doubles, so the motorway stretch at the end survives at a coarser resolution
  instead of being cut off.

### Changed

- **The trip history file states its own version.** The first shape was a bare JSON array with
  no room to say what it was; it is read as before and rewritten into the v2 envelope on the
  next append, losing nothing. A file this build cannot read — truncated, or written by a
  build that knows something this one does not — is quarantined under a new name rather than
  deleted, because it is the only copy of whatever it holds. Three bounds now apply in the
  order that keeps the most meaning per byte: at most fifty trips, then sample tracks dropped
  oldest-first to stay under the size ceiling, and only then whole trips — a trip without its
  track is still a trip, a missing trip is not.
- `EnergyTripSession.stop` returns a `RecordedTrip` (summary plus track) rather than a bare
  summary, and `EnergyTripHistoryStore.read()` returns stored trips; `readSummaries()` is the
  old view.

- **`TelemetryEvidenceRecorder` — what a signal actually did on the car, instead of what the
  code assumes it does.** Every energy figure past a state of charge divides by a number whose
  unit, sign and update rate are currently a hypothesis: the battery power reader divides the raw
  property by -1,000,000 because that is what the value looked like, not because a vehicle
  confirmed it. The recorder consumes the same snapshots the dashboard renders and keeps, per
  signal, how often it answered and how often it did not, its range and mean, its positive and
  negative split, and the interval between the values it actually published — measured from value
  *changes*, so a 1 Hz sampler over a signal republished every five seconds reports five seconds
  rather than one. A signal the car never answered keeps no statistics at all: no minimum of
  zero, no mean of zero, nothing. Captures serialise to a versioned schema for the next build and
  render as a Markdown table for the person writing the conclusion.

- **`Provenanced` — a value that carries what kind of claim it is.** The MG4 publishes no
  per-consumer energy counters, so anything past traction totals — the climate system's share,
  what a slower motorway speed would have saved, the state of charge on arrival — can only be
  inferred, and an inference rendered in the same typeface as a vehicle reading is a
  measurement as far as the driver is concerned. `Provenance` names the four kinds a figure can
  be (measured, derived, estimated, unavailable) and `Provenanced<T>` enforces them at
  construction: an estimate cannot exist without its uncertainty band, an unavailable value
  cannot carry a number, and a measurement cannot carry a band the vehicle never published.
  `Provenanced.derive` propagates gaps rather than filling them, so consumption over an
  unreadable speed is unknown — with `UnavailableReason` saying whether the firmware lacks the
  signal, has not been validated for it, or simply is not publishing it right now.

- **`EVHardware.probeTelemetryProperties()` — what each energy property actually answers on the
  car in front of you.** An unsupported property, one declared and never published, and one
  this runtime cannot reach all look identical from the driver's seat, where every unusable
  signal is one em dash. The probe reports status, raw value and publication timestamp per
  property, unfiltered and outside the supported-generation gate, so a generation's telemetry
  surface can be recorded from the vehicle instead of inferred from a blank field.

### Changed

- **A read that fails the same way every second is worth one log line, not one per sample.**
  One unreadable speed property wrote two identical lines a second, filled the 400-entry buffer
  and pushed everything else out of it, so the diagnostics screen was least useful exactly when
  something was wrong. A repeated failure is logged again only when its reason changes.
  Reflected failures also name their cause: `InvocationTargetException` carries no message of
  its own, which is why these lines used to read `exc: null`.

## [1.9.0] - 2026-08-28

### Fixed

- **The glass probe refused to run on a car whose ignition was on.** It read
  `getCurrentIgnitionState()`, which answers on the AAOS `IgnitionState` scale, against
  `CarIgnitionItem.RUN` — and the two scales do not line up: RUN is 2 for Katman5, where 2 is
  OFF. Contact mis read as 4 and was refused; contact coupé read as 2 and was let through.
  `setEsc` compared the same two scales and now refuses only a *known* non-RUN ignition, as its
  own documentation always said it did.
- **Window actions moved nothing.** They were written through the vendor hub's
  `setDriveWindow`, a setter no application on the head unit calls, whose accepted values
  nothing explains and whose dropped writes are indistinguishable from obeyed ones.

### Added

- **`VsmGlass` — the windows through the interface the car's own window switch uses.**
  `setVehicleWindowStatus(area, command)` and `getVehicleWindowValue(area)`, on the VSM already
  bound for ADAS. The commands are established rather than guessed: `STOP`, `UP` and `AUTO_UP`.
  `UP` is a switch held down and not an order — the glass travels only while the command keeps
  arriving, which is why a single write looked exactly like a command that does nothing, and
  why `hold()` re-sends it and then releases it. Reads answer 0–255, not the hub's 0–100, and a
  motor without a position sensor answers off that scale and is reported as no reading rather
  than as a closed window. VSM-based firmwares only; the hub stays the fallback.

### Changed

- **`SaicVehicleControl` prefers the VSM for every glass read and write**, falling back to the
  hub only where the VSM does not carry the methods. `GlassProbe` holds each command down for
  `HOLD_MS` instead of writing it once, which is the only way a momentary command shows up in a
  position read at all.

## [1.8.0] - 2026-08-27

### Added

- **`SaicMediaPlayer` — next track, previous track and play/pause sent to the source that is
  actually playing.** The car publishes a single Android media session
  (`com.android.bluetooth`), so a media key either reached nothing or reached Bluetooth, woke a
  phone that was not playing and changed the audio source while the radio was on. The new
  object asks the vendor service which source owns the audio and commands that source's own
  player — radio, Bluetooth, USB, online, CarPlay/Android Auto — with **no cascade between
  sources**: a player that is not playing is never addressed, because it would answer "yes" and
  start. The A9 generations have no such service and drive the framework's media sessions
  instead, then allgo's `IRemoteUIService` for projection, then a media key only while
  something is audible.
- **`SaicRadio.nextStation()` / `previousStation()` / `isPlaying()`.** Stepping through the
  tuner's stations is the one radio call that reaches **DAB**: a DAB service is addressed by
  ensemble and service id, so `tune` has no frequency to take. `isPlaying` unwinds the vendor
  `RadioBean` because `AudioManager.isMusicActive` is false while the radio plays — its stream
  is not the music one — which made play/pause answer a playing radio with another "play".
- **`ActionType.RADIO_NEXT_STATION` / `RADIO_PREV_STATION`** (AUDIO, SWI68/SWI165).
- **`GlassProbe` — settles on a car what the catalogue could not settle in the project.**
  Which of the eight window commands opens a window is written down nowhere and the vendor
  service reports success whatever it is given, so the glass actions ship refused
  (`writeProven = false`). The probe sends each command with the position read either side of
  it, in **two passes** — from a closed window a closing command is indistinguishable from an
  inert one, so the second pass retries exactly those once the glass is open — and stops as
  soon as both directions are known. Standstill is re-checked before every command, not once
  at the start, and the glass is put back where it was found or the caller is told it was not.
- **`GlassEvidence`** records what the probe watched happen and unlocks the five window
  actions **on that car only**. Evidence is scoped to the firmware generation it was observed
  on: a command proven on one generation proves nothing about another.
- **ESC and the drowsiness warning (UDW), on every generation.** Three different routes —
  VPM properties on SWI133, named methods on SWI68/SWI165, and the carapi client on A9, where
  ESC is spelled "Eps". ESC on SWI68/SWI165 needed a manager the library did not bind at all:
  `get/setEspSwitch` live on **VehicleControlManager**, not the settings one, so looking for
  them on `sVsm` failed without a sound and left ESC inert.
  Two guards come with the write, both earned on a car: it is a **toggle** driven by a read, so
  it runs only on an ignition known to be in RUN — getting in while the cluster is dark, the
  property lies, and aiming at ON from a false OFF disables an ESC that was on, in silence —
  and only on three agreeing readings, with the result checked afterwards rather than assumed.
- **`ActionType.SET_ESC`, `SET_DROWSINESS`, `SET_DROWSINESS_SENSITIVITY`** (ADAS, gated).
  The drowsiness switch is UDW and not the camera-based DMS one: both exist, their labels read
  alike, and writing the camera one changed nothing visible.
- **`A9Climate` — climate on SWI69, SWI131 and SWI132.** The vendor hub `SaicClimate` talks
  to does not exist on those generations, so eight climate actions were marked SWI68/SWI165
  only on cars whose own HVAC screen works fine. They now go through `carapi`'s
  `CarHvacClient` (`queryClient(0x7)`), the same door `queryClient(0x8)` already opens for the
  vehicle-settings client. Several A9 settings are argument-less `switch…()` calls that step to
  the next state, so aiming at one is read-step-read, bounded — which makes those writes
  blocking and unfit for the main thread. ECON and the passenger's own target stay off A9: the
  client exposes neither, and refusing is more honest than writing to the driver's side and
  calling it the passenger's.
- **`ActionType.ADJUST_MEDIA_VOLUME` and `EVHardware.adjustMediaVolume(delta)`** — a step
  rather than a target, for a wheel button or a rule that must not throw away the level the
  driver chose. Falls back to `AudioManager` when the vendor level is unreadable, and reports
  either end of the range as done rather than refused.
- **`PhysicalButtonEventDecoder` reads a double press.** Two releases of the same button
  within `DOUBLE_TAP_MS` make one `Press.DOUBLE`; a long press breaks the pairing so the
  release that follows it cannot become half of a double nobody made, and a third press starts
  over rather than producing a second overlapping double.
- **`DataUsage`** — what the head unit's connection has carried, today and this month, from
  Android's own counters. The modem presents as **Ethernet**, and the public
  `querySummaryForDevice(int, …)` only builds MOBILE and WIFI templates: it answers zero here,
  which reads exactly like a car that used no data. The hidden `NetworkTemplate` overload
  answers, with `TrafficStats` since boot as the fallback, and an unreadable counter is null
  rather than zero.
- **`ConditionType.DATA_USED_TODAY` / `DATA_USED_MONTH`** (context, in MB).
- **`ActionType.effectProven`** — `writeProven` answers "has anyone ever seen this work",
  `effectProven` answers "does it work here". Everything user-facing asks the second.

### Changed

- **An action whose write was never shown to do anything is no longer offered anywhere.**
  `ActionType.writeProven` separates two questions a single ✅ used to answer at once: whether
  the firmware carries the property (`@SupportedOn`) and whether writing it changes anything.
  The five glass actions are the case that forced the distinction — the service accepts a
  command in `0..7`, no head-unit application sends one, and which command raises a window is
  written down nowhere, so the write is accepted, dropped, and reported as applied. They ship
  `writeProven = false` until one command is observed to move a window; the generated firmware
  matrix marks them ⚠️ and says what the mark means.

## [1.7.0] - 2026-08-27

### Added

- A shared, read-only energy telemetry API: one nullable `EnergySnapshot` combines SOC,
  range, speed, battery power and temperatures, pack energy/capacity, odometer, charge state
  tyre pressures and climate state with explicit firmware dispatch and unit conversion.
- Reusable trip and charging-session integration plus bounded atomic trip-history storage.
  Both skip unreadable samples and long gaps rather than inventing energy.

### Changed

- Reflected `CarPropertyManager` getters are cached when the service connects instead of
  resolving a `Method` for every sample. The cache is keyed on "resolved", not on "found",
  so a getter this `CarPropertyManager` does not expose is no longer looked up again on
  every sample.
- A trip's reported duration is the time actually covered by usable samples, not wall clock.
  A sampler suspended for ten minutes no longer adds ten minutes to a trip whose distance
  and energy did not move — the consumption average now divides comparable numbers.

### Fixed

- The Car-service binding now retries without a bound. The previous fixed retry ladder gave
  up after one minute, so a process started before the SAIC Car service was ready never saw
  a vehicle again for its whole lifetime, and a service that died and came back was only
  recovered if the `ServiceConnection` callback fired. Consumers carried their own reconnect
  loops to work around this; the watchdog now lives next to the binding it repairs.

## [1.6.0] - 2026-08-26

### Fixed

- **Glass writes never moved a window.** `IVehicleControlService` validates a window write
  against `max_vehicle_window_set = 7` and drops anything above it without an error — the
  setter returns void, so a dropped write and an applied one are indistinguishable from the
  caller. The library was sending a position in percent, so every write above 7 was
  discarded while the history recorded it as applied. Reading is unchanged and stays a
  percentage (`max_vehicle_window_get = 100`): the two directions are not the same scale.
  `setWindow` / `setAllWindows` now take a **command in 0..7** and refuse anything else
  rather than hand it over to be dropped. Which command opens and which closes is not
  written down on the firmware and no head-unit application sends one, so the mapping is
  still open — a command sent and a position read back is what identifies it.
- **Current weather always came back unreadable.** The `ICallBack.onResult` parcel carries
  the nullable `Result` argument behind a presence flag, and the decoder started at the
  object's first field instead. Every field then landed in the previous one's place, the
  payload tag no longer matched, and an answer that had arrived was reported as "cannot
  tell". The flag is read now, and a null `Result` is a null reading.

### Changed

- Glass actions are standstill-gated outright, and `ActionType.gatedWhenOpening` is gone
  with the directional gate it fed. That gate compared the target against the position, and
  the target is no longer a position: with the direction of a command unknown, the safe
  reading of an unknown direction is that it is not one to allow at speed.
- `SaicVehicleControl.doorLockState()` exposes the raw lock status for the diagnostic. The
  service answers `0..7` and only 1 and 2 are named, so `doorsLocked()` still discards the
  rest — but "unreadable" without the number left no way to tell an unnamed state from a
  read that never answered.

### Removed

- The daily forecast — `WEATHER_TOMORROW`, `TEMP_MAX_TODAY`, `TEMP_MIN_TOMORROW` and
  `SaicWeather.forecastAt`. `ISaicService` declares seven transactions on this firmware and
  none of them answers a forecast; the `WeatherFuture` parcelable its `Result` can carry is
  never filled by anything in the map service. The transaction 1.5.0 called does not exist,
  which is what the `transact returned false` in the log was. The head unit's own weather
  application fetches the outlook itself with credentials that are not reachable from here
  and broadcasts only current conditions, so there is no second source either. Three
  conditions that could never be readable are worse than none.

## [1.5.0] - 2026-08-25

### Added

- Per-window control. `SaicVehicleControl.Window` names the four windows and
  `windowPercent` / `setWindow` address one at a time, alongside the group read and
  `setAllWindows` — which stay, because closing the glass is one gesture that must not be
  able to leave three shut and one open. Catalogue: `ConditionType.WINDOW_DRIVER`,
  `WINDOW_PASSENGER`, `WINDOW_REAR_LEFT`, `WINDOW_REAR_RIGHT` and the four matching actions.
- `SaicCharging.rangeKm` and `ConditionType.RANGE_ESTIMATE` — the cluster's own remaining
  range. A rule that diverts to a charger is about distance, which a battery percentage does
  not answer on a cold motorway.
- `SaicNav`, the head unit's navigation adapter, and `ConditionType.ODOMETER`. Trip distance
  is deliberately absent: remaining distance and time only arrive as callbacks into that
  service from the running navigation app, so reading them is a subscription, not a getter.
- `SaicWeather` with `ConditionType.WEATHER_NOW`, `WEATHER_TOMORROW`, `TEMP_MAX_TODAY` and
  `TEMP_MIN_TOMORROW` — the weather service the head unit's own map stack queries, asked for
  the car's position. The outlook is **daily**: the service answers one entry per day, so
  "rain tomorrow" is a question it takes and "rain in three hours" is not, and the catalogue
  offers only what the data supports. The only asynchronous service here: the answer
  arrives on a callback binder and is awaited with a bounded wait, so a slow query comes back
  unreadable instead of holding a rule cycle open.
- `SaicAidl` now marshals `Double` and `IBinder` arguments, for that callback.

Neither `ODOMETER` nor `WEATHER_NOW` carries `@SupportedOn`: they come from the head unit's
own applications rather than from the vehicle SDK, so nothing routes them per generation —
they answer or they do not, the same as the message action.

## [1.4.0] - 2026-08-25

### Added

- Context entries that read Android rather than the car, so they carry no firmware
  annotation: `ConditionType.MEDIA_PLAYING`, `WIFI_SSID`, `IN_CALL`, `DRIVE_DURATION`,
  `RANDOM_CHANCE`. `IN_CALL` is "a call is routed through the car", not "the phone is
  ringing" — the difference is stated rather than papered over.
- `ActionType.ENABLE_RULE` / `DISABLE_RULE` with `ValueKind.RULE`: a rule can now switch
  another on or off, which is what turns a catalogue of one-shot rules into chains.
- `ActionType.MEDIA_CONTROL` (`MediaCommand`, the platform's own media key codes) and
  `SET_BLUETOOTH` / `SET_WIFI`.
- Climate readings that had a vendor getter and no catalogue entry: `ConditionType.ECON_MODE`,
  `PASSENGER_TEMP`, `FRONT_DEFROST`, `REAR_DEFROST`. The last two also become the `currentKey`
  of the defroster actions, which until now opened on a state nothing could read back.
- `ActionType.SET_ECON` and `ActionType.SET_PASSENGER_TEMP`. The passenger target is a second
  action rather than a zone argument on `SET_CABIN_TEMP`: a zone would have made every rule
  saved before it carry a value it never chose.
- Charging schedule readings: `ConditionType.CHARGE_SCHEDULE_ENABLED`, `CHARGE_WINDOW_START`,
  `CHARGE_WINDOW_STOP`, `BATTERY_PREHEAT` — the read side of actions that could already write
  them.
- `ConditionType.CHARGING_STATUS`, the charging state itself. `CHARGING` stays a boolean and
  keeps its meaning; the state is what can say "plugged in but not charging", which a boolean
  cannot.
- `ConditionType.FRONT_DOOR_OPEN` (SWI133), reading the door status the volume-drop watcher
  already polls, through the new `EVHardware.frontDoorOpenOrNull()`. Front doors only — the
  rear ones are not claimed rather than guessed at.
- `ValueKind.TIME`: one time of day, minutes since midnight. The charging window's ends are
  clock times, and a 0–1439 slider is unanswerable at the wheel.

### Fixed

- `VehicleEnums.CHARGING_ACTIVE_STATES` names the states in which current actually flows into
  the battery. Consumers filling `KEY_CHARGING` derived it from `chargingStatus != 0`, which
  made a finished charge, a stopped one and a charging fault all report as charging.

## [1.3.0] - 2026-08-23

### Added

- `ActionType.SEND_SMS`: send a text message through the paired phone, with `ValueKind.SMS`
  (recipient in `text`, contact label in `displayName`, message in `payload`). No
  `@SupportedOn`: the message leaves over the Bluetooth Message Access Profile, not over a
  SAIC service, so nothing is routed per firmware generation — availability is a bind, read at
  runtime. The vendor Bluetooth call interface exposes no message transaction, so the
  message cannot travel that way; the head unit's Bluetooth stack does expose a MAP client
  profile at runtime, and that is the route taken.
- `ValueKind.CONFIRM`: a yes/no question plus the seconds it waits for an answer. The editor
  draws one control per kind, so the wait had to become part of the kind rather than a
  special case for one action.

### Changed

- `ActionType.ASK_CONFIRM` now carries `ValueKind.CONFIRM` (5–60 s) instead of plain text.
  `Action.number` is the wait; `0` — every rule saved before the field existed — means
  `ActionType.ASK_CONFIRM_DEFAULT_SECONDS` (10), not the bottom of the range.

## [1.2.0] - 2026-08-22

### Added

- `SaicRadio.pause`, the vendor service's `srcPauseRadio` — the counterpart of `play`. It
  mutes the tuner and marks the radio stopped; it does not hand the audio focus back, which
  the vendor service only does when it loses it.

### Changed

- `SaicRadio.tune` takes an `andPlay` flag and is responsible for the outcome. 1.1.0 removed
  the `srcPlayRadio` call and assumed that was enough to tune in silence; it is not. The
  vendor's `tune` calls `AudioController.requestMuted(false)` itself, which requests the
  audio focus and unmutes the tuner, so changing station always starts the radio. Not
  playing is therefore something that has to be undone after the fact: `andPlay = false`
  pauses the radio as soon as it is tuned. Callers must pass the flag — there is no default.

### Known limitation

- Tuning with `andPlay = false` leaves the radio holding the audio focus, silent. Whatever
  was playing before does not resume: the vendor service exposes no way to give the focus
  back for AM/FM, only for DAB scanning.

## [1.1.0] - 2026-08-22

### Changed

- `SaicRadio.tune` no longer starts playback. It used to call `srcPlayRadio` after setting
  the frequency, which made the radio the current audio source every time a station was
  tuned, so a caller that wanted the station changed silently had no way to ask for it.
  Tuning and playing are now two calls: `tune` sets the frequency, `play` switches the
  source. Callers that relied on the combined behaviour must call `play` themselves.

## [1.0.1] - 2026-08-21

### Fixed

- `SaicClimate.outsideTempCelsius` no longer returns the vendor's error value as a
  temperature. `AirConditionBinder.getOutCarTemp` answers `TEMPERATURE_ERROR_CONST`
  (-10000f) for a signal it holds no value for, which is not a reading; it now maps to
  `null`, same rule as every other getter here. A caller that stored it would have had
  every "below N degrees" comparison come out true.

## [1.0.0] - 2026-08-15

### ⚠️ Breaking

- Renamed from MG4Hardware to **EVHardware**, and the package from `com.mg4.hardware` to
  **`com.evsuite.hardware`**. Every import has to be updated; there is no compatibility
  alias.
- `VehicleWriteGate.decide` no longer takes `allowUpToKmh` or `parked`. The gate is fixed at
  a readable 0 km/h, with no caller threshold and no park rescue, so callers that passed
  either argument must drop it. Consumers using `decideNow()` or `decide(speed)` are
  unaffected.

### Added

- `FirmwareInfo.generationOf` as a pure parser, and `isDetectedGenerationSupported`, so an
  unforced unknown firmware fails closed.
- Catalogue entry `ASK_CONFIRM`, with its localised strings and firmware-matrix row.

### Changed

- Consolidated the four copies of this library that had drifted apart while it was vendored
  into EVProfile, EVTasker and EVABRPUploader.

## [0.3.0] - 2026-08-10

### Added

- **Shared diagnostic engine** in `com.evsuite.hardware.diag`, so the apps stop each carrying
  their own copy:
  - `CrashLogger` writes an uncaught exception, its cause chain and the `AppLogger` buffer
    to `filesDir/last_crash.txt`, chaining the previous handler and truncating from the tail
    so the exception survives. It replaces the two divergent copies in EVProfile and
    EVTasker, each of which carried a fix the other was missing.
  - `PrivateBin` uploads a report to a PrivateBin instance, zero-knowledge — the key never
    leaves the URL fragment. Moved verbatim from EVTasker.
  No UI: rendering a report and deciding when to paste it stay in the apps.

### Changed

- **One "Call" action.** `CALL_NUMBER` now carries a `CONTACT` value, so the same entry takes
  either a typed number or a name from the phone book. `CALL_CONTACT` is deprecated and no
  longer offered by `ActionType.byGroup()`: it stays in the enum only so rules saved by a
  release that exposed it still load and still call, since enum entries are persisted by name
  and a removed one deserialises to null.

## [0.2.0] - 2026-08-02

### Added

- Vehicle access to climate, charging, radio and calls through the vendor's own
  service, alongside the existing reflection layer.
- `SPEAK_TEXT` local action: text-to-speech through the vehicle's own voice service.
- Window and door-lock control through the vendor control service.
- Park-based fallback for the write gate, and radio tuning.

### Fixed

- Unreadable vehicle signals were being reported as "off"; they're now reported
  as unknown.

### Changed

- Adopted the EVSuite documentation structure: shared README skeleton, generated table
  of contents, and [DESIGN.md](DESIGN.md) carried in every suite repository.

## [0.1.0] - 2026-07-23

Initial tagged release.
