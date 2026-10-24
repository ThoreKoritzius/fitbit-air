import 'package:fitbit_air/fitbit_air_diagnostics.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const methods = MethodChannel('fitbit_air');
  test(
    'transport report is passive and preserves cleanup failure states',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methods, (call) async {
            expect(call.method, 'transportDiagnostics');
            expect(call.arguments, isNull);
            return {
              'appLeaseHeld': false,
              'cleanupHistory': [
                {
                  'steps': {'gattClose': 'failed'},
                },
              ],
            };
          });
      final report = await FitbitAirDiagnostics.readTransportDiagnostics();
      expect(report['appLeaseHeld'], false);
      expect(
        (report['cleanupHistory'] as List).single['steps']['gattClose'],
        'failed',
      );
    },
  );
  test('secure observation duration is bounded before opening transport', () {
    expect(
      () => FitbitAirDiagnostics.watchSecureEvents(durationSeconds: 29),
      throwsArgumentError,
    );
    expect(
      () => FitbitAirDiagnostics.watchSecureEvents(durationSeconds: 91),
      throwsArgumentError,
    );
    expect(
      FitbitAirDiagnostics.watchSecureEvents(durationSeconds: 90),
      isA<Stream<AirSecureEvent>>(),
    );
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methods, null),
  );

  test(
    'resource probe exposes response metadata without assuming decoded stats',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methods, (call) async {
            expect(call.method, 'probeReadOnlyResources');
            return {
              'resources': [
                {
                  'path': '/alarms',
                  'responseClass': 4,
                  'detail': 1,
                  'bodyLength': 0,
                },
                {
                  'path': '/md/606',
                  'responseClass': 2,
                  'detail': 5,
                  'bodyLength': 42,
                },
                {'path': 'liveactivity', 'errorCode': 'RESOURCE_READ_FAILED'},
              ],
            };
          });
      final results = await FitbitAirDiagnostics.probeReadOnlyResources();
      expect(results[0].responseCode, '4.01');
      expect(results[0].granted, isFalse);
      expect(results[1].granted, isTrue);
      expect(results[2].responseCode, isNull);
      expect(results[2].errorCode, 'RESOURCE_READ_FAILED');
    },
  );

  test(
    'presence mapping retains reported order and ignores other routes and events',
    () {
      final event = AirSecureEvent.fromMap({
        'kind': 'unhandled_request',
        'receivedAtMillis': 1234,
        'path': 'eventsSecure',
        'eventIds': [
          2003989291,
          1920165236,
          2003989293,
          1885957735,
          2003989291,
        ],
      });
      expect(event.wristPresenceEvents.map((e) => e.state), [
        AirWristPresence.onWrist,
        AirWristPresence.offWrist,
        AirWristPresence.onWrist,
      ]);
      expect(event.wristPresenceEvents.first.sourcePath, 'eventsSecure');
      expect(
        event.wristPresenceEvents.first.receivedAt.millisecondsSinceEpoch,
        1234,
      );
      final unrelated = AirSecureEvent.fromMap({
        'kind': 'unhandled_request',
        'receivedAtMillis': 1234,
        'path': 'md/1802',
        'eventIds': [2003989291],
      });
      expect(unrelated.wristPresenceEvents, isEmpty);
    },
  );

  test(
    'interrupted observation preserves coverage without implying completion',
    () {
      final interrupted = AirSecureEvent.fromMap({
        'kind': 'interrupted',
        'receivedAtMillis': 1000,
        'observationMillis': 577,
        'receivedPosts': 1,
        'otherRequests': 2,
        'errorCode': 'GATT_DISCONNECTED',
      });
      expect(interrupted.kind, 'interrupted');
      expect(interrupted.observationMillis, 577);
      expect(interrupted.otherRequests, 2);
      expect(interrupted.errorCode, 'GATT_DISCONNECTED');
      expect(interrupted.eventIds, isEmpty);
      final route = AirSecureEvent.fromMap({
        'kind': 'unhandled_request',
        'receivedAtMillis': 999,
        'path': 'eventsSecure',
        'method': 'POST',
        'bodyLength': 6,
      });
      expect(route.path, 'eventsSecure');
      expect(route.method, 'POST');
      expect(route.bodyLength, 6);
      expect(route.eventNames, isEmpty);
    },
  );

  test(
    'secure event metadata retains unknown IDs without gesture inference',
    () {
      final event = AirSecureEvent.fromMap({
        'kind': 'device_event',
        'receivedAtMillis': 1234,
        'eventIds': [1885957735, 4294967295],
        'eventNames': ['PING', 'UNKNOWN'],
      });
      expect(event.eventNames, ['PING', 'UNKNOWN']);
      expect(event.eventIds.last, 4294967295);
      expect(() => event.eventIds.add(1), throwsUnsupportedError);
      final complete = AirSecureEvent.fromMap({
        'kind': 'complete',
        'receivedAtMillis': 1235,
        'receivedPosts': 0,
        'droppedPosts': 0,
      });
      expect(complete.receivedPosts, 0);
      expect(complete.eventIds, isEmpty);
    },
  );

  test(
    'battery probe distinguishes denied access from a battery measurement',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methods, (call) async {
            expect(call.method, 'probeBatteryAccess');
            return {'responseClass': 4, 'detail': 1, 'bodyLength': 0};
          });
      final result = await FitbitAirDiagnostics.probeBatteryAccess();
      expect(result.responseCode, '4.01');
      expect(result.granted, isFalse);
      expect(result.bodyLength, 0);
    },
  );

  test(
    'battery probe formats successful access without interpreting contents',
    () {
      final result = AirBatteryAccessResult.fromMap({
        'responseClass': 2,
        'detail': 5,
        'bodyLength': 42,
        'rawBatteryLevel': 77,
        'rawVoltage': 4000,
        'rawOnCharger': 0,
      });
      expect(result.responseCode, '2.05');
      expect(result.granted, isTrue);
      expect(result.bodyLength, 42);
      expect(result.rawBatteryLevel, 77);
      expect(result.rawVoltage, 4000);
      expect(result.rawOnCharger, 0);
      expect(result.decodeError, isNull);
    },
  );

  test(
    'notification copies the codec payload without interpreting it as a tap',
    () {
      final bytes = Uint8List.fromList([1, 2, 3]);
      final event = AirDeviceEvent.fromMap({
        'kind': 'notification',
        'receivedAtMillis': 1234,
        'serviceUuid': 'service',
        'characteristicUuid': 'characteristic',
        'payload': bytes,
      });
      bytes[0] = 99;
      expect(event.payload, [1, 2, 3]);
      expect(event.isNotification, isTrue);
      expect(event.receivedAt.millisecondsSinceEpoch, 1234);
      expect(event.characteristicUuid, 'characteristic');
    },
  );

  test('control events have no measurement payload', () {
    final ready = AirDeviceEvent.fromMap({
      'kind': 'ready',
      'receivedAtMillis': 1000,
      'subscribed': 7,
    });
    expect(ready.subscribedCount, 7);
    expect(ready.payload, isEmpty);
    expect(ready.isNotification, isFalse);
    final disconnected = AirDeviceEvent.fromMap({
      'kind': 'disconnected',
      'receivedAtMillis': 2000,
      'status': 257,
    });
    expect(disconnected.status, 257);
    expect(disconnected.isNotification, isFalse);
  });

  test('channel activity maps idle, polling and syncing', () async {
    const stream = EventChannel('fitbit_air/channel_activity');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
          stream,
          MockStreamHandler.inline(
            onListen: (arguments, events) {
              for (final state in ['idle', 'syncing', 'polling']) {
                events.success({'state': state, 'receivedAtMillis': 0});
              }
              events.endOfStream();
            },
          ),
        );
    expect(await FitbitAirDiagnostics.watchChannelActivity().toList(), [
      AirChannelActivity.idle,
      AirChannelActivity.syncing,
      AirChannelActivity.polling,
    ]);
  });

  test('link health counts fallbacks and rebuilds by reason', () {
    final health = AirLinkHealth.fromMap({
      'linkRecoveries': {
        'silent_fallback': 2,
        'rebuilt_after_fallback': 2,
        'rebuilt_bluetooth_on': 1,
      },
      'lastLinkRecoveryAtMillis': 1000,
    });
    expect(health.silentFallbacks, 2);
    expect(health.rebuilds, {'after_fallback': 2, 'bluetooth_on': 1});
    expect(health.totalRecoveries, 5);
    expect(health.lastRecoveryAt, DateTime.fromMillisecondsSinceEpoch(1000));
    final empty = AirLinkHealth.fromMap(const {});
    expect(empty.totalRecoveries, 0);
    expect(empty.lastRecoveryAt, isNull);
  });
}
