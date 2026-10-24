import 'package:fitbit_air/fitbit_air.dart';
import 'package:fitbit_air_example/demo/app_theme.dart';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:fitbit_air_example/demo/device_overview.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final brightness in Brightness.values) {
    testWidgets(
      'device overview fits large text on a narrow phone in ${brightness.name}',
      (tester) async {
        tester.view.physicalSize = const Size(320, 800);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final state = DemoController()
          ..battery = const AirBattery(percent: 15)
          ..batteryReadAt = DateTime.now().subtract(const Duration(hours: 2))
          ..airConnected = false
          ..signalRssi = -67
          ..signalReadAt = DateTime.now()
          ..transportDiagnostics = {
            'linkRecoveries': {
              'silent_fallback': 1,
              'rebuilt_after_fallback': 1,
            },
            'lastLinkRecoveryAtMillis': DateTime.now().millisecondsSinceEpoch,
          };
        addTearDown(state.dispose);
        await tester.pumpWidget(
          MaterialApp(
            theme: airRelayTheme(brightness),
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(
                context,
              ).copyWith(textScaler: TextScaler.linear(1.6)),
              child: child!,
            ),
            home: Scaffold(
              body: SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: DeviceOverview(controller: state, firmware: '1.2.3'),
              ),
            ),
          ),
        );
        expect(find.text('Fitbit Air'), findsOneWidget);
        expect(find.text('15%'), findsOneWidget);
        expect(find.text('Not connected'), findsOneWidget);
        expect(find.byKey(const ValueKey('link-recoveries')), findsOneWidget);
        expect(find.textContaining('-67 dBm'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
