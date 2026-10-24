import 'package:fitbit_air/fitbit_air.dart';
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

  test('an Air out of range is shown while notifications run', () {
    state
      ..relayRunning = true
      ..relayReady = true
      ..airConnected = false;
    final status = RelayStatus.fromController(state);
    expect(status.title, 'Air not connected');
    expect(status.attention, isTrue);
    expect(status.recovery, RelayRecovery.none);
  });

  test('two unconfirmed alerts in a row are shown, one is not', () {
    state
      ..relayRunning = true
      ..relayReady = true
      ..history = [
        {'stage': 'failed', 'errorCode': 'HANDSHAKE_TIMEOUT'},
        {'stage': 'filtered'},
        {'stage': 'verified'},
        {'stage': 'failed', 'errorCode': 'TIMEOUT'},
      ];
    expect(RelayStatus.fromController(state).title, 'Ready for notifications');
    state.history.insert(0, {'stage': 'interrupted'});
    expect(
      RelayStatus.fromController(state).title,
      'Recent alerts weren’t confirmed',
    );
  });

  test('only repeated compatibility failures suggest an update', () {
    state
      ..relayRunning = true
      ..relayReady = true
      ..history = [
        for (var i = 0; i < 2; i++)
          {'stage': 'failed', 'errorCode': 'HAPTIC_READ_FAILED'},
      ];
    expect(RelayStatus.fromController(state).recovery, RelayRecovery.none);
    state.history.add({'stage': 'failed', 'errorCode': 'DTLS'});
    expect(RelayStatus.fromController(state).recovery, RelayRecovery.update);
    state.history.insert(0, {'stage': 'verified'});
    expect(RelayStatus.fromController(state).attention, isFalse);
  });

  test('a lost pairing during delivery asks for setup', () {
    state
      ..relayRunning = true
      ..relayReady = true
      ..history = [
        {'stage': 'failed', 'errorCode': 'AIR_NOT_FOUND'},
        {'stage': 'failed', 'errorCode': 'AIR_NOT_FOUND'},
      ];
    final status = RelayStatus.fromController(state);
    expect(status.recovery, RelayRecovery.setup);
    expect(status.detail, contains('Pair the Air'));
  });

  test('untested firmware shows a notice until dismissed', () {
    expect(state.showFirmwareNotice, isFalse);
    state.firmware = FitbitAir.testedFirmware.first;
    expect(state.firmwareTested, isTrue);
    expect(state.showFirmwareNotice, isFalse);
    state.firmware = '99.0.0.1';
    expect(state.showFirmwareNotice, isTrue);
    state.dismissedFirmware = '99.0.0.1';
    expect(state.showFirmwareNotice, isFalse);
    state.firmware = '99.0.0.2';
    expect(state.showFirmwareNotice, isTrue);
  });

  test('untested firmware that already worked here needs no notice', () {
    state
      ..firmware = '99.0.0.1'
      ..workingFirmware = '99.0.0.1';
    expect(state.firmwareWorks, isTrue);
    expect(state.showFirmwareNotice, isFalse);
    // A later update is unproven again until a command succeeds on it.
    state.firmware = '99.0.0.2';
    expect(state.firmwareWorks, isFalse);
    expect(state.showFirmwareNotice, isTrue);
  });

  test('every status has a visual level', () {
    expect(RelayStatus.fromController(state).level, StatusLevel.idle);
    state
      ..relayRunning = true
      ..relayReady = false;
    expect(RelayStatus.fromController(state).level, StatusLevel.busy);
    state.relayReady = true;
    expect(RelayStatus.fromController(state).level, StatusLevel.active);
    state.settings = const RelaySettings(silenceReason: 'paused');
    expect(RelayStatus.fromController(state).level, StatusLevel.quiet);
    state
      ..settings = const RelaySettings()
      ..airConnected = false;
    expect(RelayStatus.fromController(state).level, StatusLevel.warning);
    state.bluetoothEnabled = false;
    expect(RelayStatus.fromController(state).level, StatusLevel.problem);
  });
}
