import 'dart:async';

import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('fitbit_air');

  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  test('reports whether a paired Air is present', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async => true);
    expect(await FitbitAir.isAirPaired(), isTrue);
  });

  test('returns verified vibration result', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (_) async => {
            'originalIntensity': 2,
            'restored': true,
            'verified': true,
          },
        );
    final result = await FitbitAir.vibrate();
    expect(result.originalIntensity, 2);
    expect(result.restored, isTrue);
    expect(result.verified, isTrue);
  });

  test('returns a verified one-pulse intensity transition', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (call) async => {
            'previousIntensity': 2,
            'currentIntensity': 3,
            'changed': true,
            'verified': true,
          },
        );
    final result = await FitbitAir.pulse();
    expect(result.previousIntensity, HapticIntensity.low);
    expect(result.currentIntensity, HapticIntensity.high);
    expect(result.changed, isTrue);
    expect(result.verified, isTrue);
  });

  test('ring sends its pacing and reports the pulses posted', () async {
    Object? arguments;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          arguments = call.arguments;
          return {'pulses': 6, 'restored': true};
        });
    final result = await FitbitAir.ring(
      intensity: HapticIntensity.high,
      gap: const Duration(milliseconds: 650),
      maximum: const Duration(seconds: 45),
    );
    expect(arguments, {
      'intensity': 3,
      'gapMillis': 650,
      'maxMillis': 45000,
    });
    expect(result.pulses, 6);
    expect(result.restored, isTrue);
  });

  test('a bounded ring carries its pulse count', () async {
    Object? arguments;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          arguments = call.arguments;
          return {'pulses': 2, 'restored': true};
        });
    // Two pulses at one strength, rather than a strength flip whose two
    // pulses are necessarily different strengths.
    final result = await FitbitAir.ring(
      gap: const Duration(milliseconds: 300),
      maximum: const Duration(seconds: 20),
      pulses: 2,
    );
    expect((arguments! as Map)['pulses'], 2);
    expect(result.pulses, 2);
  });

  test('stopRing asks the device to stop without arguments', () async {
    String? method;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          method = call.method;
          return null;
        });
    await FitbitAir.stopRing();
    expect(method, 'stopRing');
  });

  test('returns a verified tap at the retained strength', () async {
    String? method;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          method = call.method;
          return {'intensity': 3, 'verified': true};
        });
    final result = await FitbitAir.tap();
    expect(method, 'tap');
    expect(result.intensity, HapticIntensity.high);
    expect(result.verified, isTrue);
  });

  test('tap passes an explicit strength and reports the change', () async {
    Object? arguments;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          arguments = call.arguments;
          return {
            'intensity': 2,
            'previousIntensity': 3,
            'changed': true,
            'verified': true,
          };
        });
    final result = await FitbitAir.tap(intensity: HapticIntensity.low);
    expect(arguments, {'intensity': 2});
    expect(result.intensity, HapticIntensity.low);
    expect(result.previousIntensity, HapticIntensity.high);
    expect(result.changed, isTrue);
  });

  test('maps native errors to a typed exception', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (_) async => throw PlatformException(
            code: 'AIR_NOT_FOUND',
            message: 'Pair the device first.',
          ),
        );
    await expectLater(FitbitAir.vibrate(), throwsA(isA<FitbitAirException>()));
  });

  test('reads battery, signal strength and connection state', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'readBattery':
              return {
                'percent': 55,
                'onCharger': false,
                'voltageMillivolts': 3951,
              };
            case 'readSignalStrength':
              return {'rssi': -61};
            case 'isAirConnected':
              return true;
          }
          return null;
        });
    final battery = await FitbitAir.readBattery();
    expect(battery.percent, 55);
    expect(battery.onCharger, isFalse);
    expect(battery.voltageMillivolts, 3951);
    expect(await FitbitAir.readSignalStrength(), -61);
    expect(await FitbitAir.isAirConnected(), isTrue);
  });

  test('battery fields the Air does not report stay null', () {
    final battery = AirBattery.fromMap({'percent': 80});
    expect(battery.onCharger, isNull);
    expect(battery.voltageMillivolts, isNull);
  });

  test('connection stream maps link events and errors', () async {
    const stream = EventChannel('fitbit_air/connection');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
          stream,
          MockStreamHandler.inline(
            onListen: (arguments, events) {
              events.success({'connected': true, 'receivedAtMillis': 1000});
              events.success({'connected': false, 'receivedAtMillis': 2000});
              events.error(code: 'AIR_NOT_FOUND', message: 'Pair first.');
            },
          ),
        );
    final received = <bool>[];
    final failure = Completer<Object>();
    final subscription = FitbitAir.watchConnection().listen(
      (event) => received.add(event.connected),
      onError: failure.complete,
    );
    expect(await failure.future, isA<FitbitAirException>());
    expect(received, [true, false]);
    await subscription.cancel();
  });

  test('classifies errors by what the user can do', () {
    expect(AirFailureKind.of('HANDSHAKE_TIMEOUT'), AirFailureKind.temporary);
    expect(AirFailureKind.of('AIR_NOT_FOUND'), AirFailureKind.setup);
    expect(
      const FitbitAirException('NATIVE_UNAVAILABLE', '').kind,
      AirFailureKind.compatibility,
    );
    expect(AirFailureKind.of('SOMETHING_NEW'), AirFailureKind.unknown);
  });
}
