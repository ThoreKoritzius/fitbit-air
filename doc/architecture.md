# Architecture

The public Dart surface deliberately hides the native transport:

```text
FitbitAir.isAirPaired() / FitbitAir.vibrate()
                    │ MethodChannel
                    ▼
             FitbitAirPlugin.kt
                    │ Android GATT
                    ▼
       Golden Gate JNI (bundled libxp.so)
                    │
       CoAP → DTLS → Gattlink → tracker BLE channel
                    │
                    ▼
                 Fitbit Air
```

`FitbitAirPlugin` owns one short-lived operation at a time. It locates a bonded
device whose Android name contains `Fitbit Air`, connects to the tracker service,
subscribes to its receive characteristic, bridges BLE packets into Golden Gate,
and waits for a DTLS session. It then performs the bounded `/settings` sequence
and tears down GATT in every success, error, or timeout path.

`BundledNativeLoader` loads the plugin's own Golden Gate core (`libgg.so`) and
JNI bridge (`libxp.so`), built reproducibly from public source by
`tool/native/build.py`. The Kotlin bindings match that upstream JNI ABI.

The plugin intentionally does not expose CoAP, DTLS, Gattlink, Bluetooth handles,
or protocol payloads to Dart. Additional verified operations should be modeled as
small typed Dart methods and implemented behind this boundary.

## Haptic sequence

1. Read haptic intensity using `PUT /settings` and payload `0a0101`.
2. Accept only the known low/high responses (`...0802` or `...0803`).
3. Write the alternate intensity using `POST /settings`.
4. Wait 800 ms, producing the user-visible haptic effect.
5. Restore the original intensity and read it back.
6. Hand the tracker channel back to Google Health (see below).
7. Close the Golden Gate stack and Android GATT connection.

### Channel handoff

The Air's tracker channel is single-tenant. After every DTLS operation the
plugin runs a short session whose key resolver refuses every identity. The Air
answers the failed handshake by resetting the link for all clients; the plugin
stops sending at that reset and closes, leaving Google Health to handshake
alone. The method-channel result is returned after the handoff (10 s maximum).

An operation error after the read triggers another best-effort restore. The API
returns success only after a follow-up read matches the original value.

`tap()` replaces steps 3–5 with one `POST /settings` carrying two entries,
`[alternate, original]`. The Air supersedes the alternate's pulse, so the wearer
feels one pulse at the original strength, which is then read back and verified
(and restored if needed). With an explicit, different strength it writes that
strength once instead.

`pulse()` omits steps 4–5: it reads the current strength, writes the opposite
strength once, verifies it, and intentionally leaves it active. This is the
one-pulse primitive. `setHapticIntensity()` follows the same write/verify path
for an explicitly requested strength and performs no write when already set.
Google Health remains the pairing owner and may independently reconcile its
stored setting after this short-lived connection closes.

## Deliberate limitations

- Android arm64 on Pixel 7 Pro is the verified target. The armeabi-v7a and x86_64
  libraries are bundled but untested on hardware.
- Pairing remains owned by Google Health. The plugin does not provision or copy
  the normal device-specific mobile-data PSK or read private app data.
- The haptic operation works through the bootstrap session observed on the Air.
- Incoming wrist taps and complete activity statistics remain unimplemented.

## Standard GATT reads and heart rate

Device information and connection status use their GATT characteristics directly.
They do not initialize the native library or subscribe to the tracker channel.
Unavailable optional fields remain null, with rejected reads in `readErrors`.

The standard Heart Rate Measurement (`180D` / `2A37`) is a separate path that
requires Google Health's sharing session to be active. Both a one-shot read and
an EventChannel stream are available. Streaming retains one Bluetooth client
until the listener cancels, the Air disconnects, or the plugin detaches.
The initial measurement has a 45-second deadline; no-data reads can be cancelled.

The plugin serializes reads, scans, streams, and haptics. From 0.1.1, a
process-wide identity-based lease prevents a separate Flutter engine in the
same app process from opening another SDK transport. A non-owner's cleanup
cannot release another engine's lease. This does not coordinate with Google
Health or another application/process.

Stop the heart-rate
subscription before another operation; concurrent requests receive `BUSY`.
Cancelling a read does not disable other apps' CCCD subscriptions. Cancelling
a haptic write is rejected so its verification/restore sequence can finish.

## Find my Air signal path

`FitbitAir.watchProximity()` uses a dedicated `AirFinder` GATT client for the
already bonded device. It connects directly and calls Android's remote RSSI
read about once a second. It does not scan, discover services, subscribe to a
characteristic, enter the secure transport, or write to the tracker. RSSI is
smoothed for presentation, while each event also carries its raw reading.

The stream emits a searching state after stale or failed readings and clears
its smoothing history on reconnect. Connection attempts are time-bounded and
retries are spaced out. Cancelling the stream or detaching the Flutter engine
closes the GATT client. The Air Relay example stops the stream in the background
and starts it again when the finder screen resumes. RSSI is a relative signal
indicator, not distance or direction; see [finder limitations](finder.md).

## Notification command dispatch

`fitbit_air_commands.dart` is a provider-neutral Dart helper. A bounded queue
serializes accepted haptic commands, rejects stale IDs/expired commands, limits
the interval between attempts, and remembers IDs after failures to avoid blind
replay. Its deduplication cache is finite and isolate-local. The host app owns
authentication, durable replay protection, push delivery, and background
execution. The Android lease guards the device transport across engines but
does not supply a cross-engine message queue. See [background integration](background.md).
