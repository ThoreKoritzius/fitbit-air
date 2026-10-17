import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:fitbit_air_example/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  const deviceTests = bool.fromEnvironment('AIR_DEVICE_TESTS');
  const hapticTests = bool.fromEnvironment('AIR_HAPTIC_TESTS');

  testWidgets('navigate and optionally exercise the paired device', (
    tester,
  ) async {
    final controller = DemoController();
    try {
      await tester.pumpWidget(DemoApp(controller: controller));
      for (var i = 0; i < 100 && !controller.initialized; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(controller.initialized, isTrue);
      await controller.runAction('openLab');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('secure-duration')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('nav-device')));
      await tester.pumpAndSettle();
      expect(controller.actionIds, contains('readDeviceInfo'));

      if (deviceTests || hapticTests) {
        expect(
          controller.relayRunning,
          isFalse,
          reason: 'Stop the relay before running physical trials.',
        );
        // runAction is exactly the dispatcher used by the buttons.
        var finished = false;
        Object? failure;
        final operation = controller
            .runAction(deviceTests ? 'readDeviceInfo' : 'checkPaired')
            .catchError((Object error) {
              failure = error;
            })
            .whenComplete(() => finished = true);
        for (var i = 0; i < 120 && !finished; i++) {
          await tester.pump(const Duration(milliseconds: 500));
        }
        expect(
          finished,
          isTrue,
          reason: 'Read-only operation exceeded the harness deadline.',
        );
        await operation;
        expect(failure, isNull);
        expect(controller.trials.first['outcome'], 'completed');
      }
      if (hapticTests) {
        var finished = false;
        Object? failure;
        final operation = controller
            .runAction('preview')
            .catchError((Object error) {
              failure = error;
            })
            .whenComplete(() => finished = true);
        for (var i = 0; i < 120 && !finished; i++) {
          await tester.pump(const Duration(milliseconds: 500));
        }
        expect(
          finished,
          isTrue,
          reason:
              'Haptic exceeded the harness deadline; do not retry automatically.',
        );
        await operation;
        expect(failure, isNull);
        expect(controller.trials.first['outcome'], 'completed');
        // A person must separately mark physical sensation; no synthetic receipt.
      }
    } finally {
      if (controller.busy) {
        controller.observe('harness', {
          'phase': 'harness_ended_with_operation_in_progress',
        });
        controller.finishOperation('interrupted');
      }
      binding.reportData = controller.report();
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    }
  });
}
