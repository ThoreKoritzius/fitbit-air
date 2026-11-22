import 'dart:async';
import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('paired Air emits fresh signal and releases finder on cancel', (
    tester,
  ) async {
    final readings = <AirFinderReading>[];
    final complete = Completer<void>();
    final subscription = FitbitAir.watchProximity().listen(
      (reading) {
        debugPrint(
          'FINDER ${reading.receivedAt.toIso8601String()} raw=${reading.rssi} smooth=${reading.smoothedRssi}',
        );
        if (reading.hasSignal) readings.add(reading);
        if (readings.length >= 20 && !complete.isCompleted) complete.complete();
      },
      onError: (Object error) {
        if (!complete.isCompleted) complete.completeError(error);
      },
    );
    try {
      await tester.runAsync(
        () => complete.future.timeout(const Duration(seconds: 60)),
      );
      expect(readings.length, greaterThanOrEqualTo(20));
      expect(
        readings.last.receivedAt.isAfter(readings.first.receivedAt),
        isTrue,
      );
      expect(
        readings.every((r) => r.strength! >= 0 && r.strength! <= 1),
        isTrue,
      );
    } finally {
      await subscription.cancel();
    }
    await tester.runAsync(() async {
      final reading = await FitbitAir.watchProximity()
          .firstWhere((r) => r.hasSignal)
          .timeout(const Duration(seconds: 30));
      debugPrint('FINDER restart raw=${reading.rssi}');
    });
  });
}
