# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`fitbit_air` is an Android-only Flutter plugin that talks to a Fitbit Air over a
reverse-engineered private protocol (`Flutter → Android BLE → Gattlink → DTLS
bootstrap session → CoAP /settings → Air`). The Air must already be paired in
the Google Health app on the same phone. `example/` is **Air Relay**, a full
Android app (notifications → Air vibrations) and the reference consumer of the
package.

## Commands

Flutter is pinned to **3.47.8** in CI/release (3.50 deprecates `material.dart`;
migration pending). Android builds and Gradle tests need JDK 17 (`tool/check.sh`
falls back to Android Studio's bundled JDK on macOS).

```bash
./tool/check.sh                      # full pre-PR check: analyze, all tests, debug APK, JVM tests, publish dry-run

# Package (repo root)
flutter analyze
flutter test                         # all Dart unit tests in test/
flutter test test/fitbit_air_commands_test.dart            # one file
flutter test test/fitbit_air_test.dart --plain-name 'tap'  # one test by name
dart pub publish --dry-run           # expects a clean git state

# Example app (cd example)
flutter test                         # widget/unit tests in example/test/
flutter build apk --release
flutter build appbundle --release --dart-define=AIR_RELAY_STORE=true   # Play build

# Native JVM unit tests (cd example/android)
./gradlew :fitbit_air_core:testDebugUnitTest :app:testDebugUnitTest --console=plain
./gradlew :fitbit_air_core:testDebugUnitTest --tests '*AirWarmLinkTest*'    # one class

# On-device integration tests (cd example; phone connected, Air paired)
flutter test integration_test/demo_smoke_test.dart -d DEVICE_ID        # navigation only
#   add --dart-define=AIR_DEVICE_TESTS=true for read-only device ops,
#   and --dart-define=AIR_HAPTIC_TESTS=true to allow a haptic (explicit opt-in only)

# Native Golden Gate libraries (only when touching tool/native or jniLibs)
python3 tool/native/build.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --check
```

## Architecture

**Public Dart surface (`lib/`)** — three entry points, all thin wrappers over
platform channels:
- `fitbit_air.dart`: `FitbitAir` static API (haptics, device info, battery,
  heart rate, connection, proximity/finder, `keepLinkWarm`) and
  `FitbitAirException` with stable `code`s grouped by `kind`.
- `fitbit_air_commands.dart`: `AirHapticDispatcher`, a pure-Dart bounded queue
  that serializes, dedupes and expires haptic commands. It retries only
  `HANDSHAKE_TIMEOUT` (nothing reached the Air), once; ambiguous failures are
  never retried.
- `fitbit_air_diagnostics.dart`: opt-in research streams (device events,
  secure events, wrist presence, channel activity, link health).

Channels: MethodChannel `fitbit_air`, EventChannels `fitbit_air/{heart_rate,
connection,finder,secure_events,device_events,channel_activity}`.

**Native side** — the Air logic is a plain Android library without Flutter in
`android/core/` (`FitbitAirClient.kt` owns everything: one operation at a
time, others get `BUSY`, plus `AirTransportLease`, a process-wide lease so a
second client or Flutter engine can't open a competing transport). It is built
standalone as `:fitbit_air_core` from `example/android` (which keeps Flutter
out of it and runs its tests), and compiled into the plugin through
`srcDirs`. `android/src/main/kotlin/.../FitbitAirPlugin.kt` is only the
Flutter adapter: channels and permission requests. Secure operations bridge GATT packets
into the bundled Golden Gate JNI libs (`libgg.so`/`libxp.so`, loaded by
`BundledNativeLoader`; Kotlin bindings under `com/fitbit/goldengate/bindings/`
must match the upstream JNI ABI, guarded by `NativeBindingSignaturesTest`).
Standard GATT reads, heart rate, connection watching and the finder
(`AirFinder.kt`, RSSI polling) do **not** touch the secure channel.

Key design constraints (see `doc/architecture.md`):
- CoAP/DTLS/Gattlink/BLE handles and payloads are never exposed to Dart. New
  capabilities become small typed Dart methods behind the plugin boundary.
- The Air's tracker channel is single-tenant and shared with Google Health.
  Every secure op waits while Google Health syncs (`AirChannelState`/
  `AirChannelActivity`, ≤10 s) and ends with a deliberate failed-handshake
  "channel handoff" so Google Health resyncs immediately.
- Every haptic write is read back and verified; on error a best-effort restore
  runs. Haptic writes cannot be cancelled mid-restore. `tap()` writes
  `[alternate, original]` in one POST so exactly one pulse is felt.
- `AirWarmLink` keeps a listen-only link open to speed up commands.

**Example app (`example/`)** — Dart UI in `lib/main.dart` + `lib/demo/`;
`demo/demo_controller.dart` exposes every button as a named action
(`actionIds`, `runAction(id)`) used by integration tests. The relay itself is
native: `example/android/app/src/main/kotlin/.../` holds the notification
listener, `CalendarRelayService` (foreground service running its own Flutter
engine via `fitbit_air_example/calendar_worker`), `HeadlessHapticService`, and
rule/coalescing/tone policies (each with JVM tests). `kStoreBuild`
(`lib/build_flavor.dart`, `--dart-define=AIR_RELAY_STORE=true`) strips
Developer tools/Lab and the wrist-presence trial from the Play build.

Stable Flutter keys (`nav-*`, `relay-toggle`, `action-<actionId>`, etc., listed
in `doc/demo.md`) and structured report schemas are part of the test
interface — keep them stable and covered by tests.

## Project rules (from CONTRIBUTING / docs)

- Keep observed behavior, decompiled-code conclusions and hypotheses clearly
  separated in docs and code comments. A protocol success (`verified`) is not
  proof a vibration was felt; never turn it into a synthetic physical
  observation, and never send haptics from automation without the explicit flag.
- Decoder tests use synthetic protobuf fixtures, never captured device bodies.
  Never commit Bluetooth addresses, device identifiers, pairing keys, tokens,
  APK-extracted libraries or private app data.
- The example app has no internet permission and must not gain one; exported
  reports are sanitized (see `PRIVACY.md`, `doc/play-store.md`).
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
  `<type>(<optional scope>): <imperative summary>`, e.g. `feat(finder): add
  RSSI trend`, `fix: restore intensity after handshake timeout`. Types: `feat`,
  `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.
  Mark breaking API changes with `!` (`feat!: …`) and a `BREAKING CHANGE:`
  footer. Branch names use the same prefixes (`feat/…`, `fix/…`).
- Release: pushing a `v*` tag runs `.github/workflows/release.yml` (signed APK
  for GitHub + store App Bundle); see `doc/release.md`.
