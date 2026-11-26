# fitbit_air core: the Fitbit Air for Android apps, without Flutter

The Fitbit Air logic of the `fitbit_air` plugin as a plain Android library,
with the same operations, results and error codes as the Dart package:
haptics with read-back verification, rings, device reads, the warm link, and
the notification command queue. The Flutter plugin is a thin adapter over the
same code (`../src/main/kotlin/.../FitbitAirPlugin.kt`), so both get every fix.

Requirements: Android 7 (API 24) or newer, and a Fitbit Air paired in Google
Health on the same phone. No internet permission is needed.

## Add it

Not published as an artifact yet. Include this directory as a Gradle module:

```kotlin
// settings.gradle.kts
include(":fitbit_air_core")
project(":fitbit_air_core").projectDir = file("path/to/fitbit-air/android/core")

// app/build.gradle.kts
dependencies { implementation(project(":fitbit_air_core")) }
```

## Use

```kotlin
val air = FitbitAir(context)

// The library never asks for permissions; request these first.
requestPermissions(FitbitAir.requiredPermissions(), REQUEST_CODE)

air.keepLinkWarm(true)                       // faster commands; off by default

air.tap { result ->                          // the wearer's own strength
    result.onSuccess { tap -> /* tap.verified */ }
          .onFailure { error -> (error as FitbitAirException).kind }
}

val connection = air.watchConnection { event -> /* event.connected */ }
connection.cancel()

air.close()
```

- Call on the main thread; callbacks run there, once per call.
- One operation at a time; others fail with `BUSY`. Errors are
  `FitbitAirException` with a stable `code` and a `kind` (temporary, setup,
  compatibility, unknown), identical to the Dart package.
- Every secure operation waits while Google Health syncs and hands the
  channel back afterwards.
- A verified result means the Air accepted and the strength read back as
  expected. It is not proof that the wearer felt it.

## Notifications: the dispatcher

For haptics triggered by notifications or pushes, submit through one
`AirHapticDispatcher` instead of calling `tap` directly. It runs commands one
at a time and at least 2 s apart, drops expired ones, dedupes ids for five
minutes, and retries once only when nothing can have reached the Air.

```kotlin
val dispatcher = AirHapticDispatcher(air)

dispatcher.submit(AirHapticCommand.fromEventKey(
    source = "com.whatsapp",
    eventKey = "${sbn.key}|${sbn.postTime}",
    expiresAtMillis = System.currentTimeMillis() + 30_000,
    kind = AirHapticKind.TAP,
)) { result -> /* result.status */ }
```

`fromEventKey` makes the same id as the Dart package for the same pair.

## Not here yet

The research streams of the Dart `fitbit_air_diagnostics.dart` (secure and
device events, channel activity, wrist presence) are not part of the Kotlin
API.
