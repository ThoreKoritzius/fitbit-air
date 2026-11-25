import 'dart:async';
import 'dart:convert';

import 'package:fitbit_air/fitbit_air.dart';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late DemoController controller;
  setUp(() => controller = DemoController());
  tearDown(() => controller.dispose());

  test(
    'named actions are exclusive and record parameters and outcomes',
    () async {
      final pending = Completer<void>();
      controller.actions['read'] = DemoAction(
        () => pending.future,
        parameters: () => {'mode': 'read_only'},
      );
      final running = controller.runAction('read');
      expect(controller.busy, isTrue);
      await expectLater(controller.runAction('read'), throwsStateError);
      pending.complete();
      await running;
      expect(controller.busy, isFalse);
      expect(controller.trials.single['parameters'], {'mode': 'read_only'});
      expect(controller.trials.single['outcome'], 'completed');
      expect(controller.trials.single['durationMillis'], isA<int>());
    },
  );

  test(
    'an active relay prevents device actions without hidden side effects',
    () async {
      var calls = 0;
      controller.actions['write'] = DemoAction(() {
        calls++;
      });
      controller.relayRunning = true;
      await expectLater(controller.runAction('write'), throwsStateError);
      expect(calls, 0);
      expect(controller.trials, isEmpty);
    },
  );

  test(
    'interrupted streams retain coverage and never become completed',
    () async {
      controller.actions['stream'] = DemoAction(() {}, streaming: true);
      await controller.runAction('stream');
      controller.observe('secure_event', {
        'phase': 'interrupted',
        'observationMillis': 1234,
      });
      controller.setOutcome('interrupted');
      controller.failOperation(
        const FitbitAirException('TIMEOUT', 'private native message'),
      );
      controller.finishOperation();
      expect(controller.trials.single['outcome'], 'interrupted');
      expect(controller.trials.single['errorCode'], 'TIMEOUT');
      expect(
        controller.exportJson(),
        isNot(contains('private native message')),
      );
    },
  );

  test(
    'failed writes are recorded once and never automatically retried',
    () async {
      var calls = 0;
      controller.actions['write'] = DemoAction(() {
        calls++;
        throw const FitbitAirException('TIMEOUT', 'uncertain delivery');
      });
      await expectLater(
        controller.runAction('write'),
        throwsA(isA<FitbitAirException>()),
      );
      expect(calls, 1);
      expect(controller.trials.single['outcome'], 'failed');
      expect(controller.busy, isFalse);
    },
  );

  test(
    'reports call settings and recent call signals for remote debugging',
    () {
      controller.phoneInfo = {'manufacturer': 'OnePlus', 'sdk': 36};
      controller.callSignals = [
        {
          'package': 'com.android.incallui',
          'recognized': true,
          'outcome': 'calls_off',
        },
      ];
      final report = jsonDecode(controller.exportJson()) as Map;
      expect(report['phone']['manufacturer'], 'OnePlus');
      expect(report['phone'], contains('batteryUnrestricted'));
      expect(report['calls']['enabled'], isFalse);
      expect(report['calls']['ring'], isTrue);
      expect(
        (report['calls']['recentSignals'] as List).single['outcome'],
        'calls_off',
      );
    },
  );

  test(
    'bounded reports separate manual observations and omit raw payload fields',
    () async {
      controller.actions['trial'] = DemoAction(() {}, streaming: true);
      await controller.runAction('trial');
      for (var i = 0; i < 120; i++) {
        controller.observe('device_event', {
          'phase': 'notification',
          'payload': 'secret',
          'deviceId': 'private-id',
        });
      }
      expect(controller.trials.single['observations'] as List, hasLength(100));
      controller.finishOperation('cancelled');
      controller.mark('felt_vibration');
      final report = jsonDecode(controller.exportJson()) as Map;
      expect(
        (report['manualObservations'] as List).single['kind'],
        'manual_marker',
      );
      expect(controller.exportJson(), isNot(contains('secret')));
      expect(controller.exportJson(), isNot(contains('private-id')));
      expect(controller.trials.single['outcome'], 'cancelled');
      expect(
        () => controller.mark('guessed_tracker_event'),
        throwsArgumentError,
      );
    },
  );

  test(
    'trial history stays bounded and selected apps sort before search results',
    () async {
      controller.actions['trial'] = DemoAction(() {});
      for (var i = 0; i < 25; i++) {
        await controller.runAction('trial');
      }
      expect(controller.trials, hasLength(20));
      controller.apps = [
        {'packageName': 'com.other', 'label': 'Alpha'},
        {'packageName': 'com.whatsapp', 'label': 'WhatsApp'},
      ];
      controller.selectedApps = {'com.whatsapp'};
      expect(controller.filteredApps.first['label'], 'WhatsApp');
      controller.searchApps('alpha');
      expect(controller.filteredApps.single['label'], 'Alpha');
    },
  );

  test(
    'native cancellation remains cancelled rather than a failed trial',
    () async {
      controller.actions['read'] = DemoAction(() {
        throw const FitbitAirException('CANCELLED', 'Read cancelled.');
      });
      await expectLater(
        controller.runAction('read'),
        throwsA(isA<FitbitAirException>()),
      );
      expect(controller.trials.single['outcome'], 'cancelled');
      expect(controller.busy, isFalse);
    },
  );
}
