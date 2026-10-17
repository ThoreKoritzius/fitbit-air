import 'dart:async';
import 'package:fitbit_air/fitbit_air_diagnostics.dart';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:fitbit_air_example/demo/relay_settings.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const device = MethodChannel('fitbit_air');
  tearDown(() {
    messenger.setMockMethodCallHandler(DemoController.channel, null);
    messenger.setMockMethodCallHandler(device, null);
  });
  test(
    'typed settings preserve defaults overrides and silence without exporting private identities',
    () async {
      final controller = DemoController();
      addTearDown(controller.dispose);
      messenger.setMockMethodCallHandler(
        device,
        (_) async => <String, Object?>{},
      );
      messenger.setMockMethodCallHandler(
        DemoController.channel,
        (_) async => {
          'rules': {
            'defaultPreset': 'double',
            'apps': {
              'com.whatsapp': {'enabled': true, 'preset': 'low'},
            },
            'chats': {'private-chat-identity': 'mute'},
            'pauseUntil': -1,
            'silenceReason': 'paused',
            'quietEnabled': true,
            'startMinute': 1320,
            'endMinute': 420,
          },
        },
      );
      await controller.refresh();
      expect(controller.settings.defaultPreset, VibrationPreset.doublePulse);
      expect(
        controller.settings.apps['com.whatsapp']?.preset,
        VibrationPreset.low,
      );
      expect(
        controller.settings.chats['private-chat-identity'],
        VibrationPreset.mute,
      );
      expect(controller.settings.silenceReason, 'paused');
      expect(controller.exportJson(), isNot(contains('private-chat-identity')));
      expect(
        controller.report()['settings'],
        containsPair('chatOverrideCount', 1),
      );
    },
  );
  test(
    'running worker preview waits for completion and leaves pause available',
    () async {
      final controller = DemoController();
      addTearDown(controller.dispose);
      controller.relayRunning = true;
      controller.relayReady = true;
      final delivery = Completer<void>();
      final started = Completer<void>();
      var paused = false;
      messenger.setMockMethodCallHandler(
        device,
        (_) async => <String, Object?>{},
      );
      messenger.setMockMethodCallHandler(DemoController.channel, (call) async {
        if (call.method == 'previewPreset') {
          expect(call.arguments, {'preset': 'high'});
          started.complete();
          await delivery.future;
        }
        if (call.method == 'setPause') paused = true;
        if (call.method == 'status') return {'running': true, 'ready': true};
        return null;
      });
      var completed = false;
      final future = controller
          .previewPreset(VibrationPreset.high)
          .then((_) => completed = true);
      await started.future;
      expect(controller.previewing, isTrue);
      expect(controller.changingRelay, isFalse);
      expect(completed, isFalse);
      await controller.setPause(15);
      expect(paused, isTrue);
      await expectLater(
        controller.previewPreset(VibrationPreset.low),
        throwsStateError,
      );
      delivery.complete();
      await future;
      expect(completed, isTrue);
      expect(controller.previewing, isFalse);
    },
  );
  test('wrist observations stay historical and do not change relay rules', () {
    final controller = DemoController();
    addTearDown(controller.dispose);
    expect(controller.lastWristObservation, isNull);
    final event = AirSecureEvent.fromMap({
      'kind': 'device_event',
      'path': 'events',
      'receivedAtMillis': 1000,
      'eventIds': [2003989293],
    });
    controller.recordWristObservation(event.wristPresenceEvents.single);
    expect(controller.lastWristObservation?.state, AirWristPresence.offWrist);
    expect(controller.settings.silenceReason, isNull);
    expect(controller.selectedApps, {'com.google.android.calendar'});
  });
}
