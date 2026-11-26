# fitbit_air core (Android, no Flutter)

The Fitbit Air logic of the `fitbit_air` plugin as a plain Android library:
haptics with read-back verification, device reads, the warm link, and the
research streams. The Flutter plugin is a thin adapter over it
(`../src/main/kotlin/.../FitbitAirPlugin.kt`), so both get every fix.

Not published as an artifact yet. To try it in an Android app, include this
directory as a Gradle module:

```kotlin
// settings.gradle.kts
include(":fitbit_air_core")
project(":fitbit_air_core").projectDir = file("path/to/fitbit-air/android/core")

// app/build.gradle.kts
dependencies { implementation(project(":fitbit_air_core")) }
```

## Use

```kotlin
val air = FitbitAirClient(context)

// Ask for FitbitAirClient.requiredPermissions() first; the client never asks.
air.keepLinkWarm(true, object : AirReply {
    override fun success(value: Any?) {}
    override fun error(code: String, message: String) {}
})

air.tap(null, object : AirReply {   // null: the wearer's own strength
    override fun success(value: Any?) { /* verified: see the map's fields */ }
    override fun error(code: String, message: String) { /* e.g. BUSY, HAPTIC_UNCONFIRMED */ }
})

air.close()
```

- Call it on the main thread; replies and events arrive there.
- One operation at a time; others get `BUSY`. Error codes match the Dart
  `FitbitAirException` codes (see `lib/fitbit_air.dart`).
- The Air must be paired in Google Health on the same phone. Every secure
  operation waits while Google Health syncs and hands the channel back after.
- The Dart package adds a command queue on top (`AirHapticDispatcher`:
  dedupe, expiry, one safe retry). It is not part of this library yet.

The API is generic (`AirReply` with a map) for now and will get typed Kotlin
results before it is published.
