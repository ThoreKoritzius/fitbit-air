# Find my Air

Home’s **Find my Air** shortcut (beside connection and battery), or
Device → Find my Air, opens a foreground-only finder for the already bonded
Fitbit Air. The SDK API is `FitbitAir.watchProximity()`. It opens a separate
read-only GATT client, polls RSSI every second, and smooths it with an exponential
moving average (alpha 0.3). No service discovery, scan, secure bootstrap or
control write is involved. Cancel the subscription when leaving the screen.

A searching event clears the previous value and smoothing history. The app also
expires readings after five seconds. Connection attempts time out after 20 s;
lost clients are closed and retries are spaced by at least three seconds. RSSI
callbacks that fail or contain invalid readings cause a reconnect. The native
client is closed on stream cancellation and Flutter engine detach. The app
suspends it in the background.

The UI shows an animated 270-degree signal gauge with a needle and cold/warm/hot
labels, recent-signal sparkline, a stronger/weaker trend, raw and smoothed dBm,
sample count/range, and a bounded 300-update CSV export held in memory.
The vibration button uses the app's existing verified two-pulse path, including
restoration of the original strength and routing through the relay when active.
It does not automatically vibrate while searching. A scrollable help sheet
explains how to move and follow changes in the signal. The gauge is an original
canvas drawing using the app’s theme and respects reduced-motion settings.

## Physical trial: 2026-11-17

On the connected Android phone with a paired Air, the integration trial recorded
20 consecutive valid readings over approximately 19 seconds. The user moved the
Air away and back. Raw RSSI went from -65 down to -86 and returned to -72 dBm.
After cancellation and a new subscription, the first valid reading was -68 dBm.
The restart test passed. No per-sample device log is included in the repository.

This verifies live signal reception and subscription restart on one phone/Air.
The finder was also opened in the app on the phone. Its vibration button sent
two pulses, physically confirmed by the user, while RSSI monitoring continued.
Backgrounding closed the GATT client; resuming reopened it. Static analysis and
all 40 package tests passed.

This does not calibrate distance, establish direction, or prove recovery from
power loss/out-of-range/Bluetooth toggling. Coexistence with an active notification
relay requires further physical trials. The SDK's separate GATT client is
intended to avoid ownership conflicts with the relay's secure session.

Run the read-only physical trial with:

```sh
cd example
flutter test integration_test/finder_test.dart -d PHONE_SERIAL
```

Before installing a development build, check its signing certificate against the
installed app. Flutter may automatically uninstall on a signature mismatch,
which clears app settings and notification access. Use matching signing keys for
updates; `adb install -r` fails safely if signatures differ.

## Remaining range trials

- Keep orientation fixed, move the Air away through a doorway, then return.
- Take the Air out of range for over 20 seconds; confirm searching and recovery.
- Leave the finder and reopen it; background/foreground the app.
- Start the relay, open the finder, and receive a selected notification.
- Use Vibrate my Air and physically confirm both pulses and continued Health sync.

Results depend strongly on phone orientation, walls, people and radio conditions.
A signal gauge is an aid to searching, not a reliable distance or compass.
