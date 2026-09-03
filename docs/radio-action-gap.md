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
| D | Select a band | **added** (2026-08-29, reversed), **merged into `TUNE_RADIO`** (2026-09-03) | Originally rejected because the binding contract exposes no band-only call. The owner asked for DAB support anyway, and the rejection turned out to be answering the wrong question: a band action does not need a band-only *call*, because `tune(band, frequencyKhz)` already carries the band as its first argument. `SELECT_RADIO_BAND` composes what is established — read the current band from `RadioBean`, tune into the requested one — and guesses no transaction code. The frequency it lands on is the station this car was last heard on for that band (`RadioBandMemory`), or the band floor followed by one `nextStation()` when it has never been there, so it does not strand the driver on an empty frequency. It shipped as its own `SELECT_RADIO_BAND` entry and was folded into `TUNE_RADIO` a week later — see *Merge* below. |
| E | Select a DAB service | **reject** (unchanged) | A DAB service is addressed by ensemble id + service id, not by a frequency, and neither is a stable identifier a driver could enter or a rule could store across a re-scan. No on-vehicle evidence establishes a portable contract. DAB is now reachable as a **band** (candidate D, inside `TUNE_RADIO`) and stepped through with `RADIO_NEXT_STATION` / `RADIO_PREV_STATION`; what stays out is selecting one named service directly. A DAB *block* is a real frequency in Band III, which is what a band switch tunes; a DAB *service* is not. |
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

## Reversal, 2026-08-29

Candidate D was reopened at the owner's request after an on-vehicle diagnostic (SWI68). The
original rejection assumed a band action required a band-only transaction, and that assumption
was wrong: `tune` takes the band as an argument, so the band is reachable through a call that
was already proven. The rejection of candidate E stands unchanged — a DAB service still has no
identifier stable across a re-scan, and none was guessed.

## Merge, 2026-09-03

Candidate D shipped as an action of its own, and that was one entry too many. Band, frequency
and playback are three parts of one instruction — "put the radio on FM 103.5 and play it" — and
splitting them left each part unable to say the whole thing: `SELECT_RADIO_BAND` could not name
a station, `TUNE_RADIO` could not reach DAB, and a driver who wanted both wrote two rows with a
delay between them. They are now one entry, `TUNE_RADIO`, carrying the band in `number`, the
frequency in `text` and playback in `flag`.

Nothing about the vehicle contract changed: a frequency still goes through `tune`, a band with
no frequency still goes through `selectBand`, and DAB is still reachable only as a band. What
changed is which of the two a rule asks for, and that is now read off the rule rather than off
which of two entries it picked. `selectBand` gained `andPlay` so that the merged action's
"enable radio" switch means the same thing on both paths.

Two things are refused rather than resolved. A typed frequency that disagrees with the picked
band (`AM 103.5` under an FM picker) parses to nothing, as `AM 103.5` already did on its own —
two things were said, and silently keeping one is how a rule lands on a station nobody chose.
And DAB takes no frequency at all: the editor removes the field rather than accept a number it
would have to ignore.

Rules and exported files that still name `SELECT_RADIO_BAND` are rewritten on load by
EVTasker's `LegacyRuleJson`, keeping their band and their playback — the old band switch always
took the audio focus, so dropping playback would have silenced a rule that used to make a
sound.

## On-vehicle evidence

_(pending — CR-020 requires a run. Record, per firmware: `SaicRadio.isAvailable`, whether
`isPlaying()` answered on FM, AM and DAB, whether `PAUSE_RADIO` silenced the tuner, whether
`RADIO_PLAY_PAUSE` toggled in the direction the state predicted, whether
`OPEN_RADIO_SCREEN` was correctly refused while moving, and whether `TUNE_RADIO` reached **DAB**
with its frequency left empty and came back to FM on the station it had left.)_

Availability is evidenced on SWI68: the 2026-08-29 diagnostic reported every radio action
`[ OK ]`, with the radio service bound. What is still missing is the behavioural half — that
each one does what its label says.
