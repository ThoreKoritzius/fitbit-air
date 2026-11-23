# Air Relay privacy policy

Last updated: 2026-11-17

Air Relay is an independent Android app that turns selected phone
notifications into vibrations on your Fitbit Air. It is not affiliated with
Google or Fitbit. This policy covers the Air Relay app (`dev.airpulse.airrelay`)
and the `fitbit_air` Flutter package it is built on.

## Summary

- Air Relay has **no internet access**. Android does not let it connect to any
  server, so nothing it handles can leave your phone through it.
- It reads notifications **only on your phone** to decide which ones become a
  vibration. Notification text is never stored, uploaded or shared.
- It has no account, analytics, advertising or tracking, and collects no data.

## What Air Relay accesses and why

| Access | Why | What is kept |
|---|---|---|
| Notifications from all apps (Android notification access) | To match notifications against your rules and decide whether to vibrate your Air | Nothing from the content. A short local history keeps the app's package name, time, result, chosen vibration and, if you turn on **Message tone**, the category (urgent, negative or neutral). |
| Message text (only with **Message tone** on) | Classified on the phone, in memory, to choose a vibration | Only the category above; the text is discarded immediately |
| Chat names from supported messaging apps (only if you choose chats) | To match vibration rules for the chats you pick | The chat names you select and hashed match identifiers |
| Nearby devices (Bluetooth) | To connect to your paired Fitbit Air and send vibrations, read its battery, firmware version and signal strength | The last battery reading and firmware version |
| Notifications (posting) | To show the running relay with pause and stop controls | Nothing |

The app also stores your settings: selected apps, vibration rules, quiet hours
and setup progress.

## Where data is stored

All of the above stays in Air Relay's private storage on your phone. It is
excluded from Android cloud backup and device-to-device transfer, so it is not
copied to Google Drive or a new phone. Uninstalling Air Relay, or clearing its
storage in Android settings, deletes it. **Clear history** in the app deletes
the activity history.

## Sharing

Air Relay shares no data with anyone. It talks only to your Fitbit Air over
Bluetooth, using the connection your phone already has with it.

If you choose **Copy diagnostic report**, the app copies a report to your
clipboard so you can attach it to a bug report yourself. It contains no
message text, but does list the app packages that triggered alerts, and the
Air's firmware version. Nothing is sent automatically.

## Google Play

When installed from Google Play, Google may collect crash and performance
reports under its own policies; Air Relay itself sends nothing.

## Children

Air Relay is not directed at children under 13.

## Changes and contact

Changes to this policy are published in this file, with the date above.
Questions: open an issue at
https://github.com/ThoreKoritzius/fitbit-air/issues.

## Find my Air

The optional finder reads the paired Air's Bluetooth signal strength while its
screen is open. Signal readings and timestamps are kept in memory only (up to
300 updates for the copyable signal report). It does not use GPS or upload
location information. **Copy signal report** places those readings on the
clipboard only when you request it. Closing the finder discards the report.
