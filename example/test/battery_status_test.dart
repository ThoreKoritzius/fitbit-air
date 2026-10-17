import 'dart:async';
import 'package:fitbit_air/fitbit_air.dart';
import 'package:fitbit_air_example/demo/air_status_strip.dart';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const device = MethodChannel('fitbit_air');
  late DemoController controller;
  var reads = 0;
  Map<Object?, Object?>? saved;
  Completer<Map<String, Object?>>? pending;
  var fail = false;
  setUp(() {
    reads = 0;
    saved = null;
    pending = null;
    fail = false;
    controller = DemoController()
      ..initialized = true
      ..bluetoothPermission = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(device, (call) async {
          if (call.method == 'readBattery') {
            reads++;
            if (fail) throw PlatformException(code: 'TIMEOUT');
            return pending == null
                ? {'percent': 55, 'onCharger': true}
                : await pending!.future;
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(DemoController.channel, (call) async {
          if (call.method == 'saveBattery') saved = call.arguments as Map;
          if (call.method == 'batteryCache') return saved;
          if (call.method == 'apps') return [];
          return null;
        });
  });
  tearDown(() {
    controller.dispose();
  });

  test(
    'passive connection subscription is shared across refreshes and released in background',
    () async {
      final events = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('fitbit_air/connection'),
            (call) async {
              events.add(call.method);
              return null;
            },
          );
      controller.setVisible(true);
      await controller.refresh();
      await controller.refresh();
      expect(events.where((e) => e == 'listen').length, 1);
      controller.setVisible(false);
      await Future<void>.delayed(Duration.zero);
      expect(events.where((e) => e == 'cancel').length, 1);
      expect(reads, 0);
    },
  );

  test(
    'persist readings with their original time and restore without reading Bluetooth',
    () async {
      await controller.refreshBattery();
      expect(saved?['percent'], 55);
      expect(saved?['onCharger'], true);
      final restored = DemoController();
      await restored.initialize();
      expect(restored.battery!.percent, 55);
      expect(
        restored.batteryReadAt!.millisecondsSinceEpoch,
        controller.batteryReadAt!.millisecondsSinceEpoch,
      );
      expect(reads, 1);
      await controller.refreshBattery();
      expect(reads, 1); // Fresh values cannot trigger another secure session.
      restored.dispose();
    },
  );

  test(
    'battery cannot compete with a relay, cleanup, preview or another request',
    () async {
      controller.relayRunning = true;
      await controller.refreshBattery();
      controller.relayRunning = false;
      controller.relayStopping = true;
      await controller.refreshBattery();
      controller.relayStopping = false;
      controller.previewing = true;
      await controller.refreshBattery();
      controller.previewing = false;
      expect(reads, 0);
      pending = Completer();
      final task = controller.refreshBattery();
      await controller.refreshBattery();
      await expectLater(controller.startRelay(), throwsStateError);
      expect(controller.controlsBlocked, isTrue);
      pending!.complete({'percent': 50});
      await task;
      expect(reads, 1);
      expect(controller.batteryReading, isFalse);
    },
  );

  test(
    'failure retains the reading and timestamp without automatic retries',
    () async {
      controller.battery = const AirBattery(percent: 20);
      final at = DateTime.now().subtract(const Duration(days: 1));
      controller.batteryReadAt = at;
      fail = true;
      await controller.refreshBattery();
      expect(controller.battery!.percent, 20);
      expect(controller.batteryReadAt, at);
      expect(controller.batteryError, isNotNull);
      expect(reads, 1);
    },
  );

  testWidgets(
    'saved low battery opens a readable sheet with refresh disabled during relay',
    (tester) async {
      controller.battery = const AirBattery(percent: 15, onCharger: true);
      controller.batteryReadAt = DateTime.now().subtract(
        const Duration(days: 1),
      );
      controller.relayRunning = true;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: AirStatusStrip(controller: controller)),
        ),
      );
      expect(find.text('15% · Saved'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('battery-status')));
      await tester.pumpAndSettle();
      expect(find.text('Checked 1 d ago'), findsOneWidget);
      expect(find.text('On charger when checked'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const ValueKey('refresh-battery')))
            .onPressed,
        isNull,
      );
      expect(reads, 0);
    },
  );
}
