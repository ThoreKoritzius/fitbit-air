import 'demo_controller.dart';

enum RelayRecovery { setup, bluetooth, notifications, rules, none }

/// Describes observed readiness, never an assumed live connection to the band.
class RelayStatus {
  const RelayStatus(
    this.title,
    this.detail, {
    this.recovery = RelayRecovery.none,
  });
  final String title;
  final String detail;
  final RelayRecovery recovery;

  factory RelayStatus.fromController(DemoController state) {
    if (!state.initialized) {
      return const RelayStatus(
        'Checking setup',
        'Checking permissions and notification settings.',
      );
    }
    if (state.relayStopping) {
      return const RelayStatus(
        'Finishing up',
        'Letting the current vibration finish and releasing the Air.',
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
      );
    }
    if (state.settings.silenceReason == 'quiet_hours') {
      return const RelayStatus(
        'Quiet hours',
        'Your schedule is keeping notifications quiet.',
      );
    }
    if (!state.relayReady) {
      return const RelayStatus(
        'Starting notifications',
        'Getting ready to listen for your alerts.',
      );
    }
    return const RelayStatus(
      'Ready for notifications',
      'Listening for your chosen apps. The Air connects only when an alert is sent.',
    );
  }
}
