# Background notification integration

`fitbit_air` controls the device; it does not receive push messages, observe
other apps' notifications, or keep the Android app alive. These responsibilities
belong to the host app. A displayed phone notification is not automatically
forwarded to the Air.

## Current evidence

- Foreground haptics have been physically verified.
- The example includes a user-started `connectedDevice` foreground service
  which creates a separate Flutter engine and calls the existing SDK without
  an attached Activity. Permissions must already be granted.
- In the first display-off headless haptic test, BLE connection, discovery and
  tracker subscription succeeded. Native DTLS attempted its normal-identity
  fallback, but a usable session was not established before the 45-second SDK
  deadline. The operation returned TIMEOUT and the service stopped.
- This demonstrates that the plugin can execute headlessly, **not** that
  background vibration is reliable. It does not establish whether display-off
  execution caused the failure; session contention remains a candidate.
- A separate headless `readDeviceInfo()` trial succeeded, returning an available
  model field and automatically stopping the service. The display was turned
  off after starting the trial, but was awake by the subsequent check, so this
  is not a verified sustained-screen-off test.
- A subsequent **release-mode** headless haptic trial succeeded with the demo
  backgrounded and display awake. The SDK verified restoration of the original
  setting; the user confirmed feeling the two pulses. The separate service-owned
  engine had no attached Activity, and the service stopped after completion.
  This confirms the existing SDK can perform haptics headlessly under these
  conditions, but not guaranteed delivery in arbitrary background/idle states.
- With the bounded wake lock and dispatcher in the release example, a controlled
  trial completed while Android's screen-state event log showed no wake between
  display-off and command completion. The SDK returned completed, the simulated
  duplicate was dropped, and the service/wake lock were released. The user also
  confirmed feeling the pulses while the phone was untouched. This is one
  screen-off success, not a deep-idle, reboot, or reliability guarantee. The
  wake-lock change has not been proven to explain the earlier timeout.

## Reproduce locally

1. Run the example and grant Nearby devices permission.
2. Choose the headless test. It starts an Android foreground service immediately,
   while the Activity is visible. Android controls notification visibility; on
   newer Android versions without notification permission it may appear only
   in the system's active-apps UI, not the notification drawer.
3. Press Home or lock the phone. After 15 seconds, a fresh service-owned engine
   invokes either `readDeviceInfo()` or `vibrate()`.
4. The service records only the outcome locally and stops. Return to the demo
   for the result, or inspect `adb logcat -s AirHeadlessTest`.

The service is not exported and accepts no remote intents. It is example code,
not a background service installed by the package. The haptic test makes at
most one SDK call; it does not repeatedly retry sessions. A timeout must not
trigger automatic repeated pulses: a lost response does not prove the command
never reached the device.

## What a notification app still needs

For your own push messages, implement message reception and dispatch eligible
commands to a live service/engine. For other apps' notifications, Android's
NotificationListenerService requires explicit user-granted notification access;
it is a separate integration, not provided here.

Grant Bluetooth permissions while a screen is visible. Queue/deduplicate
commands, expire stale messages, rate-limit haptics and avoid simultaneous SDK
operations across engines. Version 0.1.1 adds a process-wide transport lease:
another engine gets BUSY instead of opening a competing session. This is not
a queue shared across processes and does not coordinate with Google Health.
Neither FCM delivery nor foreground-service startup from arbitrary background
states is guaranteed. Do not rely
on a bare background callback for an operation that can take 45 seconds.

Remaining validation before promising reliable delivery: repeated screen-off and
deep-idle operation, process restart, duplicate messages across restarts,
unavailable-device cleanup, and Google Health coexistence.
No push provider or end-to-end remote delivery has been tested by this example.

## Command dispatcher (0.1.1)

The optional `fitbit_air_commands.dart` library provides a bounded queue in one
Dart isolate. It rejects expired commands, rechecks expiry after queue/rate-limit
delays, remembers accepted IDs even after ambiguous failures, and does not
automatically retry a device command. Deduplication is bounded by both a time
window and cache capacity; it is not a durable at-most-once delivery guarantee.

Use `AirHapticCommand.fromEventKey` with a stable source namespace and an ID for
the individual event. It derives a 64-character command ID even from a long
Android notification key. If a conversation reuses the same notification key,
include a stable message ID or the original message timestamp so each new
message can trigger a pulse. Reuse the same inputs when retrying the same
event. The regular constructor accepts an application-assigned, nonblank ID of
at most 128 UTF-16 code units.

```dart
import 'package:fitbit_air/fitbit_air_commands.dart';

// Keep one dispatcher for this message-handling isolate.
final haptics = AirHapticDispatcher();

// Call from your host app's authenticated, authorized message pipeline.
Future<AirHapticDispatchResult> handleTouch(
  String messageId,
  DateTime expiresAt,
) => haptics.submit(AirHapticCommand.fromEventKey(
  source: 'my-app.messages',
  eventKey: messageId,
  expiresAt: expiresAt,
  kind: AirHapticKind.tap, // One pulse; the wearer's strength is kept.
));
```

The `id` must be 1–128 characters, otherwise the constructor throws. Android
notification keys can be longer (an Outlook email key on a Samsung A54 was
well past 128), so hash such keys into a fixed-length ID before using them.

Omit `kind` for the two-pulse preview with best-effort restoration. A completed
result means the SDK's verification passed, not that the recipient felt it.
`failed` carries the SDK error, including BUSY. `duplicate`, `expired`,
`queueFull`, and `closed` send no new device command. `close()` drops queued
work but lets an already-started write finish; it is not haptic cancellation.
Freshness is checked before starting; expiry never interrupts a restore write.

This helper does not authenticate network data. The host must authorize the
sender/recipient, persist replay protection across restarts if required, and
keep an engine alive under Android's background rules. Do not pass arbitrary
notification text to the device or grant notification-listener access when
only your own app's messages are needed.

The demo's haptic test submits the same command twice and reports whether the
duplicate was dropped. Its example-only wake lock has a 70-second maximum and
is released on completion; it keeps the CPU running, not the display awake.
See [Android wake-lock guidance](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/set).

References:

- [Android background BLE guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [FCM Flutter background handling and execution limits](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages)

## Notification relay example

The example now includes a user-enabled notification relay for selected apps; see
[setup and behavior](../example/README.md#notifications--air-google-calendar-whatsapp-and-other-apps). Its listener and
foreground-service lifecycle belong to the example, not the published plugin.
The listener dispatches only to an already-running service; receiving an alert
never attempts to launch a foreground service from the background. It forwards
only an application-generated command ID and expiry to a service-owned Flutter
engine, using the existing `AirHapticDispatcher` and restore-safe `vibrate()`.

Queue limits, stale-alert rejection, notification-key deduplication and no-retry
behavior bound delivery. A Stop request closes the dispatcher, rejects queued
work, and allows an active command to finish before destroying the engine. A
four-minute watchdog bounds hung processing; if it fires, physical delivery and
restoration may be uncertain. Re-enable the relay explicitly after interruption.
The earlier screen-off evidence above concerns the headless test, not a verified
Calendar end-to-end trial. A user reported successful WhatsApp notification relay operation on 2026-10-16;
precise versions and structured evidence were not captured. The revised UI and
repeated idle delivery still need their own physical validation.

The relay now persists a user-selected app allowlist (Calendar by default),
discovers installed launcher apps, and supports WhatsApp-style conversation
updates through standard Android MessagingStyle timestamps. Only command IDs
and expiry cross to Flutter; message text is not forwarded or persisted. The
original native listener/service names and channels are retained so updating the
demo does not intentionally reset notification access. Providers that do not
supply message timestamps still get conservative once-per-key suppression.

The relay now retains up to 100 metadata-only lifecycle entries and returns typed
worker outcomes. Test vibration reuses the running worker rather than opening a
second device session. Device/Lab actions explicitly stop and await relay cleanup
before using the SDK. See the [demo guide](demo.md) for result meanings and agent
controls. Relay history never stores notification text, sender names, raw keys,
or BLE identifiers. A missing terminal history record is not delivery evidence.
