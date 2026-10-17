import 'package:fitbit_air_example/demo/app_theme.dart';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:fitbit_air_example/demo/relay_panel.dart';
import 'package:fitbit_air_example/demo/rules_panel.dart';
import 'package:fitbit_air_example/demo/setup_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final brightness in Brightness.values) {
    testWidgets('relay controls fit a phone in ${brightness.name} mode', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final controller = DemoController()
        ..initialized = true
        ..setupCompleted = true
        ..notificationControlsAllowed = true
        ..access = true
        ..listenerConnected = true
        ..bluetoothEnabled = true
        ..bluetoothPermission = true
        ..apps = [
          {'packageName': 'com.google.android.calendar', 'label': 'Calendar'},
        ];
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: airRelayTheme(brightness),
          home: Scaffold(
            appBar: AppBar(title: const Text('Air Relay')),
            body: RelayPanel(
              controller: controller,
              perform: (_) {},
              testVibration: () {},
              exportReport: () {},
            ),
          ),
        ),
      );
      final start = find.byKey(const ValueKey('relay-toggle'));
      expect(start.hitTestable(), findsOneWidget);
      expect(
        find.byKey(const ValueKey('test-vibration')).hitTestable(),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    });
  }

  for (final brightness in Brightness.values) {
    testWidgets(
      'guided steps fit a narrow phone with large text in ${brightness.name}',
      (tester) async {
        tester.view.physicalSize = const Size(320, 720);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final controller = DemoController()
          ..initialized = true
          ..notificationControlsAllowed = true
          ..access = true
          ..listenerConnected = true
          ..bluetoothPermission = true
          ..bluetoothEnabled = true
          ..paired = true
          ..apps = [
            {'packageName': 'com.google.android.calendar', 'label': 'Calendar'},
          ];
        addTearDown(controller.dispose);
        await tester.pumpWidget(
          MaterialApp(
            theme: airRelayTheme(brightness),
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(textScaler: TextScaler.linear(1.6)),
              child: child!,
            ),
            home: RelaySetupPage(controller: controller),
          ),
        );
        for (var step = 0; step < 4; step++) {
          expect(
            find.byKey(const ValueKey('setup-next')).hitTestable(),
            findsOneWidget,
          );
          expect(
            find.byKey(const ValueKey('close-setup')).hitTestable(),
            findsOneWidget,
          );
          expect(tester.takeException(), isNull);
          if (step < 3) {
            await tester.tap(find.byKey(const ValueKey('setup-next')));
            await tester.pumpAndSettle();
          }
        }
        await tester.pumpWidget(const SizedBox());
      },
    );
  }

  testWidgets(
    'separate notification permission stays reachable with large text',
    (tester) async {
      tester.view.physicalSize = const Size(320, 720);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final controller = DemoController()
        ..access = true
        ..listenerConnected = true
        ..notificationControlsAllowed = false;
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: airRelayTheme(Brightness.light),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(1.6)),
            child: child!,
          ),
          home: RelaySetupPage(controller: controller, initialStep: 1),
        ),
      );
      expect(
        find.byKey(const ValueKey('setup-notification-controls')).hitTestable(),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('setup-skip-controls')).hitTestable(),
        findsOneWidget,
      );
      expect(find.byKey(const ValueKey('setup-next')), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'narrow phone with large text keeps setup and filters reachable',
    (tester) async {
      tester.view.physicalSize = const Size(320, 720);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final controller = DemoController()
        ..apps = List.generate(
          100,
          (index) => {'packageName': 'app.$index', 'label': 'App $index'},
        );
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(
          theme: airRelayTheme(Brightness.light),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(
              context,
            ).copyWith(textScaler: TextScaler.linear(1.6)),
            child: child!,
          ),
          home: Scaffold(
            body: RulesPanel(controller: controller, perform: (_) {}),
          ),
        ),
      );
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('conversation-picker')),
        100,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.ensureVisible(
        find.byKey(const ValueKey('conversation-picker')),
      );
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('conversation-picker')).hitTestable(),
        findsOneWidget,
      );
      await tester.tap(find.byKey(const ValueKey('conversation-picker')));
      await tester.pumpAndSettle();
      expect(find.text('Who can reach your wrist?'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pageBack();
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('app-search')),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.byKey(const ValueKey('app-search')), findsOneWidget);
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('conversation-picker')),
        -200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(tester.takeException(), isNull);
    },
  );
}
