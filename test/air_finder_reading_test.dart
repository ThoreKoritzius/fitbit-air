import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('searching drops a previous signal even if a platform includes it', () {
    final reading = AirFinderReading.fromMap({
      'state': 'searching',
      'rssi': -45,
      'smoothedRssi': -50.0,
      'receivedAtMillis': 1000,
    });
    expect(reading.hasSignal, isFalse);
    expect(reading.strength, isNull);
  });
  test('signal strength is bounded without implying meters', () {
    for (final value in [-127.0, -70.0, -1.0]) {
      final reading = AirFinderReading.fromMap({
        'state': 'signal',
        'rssi': value.toInt(),
        'smoothedRssi': value,
        'receivedAtMillis': 1000,
      });
      expect(reading.hasSignal, isTrue);
      expect(reading.strength, inInclusiveRange(0, 1));
    }
  });
}
