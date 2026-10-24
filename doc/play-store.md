# Google Play submission

Answers for the Play Console forms, matching what Air Relay actually does
(checked against the release APK's merged manifest). Update this file when
the app's data handling or permissions change.

- **Package:** `dev.airpulse.airrelay`; it can never change after publishing.
- **Privacy policy URL:** https://github.com/ThoreKoritzius/fitbit-air/blob/main/PRIVACY.md.
  The app links to it from setup and About.
- **Permissions in the release APK:**
  - Bluetooth: `BLUETOOTH_CONNECT`, and `BLUETOOTH_SCAN` with `neverForLocation`.
  - On Android 11 and older only: `BLUETOOTH`, `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION`.
  - Relay service: `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
  - Other: `WAKE_LOCK`, `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED`, plus the notification listener service.
    The boot permission only resumes the relay after a restart if the user left it on.
  - **No `INTERNET` permission.**

## Data safety

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No.** Nothing leaves the device: the app has no internet permission. Play's definitions: "collect" means transmitted off the device, and on-device processing is not collection. |
| Is all data encrypted in transit? | Not applicable; no data is transmitted. |
| Can users request deletion? | Not applicable; nothing is collected. Users can clear history in the app or uninstall. |

Data the app *accesses* but does not collect, to explain if asked:
- **Messages / app activity:** notification content is read on the device to apply the user's rules.
- **Location:** `ACCESS_FINE_LOCATION` exists only on Android 11 and older, where Bluetooth scanning requires it. Location is never read.
- **Device identifiers:** the Air's Bluetooth address is used to connect and is not stored or transmitted.

## Prominent disclosure (User Data policy)

- **What and where:** setup step 2, "Allow notifications", shows **How Air Relay uses your notifications** before Android's notification-access settings open. It says:
  - what is read: notifications from all apps, including the app, title and text, also in the background;
  - why: only to decide which ones vibrate the Air;
  - that nothing is stored, uploaded or shared, and that the app has no internet access;
  - what is kept locally;
  - plus a link to the privacy policy.
- **Consent:** tapping **Allow notification access** is the affirmative action. Android then shows its own confirmation.

## Foreground service declaration

- **Type:** `connectedDevice` (`CalendarRelayService` and `HeadlessHapticService`).
- **Declaration text:**

> Air Relay sends vibrations to the user's paired Fitbit Air smart band when a
> notification from an app they selected arrives. The foreground service runs
> only after the user taps **Start notifications** and stops when they tap
> **Stop**. While running it keeps the Bluetooth connection to the Air ready,
> so a vibration can be sent within seconds of a notification, also when the
> screen is off. A persistent notification shows that it is running, with
> pause and stop controls. The task is user-initiated, user-perceptible and
> cannot be deferred or replaced by WorkManager without missing alerts.

- **Video:** a screen recording of about 30 s.
  1. Tap **Start notifications** and show the persistent notification.
  2. Lock the screen.
  3. Receive a test notification from a selected app; the Air vibrates.
  4. Open the notification shade, then tap **Stop**.

## Store listing

- **Title:** Air Relay. Mention Fitbit Air only descriptively, for example in the short description "Notification vibrations for Fitbit Air". Never use Fitbit or Google logos, or an icon that looks like theirs.
- **Short description:** "Turn chosen notifications into vibrations on your Fitbit Air."
- **Full description:** include these points:
  - independent and not affiliated with Google or Fitbit;
  - needs a Fitbit Air paired through Google Health on the same phone;
  - vibrations arrive a few seconds after a notification;
  - an Air firmware update can stop it until the app is updated;
  - no internet access, and nothing leaves the phone;
  - optional, off by default: "vibrate differently for urgent messages
    (analysed on the phone)". Message tone asks for consent when enabled.
- **Category:** Tools. **Content rating:** no objectionable content. **Target audience:** 13+.
- **Ads:** none.

## Before submitting

- **Release build:** build with your release key (see [release.md](release.md)) as an App Bundle.
- **Lab tools:** hide them in the Play build. Research tools such as event observation and resource probes can look like device probing to a reviewer.
- **Testing:** new personal developer accounts must run a closed test with at least 12 testers for 14 days before production access.
- **Authorization:** the question about Google's terms for third-party connections to the Air is still open. Consider asking Google first.
