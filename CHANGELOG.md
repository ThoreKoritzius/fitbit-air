## Unreleased

### Example app

- Notifications stay on until you stop them. The relay remembers that you
  turned it on and resumes by itself after a phone restart, an app update,
  Android ending it in the background, a temporary notification-listener
  disconnect, or a stuck command. Stopping it, or turning off notification
  access, still turns it off for good. Activity shows each automatic resume
  and why.

## 0.2.1

### Package

- `AirHapticDispatcher` retries a `HANDSHAKE_TIMEOUT` once after 3 s
  (`retryUnsent`, `retryDelay`). That error is raised before the secure
  session exists, so nothing can have reached the Air; ambiguous failures are
  still never retried. `AirHapticDispatchResult.attempts` is 2 when retried.
- Add `FitbitAirDiagnostics.readLinkHealth()`: warm-link fallbacks and
  rebuilds by reason since the process started.
- Fix commands failing after ~22 s with `HANDSHAKE_TIMEOUT` while the warm
  link stayed connected but no longer received the Air's notifications. The
  warm link re-registers before each command; if a command on it still hears
  nothing within 2 s, it continues on a fresh connection (the vibration then
  arrives ~2.5 s later instead of failing), reported as
  `warmLinkFallback: silent`. The warm link is rebuilt after such a fallback,
  when Bluetooth is switched back on, on a service change, and when it stays
  disconnected for 2 minutes, so the relay no longer needs a restart.

### Example app

- The Device tab notes when the connection recovered automatically, with
  the count and time; the diagnostic report includes the counts.
- One status language on every screen: the Air emblem with a badge and color
  per state (teal ready, neutral off, moon/pause for quiet, amber triangle
  for alerts that may not arrive, red for something to fix, a ring while
  starting). Home's tab carries a dot when something needs attention, the
  connection chip turns amber when the Air isn't connected, and the Device
  header follows the connection. Reduced motion replaces the spinner.

## 0.2.0

### Package

- Bundle the Golden Gate transport, built reproducibly from its public
  Apache-2.0 source for arm64-v8a, armeabi-v7a and x86_64. The plugin no
  longer copies or loads `libxp.so` from the installed Google Health app, so
  Google Health updates cannot break it. Mbed TLS is the final 2.28 LTS
  release (2.28.10) instead of Golden Gate's 2.28.0. Verified on a Pixel 7 Pro: battery
  read, single tap, two-pulse restore and handoff; Google Health synced
  normally right after commands.
- Add `FitbitAir.keepLinkWarm(bool)`: a listen-only link that commands reuse,
  skipping connection setup and the channel observation. With it, commands
  also request the fast link as soon as they start. On the Air's idle link a
  tap is felt 2.4–2.8 s after the command starts (10 of 10 trials), down from
  3.5–5.8 s with a warm link alone and 5.5–8.2 s with a fresh connection.
  The fast link is held about 3.5–4 s per command, then released to low power.
- Start immediately once the shared channel has been silent for 0.3 s instead
  of always observing 0.75 s.
- Add `AirFailureKind` and `FitbitAirException.kind`, grouping error codes
  into temporary, setup, compatibility and unknown, and
  `FitbitAir.testedFirmware`, the Air firmware versions this release was
  verified with.
- Report `NATIVE_UNAVAILABLE` when the transport cannot load on a device
  instead of the generic `GOLDENGATE` error.
- Add `FitbitAir.registerLicenses()`, which adds the Golden Gate, Mbed TLS
  and lwIP licenses to Flutter's license page; apps shipping the plugin must
  include them. Modified upstream binding files now say what changed.

### Example app

- Simpler WhatsApp people: one list of saved chats with their vibration, and an
  **Everyone else** switch in place of the All chats/Selected mode. **Add person
  or group** lists chats from current notifications right away. Changes apply
  while the relay runs instead of stopping it, and chats matched by name are
  explained inline instead of behind a dialog. Saved chats now live with the
  relay rules; earlier setups are merged once on upgrade.
- **Message tone** is labelled Experimental and asks before it starts reading
  message text: a dialog explains that text is checked in memory on the phone
  and never saved or sent.
- The relay keeps the link warm while it runs, so alerts arrive sooner.
- Home shows when the Air is out of range while notifications run, and when
  two alerts in a row were not confirmed. Only repeated compatibility errors
  suggest an update, with a **Check for updates** action.
- A notice appears once when the Air runs firmware this release was not
  tested with, unless a battery read or vibration has already succeeded on
  it here ("works on your Air"). The firmware is read at most daily and shown
  on the Device tab as tested, works on your Air, or not yet tested.
- Issue templates for problems and for reports on new Air firmware.
- **View licenses** in About lists the bundled native code and VADER.
- Final app ID `dev.airpulse.airrelay` for Google Play and GitHub releases
  (installs next to earlier example builds). Release builds use your own key
  from `key.properties` or CI secrets; a tag builds a signed APK for GitHub
  releases and an App Bundle for Play ([release guide](doc/release.md)).
- Privacy: the notification-access step shows a prominent disclosure with a
  link to the new [privacy policy](PRIVACY.md); app data is excluded from
  cloud backup and device transfer. [Play submission notes](doc/play-store.md)
  cover data safety and the foreground-service declaration.
- About explains typical delay, range and the firmware risk, shows the tested
  and current firmware, and links to releases and issue reports. Setup sets
  the same expectations.

- Add an opt-in **Message tone** rule: an on-device English/German keyword
  heuristic marks relayed messages as urgent, negative or neutral, and each
  category can pick its own vibration (or keep the app/chat rule, or mute).
  Message text is classified in memory only and never stored or exported.
  English messages are also scored with the VADER sentiment lexicon (MIT) and
  German ones with a project-written lexicon that handles word endings,
  compounds and negation after the verb; mixed messages use both, and
  message bursts, priority conversations, call/alarm/reminder notifications and
  silent channels feed the result. Relay history shows the vibration sent and
  the message's category.

## 0.1.4

### Package

- Add `FitbitAir.readBattery()`: the battery percentage Google Health shows
  (verified equal), voltage in millivolts, and whether the Air is on its
  charger (verified on and off the charger).
- Add `FitbitAir.isAirConnected()` and `watchConnection()` from Android's
  system connection and Bluetooth on/off events (no connection of their own),
  and `readSignalStrength()` for a rough proximity hint in dBm.
- Add `FitbitAirDiagnostics.watchChannelActivity()`: passively reports whether
  Google Health is idle, polling or syncing on the Air's shared channel.
- Sync-aware hold: before each secure command, passively observe the shared
  tracker channel and wait (up to 10 s) while Google Health is transferring
  data. Polling never blocks a command. Reported as `channelHold`. In 8
  vibrations sent during Google Health syncs, all 8 syncs completed.
- Request the connection-priority boost only after the session exists; a fast
  link during the handshake let Google Health win the Air's handshake race.
- Retry a lost handshake race (`DTLS` error `-1`) after 1.5 s instead of waiting
  for the 6 s stall deadline.
- Add optional explicit tap intensity to `AirHapticCommand`; other command kinds
  reject it. Existing commands and defaults remain compatible.
- Research: taps, including a recognized alarm dismissal, send nothing to the
  phone; real-time tap detection is not possible with the tested firmware.

### Example app (Air Relay)

- Home/Rules/Device navigation with accessible Lab tools; per-app and
  WhatsApp-chat vibration presets, quiet hours and a timed pause.
- A four-step guided setup that opens full screen on first launch: permission
  explanations, app selection and optional vibration confirmation, with saved
  progress. Reading notifications and showing relay controls are separate,
  explicit grants; starting the relay no longer prompts unexpectedly.
- Home shows the Air's battery (timestamped, refreshed hourly while visible and
  on resume, never by stopping the relay) and its passive connection state. A
  Device overview adds signal strength and model/firmware.
- Battery reads and notification commands share the relay's worker, so they
  never overlap; Start queues behind a finishing test or battery check.
- Actionable relay readiness that separates past delivery failures from current
  permission, Bluetooth, pause and startup states.
- WhatsApp people get a dedicated screen with chat search and vibration editing.
- Merged alerts use a deterministic preset while keeping the transport
  cooldown; alerts during quiet hours are suppressed and stale ones expire.
- Calmer UI: stable Start/Stop headings and button geometry during transitions,
  stable app rows and icons while toggling rules, subtle haptic feedback on
  Start/Stop, flat settings rows, reduced-motion support.
- Lab shows timestamped wrist observations from manual trials only.

## 0.1.3

- Add `FitbitAir.tap()`: exactly one pulse at the strength set in Google Health,
  which is kept. It sends the other strength and the original in one settings
  message; the Air supersedes the first pulse. `tap(intensity: …)` pulses at an
  explicit strength (a different strength is kept and reported as `changed`).
  `AirHapticKind.tap` dispatches it. Verified in 14 felt trials on one Air.
- Relay demo: **One pulse** (`tap()`, now the default) or **Two pulses**
  (`vibrate()`). Test vibration follows the selected style whether or not the
  relay is running. The relay screen is simplified: Start is the single primary
  action; a "Vibration" section holds the style selector and a compact
  **Try it** button that shows progress and "Sent" in place, without the
  layout-shifting "In progress" banner. Failures appear in a floating bar with
  Retry. The "I felt vibration" marker moved to the Lab only. Device tab adds
  **Single tap (keeps strength)**.
- Research: the Air pulses only when its strength becomes different from the
  last non-off strength; native notification and heart-rate sharing resources
  return `4.01` on the accessible session.

- Speed up commands by about 40%: request high connection priority for the
  command's session only and return the link to low power afterwards. The idle
  link (~150 ms interval with peripheral latency) made every round trip wait
  several radio events. Measured end to end: 8.7 s before, 4.0–4.8 s after.
- Retry a stalled handshake once. After the Air resets its link, Google Health
  and the SDK can both answer its handshake and neither wins; previously the
  command then hung until the 45 s timeout while Google Health was also stuck.
  A handshake missing after 6 s now gets one fresh attempt (no device command
  has been sent at that point), then fails with `HANDSHAKE_TIMEOUT`.
- Relay demo: cooldown reduced from 20 s to 15 s, based on measured Google
  Health sync times after a handoff.

- Relay demo: merge notification bursts. The first alert still vibrates
  immediately; alerts during a command or its 20 s cooldown join one follow-up
  vibration sent when the cooldown ends. A burst costs at most two tracker
  sessions instead of one per message, leaving Google Health quiet windows.
  History shows merged alerts as `merged`.

- Fix Google Health sync stalling for about a minute after every DTLS command.
  The Air has one Gattlink/DTLS channel shared by all clients on the link; our
  session left Google Health's channel out of sync until its ~60 s watchdog
  reconnected. Commands now end with a handoff: a short session that refuses
  the Air's key, which makes the Air reset the link for every client, after
  which the SDK goes silent so Google Health re-handshakes alone. Results are
  returned after the handoff (typically 1–3 s). Verified in five vibrations on
  a Pixel 7 Pro, including the relay worker and Google Health in foreground.

- Serialize command Bluetooth callbacks with teardown, discard stale native
  callbacks/completions, and track partially initialized resources for cleanup.
  These fix lifecycle races; Health sync coexistence still needs phone validation.

- Expose bounded, process-wide command cleanup diagnostics in demo reports,
  including teardown failures and app-local transport ownership. This aids
  Health sync investigation; it does not establish or fix the underlying cause.

- Fix empty WhatsApp capture by inspecting unread notifications immediately,
  retaining results after listening stops, and exposing safe diagnostic counts.
- Add explicitly confirmed exact-chat-name rules when message notifications lack
  a conversation ID; show duplicate-name/rename limitations before saving.
- Simplify relay setup with a visible Choose people card, collapsed completed
  device setup, clearer capture states and a nearby sanitized debug report.

- Add example-only WhatsApp/WhatsApp Business conversation allowlists with
  explicit 60-second local capture, app-scoped conversation matching,
  fail-closed handling of missing IDs and controls locked while relaying.
- Keep conversation labels/IDs out of diagnostic reports and Android backups;
  direct-chat selection supports people, while selected groups allow all senders.

## 0.1.2

- Organize the example into Relay, Device and Lab screens with a shared named
  action controller, explicit transport ownership, and safe relay-stop handoff.
- Add app search/icons, setup checks, a relay-aware vibration test, and bounded
  persistent metadata history with terminal outcomes, latency and recovery hints.
- Add saved research durations, structured bounded trial results, sanitized JSON
  export, manual physical-observation markers, stable control keys and opt-in
  physical-device integration scenarios. Existing research tools remain available.

- Generalize the example relay to a saved app allowlist with an installed-app
  picker, including WhatsApp. Accept newer MessagingStyle timestamps on reused
  chat notification keys while suppressing duplicate updates and old history.

- Add an example-only Google Calendar notification relay with notification-access
  setup, foreground Start/Stop controls, duplicate filtering and bounded haptic
  dispatch. WhatsApp relay smoke success was user-reported on 2026-10-16;
  long-term reliability and the revised UI still need physical validation.

- Allow bounded 30–90 second secure-event research trials; use 90 seconds for
  alarm dismissal so delayed events can be observed after the gesture.

- Acknowledge valid POSTs on both event routes with CoAP `2.04`, matching
  Google Health's event handler; reject malformed event payloads with `4.00`.
- Add a manual tap-attempt marker independent of LED confirmation in the demo.
- Add an alarm-dismissal observation trial with separate manual onset, attempt,
  and stopped-vibration markers; the demo does not create alarms.
- Observe additional incoming routes, decode bounded `eventsSecure` event IDs,
  and report partial observation duration on interruption. `WRS_ON` receipt
  verified in a bootstrap session, including both presence states during a
  user-confirmed removal/replacement trial.
- Add a dedicated wrist-presence trial with manual action markers in the demo.
- Add experimental, bounded `watchWristPresence()` with typed device-reported
  states and phone receipt times.
- Guide physical tap research with timed quiet/tap intervals and explicitly
  manual LED-observation markers in the demo; no tap-detection API is claimed.
- Fix the incoming CoAP request-filter JNI signatures to match the installed
  transport; add ABI regression tests for active native constructors.
- Add bounded, opt-in secure protocol-event observation. Startup/sync and
  wrist-presence events verified on hardware; wrist-tap detection remains open.
- Add a fixed read-only resource-access probe without exposing response bodies.
  Device-info access succeeds; alarms, live activity and sync status are denied
  in the tested bootstrap session.

## 0.1.1

- Add bounded haptic command dispatch with expiry, duplicate suppression,
  rate limiting, and no automatic retry of ambiguous device failures.
- Guard transport ownership across Flutter engines in the same process.
- Add bounded CPU wake-lock handling to the example headless service.
- Verify one controlled screen-off haptic with physical user confirmation and
  simulated duplicate suppression; deep-idle/restart reliability remains open.
- Add research-only battery-resource access and selected raw-field decoding;
  bootstrap GET access verified, units/percentage scale not yet claimed.
- Update installation instructions for the published 0.1.0 package and describe
  the broader Bluetooth API beyond haptics.
- Add user-started, headless read/haptic tests to the Android example and
  document background integration limitations. Headless reads and release-mode
  haptics verified on Pixel 7 Pro; user physically confirmed the pulses.
- Include the full Apache-2.0 license for the adapted JNI bindings.
- Add Dart dispatcher regression tests and native lease/protobuf tests to CI.

## 0.1.0

- Add opt-in, local-only diagnostic BLE event monitoring (not tap detection).
- Preserve JNI binding names in minified Android builds and allow bounded
  haptic recovery requests to finish before reporting an operation failure.
- Add read-only device information, BLE service inventory, and link diagnostics.
- Add standard heart-rate discovery, verified readings, continuous streaming,
  and read cancellation. Google Health heart-rate sharing must be active.
- Add direct Android BLE discovery for an already-paired Fitbit Air.
- Add bounded haptic command with best-effort restoration and verification.
- Add typed read/set intensity controls and a verified single-pulse toggle.
- Load the compatible native transport from a certificate-pinned, installed
  Google Health APK without redistributing its binary or reading private data.
- Include adapted Apache-2.0 Golden Gate JNI-facing Kotlin bindings.
- Add a demo app covering every supported operation and a typed Dart API.
