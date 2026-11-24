# Demo operation and agent-driven trials

The example remains the reference app for `fitbit_air`; app permissions,
notification filtering and Android lifecycle management are not package APIs.

## Screens

- **Home:** relay status, Start/Stop, timed pause, default vibration preview,
  setup checks and recent outcomes.
- **Rules:** an overview grouped as *What buzzes* (Apps, Chats, Keywords,
  Incoming calls), *How it buzzes* (default vibration, Message tone) and *When it
  stays quiet* (quiet hours, screen-off only). Apps, Chats, Keywords and Message
  tone each open their own page. Edits apply to new alerts while on.
- **Device:** pairing, device controls/information, heart rate and historical
  wrist observation. **Developer tools** opens Lab.
- **Lab:** existing monitors, probes, headless tests, timed wrist/alarm trials,
  manual markers and structured results. Android Back returns to Device.

Home owns the relay status; an owner banner identifies active device work. Merely
opening Device/Lab does not stop the relay. An exclusive device action asks
whether to stop it, waits for any active command's verification/restoration, and
leaves the relay stopped afterward. Cancelled/incomplete trials keep their
partial observations. Haptic writes cannot be cancelled mid-restoration.

Secure-event observation can be set to 30, 60 or 90 seconds and is saved on the
phone. The guided wrist-presence and alarm trials retain fixed 30/90-second
windows. Manual markers remain separate from device-reported events. Neither a
successful command nor an absence of events establishes a physical sensation or
a negative gesture result.

## Shared controller and stable controls

`example/lib/demo/demo_controller.dart` exposes the same named actions used by
the buttons. A device integration test can pass a `DemoController` to `DemoApp`,
wait for `initialized`, inspect `actionIds`, and invoke `runAction(id)`.

Core IDs: `checkPaired`, `preview`, `tap`, `pulse`, `setLow`, `setHigh`, `readDeviceInfo`,
`scanHeartRate`, `watchHeartRate`, `watchDeviceEvents`, `watchSecureEvents`,
`wristPresenceTrial`, `alarmTrial`, `probeBattery`, `probeResources`,
`headlessHaptic`, `headlessRead`. Heart-rate discovery additionally registers
`readHeartRate0`/`watchHeartRate0`, etc.; these indices are local to a scan and
are not persistent sensor identities.

A second operation is rejected while one is active or the relay owns the
transport. Tests must explicitly call `stopRelayAndWait()` before an exclusive
action. A finished Future does not by itself establish success: inspect the latest
trial's `outcome` and `errorCode`. Streams remain active until completion or `cancelCurrent()`; that
method is a control action, not a second device operation. There is no automatic
retry for an ambiguous device write and no arbitrary outgoing protocol command
endpoint in the release APK.

Stable Flutter keys are part of the demo's test interface:

| Purpose | Key |
|---|---|
| Navigation | `nav-relay` (Home), `nav-rules`, `nav-device`, `nav-lab` (Developer tools) |
| Ownership and cancellation | `operation-owner`, `cancel-operation` |
| Relay controls | `relay-toggle`, `relay-status`, `test-vibration`, `pulse-style`, `pulse-style-hint`, `error-bar` |
| App selection | `app-picker`, `app-search`, `app-<packageName>` |
| Device/research action | `action-<actionId>` |
| Saved observation duration | `secure-duration` |
| Confirm/decline relay stop | `stop-relay-for-operation`, `keep-relay` |
| Physical user observation (Lab) | `lab-marker-felt-vibration` |
| Report export | `export-report`, `lab-export` |

Buttons also have descriptive labels and status updates use accessibility live
regions. Android UI tools can use those labels; Flutter integration tests should
prefer keys or controller action IDs. Do not rely on screen coordinates or
notification titles.

## Structured reports and privacy

**Copy sanitized report** copies JSON to the clipboard. It contains schema/demo
versions, export time, up to 100 relay history entries, the last 20 in-memory
trials (up to 100 observations each), and up to 100 standalone manual markers.
Relay history survives an app process restart; trial results do not, so export
before ending a research session. Clear relay history removes only that history.

Trial records include operation/parameters, phone start/end timestamps, duration,
outcome, error code and bounded observations. Interruption records retain actual
reported observation coverage. Known device model/firmware and protocol event
IDs/names can appear; raw BLE payloads, Bluetooth addresses/sensor IDs, account
data, notification contents and sender names are not included. With Message
tone on, message text is classified in memory on the phone and discarded; only
whether the feature is enabled appears in the report. Heart-rate
receipt timing can be recorded, but readings are not exported. Source application
package IDs and command timings remain in relay history. Treat exports as
research data and review them before sharing.

Relay stages distinguish `detected`, `queued`, `verified`, `expired`,
`queue_full`, `failed`, `cancelled`, `duplicate`, and `interrupted`. Total latency
includes queue time and Bluetooth setup. `verified` is a protocol receipt, not
proof someone felt vibration. A process killed without cleanup can leave a
`queued` entry without a terminal result; do not infer delivery or replay it.

## Running integration scenarios on a Mac or test machine

Run from `example/`, with a connected Android phone, the Air already paired in
Google Health, and Nearby devices access granted. Run one trial at a time. The
integration test never re-enables a stopped relay.

```bash
# Navigation and setup only; no device commands or haptics.
flutter test integration_test/demo_smoke_test.dart -d YOUR_DEVICE_ID

# Opt in to a read-only device-information trial.
flutter test integration_test/demo_smoke_test.dart -d YOUR_DEVICE_ID \
  --dart-define=AIR_DEVICE_TESTS=true

# Explicitly opt in to one restore-safe two-pulse haptic as well.
flutter test integration_test/demo_smoke_test.dart -d YOUR_DEVICE_ID \
  --dart-define=AIR_DEVICE_TESTS=true --dart-define=AIR_HAPTIC_TESTS=true
```

To save the structured result to `example/build/air-demo-report.json`, use the
included host driver (same opt-in flags):

```bash
flutter drive --driver=test_driver/demo_driver.dart \
  --target=integration_test/demo_smoke_test.dart -d YOUR_DEVICE_ID \
  --dart-define=AIR_DEVICE_TESTS=true
```

The driver writes sanitized reports on success and assertion failure. An active
operation when the harness ends is recorded as interrupted, not successful. The
binding exposes `reportData` to that integration driver. For interactive
trials, use the copy button and physical manual markers. Default automation must
not claim physical confirmation or send haptics without the explicit haptic flag.
APK builds run on a development machine or CI. Device integration tests require
a connected test phone.

## 0.1.2 acceptance and release

Before merge/publication, run CI package checks and the release APK build, then
record the following on a paired phone:

- Upgrade without losing selected apps or notification access; navigate all
  three screens and verify opening Lab leaves the relay running.
- Trigger a Calendar reminder and two separate WhatsApp messages in the same
  chat with the phone locked. Compare detection/queue/result history and actual
  sensation. Check an unselected app does not buzz.
- Use Test vibration with the relay both on and off. Confirm the two-pulse
  behavior/restoration and mark physical sensation separately.
- Start a Lab operation while relaying, decline once, then accept. Ensure an
  in-flight haptic finishes before the Lab operation starts; resume explicitly.
- Complete and cancel a bounded secure-event trial. Confirm saved duration,
  manual markers, partial/interrupted outcomes, and sanitized export.
- Check unavailable Air/Bluetooth-off/notification-access revocation and cleanup;
  do not repeatedly retry uncertain writes. Confirm Google Health still works.

Record phone/Android, Air firmware and Google Health versions alongside exact
steps and observations. A user reported successful WhatsApp relay operation on
2026-10-16; detailed versions and a structured trial report were not captured.
That smoke report does not establish idle/reboot/overnight reliability. This UI
revision needs its own physical acceptance test. Tag/publish 0.1.2 only after that
checklist passes. Automatic reboot recovery and durable replay protection remain
future work.

## Incoming calls

On Android 14 and later, turn on **Rules → Incoming calls**, choose a call
vibration, and enable the Phone app or calling app under **Your apps**. Leave
the relay running. This works when the calling app posts Android’s standard
incoming-call notification. App and device support can vary.

Air Relay queues at most one vibration per ringing notification. A ringing call takes
priority over messages in the same queued burst. The usual cooldown still
applies between Bluetooth commands. If a call stops ringing before dispatch,
its vibration is skipped. Pause, quiet hours, and the phone sleep rule also
apply. Air Relay uses its existing notification access and does not request
the phone permission, call log, or caller number.

## Chats

Chat rules work in any chat app that posts conversation notifications
(MessagingStyle or the message category): WhatsApp and WhatsApp Business,
Signal, Telegram, Messenger, Google Messages and others. Changes apply
immediately, also while the relay is running.

1. Open **Rules → Chats**. **Your chats** lists saved people and groups with
   their app and vibration; tap one to change it (or mute it), or tap **×** to
   remove it.
2. **Only saved chats buzz** has one switch per turned-on chat app (well-known
   chat apps, apps with a saved chat, and apps already restricted). Off, every
   chat in that app buzzes. On, only saved chats buzz there; with nobody saved,
   nothing from that app buzzes. Other apps are unaffected.
3. Tap **Add a chat**. The sheet lists chats from the turned-on apps' current
   notifications and waits for new ones for a minute. Tap **Add** next to a
   chat; it starts with the app vibration, and tapping it again changes that.
   If someone is missing, ask them to send a message, or leave one of their
   messages unread, then tap **Wait again**. Closing the sheet stops waiting;
   found chats stay addable for five minutes.
4. A chat is matched by its conversation ID when the app provides one. Otherwise
   it is **Matched by name**: the exact notification chat name is used, so
   renaming the chat stops matching and another chat with the same name matches
   too. The page explains this when such a chat is saved.
5. Test a fresh message with the phone locked, and one from a chat you have not
   saved, with **Only saved chats buzz** on and off for that app.

A saved direct chat is one person; a saved group covers everyone in it. Sender
matching inside a group is not implemented. Name matching uses the current
notification's conversation title (or title), never senders from message
history, and needs conversation metadata; generic titles (the app's own name),
blank titles and titles over 512 characters are rejected. Identities are hashed
with the app, so a chat saved in one app never matches in another.

**Nobody found?** appears when waiting finished without results. It explains
whether no chat notification arrived, notifications lacked chat details, or
only summaries arrived, and **Copy sanitized debug report** copies the safe
counts (notifications seen, unread snapshot, summaries, ongoing, missing IDs,
name-only, unusable, errors). Counts contain no names, IDs, notification keys,
titles or message text. Finding chats never queues vibrations.

Finding holds up to 32 chats in memory; unsaved results are discarded after five
minutes, listener disconnection or process death. Up to 64 saved chats (label,
app, match type, vibration and a hashed rule ID) are stored with the relay rules
in app-private preferences, which are excluded from cloud backup and device
transfer. Raw shortcut IDs and exact titles are hashed with app/type namespaces;
the original stable-ID hash format is preserved, so chats saved by earlier
versions keep matching. The earlier WhatsApp-only **Everyone else: silent**
switch migrates to **Only saved chats buzz** for WhatsApp and WhatsApp Business,
and is still written alongside the new `onlySavedChats` list for downgrades.

Reports include only how many apps are set to only saved chats, the saved-chat
count, the number of chats with their own vibration and the capture counts,
never names, IDs or found chats.

For agent-driven local setup, `DemoController` offers `findChats(listen)`,
`saveChat(id, preset)` (adds a found chat or changes a saved chat's vibration;
`null` uses the app vibration), `removeChat(id)` and
`setOnlySavedChats(packageName, enabled)`. Stable UI keys include
`conversation-picker`, `only-saved-<packageName>`, `add-chat`, `chat-<id>`,
`remove-chat-<id>`, `confirm-remove-chat`, `candidate-<id>`, `add-<id>`,
`listen-again`, `close-add-chat`, `capture-details` and `copy-capture-report`.
Private names and IDs exist only in local controller state; do not copy them
into test logs or PRs.

Physical acceptance remains pending. Test finding unread and new messages in
WhatsApp and at least one other chat app, waiting again, missing-ID chats, saved
versus unsaved chats with **Only saved chats buzz** on and off, edits while the
relay runs, groups, app separation, private notifications, upgrade from an
**Everyone else: silent** setup, process restart and removal. Local tests cannot
establish what metadata each chat app supplies on the paired phone.

## Keywords and message tone

**Rules → Keywords** gives messages containing chosen words, phrases or emoji
their own vibration, or keeps them silent. Words match whole words, ignoring case
and umlauts; a trailing `*` matches word forms (`call*` matches "calling"); emoji
match anywhere. If several rules match, the top one wins; drag to reorder. Up to
20 rules of up to 20 keywords each (40 characters) are stored with the relay
rules. Adding the first keyword asks for consent, because message text is then
checked on the phone, in memory, and discarded. History records only the
category `keyword`; reports carry only the rule count. Stable keys include
`keyword-rules`, `add-keyword-rule`, `keyword-rule-<index>`, `keyword-input`,
`keyword-preset-<id>`, `save-keyword-rule`, `delete-keyword-rule` and
`keyword-consent-confirm`.

**Rules → Message tone** is the experimental automatic classifier (urgent,
negative, everything else), with its own consent. Order: apps that are off,
unsaved chats in restricted apps, muted chats and silence never buzz; then a
matching keyword decides, then message tone, then the chat, app and default
vibration. A keyword cannot unmute a muted chat.

## Custom notification rules (0.1.4 development)

Everyday navigation is **Home**, **Rules**, **Device**. Home owns Start/Stop,
Try it and timed pause. Rules owns app selection, default and app-specific
vibrations, chat overrides and a daily quiet-hours window. Device
retains device controls and opens **Developer tools** (Lab). Lab has a normal
back action to Device; entering it never stops the relay. Device experiments
still require the existing explicit relay-stop handoff.

App rows keep their positions while you toggle notifications. Tap **Selected first**
to regroup them, or reopen Apps. Status refreshes reuse the existing app icons.

The four presets are `single` (current strength), `low`, `high`, and `double`
(two-pulse preview with restoration). Explicit low/high persists that strength
on the band. Chat overrides additionally support `mute`. Removing an override
uses the app rule, subject to the selected-chat allowlist. Choosing or forgetting
chats and capture still requires a stopped relay; app and vibration edits apply
to new alerts while running. In-flight commands keep their captured preset.

The existing 15-second transport cooldown is retained. A follow-up combines
fresh alerts, preferring chat-specific presets over app presets and the newest
match for equal specificity. It does not send a vibration for each merged
notification. Muted or restricted-out alerts cannot win a burst. Stop, pause
and quiet hours cancel waiting follow-ups; the active write/handoff finishes.
Notification freshness advances during silence and there is no replay afterward.
Alerts older than 30 seconds expire rather than being refreshed by combining.

Pause supports 15 minutes, one hour or until resumed; resuming never starts a
stopped relay. Quiet hours are disabled by default, run daily in local phone
time, and support overnight intervals. The service rechecks time at dispatch,
after time/timezone changes and at minute/pause boundaries without opening BLE
or acquiring a periodic wake lock. Missed timers during sleep do not bypass the
dispatch-time silence check. No exact-alarm permission is needed.

Settings migrate existing selected apps and pulse style once. Existing WhatsApp
restrictions and identities remain authoritative. `relay_rules.xml` is excluded
from cloud backup and device transfer along with captured chats. A migration
marker prevents an older backed-up app selection from being re-enabled if the
private rule file is absent after restore; users must choose apps again. Invalid
configuration fails closed. Reports contain rule counts, preset and suppression
reasons, not chat names, hashes, titles or message text.

### Agent and integration access

The same `DemoController` drives UI and automation. Existing device action IDs
remain. `runAction('openLab')` opens Lab, `previewDefault` tries the configured
default, and `pause15`, `pause60`, `pauseUntilResumed`, `resumeRelay` control
silence without acquiring transport ownership. Existing transport actions still
require exclusivity. `nav-relay` remains the Home tab key, `nav-rules` identifies
Rules, `nav-device` identifies Device, and `nav-lab` now identifies the Developer
tools entry on Device. Integration navigation uses `openLab` so it does not
depend on the entry's scroll position.

Typed controller methods expose `saveAppRule(packageName, enabled, preset)`,
`saveChatRule(id, preset)`, `saveDefaultPreset`, `previewPreset`, `setPause` and
`saveQuietHours`. Null app/chat preset means inheritance; `mute` is chat-only in
the editor. Preview does not save; while relaying it waits for the service-owned
worker's verified result. Pause/Stop remain available during that preview.

A manual secure-event Lab trial can populate **Wrist presence** on Device. It is
explicitly the last observed on/off-wrist state and receipt timestamp, never live
presence, and is discarded when the process exits. It never filters relay rules
or adds a continuous Bluetooth monitor.

Before release, exercise low/high retained strength, current-strength single
and restored double, app/chat overrides, mute, quiet hours crossing midnight,
timezone changes, pause expiry, combined bursts and Google Health sync after
both previews and real relay commands. Builds and physical trials belong in CI
or on a development machine. Keep the connection agent's handoff and recovery
implementation intact.

### Home battery and connection

Home shows the last battery percentage, a low-battery accent at 20% or below,
and a small clock badge on readings older than an hour. Tap it to see when it was checked,
whether it was on its charger then, and an Update battery action. Readings and
their original timestamps survive app restarts in local preferences excluded
from Android backup and device transfer. A failed refresh keeps the last value.

Battery updates automatically while the app is visible, setup is complete,
and a reading is missing or an hour old. Returning to the app also refreshes
readings that were already at least 15 minutes old when it became visible. The passive Bluetooth connection
label is informational and never prevents a read: Android may report no
existing link even though the paired Air can be contacted.

A three-second delay lets the screen settle. When notifications are on,
battery reads use the relay's existing worker only if its queue is idle.
Notifications arriving during a read wait in the same serial queue, retaining
their original expiry, instead of racing another engine for Bluetooth. Stop
remains available and waits for the read's cleanup before destroying the worker.
When the relay is off, the foreground engine performs the read instead.
The relay is never stopped or restarted automatically to check battery.

A busy worker defers automatic retry for 30 seconds; other read failures back
off for 15 minutes. Pending updates are cancelled in the background. Manual
refresh remains available, and successful readings are reused for at least a
minute. The ordinary two-second status poll only schedules eligible updates.
Battery read errors and timestamps are included in diagnostic exports without
chat identities or device addresses.

The connection label uses PR #6's passive Android Bluetooth events only while
the app is visible and permission is granted. It describes the phone's Bluetooth
link, independently of whether notification relaying is enabled. It does not
claim that a notification will be delivered or that Google Health is syncing.
Signal strength and channel-traffic monitoring are not enabled by Home.

### Starting after a test and Device overview

Start remains available during a finite Try it preview or device-status read.
Tapping it records one start request, shows Starting with a short wait reason,
and waits for the active operation's cleanup before starting the relay. The
request proceeds even if the preview fails; its error remains visible. Ongoing
Lab streams still require an explicit stop. Disposing the controller prevents
an uncompleted queued start from launching later.

Device begins with a compact overview: battery (tap for details), the passive
connection hint, timestamped signal strength, and model/firmware after a device
info read. Signal is only a rough proximity hint. Device checks it on entry
and at most every two minutes while visible; explicit checks have a 15-second
minimum interval. RSSI stays in memory, since an old proximity reading is not
useful across app restarts. Signal and battery reads share the worker queue
when the relay is on, and a pending Start takes priority over new status reads.

Home labels its status group Fitbit Air so the Bluetooth link, battery and
notification controls clearly refer to the band. The clock badge replaces
inline stale-reading prose; its tooltip and the battery detail sheet give the
reading age. Refresh progress keeps the previous percentage visible, and a
failed refresh retains the reading and its original timestamp. Battery reads
remain foreground-only and share the existing device-operation queue.
