# Reverse-engineering status

This file separates confirmed protocol facts from candidates that are not safe
to expose through the package yet.

## Confirmed and packaged

- Direct tracker channel over BLE, Gattlink, DTLS bootstrap, and CoAP.
- Haptic setting read: `PUT /settings` with `0a0101`.
- Haptic setting write: `POST /settings` with the protobuf value `2` (low) or
  `3` (high).
- A changed setting produces one physical pulse. Restoring it produces another.
- The plugin closes its GATT client after every operation and does not alter
  pairing. The Air has one Gattlink/DTLS channel shared by all link clients, so
  an SDK session desynchronizes Google Health's session. Without intervention,
  Google Health recovers only through a ~60 s watchdog reconnect. A refused
  handshake makes the Air reset the link for all clients and switch identity;
  the plugin's post-operation handoff uses this so Google Health resynchronizes
  immediately. See [demo guide](demo.md#root-cause-and-fix-dev5).

## GATT information and standard heart rate

The current Air reports model `67`, firmware `67.20001.253.2`, manufacturer
`Fitbit`, and eight services. Standard Device Information reads are verified.
There is no `180F` Battery Service, so a battery percentage is not inferred.

`ABBAFD01` is the current connection configuration (interval, latency, timeout,
MTU, mode). `ABBAFD02` is connection status (bonded/encrypted flags and data length
limits). Both are read without subscribing to tracker traffic or opening DTLS.

The Air exposes standard heart-rate `180D` / `2A37`. Before activating its
sharing session, subscriptions returned GATT success but produced no data,
including while worn. Scanning while Google Health's “Share heart rate” page
was open discovered the Air. Connecting then produced a three-byte standard
measurement decoded to a non-zero BPM reading on 2026-10-14. Google Health
simultaneously displayed that it was sharing heart rate with the test phone.

Android's GATT history resolves the selected advertisement to the same bonded
Air connection, so this is evidence for a sharing-session prerequisite, not a
second physical address. The “Always visible” switch was off throughout and
was not changed. No private PSK or Goldengate session is needed for standard HR.

The package includes one-shot reads and a stream. Streaming was verified with
successive non-zero BPM samples roughly once per second.
Cancelling the stream removed the demo's GATT client; Google Health's client
remained connected. The read-only link status returned encrypted=true,
MTU=247, interval=150 ms. It decodes standard UINT8 or
UINT16 BPM, optional contact state, energy, and RR intervals. The observed sample
did not contain contact state or RR intervals; those optional fields remain
untested on hardware. Streaming must be stopped before a haptic operation.

References:

- [Bluetooth Heart Rate Service specification](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/HRS_v1.0/out/en/index-en.html)
- [Google Health heart-rate sharing instructions](https://support.google.com/googlehealth/answer/14236705?hl=en)

The Google Health source uses `aspen/request/adv_on`,
`aspen/request/session/accept`, and `aspen/info` for its sharing flow. These
resources have not been probed or exposed through this package.

## Single-tap investigation (2026-10-17)

Goal: one haptic pulse at the user's own strength, with no setting left changed,
as Google Health produces for heart-rate sharing requests. **Result: achieved
through `/settings` alone; packaged as `FitbitAir.tap()`.**

**The haptic rule.** On the tested Air, writes to the `/settings` haptics field
(OFF `1`, LOW `2`, HIGH `3`; all accepted with `2.04`) produced a pulse only
when the strength became different from the last non-off strength. The wearer
reported:

| Write | Pulse |
|---|---|
| HIGH → HIGH (same value) | none |
| HIGH → OFF | none |
| OFF → HIGH | none |
| HIGH → OFF → LOW | one, at LOW |
| LOW → HIGH | one, at HIGH |

These trials spaced writes 2.5 s apart, so every change's pulse played in
full and a restore always cost a second pulse.

**Superseded pulses.** When the second change arrives before the first pulse
plays, only the second pulse is felt. The wearer counted, after a calibration
of the known two-pulse pattern (alternate, 800 ms, original → 2 pulses):

| Trial | Pulses felt |
|---|---|
| alternate then original, sent back to back | 1, at the original strength |
| both writes pipelined without awaiting a reply | 1, at the original strength |
| one message with two entries `[alternate, original]` | 1, at the original strength |
| the one-message form, 5× at HIGH | 1 HIGH pulse each |
| the one-message form, 3× at LOW | 1 LOW pulse each |

The strength read back unchanged after every trial. `tap()` uses the
one-message form (one request, no window where the alternate strength persists),
then reads back and restores if needed. A pulse at a strength other than the
current one cannot keep the current strength, because returning to it is
another change; `tap(intensity: …)` with a different strength therefore keeps
the requested strength and reports `changed`. This relies on firmware timing
observed on one Air (firmware `67.20001.253.2`); validate on other firmware.

**Native single-pulse paths need the paired-device key.** Google Health source
inspection shows two other mechanisms that produce single, configurable haptics:
notification records posted to `sb/records` (a switchboard record whose common
notification part carries a haptic-pattern field) and the heart-rate sharing
flow (`aspen/request/adv_on`, `aspen/request/session/accept`, `aspen/info`;
states IDLE → READY_FOR_CONNECTION → CONNECTING → SESSION_REQUESTED →
SESSION_ACCEPTED → SHARING_HR). Over the bootstrap session, `POST sb/records`
with an empty body, `GET aspen/info` and `GET aspen/summary` all returned
`4.01 Unauthorized`, like `liveactivity`, `/alarms` and `/sync/status`. Only
`/settings` and `/md/606` were accessible. The normal key is fetched by Google
Health from the user's account and stored privately; this project does not
obtain it.

Google Health also hosts a phone-side GATT notification service
(`16BCFD00-253F-C348-E831-0DB3E334D580`, ANCS-like source/control/data
characteristics) to which the Air connects. It belongs to Google Health's GATT
server registration; another app cannot notify through it, and registering a
duplicate service would risk Google Health's own link. Google Health's
all-app notification listener was not enabled on the test phone.

**No standard alert service.** The Air's GATT database contains GAP, GATT,
Device Information, `ABBAFD00` (link status), `ABBAFF00` (tracker channel),
`AC2F0045` (Golden Gate GATT-cache confirmation), `180D` (heart rate) and
`4EEE1C00` (not referenced by Google Health). There is no Immediate Alert
(`1802`) or Alert Notification (`1811`). Writable characteristics of unknown
services were not written.

## Faster commands with a warm link (2026-10-19)

Timings from command start to the tap write (when the pulse is felt), on a
Pixel 7 Pro with the Air's link idle at 150 ms / latency 3 or 120 ms / latency 7:

| Variant | Start → tap |
|---|---|
| Fresh connection per command | 5.5–8.2 s |
| Warm link (listen-only client kept open; no setup, no observation wait) | 3.5–5.8 s; ≈1.0–1.2 s when the link happened to be fast |
| Warm link + fast-link request at command start | **2.4–2.8 s** (10 of 10, no lost handshake races) |

The remaining floor is the connection-parameter update itself: the fast link
takes effect 2.2–2.5 s after the request, because the request travels over
the slow idle link. Each command held the fast link 3.5–4.0 s, then released
it to low power (the link then settled at 120 ms / latency 7, below the Air's
usual idle). Keeping the link permanently fast would remove the floor but
costs the Air's battery continuously, so it is not done.

The warm client has no radio traffic of its own; it shares the link Google
Health keeps open and only receives the Air's notifications. One command
started immediately after the relay (and the warm link) started received no
reply from the Air and gave up via the handshake retry; six deliberate
restarts did not reproduce it. Commands now use the warm link only after it
has been subscribed for 3 s, otherwise a fresh connection.

The early request is limited to the warm path. Earlier, a fast link during the
handshake let Google Health win the Air's handshake race; with the warm link
none of 10 trials lost the race, and a lost race is retried after 1.5 s.

## Passive tap observation (2026-10-17)

Earlier tap trials held a secure session on the Air's single tracker channel.
That desynchronized Google Health, and the unexplained ~60 s disconnects
(status 257) in those trials match Google Health's watchdog reconnect, which
0.1.3 fixed. They were therefore repeated without any session of our own.

**Method.** All GATT clients on the link receive the tracker channel's
notifications. A diagnostic client subscribed to `ABBAFF02` (tracker channel,
timing and length only; payloads are encrypted), `ABBAFD00` (link status) and
all five notify/indicate characteristics of `4EEE1C00`, and wrote nothing
else. Google Health was closed, so its own polling did not mask events.

| Trial | Gesture recognized? | Notifications from the Air |
|---|---|---|
| 4+ firm double-taps over ~90 s | Unknown (status light did not respond) | 0 |
| Alarm at 15:29 dismissed by double-taps; 4 min 18 s window | Yes (vibration stopped after several double-taps) | 0 |
| Control: Google Health opened to set the alarm | n/a | Hundreds: a 244-byte sync burst, then a 106-byte response plus 1-byte ack each second (live-activity polling) |

The control proves the listener receives everything the Air sends to the phone.
Neither idle double-taps nor a recognized alarm dismissal produced any traffic,
live or within the following minutes. With this firmware the Air handles taps
on the device only. **Real-time tap detection is not possible** through the
Bluetooth paths available to this package; a dismissal might appear in a later
Google Health sync, which is encrypted with the paired-device key.

### Sync-aware hold (implemented after 0.1.3)

The same passive view now gates every secure command. After subscribing to the
tracker channel and before opening its own session, the plugin observes the
Air's notifications for 0.7 s. Google Health's foreground polling is about
107 bytes/s; a sync transfer is a burst of large packets. If at least 400 bytes
arrived within the last 1.2 s, the command waits until the channel has been
quiet that long, for at most 10 s. The outcome is reported per command as
`channelHold` (`none`, `waited`, `max`).

Two interactions surfaced while testing:

- **Link boost timing.** The hold delays the handshake by 0.7 s, long enough
  for an early high-priority request to take effect. On a fast link Google
  Health tends to answer the Air's handshake first, and both stall (`DTLS`
  error `-1`). The boost is therefore requested only after our session exists.
- **Lost-race recovery.** That error is now treated as a lost race: the
  handshake is retried after 1.5 s instead of waiting for the 6 s stall
  deadline.

Measured on a Pixel 7 Pro by pressing Google Health's sync, then sending a test
vibration about 1.1 s later (8 trials, spaced 70 s apart):

| Outcome | Count |
|---|---|
| Google Health sync completed ("synced just now"), no ack timeouts | 8 of 8 |
| Vibration delivered with handoff `reset_observed` | 8 of 8 |
| Hold waited (Google Health was transferring: 967–1,384 bytes observed) | 3 |
| Air not yet transferring at observation time (0 packets) | 5 |
| Handshake race lost and recovered (2 with the 6 s deadline, 2 with the 1.5 s retry) | 4 |

A sync starts with Google Health contacting its servers; the Air's transfer
begins a few seconds later. The hold cannot see that coming, because only the
Air's notifications are visible to other clients, not Google Health's requests.
In those cases the race recovery applies, and Google Health still completed
its sync every time. Eight additional idle-channel commands needed no retry;
one of them waited 2.4 s for Google Health's post-handoff traffic.

## Live activity and heart rate

Google Health reads `GET liveactivity` once per second in its normal direct
communications session. The response is `LiveActivity.Activity`, effectively:

```proto
message Activity {
  optional int32 timestamp = 1;
  optional int32 steps = 2;
  optional int32 distance = 3;
  optional int32 calories = 4;
  optional int32 elevation = 5;
  optional int32 active_minutes = 6;
  optional int32 heart_rate = 7;
  optional HeartRateConfidence heart_rate_confidence = 8;
  optional int32 daily_zone_minutes = 9;
  optional int32 weekly_zone_minutes = 10;
}
```

On the tested Fitbit Air, the endpoint returned CoAP `4.01 Unauthorized` over
the bootstrap DTLS session used by the haptic setting. It apparently requires
the normal mobile-data DTLS identity and paired-device PSK. The package does not
attempt to obtain that private credential, so live activity is not in the
public API.

## Incoming interactions

The decompiled stack contains device-state/on-wrist events and unsolicited CoAP
resource handlers. `ON_WRIST_ALERT` is not evidence of a user tap, however, and
no wrist-tap event payload has been confirmed. Tap detection remains open work.

Further source inspection identifies incoming `POST events` with an
`EventSet.Set` protobuf body. `GoldenGateCommandHandler` registers its handler
with `CoapGroupRequestFilterMode.GROUP_1`; `EventConsumer` handles `WRS_ON` and
`WRS_OFF` as wrist-presence transitions. `Event` also contains RSET, BSNC, CGPS,
PING, AOFL and UNKNOWN, but no explicit tap enum. The existence of PING does
not identify a tap or a vibration command. Event parsing and authorization
would need separate hardware validation before exposing an on-wrist API.

### Secure event observation (2026-10-15)

The release demo now registers the local `events` POST handler with GROUP_1
and decodes bounded `EventSet.Set` payloads, including unknown uint32 IDs.
It acknowledges valid POSTs with `2.04`, but does not respond by issuing the
settings/sync operations that Google Health's `EventConsumer` would run.
No outgoing reset, alarm, haptic or configuration commands were sent.

During the measured 30-second window, READY was recorded at 08:17:39.837 on
the phone, followed by `RSET` at 08:17:40.296 and `BSNC` at 08:17:41.785.
Observation completed at 08:18:09.856 with two POSTs and no dropped POSTs.
The user reported performing the requested three double-taps, but their reply
arrived after completion; exact physical timing cannot be independently
established. No identifiable tap event appeared in the recorded window.
This does not establish that taps are unavailable through a different resource,
firmware state or normal paired-device session. At this stage wrist-presence
events had not been received; see the later route probe below. `RSET` here is a received protocol event, not an
instruction from the demo to reset the device.

The first incoming-request attempt crashed the demo's native loop. Comparing
the installed app's JNI declarations identified an ABI mismatch in the adapted
`CoapGroupRequestFilter`: `create()` returns `NativeReferenceCreationResult`,
not a pointer-valued Long; `setGroup()` and `destroy()` return Int status codes,
not void. These declarations and status handling are corrected. A subsequent
release APK received both events without that crash. Reflection tests now
protect these signatures and the active native constructor signatures. This is
not a guarantee of compatibility with future Google Health native versions.
Google Health's process remained running, which alone does not prove its sync
was uninterrupted.

### Read-only resource boundary (2026-10-15)

One bootstrap session issued the following sequential GETs, with no writes:

| Resource | CoAP result | Body bytes |
| --- | --- | --- |
| `/md/606` | `2.05 Content` | 79 |
| `liveactivity` | `4.01 Unauthorized` | 0 |
| `/alarms` | `4.01 Unauthorized` | 0 |
| `/sync/status` | `4.01 Unauthorized` | 0 |

The public diagnostic result contains only path, status and byte count; it
does not expose alarm/health data or device identifiers from the response body.
This is a fixed allowlist, not an arbitrary resource or write API. These results
apply to the tested firmware and bootstrap role, not all Fitbit devices or roles.

### Tap-path reassessment and physical positive control

The user subsequently reported that double-tapping did not produce a visible
status LED flash. This does not prove the tracker failed to recognize every
earlier attempt (the LED can also be obscured), but it means recognized gestures
were not established as a positive control. Google's [Air guide](https://support.google.com/googlehealth/answer/17033101?hl=en)
documents a firm double-tap waking the status light, and double-tap alarm
dismissal. It does not document a phone-facing gesture API.

The example now guides a 30-second secure-event trial: five seconds of quiet,
20 seconds for spaced double-taps, then five seconds of quiet. A user can mark
an observed LED flash while the capture is active. These timestamped manual
markers are explicitly distinguished from Bluetooth events and stay in the
bounded local log. A marker is delayed by the user's reaction time and is not
an exact sensor timestamp. This instrumentation is not a tap detector.

Additional source tracing narrowed the remaining leads:

- `GoldenGateCommandHandler.registerResourceHandlers()` registers both
  `events` (GROUP_1) and `eventsSecure` (GROUP_0), using the same event decoder.
  `StackPeer.getDtlsEventObservable$lambda$0$4` changes the local filter to
  GROUP_0 only when the session identity is neither `BOOTSTRAP` nor `hello`.
  The current monitor covers `events` in the bootstrap session. This is not
  evidence that `eventsSecure` carries taps, nor that changing our local filter
  changes the tracker's authorization or event emission behavior.
- `agxl` maps `phy` (`TRACKER_TAP_SUCCESS`) into `agjq` (`TrackerTapped`). Its
  producer is `pwl` receiving `pid.TAPPED`; `ooi` emits that result after
  `oxu.showPairingCodeOnDevice(TAP)` completes `ozn`, named
  `DisplayClassicPairConfirmation`, using `AirlinkPacketDecoder`. This is a
  classic pairing flow. The Golden Gate `PairCommandsHandler` instead sends
  `PUT pair/display` and maps success to `DISPLAYED_CODE`, not `TAPPED`.
  No pairing operation was invoked during this investigation.
- `AspenRemoteUi.AspenInfo.AspenState` describes heart-rate-sharing states
  (`SHARING_HR`, `SHARING_PAUSED`, connection/session states). The feature name
  is not evidence for remote gesture input.
- The inspected `LiveActivity.Activity` schema exposes summary activity/heart
  rate fields, not raw acceleration samples. Its existence therefore does not
  establish a route for implementing our own accelerometer tap classifier.

Next evidence needed: repeated LED-confirmed taps correlated with incoming
events and compared against quiet/movement controls. If the bootstrap path
still has no signal, a read-only capture of the normal Google Health session
can test whether recognized taps cause traffic at all, though encrypted
traffic timing alone cannot establish a reliable gesture decoder. A useful
package API still requires identified semantics, false-positive tests and
coexistence verification; it is not implemented yet.

### Additional incoming routes and WRS_ON receipt

The normal-ACK tap trial on 2026-10-15 at 13:30:20–13:30:50 completed
30,003 ms of observation. It received RSET on both event routes, a sync/request,
and BSNC on events. No manual markers were recorded; the user confirmed
double-taps but no LED flash. No tap-specific event was identified. This does
not establish that the hardware recognized the gestures. The next positive
control is dismissal of a user-authorized alarm, with separate manual markers
for alarm onset, attempted dismissal, and vibration stopping. Even a detected
dismissal would not establish unrestricted, idle-state tap detection.

During preparation of this positive-control trial, Google Health displayed
connected/81% on its device page but failed to synchronize alarms, including
one retry. The add-alarm control stayed disabled while loading. No test alarm
was created and no diagnostic capture was active. Cause is unestablished;
normal Google Health reconnection/coexistence remains a reliability question,
not a validated property of the bootstrap monitor.

Google Health subsequently loaded the alarm list without resetting pairing.
With user approval, a temporary 13:39 alarm was saved (Smart Wake off), leaving
the existing 07:10 alarm unchanged. The alarm-dismissal capture ran from
13:38:56.375 to 13:39:26.404 on 2026-10-15 (30,005 ms reported coverage).
Only RSET on events and eventsSecure arrived, both before the alarm.
Manual markers: alarm started +14,684 ms, dismissal tap attempted +22,812 ms,
vibration stopped after tap +28,929 ms. No other incoming requests or dropped
events were reported. This shows no event correlated with the marked actions
in this window, not proof that all sessions or routes lack dismissal reports.
The final marker left only about one second of observation; a delayed report
is not excluded. The user initially reported that double-tapping stopped
the alarm rather than it stopping on its own. However, the later no-tap control
also stopped vibrating spontaneously, so this observation alone does NOT
establish a physically recognized gesture or causal dismissal.

A repeat using a 90-second configured window and a temporary 13:45 alarm
was READY at 13:44:38.966. Markers were onset +26,980 ms, attempted tap
+32,506 ms, stopped +34,760 ms. The user reported an impression that tapping
stopped it. The connection failed with status 257 at 13:45:38.524, after
59,558 ms of observation, approximately 24.8 seconds after the stopped marker.
Only the two initial RSET posts were received, with zero other requests.
This is an interrupted trial with useful post-action coverage, not a completed
90-second negative result. Disconnect cause is not established.

No-tap control: the temporary alarm was moved to 13:49. The user agreed to
avoid tapping for at least 20 seconds and subsequently reported that vibration
stopped without touching the device. Capture was READY at 13:48:52.179;
the onset marker was +12,791 ms. Only the two startup RSET posts arrived;
no other requests. Status 257 interrupted observation at 13:49:51.883 after
59,703 ms. Two approximately 60-second disconnects suggest investigating
session lifetime/idle teardown, without establishing the cause. Natural
vibration stopping confounds the earlier claimed tap dismissals. A future
positive control needs a discriminator beyond cessation of vibration (for
example verified alarm state or a reproducible difference in resumed pulses).
No tap-detection API is justified by these observations.

After the no-tap trial, Google Health displayed 13:58 in place of 13:49.
The user confirmed making no edit. This nine-minute advancement is consistent
with automatic snoozing, but its exact mechanism has not been traced. Earlier
tap trials showed their original times with "tomorrow" after cessation,
providing a possible state-based discriminator for a future controlled trial.
Do not discard this distinction or claim either causal explanation proven.
The user authorized removal of the advanced temporary test alarm. Google
Health confirmed deletion; only the unchanged 07:10 alarm remained.

The initial diagnostic monitor registered observers for the other phone-side routes
traced in `GoldenGateCommandHandler`: `eventsSecure`, `sync/request`, `md/3d02`,
`md/1802`, `sb/reply` and `sb/delete-records`. They returned `4.04`, retaining the
response used when those routes were absent from this endpoint. This differs
from Google Health's full handlers, which can acknowledge and act on requests.
These local GROUP_1 observers do not issue requests to tracker resources or
obtain a normal paired key. Only the bounded `eventsSecure` event-ID schema is
decoded; other probes return method, route label and byte count. Raw bodies are
not returned to Dart or written to the repository. Route labels identify the
registered prefix, not arbitrary URI suffixes.

For the unreleased 0.1.2 experiment, both `events` and `eventsSecure` now use
the same bounded EventSet decoder and acknowledge valid POSTs with `2.04`;
malformed payloads receive `4.00`. The five other routes still return `4.04`.
Acknowledgment does not execute Google Health's sync/reset handlers or issue
settings commands. This tests whether rejection was affecting event delivery;
it is not evidence that tap events exist. The demo reports the acknowledgment
mode at READY and separates manual tap-attempt markers from LED confirmation.
The observations below predate this acknowledgment change. These diagnostics
are not part of the already-published 0.1.1 release.

An earlier completed 30-second trial recorded five six-byte `eventsSecure`
POSTs and two manual wrist-removal/replacement markers. Those bodies were not
retained and therefore cannot be retrospectively decoded. Repeated requests
may include retries after rejection; their count alone is not an event count
or evidence of gestures.

The later release capture started at phone time 13:17:21.988 and completed
after 30004 ms. It received `RSET` on `events`, a 12-byte `eventsSecure` POST
decoded as `[WRS_ON, RSET]` about 447 ms after ready, a 35-byte `sync/request`
POST, then `BSNC` on `events`. No posts exceeded the limits. The user reported
missing this physical trial; no manual markers were recorded. This establishes
receipt of an on-wrist event in the bootstrap session, but not physical
transition timing or tap detection. The route name and Google Health's local
GROUP_0 policy did not mean the Air would never send the route in this session.

A subsequent user-started trial ran for 30002 ms with two manual markers, one
POST on `events`, four observed requests on other routes, and none dropped.
It received an initial `WRS_ON`, then `[WRS_ON, RSET]`, followed by `WRS_OFF`
at about +10.0 seconds and `WRS_ON` at about +18.1 seconds. The user marked
removal at +11.6 seconds and replacement at +23.6 seconds and confirmed both
actions. Both reports preceded their manual markers; those reaction delays
prevent a sensor-latency estimate. This is hardware evidence for both presence
states in a physical removal/replacement trial, not a tap signal or validation
of continuous monitoring. The experimental `watchWristPresence()` Dart stream
filters only these source-traced IDs from the two EventSet routes, preserves
order and repeated reports, and retains the same bounded capture lifecycle.

A subsequent user-started tap trial covered 30001 ms. It received one `RSET`
POST on `events` and one `RSET` POST on `eventsSecure`, with no later event or
other observed route request, no dropped messages and no LED markers. The user
confirmed performing the taps and reported no LED flash. Thus adding the
second route did not identify a tap signal in this trial, while recognition
of the physical gestures remained unconfirmed. It does not justify treating
RSET or wrist-presence transitions as taps, or concluding that recognized taps
can never be observed. No further automatic capture was started afterward.

Connection reliability remains open. In the preceding attempts, one connection
timed out during secure-session setup and another received `RSET` before
Android reported a disconnect with status 257. The latter lasted less than a
second after ready. That status does not establish the underlying cause or
whether Google Health contributed. Charging/unplugging had been reported by
the user, but no causal relationship was established. Interrupted captures now
emit partial counts and actual observation milliseconds before their error;
zero coverage means the observer never reached ready. These failures cannot be
used as negative gesture trials. No pairing or Google Health settings changes
were performed to obtain these observations.

## Credential provisioning confirmation

`qax.run()` case 5 obtains JSON through the app's authenticated service using
`user/-/devices/device/<deviceId>` and the `keys` subresource. It reads
`data.attributes.primaryKey` and `secondaryKey`, each containing `key` and
`keyId`, Base64-decodes both keys, and stores them as AES SecretKey entries.
It then persists the BKS through `openFileOutput()` and `keyStore.store()`.
`qpa.call()` simply invokes `oyh.e.d().k()` to load the store. Thus these bytes
are fetched, not derived from the Bluetooth address or android_id. The latter
participates in local keystore protection, not key generation. Two named keys
do not by themselves prove the rotation schedule. No provisioning calls were
made and no credentials were extracted during this work.

The optional diagnostic stream subscribes to notifications/indications in
`ABBAFD00-E56A-484C-B832-8B17CF6CBFE8` (link status) and
`4EEE1C00-4133-479B-8663-02C84BDC14BE` (unclassified). It deliberately excludes
the tracker/DTLS service and writes only subscription descriptors. Seven
subscriptions succeeded on the tested Air; two initial link-status values
arrived. During the physical double-tap trial, the peripheral terminated the
connection (HCI reason 0x13, Android GATT status 257). Google Health reconnected
with the existing bond. No tap-correlated payload was established. The cause
and exact timing relative to the taps remain unresolved; this is inconclusive,
not proof that taps cannot be observed.

A later passive observation stayed connected through the observation window
with all seven subscriptions accepted. It received only one link-configuration
notification and no identifiable tap payload. The requested physical sequence
was not confirmed before cleanup, so this is not a completed negative tap
trial. The monitor was explicitly stopped to release its Bluetooth client.

## Battery source lead

The tested Air has no standard Battery Service. Decompiled Google Health
`CoapRequestUtils.providePathForProtocol()` maps protocol numbers to hexadecimal
`/md/` resource paths, and `ProtobufTranslatorsRegistry` maps decimal 1542
(0x606) to `DeviceInfoTranslator`. This identifies `/md/606` as a candidate
device-information resource, with `DeviceInfo.Info` fields `onCharger = 10`,
`voltage = 12`, and `batteryLevel = 13`.

A read-only hardware probe subsequently returned CoAP `2.05 Content` through
the bootstrap path. This resource is therefore accessible on the tested Air;
it must not be grouped with the rejected `liveactivity` resource. The optional
diagnostic API exposes only access status and these selected battery fields,
not the original body or its device identifiers. The selected fields were also
decoded on hardware; battery level was close to Google Health's displayed
percentage, but no across-firmware scale or voltage calibration was established.
Its small protobuf decoder
skips unrelated fields, rejects malformed/truncated data and preserves missing
values as absent. Standard GATT device-info reads still do not open a Golden
Gate session merely to populate batteryPercent.

**Calibration (2026-10-17).** At 22:10 the Air reported level `55` while Google
Health's Devices page showed "55 %"; a second read at 22:15 again returned
`55`, with voltage `3951` and charger `0`. The level is therefore the
percentage Google Health displays, and is exposed as `FitbitAir.readBattery()`.
Voltage is consistent with millivolts for a lithium cell at that charge
(3.95 V) and is reported as `voltageMillivolts`. At 22:21, with the Air placed
on its charger, the charger field read `1` (level `55`, Google Health "55 %");
every earlier off-charger read was `0`. It is exposed as `onCharger`.

Other candidates—notifications, alarms, battery alerts, sync status, and the
ping characteristic—must be traced and tested individually. They should not be
added to the stable Dart surface until their payload, side effects, credential
requirements, cleanup, and Google Health coexistence are verified.

## Status light and the settings surface (2026-11-03)

Driving the status light from the phone was the goal; it is not reachable.
Measured on a CPH2569 (Android 15), Air firmware 67.20001.253.2, Google
Health paired and working throughout.

**Observed.** A fixed GET allowlist of 122 paths was read in one secure
session. `/md/606` answered `2.05` with 79 bytes. `liveactivity`, `/alarms`,
`/sync/status` and `/time` answered `4.01`. `/sync/response` and `/settings`
answered `4.05`. Everything else answered `4.04`, including `/led`, `/leds`,
`/light`, `/display`, `/screen`, `/ui`, `/device`, `/status`,
`/notification`, `/notifications`, and every `/md/600`–`/md/699` except 606.

**Conclusion.** The light has no resource. Resources that exist behind
Google Health's credentials answer `4.01` rather than `4.04`, so the
light-shaped names are absent from the routing table for this filter group,
not merely refused to an unauthenticated peer.

**Observed.** `/settings` refuses GET, so its selectors were enumerated with
the selector-only PUT body the haptic read already uses, `0a 01 NN`, which
carries no value to assign. Of selectors 2–127, exactly one answered:
selector 2 returned `0a 04 12 02 08 00`, the shape of the haptics reply one
field across. Selectors 3–127 answered `4.00`. Selector 1 was not probed,
being haptics.

**Observed.** Selector 2 was written to 1, read back as 1, held two seconds
and restored to 0, verified, twice. No vibration and no light on either run,
with the Air worn. Google Health continued to sync afterwards.

**Conclusion.** The device exposes two settings within a one-byte selector,
and only haptics has a side effect on the device. Haptics is usable precisely
because *changing* it makes the Air buzz; setting 2 offers no equivalent, so
there is nothing to borrow for a light or any other output.

**Hypothesis, untested.** Selectors above 127 would need a two-byte varint
and a different message shape, and were not swept. Identifying setting 2
without writing to it looks possible by toggling the Air's own options in
Google Health and re-reading the selector; that was not needed once the
toggle proved inert.

`/sync/response` is deliberately left alone. It is write-only and carries the
sync protocol, so probing it means sending messages of unknown shape into the
mechanism that moves activity and sleep data; unlike a selector carrying no
value, no bounded version of that probe was available.

## Who is calling (2026-11-03)

**Observed.** On the same phone and firmware, `Notification.CallStyle`
notifications carry
a `Person` in `android.callPerson`. The Google dialer's `Person` has a
`content://com.android.contacts/...` lookup URI and a display name, and no
key. WhatsApp's has a display name only: no URI, no key, nothing dialable.

**Conclusion.** A dialer call can be attributed to a contact by resolving the
lookup URI to a number, which survives the contact being renamed. A WhatsApp
call can only be attributed by display name, which does not.
