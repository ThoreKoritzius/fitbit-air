import 'package:fitbit_air/fitbit_air.dart';

import 'demo_controller.dart';

enum RelayRecovery { setup, bluetooth, notifications, rules, update, none }

/// How a status looks: one visual language for every screen.
enum StatusLevel {
  /// Checking, starting or stopping.
  busy,

  /// Something the user must fix before alerts can work.
  problem,

  /// Running, but alerts may not arrive right now.
  warning,

  /// Ready, notifications off.
  idle,

  /// Running but silenced by a pause or quiet hours.
  quiet,

  /// Running and ready for alerts.
  active,
}

/// Describes observed readiness, never an assumed live connection to the band.
class RelayStatus {
  const RelayStatus(
    this.title,
    this.detail, {
    this.recovery = RelayRecovery.none,
    StatusLevel? level,
  }) : level =
           level ??
           (recovery == RelayRecovery.none
               ? StatusLevel.idle
               : StatusLevel.problem);
  final String title;
  final String detail;
  final RelayRecovery recovery;
  final StatusLevel level;

  /// Worth the user's attention although there is nothing to fix in setup.
  bool get attention => level == StatusLevel.warning;

  factory RelayStatus.fromController(DemoController state) {
    if (!state.initialized) {
      return const RelayStatus(
        'Checking setup',
        'Checking permissions and notification settings.',
        level: StatusLevel.busy,
      );
    }
    if (state.relayStopping) {
      return const RelayStatus(
        'Finishing up',
        'Letting the current vibration finish and releasing the Air.',
        level: StatusLevel.busy,
      );
    }
    if (!state.bluetoothPermission) {
      return const RelayStatus(
        'Allow nearby devices',
        'Air Relay needs Bluetooth permission to reach your band.',
        recovery: RelayRecovery.setup,
      );
    }
    if (state.bluetoothEnabled == false) {
      return const RelayStatus(
        'Bluetooth is off',
        'Turn it on to send notifications to your wrist.',
        recovery: RelayRecovery.bluetooth,
      );
    }
    if (!state.access) {
      return const RelayStatus(
        'Allow notification access',
        'Choose which notifications can become a vibration.',
        recovery: RelayRecovery.notifications,
      );
    }
    if (!state.listenerConnected) {
      return const RelayStatus(
        'Notification access disconnected',
        'Android isn’t sending notifications to Air Relay yet. Check notification access, then return here.',
        recovery: RelayRecovery.notifications,
      );
    }
    if (state.bluetoothEnabled == null) {
      return const RelayStatus(
        'Check your Air',
        'Check Bluetooth and pairing to finish getting connected.',
        recovery: RelayRecovery.setup,
      );
    }
    if (state.paired == false) {
      return const RelayStatus(
        'Pair your Air first',
        'Pair it in Google Health, then return to check again.',
        recovery: RelayRecovery.setup,
      );
    }
    if (state.selectedApps.isEmpty ||
        (state.apps.isNotEmpty && state.selectedInstalledAppCount == 0)) {
      return const RelayStatus(
        'Choose your apps',
        'Select at least one app to receive its notifications.',
        recovery: RelayRecovery.rules,
      );
    }
    if (!state.relayRunning && !state.setupCompleted && state.paired == null) {
      return const RelayStatus(
        'Finish setting up',
        'Check your paired Air before starting notifications.',
        recovery: RelayRecovery.setup,
        level: StatusLevel.idle,
      );
    }
    if (!state.relayRunning) {
      return const RelayStatus(
        'Ready when you are',
        'Start notifications when you want alerts on your wrist.',
      );
    }
    if (state.settings.silenceReason == 'paused') {
      return const RelayStatus(
        'Taking a break',
        'Notifications are paused. Missed alerts will not be replayed.',
        level: StatusLevel.quiet,
      );
    }
    if (state.settings.silenceReason == 'quiet_hours') {
      return const RelayStatus(
        'Quiet hours',
        'Your schedule is keeping notifications quiet.',
        level: StatusLevel.quiet,
      );
    }
    if (!state.relayReady) {
      return const RelayStatus(
        'Starting notifications',
        'Getting ready to listen for your alerts.',
        level: StatusLevel.busy,
      );
    }
    if (state.airConnected == false) {
      return const RelayStatus(
        'Air not connected',
        'Alerts can’t reach your wrist until the Air is back near this phone. Missed alerts are not replayed.',
        level: StatusLevel.warning,
      );
    }
    switch (state.deliveryProblem) {
      case AirFailureKind.compatibility:
        return const RelayStatus(
          'Alerts aren’t reaching your Air',
          'Your Air keeps answering in an unexpected way, possibly after a firmware update. An Air Relay update may be needed.',
          recovery: RelayRecovery.update,
          level: StatusLevel.warning,
        );
      case AirFailureKind.setup:
        return RelayStatus(
          'Alerts aren’t reaching your Air',
          DemoController.recoveryHint(state.lastFailureCode ?? ''),
          recovery: RelayRecovery.setup,
          level: StatusLevel.problem,
        );
      case AirFailureKind.temporary || AirFailureKind.unknown:
        return const RelayStatus(
          'Recent alerts weren’t confirmed',
          'Keep the Air near your phone. Air Relay tries again with the next alert, or use Try it to check now.',
          level: StatusLevel.warning,
        );
      case null:
        break;
    }
    return const RelayStatus(
      'Ready for notifications',
      'Listening for your chosen apps. The Air connects only when an alert is sent.',
      level: StatusLevel.active,
    );
  }
}
