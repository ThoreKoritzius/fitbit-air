import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:fitbit_air_example/demo/relay_settings.dart';
import 'package:fitbit_air_example/demo/relay_status.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  late DemoController state;
  setUp(() {
    state = DemoController()
      ..initialized = true
      ..access = true
      ..listenerConnected = true
      ..bluetoothPermission = true
      ..bluetoothEnabled = true
      ..paired = true;
  });
  tearDown(() => state.dispose());

  test('readiness requires the worker, not just a running service', () {
    state.relayRunning = true;
    expect(RelayStatus.fromController(state).title, 'Starting notifications');
    state.relayReady = true;
    expect(RelayStatus.fromController(state).title, 'Ready for notifications');
    expect(
      RelayStatus.fromController(state).detail,
      contains('only when an alert is sent'),
    );
    state.relayRunning = false;
    expect(RelayStatus.fromController(state).title, 'Ready when you are');
  });

  test('revoked access and Bluetooth override a running or paused relay', () {
    state
      ..relayRunning = true
      ..relayReady = true
      ..settings = const RelaySettings(silenceReason: 'paused');
    expect(RelayStatus.fromController(state).title, 'Taking a break');
    state.listenerConnected = false;
    expect(
      RelayStatus.fromController(state).recovery,
      RelayRecovery.notifications,
    );
    state.bluetoothEnabled = false;
    expect(RelayStatus.fromController(state).recovery, RelayRecovery.bluetooth);
    state.bluetoothPermission = false;
    expect(RelayStatus.fromController(state).recovery, RelayRecovery.setup);
    state.relayStopping = true;
    expect(RelayStatus.fromController(state).title, 'Finishing up');
  });

  test('empty selection and pairing have specific recovery actions', () {
    state.selectedApps.clear();
    expect(RelayStatus.fromController(state).recovery, RelayRecovery.rules);
    state.paired = false;
    expect(RelayStatus.fromController(state).title, 'Pair your Air first');
    state.initialized = false;
    expect(RelayStatus.fromController(state).title, 'Checking setup');
  });

  test('quiet hours and historical failures never imply a live connection', () {
    state
      ..relayRunning = true
      ..relayReady = true
      ..settings = const RelaySettings(silenceReason: 'quiet_hours');
    expect(RelayStatus.fromController(state).title, 'Quiet hours');
    state.settings = const RelaySettings();
    state.history = [
      {'stage': 'failed', 'errorCode': 'TIMEOUT'},
    ];
    expect(RelayStatus.fromController(state).title, 'Ready for notifications');
  });
  test(
    'setup completion rejects revoked permissions and missing pairing',
    () async {
      state.access = false;
      await expectLater(state.completeSetup(), throwsStateError);
      expect(state.setupCompleted, isFalse);
      state.access = true;
      state.paired = false;
      await expectLater(state.completeSetup(), throwsStateError);
      expect(state.setupCompleted, isFalse);
    },
  );
  test('an existing active relay stays ready before optional onboarding', () {
    state
      ..paired = null
      ..setupCompleted = false
      ..relayRunning = true
      ..relayReady = true;
    expect(RelayStatus.fromController(state).title, 'Ready for notifications');
    state.relayRunning = false;
    expect(RelayStatus.fromController(state).recovery, RelayRecovery.setup);
  });
  test(
    'saving settings does not replace relay readiness with a generic updating state',
    () {
      state
        ..relayRunning = true
        ..relayReady = true
        ..changingRelay = true;
      expect(
        RelayStatus.fromController(state).title,
        'Ready for notifications',
      );
    },
  );
}
