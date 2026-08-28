# Radio action gap — community references vs the EVSuite catalogue

Decides every radio candidate raised by the 2026-08-28 community baseline (CR-020). The
comparison is against what `SaicRadio`, `SaicMediaPlayer` and `ActionType` already do, not
against the initial community audit, which under-counted the existing catalogue.

**Provenance.** CarMediaPlayer (MIT) and the community DAB+ fix (no explicit license at the
baseline review, and it replaces an OEM application) are read as *behaviour* only. No code,
resource or replacement strategy from either is used here. What the head unit's own radio
app does is the evidence that matters, and the binding contract below comes from the vendor
SDK already documented in `SaicMedia.kt`.

## What already existed

| Capability | Where | Reachable from a rule |
| --- | --- | --- |
| Play / resume the tuner (`srcPlayRadio`, tx 27) | `SaicRadio.play` | `PLAY_RADIO` |
| Tune FM/AM by frequency (tx 8) | `SaicRadio.tune` | `TUNE_RADIO` |
| Next / previous station (tx 13 / 14) | `SaicRadio.nextStation` / `previousStation` | `RADIO_NEXT_STATION`, `RADIO_PREV_STATION` |
| Read the tuner's play state (tx 19, `RadioBean.state`) | `SaicRadio.isPlaying` | — (internal) |
| Pause / silence the tuner (`srcPauseRadio`, tx 28) | `SaicRadio.pause` | — (internal) |
| Open the radio screen (tx 26) | `SaicRadio.openScreen` | — (internal) |
| Play/pause of *whatever owns the audio* | `SaicMediaPlayer.command` | `MEDIA_CONTROL` |

Three primitives existed in the library with no way to ask for them from a rule. That, not
a missing service call, is the actual gap.

## Decisions

| # | Candidate | Decision | Why |
| --- | --- | --- | --- |
| A | Pause / stop the radio | **add** — `PAUSE_RADIO` | `srcPauseRadio` is implemented and unreachable. `MEDIA_CONTROL` is not a substitute: it addresses whichever source owns the audio, so it silences Bluetooth when Bluetooth is playing. "Silence the tuner" was not expressible. |
| B | Explicit radio play/pause toggle on readable state | **add** — `RADIO_PLAY_PAUSE` | One shortcut for a driver who wants the tuner specifically, whatever the current source. Built on `isPlaying()` (`RadioBean.state`), because `AudioManager.isMusicActive` is **false while the radio plays** — a toggle driven by it would send "play" to a playing radio forever. Unknown state fails closed (see below). |
| C | Open the radio screen | **add, gated** — `OPEN_RADIO_SCREEN` | `startRadioActivity` is implemented and unreachable. It is the one radio action that is not audio-only: throwing a full-screen app in front of a driver at speed is a distraction, so it takes the standstill gate and is refused when speed is unreadable. |
| D | Select a band | **reject** | The binding contract exposes no band-only call. `tune` takes `(band, frequencyKhz)` together, so a band action would have to invent a frequency — and the one it invented would be the station the driver did not ask for. FM/AM band selection is already carried by `TUNE_RADIO`, which infers the band from what was typed. |
| E | Select a DAB service | **reject** | A DAB service is addressed by ensemble id + service id, not by a frequency, and neither is a stable identifier a driver could enter or a rule could store across a re-scan. No on-vehicle evidence establishes a portable contract. DAB stays reachable through `RADIO_NEXT_STATION` / `RADIO_PREV_STATION`, which step the tuner's own list on whatever band it is on — band 4 in `MediaConstants` is DAB, and the launcher's own skip is this same call. |
| F | Restore the last audible radio state at ignition | **already covered** | Composition, not a new action: an ignition trigger plus `PLAY_RADIO`, whose `srcPlayRadio` resumes the last station by itself. A persistent service that remembered and re-asserted state would be a background watchdog the app does not have and does not want. |
| G | Radio artwork caching, station metadata display | **reject as an action** | Presentation, not automation. A rule cannot usefully "cache artwork"; a screen can show it. If it is ever wanted it belongs to an app's UI, reading `RadioBean`, not to the action catalogue. |
| H | Replace or patch the OEM radio APK (the DAB+ fix's strategy) | **reject** | Out of scope by the ticket and by the suite's boundary: EVSuite talks to the vendor services the car ships. It does not swap the car's applications, and the upstream fix carries no license that would permit reuse anyway. |

## Failure behaviour of what was added

- **Missing service.** All three call `SaicRadio`, whose `service.binder()` is null when the
  radio service is absent or its bind has not landed. Every call reports false; the executor
  turns that into an error rather than a silent success. EVTasker's diagnostic screen answers
  `NO_VENDOR_SERVICE` for the whole radio family — including `RADIO_NEXT_STATION` and
  `RADIO_PREV_STATION`, which were answering for the AOSP car layer instead.
- **Unreadable play state.** `RADIO_PLAY_PAUSE` reads `isPlaying()` first and sends
  **nothing** when it answers null: `ToggleResult.STATE_UNKNOWN`, reported as an error with
  its reason. It does not fall back to "assume playing" the way `SaicMediaPlayer.radioCommand`
  does — that fallback is right for a media-key shortcut a driver can press twice, and wrong
  for a rule that fires unattended and would toggle the audio to the state nobody chose.
- **Unsupported firmware.** `@SupportedOn(SWI68, SWI165)` on all three, the generations where
  the radio service is bound. The A9 generations (SWI69, SWI131, SWI132) have no such service;
  `ActionCompatibility` refuses the action there before it is attempted, and the editor does
  not offer it.

## On-vehicle evidence

_(pending — CR-020 requires a run. Record, per firmware: `SaicRadio.isAvailable`, whether
`isPlaying()` answered on FM, AM and DAB, whether `PAUSE_RADIO` silenced the tuner, whether
`RADIO_PLAY_PAUSE` toggled in the direction the state predicted, and whether
`OPEN_RADIO_SCREEN` was correctly refused while moving.)_
