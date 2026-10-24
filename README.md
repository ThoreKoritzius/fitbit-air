# fitbit_air

Experimental Flutter access to a Fitbit Air over its direct Android Bluetooth
transport: haptic controls, live heart rate, device information, and connection
diagnostics. Haptics are one use case, using this communication path:

```text
Flutter → Android BLE → Gattlink → DTLS bootstrap session → CoAP /settings → Air
```

This is not a Fitbit Web API wrapper. It does not require OAuth, root, unpairing,
or access to Google Health's private data. The Air must already be paired to the
same Android phone in the installed Google Health app.

> [!WARNING]
> This is independent reverse-engineering research, not an official Google or
> Fitbit SDK. It relies on private, version-sensitive device protocols and on a
> compatible, authentic Google Health installation. Android arm64 is the only
> configuration tested so far. Taps on the Air are not reported to the phone.

## Air Relay

<img src="doc/images/air-relay-home.png" alt="Air Relay home screen showing notifications on, connection and battery status, and recent activity" width="280" align="right">

The [example](example/) is **Air Relay**, a complete Android app built on this
package: it turns phone notifications into a gentle vibration on your Fitbit Air.

- **Choose what buzzes:** pick apps, or specific WhatsApp chats, and give each
  its own vibration (one pulse, two pulses, or a strength).
- **Stays out of the way:** bursts of messages merge into one follow-up buzz,
  quiet hours and a timed pause silence it, and Google Health keeps syncing.
- **Shows the Air's status:** connection, battery and recent deliveries at a
  glance, with research tools under Device → Developer tools.
- **Runs in the background** as a foreground service once you grant
  notification access in its guided setup.
- **Private by design:** no internet permission, no backup; notifications are
  matched on the phone and never stored. See the [privacy policy](PRIVACY.md).

<br clear="right">

Download the signed APK from [GitHub releases](https://github.com/ThoreKoritzius/fitbit-air/releases)
(verify it with the `.sha256` file), or build it yourself: connect an Android
phone with the Air already paired in Google Health, then:

```bash
cd example
flutter run
```

## Install

```bash
flutter pub add fitbit_air
```

Or add it to `pubspec.yaml`:

```yaml
dependencies:
  fitbit_air: ^0.2.1
```

To try unreleased changes, depend on the Git repository instead:

```yaml
dependencies:
  fitbit_air:
    git:
      url: https://github.com/ThoreKoritzius/fitbit-air.git
```

**Requirements:** an Android phone (arm64 tested) with the Fitbit Air paired in
the Google Health app. The plugin declares the Bluetooth permissions; on
Android 12+ the user is asked for Nearby devices access on first use.

## Use

```dart
import 'package:fitbit_air/fitbit_air.dart';
```

### Check for a paired Air

```dart
if (!await FitbitAir.isAirPaired()) {
  // Ask the user to pair the Air in Google Health first.
}
```

### Send a single tap

```dart
final result = await FitbitAir.tap();
print('Tapped at ${result.intensity.name} strength');
```

`tap()` produces exactly one pulse at the vibration strength chosen in Google
Health, and leaves that setting unchanged. To use a specific strength:

```dart
final result = await FitbitAir.tap(intensity: HapticIntensity.low);
if (result.changed) {
  // The Air now keeps the low strength: it only vibrates when its strength
  // changes, so it cannot pulse at one strength and return to another silently.
}
```

### Haptic operations at a glance

| Method | Pulses | Vibration strength afterward |
|---|---|---|
| `tap()` | 1 | Unchanged |
| `tap(intensity: …)` | 1 | The requested strength |
| `vibrate()` | 2 | Unchanged (changed, then restored) |
| `pulse()` | 1 | Switched between low and high on every call |
| `setHapticIntensity(…)` | 1 if the value changes, else 0 | The requested strength |
| `getHapticIntensity()` | 0 | Unchanged (read only) |

Every write is read back from the Air and verified. Each command takes a few
seconds, and only one runs at a time.

### Read device information and heart rate

```dart
final info = await FitbitAir.readDeviceInfo();
print('${info.model} running firmware ${info.firmware}');

// Requires "Share heart rate" to be active in Google Health.
FitbitAir.watchHeartRate().listen((sample) {
  print('${sample.beatsPerMinute} bpm');
});
```

### Battery, connection and proximity

```dart
final battery = await FitbitAir.readBattery();
print('${battery.percent} %');

// Passive: no connection of our own, safe to keep running.
FitbitAir.watchConnection().listen((event) {
  if (!event.connected) {
    // Out of range, Bluetooth off, or the Air powered down.
  }
});

final rssi = await FitbitAir.readSignalStrength(); // e.g. -67 dBm
```

`readBattery()` opens a short secure session (a few seconds) that waits while
Google Health is syncing, so read it on demand rather than polling frequently.
Connection events and signal strength do not touch the secure channel.

### Handle errors

Every method throws a `FitbitAirException` with a stable `code`:

```dart
try {
  await FitbitAir.tap();
} on FitbitAirException catch (error) {
  switch (error.code) {
    case 'AIR_NOT_FOUND': // No Air paired in Google Health.
    case 'BLUETOOTH_PERMISSION': // Nearby devices permission denied.
    case 'BUSY': // Another command is still running.
    case 'HANDSHAKE_TIMEOUT': // Google Health held the Air; try again shortly.
    default:
      print(error.message);
  }
}
```

`error.kind` groups codes by what the user can do: `temporary` (retry
later), `setup` (fix Bluetooth, a permission or pairing), `compatibility`
(the Air answered unexpectedly, for example after a firmware update) or
`unknown`. A single compatibility error can still be a glitch; when it
repeats, suggest checking for an app update.

### Prepare for firmware updates

The Air's interface is undocumented, so a firmware update can change it.
`FitbitAir.testedFirmware` lists the versions this package release was
verified with; compare it with `readDeviceInfo().firmware` to tell users when
their Air runs an untested version:

```dart
final firmware = (await FitbitAir.readDeviceInfo()).firmware;
if (firmware != null && !FitbitAir.testedFirmware.contains(firmware)) {
  // Show a gentle "not yet tested" notice, not an error.
}
```

The Google Health app no longer matters for compatibility: the plugin ships
its own transport and loads no code from it.

### Vibrate on notifications

For alerts from push messages or other apps, queue commands through
`AirHapticDispatcher` from `package:fitbit_air/fitbit_air_commands.dart`. It
serializes commands and drops duplicates and expired ones. It retries only a
failure that guarantees nothing reached the Air (`HANDSHAKE_TIMEOUT`: the
secure session never started), once, after 3 s; a lost or ambiguous result
is never retried:

```dart
final haptics = AirHapticDispatcher();

await haptics.submit(AirHapticCommand(
  id: messageId,
  expiresAt: DateTime.now().add(const Duration(seconds: 30)),
  kind: AirHapticKind.tap,
));
```

Receiving notifications and keeping your app alive in the background are up
to your app; see [background integration](doc/background.md). The example app
implements a complete notification relay.

While your app expects to send commands, keep a listen-only link open so each
command starts about twice as fast:

```dart
await FitbitAir.keepLinkWarm(true);  // e.g. when your relay starts
// ... taps now reuse the open link ...
await FitbitAir.keepLinkWarm(false); // when it stops
```

The warm link never writes on its own and does not compete with Google Health;
Android reconnects it when the Air returns to range. If it stops receiving
from the Air, a command continues on a fresh connection and the link is
rebuilt, as it is after Bluetooth is switched back on.
`FitbitAirDiagnostics.readLinkHealth()` reports how often that happened.

## Current scope

| Capability | Status |
|---|---|
| Detect an Android-bonded Fitbit Air | Implemented |
| Trigger a short Air haptic | Physically verified from this package on Pixel 7 Pro (2026-10-14) |
| Exactly one pulse | `tap()` keeps the wearer's strength; felt as one pulse in every trial on Pixel 7 Pro / firmware 67.20001.253.2. `pulse()` (alternating strength) remains |
| Read/set low or high vibration strength | Implemented and verified |
| Device model, firmware, manufacturer | GATT read verified on Pixel 7 Pro / Air firmware 67.20001.253.2 |
| BLE services and connection status | Read-only discovery and link status decoder implemented |
| Connection state and proximity | `isAirConnected()`, `watchConnection()` (system events, no connection) and `readSignalStrength()` (dBm) verified on Pixel 7 Pro |
| Google Health activity | `FitbitAirDiagnostics.watchChannelActivity()` reports idle / polling / syncing passively; verified against opening Google Health and a manual sync |
| Scan advertised heart-rate sensors | Experimental; requires Nearby devices scan permission |
| Restore/verify prior vibration intensity | Physically verified |
| Root/OAuth/private PSK required | No, for the bootstrap haptic path |
| Detect taps on the Air | Not possible with the tested firmware: taps, including a recognized alarm dismissal, send nothing to the phone ([research](doc/research.md#passive-tap-observation-2026-10-17)) |
| Headless haptics | Release-mode background and controlled screen-off commands verified; a user-reported overnight relay run worked. Deep-idle/reboot reliability not systematically measured |
| Raw diagnostic events | Opt-in research stream; no confirmed tap decoder |
| Battery | `readBattery()`: level matched Google Health's percentage exactly; voltage in mV; charger flag verified on and off the charger |
| Heart rate | Standard BLE readings and continuous updates verified; requires an active Google Health sharing session |
| Google Health coexistence | Commands wait while Google Health is transferring and end with a channel handoff, so it resynchronizes immediately instead of after a ~60 s watchdog reconnect. In 8 vibrations sent during Google Health syncs, all 8 syncs completed |
| iOS | Not implemented |
| Non-arm64 Android | Bundled for armeabi-v7a and x86_64; untested on hardware |

The Google Health implementation contains a `GET liveactivity` resource with
steps, activity, and heart-rate fields, but the Air rejects it with CoAP `4.01`
in the bootstrap session available to this plugin. It is therefore documented
as research rather than exposed as a misleading public API. See
[`doc/research.md`](doc/research.md).

`readDeviceInfo()` returns available standard fields, the actual GATT service
inventory, and the read-only connection configuration/status. Battery is
nullable: the tested Air does not expose the standard Battery Service.

Heart rate uses the separate standard Bluetooth Heart Rate Service. Start the
sharing flow in Google Health (Connections → Air → Share heart rate). Keep that
page open during initial discovery. `scanHeartRateDevices()` finds advertised
sensors, and `readHeartRate(deviceId: ...)` reads one measurement. With sharing
already active for this phone, `readHeartRate()` uses the paired Air directly.

`watchHeartRate()` keeps one Bluetooth client open and streams measurements;
cancel its subscription to release that client before sending a haptic:

```dart
final subscription = FitbitAir.watchHeartRate().listen((sample) {
  print('${sample.beatsPerMinute} bpm');
});
// Later:
await subscription.cancel();
```

An accepted subscription alone does not guarantee data: the Air accepted it
but sent nothing before sharing was active. `cancelOperation()` cancels reads
and scans; haptic writes finish their verification before returning.

### Experimental device events

Import `package:fitbit_air/fitbit_air_diagnostics.dart` to use
`FitbitAirDiagnostics.watchDeviceEvents()`. This monitors a limited set of
link-status and unclassified BLE characteristics, not the encrypted tracker
channel. It enables notification subscriptions but sends no haptic/control
commands. The demo keeps a bounded event log in memory only. Payloads may
contain sensitive device data; do not upload them without user consent.

These events are **not wrist-tap detection**. The first physical tap test was
inconclusive: subscriptions succeeded, but the connection ended before any
tap-related payload could be established. Cancel the stream before using
other SDK operations. Health Connect and historical stats are deferred.

`FitbitAirDiagnostics.watchSecureEvents()` observes incoming CoAP `events`
POSTs for 30 seconds by default after its `ready` event. Research trials may
set `durationSeconds` from 30 to 90; the total deadline is that duration plus
15 seconds, so slow negotiation can shorten observation. Unlike the passive BLE monitor, it opens a separate
Golden Gate session and acknowledges valid event POSTs. Startup/sync events
were decoded on hardware; a user-reported double-tap sequence produced no
identified tap event in the recorded window. This is **not a tap or on-wrist
API**. Unknown IDs remain unknown; no settings, sync or haptic commands are
sent. Cancel before another operation; Google Health coexistence is not
guaranteed. The demo keeps only a bounded in-memory log.

The monitor decodes both `events` and `eventsSecure`, acknowledging valid
EventSet POSTs with `2.04` and rejecting malformed bodies with `4.00`.
Five other source-traced incoming routes retain a `4.04` rejection response.
The two event routes use the same bounded event-ID decoder; other probes expose only route,
method and byte count. Both `WRS_OFF` and `WRS_ON` were received through
`eventsSecure` during a user-confirmed removal/replacement trial in a bootstrap
session. Tap correlation still needs confirmation. An `interrupted` event reports actual observation
time before a transport error; an incomplete trial cannot establish that a
gesture produces no event. The demo includes a separate wrist-presence trial
with manual removal/replacement markers. Start it on the phone when ready.

For typed, experimental presence events, use
`FitbitAirDiagnostics.watchWristPresence()` from the diagnostics import. Each
event has `state` (`AirWristPresence.onWrist` or `offWrist`), `receivedAt`, and
`sourcePath`. It shares the monitor's 30-second window and transport ownership;
it retains repeated reports and supplies no synthetic initial state. Phone
receipt times do not establish the time the tracker detected a transition.

`FitbitAirDiagnostics.probeReadOnlyResources()` checks a fixed GET allowlist
and returns status/body length, never raw response bodies. On the tested Air,
device info succeeded; alarms, live activity and sync status returned `4.01`.
See [research notes](doc/research.md) for scope and limitations.

`FitbitAirDiagnostics.probeBatteryAccess()` opens a separate Golden Gate
session and performs a read-only GET of the device-info resource. Access was
verified on the tested Air. It returns CoAP status and selected raw battery
fields, not a verified percentage or voltage unit. This is an explicit research
operation with the same Google Health coexistence caveat as other secure
sessions; it is never run automatically by standard GATT reads.

## Background and notification integration

For notification-triggered commands and the headless Android test, see
[`doc/background.md`](doc/background.md). A release-mode headless haptic was
physically verified with the demo backgrounded. A controlled screen-off command
also completed with duplicate suppression. An earlier trial timed out during
session establishment; long-term and deep-idle reliability remain unproven.
The host app must provide message reception and lifecycle management;
a phone notification alone does not trigger the Air. The example includes an
optional [notification relay with an app selector](example/README.md#notifications--air-google-calendar-whatsapp-and-other-apps)
with explicit notification-access setup and Start/Stop controls. The demo has
Relay, Device and Lab screens, metadata-only relay history and structured trial
exports; see the [demo and agent guide](doc/demo.md).

## Native compatibility and provenance

The plugin bundles its own Golden Gate transport (`libgg.so` and `libxp.so`),
built from the public Apache-2.0 source at commit
`b8a6f2552e12aaf92c1549dc88a41c9b7e73f1aa` with Mbed TLS 2.28.10 and lwIP. It
loads no code from Google Health or any other app. The build is reproducible:
[`tool/native/build.py`](tool/native/build.py) rebuilds the libraries with a
pinned NDK, and CI checks that the output is byte-identical to the committed
files. The JNI-facing Kotlin bindings follow the same upstream revision. See
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for licenses.

Google Health updates no longer affect the transport; only changes in the
Air's firmware can.

## Safety and compatibility

- The operation is serialized; concurrent calls return `BUSY`.
- Starting with 0.1.1, that transport guard also covers separate Flutter engines
  in the same Android process. It cannot lock Google Health's separate process.
- Operations normally have a 45-second deadline. Secure-event research capture
  allows 30–90 seconds of observation plus 15 seconds for connection setup.
- Google Health remains the pairing owner. This plugin opens a short-lived
  connection and does not unregister, factory-reset, or alter pairing keys.
- The Air has one secure channel shared with Google Health. Each command first
  waits while Google Health is transferring (`channelHold`, at most 10 s), then
  hands the channel back afterward (`channelHandoff`). During 30–90 s research
  captures Google Health cannot sync.
- The plugin accepts the native implementation only from the expected signed
  Google Health package; a look-alike package is rejected.
- Firmware or Google Health updates may change the private protocol.
- Do not use this package for medical or safety-critical behavior.

## Name and trademarks

The Dart package name `fitbit_air` was unclaimed on pub.dev when checked on
2026-10-14. Availability is not a reservation until publication. Fitbit and
Google are trademarks of their respective owners; this project is unaffiliated.

## License

Plugin code is MIT licensed. Adapted upstream source and the bundled Golden Gate
libraries retain their Apache-2.0 license; the libraries also contain mbedTLS
(Apache-2.0) and lwIP (BSD-3-Clause). See
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Apps that ship the plugin must include those license texts. Call
`FitbitAir.registerLicenses()` once at startup to add them to Flutter's
license page (`showLicensePage` or the About dialog).
