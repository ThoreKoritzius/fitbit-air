import 'package:fitbit_air_example/demo/app_theme.dart';
import 'package:fitbit_air_example/demo/relay_status.dart';
import 'package:fitbit_air_example/demo/status_style.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final brightness in Brightness.values) {
    testWidgets('emblem badges match their level in ${brightness.name}', (
      tester,
    ) async {
      for (final level in StatusLevel.values) {
        await tester.pumpWidget(
          MaterialApp(
            theme: airRelayTheme(brightness),
            home: Scaffold(
              body: Center(child: StatusEmblem(level: level)),
            ),
          ),
        );
        final badged = {
          StatusLevel.warning,
          StatusLevel.problem,
          StatusLevel.quiet,
        }.contains(level);
        expect(
          find.byKey(ValueKey('status-badge-${level.name}')),
          badged ? findsOneWidget : findsNothing,
          reason: level.name,
        );
        expect(
          find.byKey(const ValueKey('status-progress')),
          level == StatusLevel.busy ? findsOneWidget : findsNothing,
        );
        expect(tester.takeException(), isNull);
      }
    });
  }

  testWidgets('reduced motion replaces the spinner with a ring', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: airRelayTheme(Brightness.light),
        home: const MediaQuery(
          data: MediaQueryData(disableAnimations: true),
          child: Scaffold(body: StatusEmblem(level: StatusLevel.busy)),
        ),
      ),
    );
    expect(find.byKey(const ValueKey('status-progress')), findsNothing);
  });

  test('warning colors differ by brightness', () {
    expect(
      StatusStyle.warningForeground(Brightness.light),
      isNot(StatusStyle.warningForeground(Brightness.dark)),
    );
  });
}
