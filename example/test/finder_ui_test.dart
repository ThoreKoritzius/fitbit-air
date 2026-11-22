import 'package:fitbit_air_example/demo/air_status_strip.dart';
import 'package:fitbit_air_example/demo/app_theme.dart';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:fitbit_air_example/demo/proximity_gauge.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('Home finder shortcut opens the live finder and help', (
    tester,
  ) async {
    final controller = DemoController()..initialized = true;
    addTearDown(controller.dispose);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('fitbit_air/finder'),
          (_) async => null,
        );
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('fitbit_air/finder'),
            null,
          ),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AirStatusStrip(controller: controller)),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('home-find-my-air')));
    await tester.pumpAndSettle();
    expect(find.byType(ProximityGauge), findsOneWidget);
    expect(find.text('Searching for your Air…'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('finder-vibrate')),
      200,
    );
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('finder-vibrate')))
          .onPressed,
      isNull,
    );
    await tester.scrollUntilVisible(find.text('How to find your Air'), 150);
    await tester.ensureVisible(find.text('How to find your Air'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('How to find your Air'));
    await tester.pumpAndSettle();
    expect(find.text('Follow the signal'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
  for (final brightness in Brightness.values) {
    testWidgets(
      'gauge fits narrow screen with large text in ${brightness.name}',
      (tester) async {
        tester.view.physicalSize = const Size(320, 800);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await tester.pumpWidget(
          MaterialApp(
            theme: airRelayTheme(brightness),
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(textScaler: TextScaler.linear(2)),
              child: child!,
            ),
            home: const Scaffold(
              body: Padding(
                padding: EdgeInsets.all(24),
                child: ProximityGauge(strength: .7, rssi: -58),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(
          find.bySemanticsLabel('Bluetooth signal -58 dBm'),
          findsOneWidget,
        );
        expect(tester.takeException(), isNull);
      },
    );
  }
}
