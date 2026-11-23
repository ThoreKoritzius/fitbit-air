# iOS haptics prototype

This branch adds an experimental iOS implementation of `FitbitAir.tap()`.
It also supplies a separate Flutter probe app and an iOS 27 Shortcuts action
called **Tap Fitbit Air**. The Android example app is a separate host.

The native implementation uses CoreBluetooth and the same pinned upstream
Golden Gate core as Android. It looks for a single Fitbit Air already
connected at the system level, such as shortly after a Google Health sync. It opens
a bounded session, sends a single tap, reads the setting back, and attempts
to hand the tracker channel back to Google Health. The app does not import
AirBuzz code or read notification text. See [third-party notices](../THIRD_PARTY_NOTICES.md).

## Build locally

Use macOS, Xcode 27, Flutter, CocoaPods, CMake, and Python 3. From the repository
root:

```sh
python3 tool/ios/build.py
python3 tool/ios/probe.py build/ios-probe
cd build/ios-probe
flutter build ios --simulator --debug
flutter build ios --release --no-codesign
```

The first command builds the ignored `ios/Frameworks/AirGoldenGate.xcframework`
from pinned public source, with an arm64 device slice and arm64/x86_64 simulator
slices. Build it before installing dependencies in a Flutter iOS app that uses
this local checkout. Host apps must provide a Bluetooth usage description.
The probe generator supplies it and includes the Shortcuts action.

The simulator can launch the probe and check that the native transport starts
and closes, and that calling tap reports Bluetooth or device unavailability.
After the simulator build, run the generated host's tests with Xcode's
**Product → Test** or from the generated host directory:

```sh
xcodebuild test -workspace ios/Runner.xcworkspace -scheme Runner \
  -destination 'platform=iOS Simulator,id=SIMULATOR_UUID'
```

Actual Fitbit vibration and Google Health sync require an iPhone.

## Test on an iPhone

1. Pair the Fitbit Air in Google Health. Let a sync finish, open the probe, allow
   Bluetooth access, and tap **Tap Fitbit Air**. Confirm that you feel one
   pulse and that the probe reports success.
2. Open Google Health again and confirm that it can sync. If the probe cannot
   find the Air, verify that it is connected in Health and retry.
3. On iOS 27, run **Tap Fitbit Air** from Shortcuts. If that works, create
   a **Notification** automation for one app, choose **Run Immediately**,
   and add the action. Send a new notification while the iPhone is locked.
   Confirm that the band vibrates and Google Health can still sync.

The probe currently implements tap only. Discovery, locked-screen execution,
and the tracker handoff still need validation on real hardware.
