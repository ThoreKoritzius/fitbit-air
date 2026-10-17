import 'dart:async';

import 'package:clock/clock.dart';
import 'package:fake_async/fake_async.dart';
import 'package:fitbit_air/fitbit_air_commands.dart';
import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('fitbit_air');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  var calls = <String>[];
  const success = {'originalIntensity': 2, 'restored': true, 'verified': true};
  AirHapticCommand command(String id, {int lifetime = 60}) => AirHapticCommand(
    id: id,
    expiresAt: clock.now().add(Duration(seconds: lifetime)),
  );

  setUp(() {
    calls = [];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      return success;
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'explicit strength reaches a tap and cannot be attached to other kinds',
    () async {
      expect(
        () => AirHapticCommand(
          id: 'bad',
          expiresAt: clock.now().add(const Duration(minutes: 1)),
          intensity: HapticIntensity.low,
        ),
        throwsArgumentError,
      );
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'tap');
        expect(call.arguments, {'intensity': 2});
        return {
          'intensity': 2,
          'previousIntensity': 3,
          'changed': true,
          'verified': true,
        };
      });
      final dispatcher = AirHapticDispatcher();
      final result = await dispatcher.submit(
        AirHapticCommand(
          id: 'low',
          expiresAt: clock.now().add(const Duration(minutes: 1)),
          kind: AirHapticKind.tap,
          intensity: HapticIntensity.low,
        ),
      );
      expect(result.status, AirHapticDispatchStatus.completed);
      dispatcher.close();
    },
  );

  test('defaults to restoration preview and reports completion', () {
    fakeAsync((time) {
      final dispatcher = AirHapticDispatcher();
      AirHapticDispatchResult? result;
      dispatcher.submit(command('one')).then((value) => result = value);
      time.flushMicrotasks();
      expect(calls, ['vibrate']);
      expect(result?.status, AirHapticDispatchStatus.completed);
      expect(dispatcher.pendingCount, 0);
      dispatcher.close();
    });
  });

  test(
    'duplicate IDs do not trigger a second command, including in flight',
    () {
      fakeAsync((time) {
        final response = Completer<Object?>();
        messenger.setMockMethodCallHandler(channel, (call) {
          calls.add(call.method);
          return response.future;
        });
        final dispatcher = AirHapticDispatcher();
        final item = command('same');
        final statuses = <AirHapticDispatchStatus>[];
        dispatcher.submit(item).then((r) => statuses.add(r.status));
        dispatcher.submit(item).then((r) => statuses.add(r.status));
        time.flushMicrotasks();
        expect(statuses, [AirHapticDispatchStatus.duplicate]);
        response.complete(success);
        time.flushMicrotasks();
        dispatcher.submit(item).then((r) => statuses.add(r.status));
        time.flushMicrotasks();
        expect(calls.length, 1);
        expect(statuses.last, AirHapticDispatchStatus.duplicate);
        dispatcher.close();
      });
    },
  );

  test('expired messages never invoke the device', () {
    fakeAsync((time) {
      final dispatcher = AirHapticDispatcher();
      AirHapticDispatchStatus? status;
      dispatcher
          .submit(command('old', lifetime: 0))
          .then((r) => status = r.status);
      time.flushMicrotasks();
      expect(status, AirHapticDispatchStatus.expired);
      expect(calls, isEmpty);
      dispatcher.close();
    });
  });

  test('rechecks expiry after waiting for the rate limit', () {
    fakeAsync((time) {
      final dispatcher = AirHapticDispatcher();
      dispatcher.submit(command('first'));
      time.flushMicrotasks();
      AirHapticDispatchStatus? status;
      dispatcher
          .submit(command('expires-queued', lifetime: 1))
          .then((r) => status = r.status);
      time.elapse(const Duration(seconds: 2));
      time.flushMicrotasks();
      expect(status, AirHapticDispatchStatus.expired);
      expect(calls.length, 1);
      dispatcher.close();
    });
  });

  test('rate limit separates completed device operations', () {
    fakeAsync((time) {
      final dispatcher = AirHapticDispatcher();
      dispatcher.submit(command('first'));
      time.flushMicrotasks();
      dispatcher.submit(command('second'));
      time.flushMicrotasks();
      expect(calls.length, 1);
      time.elapse(const Duration(milliseconds: 1999));
      expect(calls.length, 1);
      time.elapse(const Duration(milliseconds: 1));
      time.flushMicrotasks();
      expect(calls.length, 2);
      dispatcher.close();
    });
  });

  test('bounds outstanding work and close leaves an active write alone', () {
    fakeAsync((time) {
      final response = Completer<Object?>();
      messenger.setMockMethodCallHandler(channel, (call) {
        calls.add(call.method);
        return response.future;
      });
      final dispatcher = AirHapticDispatcher(maxPending: 2);
      final statuses = <AirHapticDispatchStatus>[];
      dispatcher.submit(command('active')).then((r) => statuses.add(r.status));
      dispatcher.submit(command('queued')).then((r) => statuses.add(r.status));
      dispatcher
          .submit(command('overflow'))
          .then((r) => statuses.add(r.status));
      time.flushMicrotasks();
      expect(dispatcher.pendingCount, 2);
      expect(statuses, [AirHapticDispatchStatus.queueFull]);
      dispatcher.close();
      time.flushMicrotasks();
      expect(statuses.last, AirHapticDispatchStatus.closed);
      expect(dispatcher.pendingCount, 1);
      response.complete(success);
      time.flushMicrotasks();
      expect(statuses.last, AirHapticDispatchStatus.completed);
      expect(calls, ['vibrate']);
      expect(dispatcher.pendingCount, 0);
    });
  });

  test('ambiguous native failures are not retried or replayed', () {
    fakeAsync((time) {
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        throw PlatformException(code: 'TIMEOUT', message: 'Timed out.');
      });
      final dispatcher = AirHapticDispatcher();
      AirHapticDispatchResult? result;
      final item = command('unknown-outcome');
      dispatcher.submit(item).then((r) => result = r);
      time.flushMicrotasks();
      expect(result?.status, AirHapticDispatchStatus.failed);
      expect(result?.error?.code, 'TIMEOUT');
      dispatcher.submit(item).then((r) => result = r);
      time.flushMicrotasks();
      expect(result?.status, AirHapticDispatchStatus.duplicate);
      expect(calls.length, 1);
      dispatcher.close();
    });
  });

  test('single pulse is explicit and verification is required', () {
    fakeAsync((time) {
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return {
          'previousIntensity': 2,
          'currentIntensity': 3,
          'changed': true,
          'verified': false,
        };
      });
      final dispatcher = AirHapticDispatcher();
      AirHapticDispatchResult? result;
      dispatcher
          .submit(
            AirHapticCommand(
              id: 'pulse',
              expiresAt: clock.now().add(const Duration(minutes: 1)),
              kind: AirHapticKind.singlePulse,
            ),
          )
          .then((r) => result = r);
      time.flushMicrotasks();
      expect(calls, ['pulse']);
      expect(result?.status, AirHapticDispatchStatus.failed);
      expect(result?.error?.code, 'UNVERIFIED');
      dispatcher.close();
    });
  });

  test('tap commands use tap and require verification', () {
    fakeAsync((time) {
      var verified = true;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return {'intensity': 2, 'verified': verified};
      });
      final dispatcher = AirHapticDispatcher(minimumInterval: Duration.zero);
      final results = <AirHapticDispatchResult>[];
      AirHapticCommand tap(String id) => AirHapticCommand(
        id: id,
        expiresAt: clock.now().add(const Duration(minutes: 1)),
        kind: AirHapticKind.tap,
      );
      dispatcher.submit(tap('a')).then(results.add);
      time.flushMicrotasks();
      verified = false;
      dispatcher.submit(tap('b')).then(results.add);
      time.flushMicrotasks();
      expect(calls, ['tap', 'tap']);
      expect(results.map((r) => r.status), [
        AirHapticDispatchStatus.completed,
        AirHapticDispatchStatus.failed,
      ]);
      expect(results.last.error?.code, 'UNVERIFIED');
      dispatcher.close();
    });
  });

  test('deduplication is time bounded and rejects work after close', () {
    fakeAsync((time) {
      final dispatcher = AirHapticDispatcher(
        deduplicationWindow: const Duration(seconds: 3),
      );
      dispatcher.submit(command('reusable'));
      time.flushMicrotasks();
      time.elapse(const Duration(seconds: 4));
      dispatcher.submit(command('reusable'));
      time.flushMicrotasks();
      expect(calls.length, 2);
      dispatcher.close();
      AirHapticDispatchStatus? status;
      dispatcher.submit(command('new')).then((r) => status = r.status);
      time.flushMicrotasks();
      expect(status, AirHapticDispatchStatus.closed);
      expect(calls.length, 2);
    });
  });

  test('validates IDs and capacity configuration', () {
    expect(() => command(' '), throwsArgumentError);
    expect(() => command('a' * 129), throwsArgumentError);
    expect(() => AirHapticDispatcher(maxPending: 0), throwsArgumentError);
    expect(
      () => AirHapticDispatcher(maxPending: 5, maxRemembered: 4),
      throwsArgumentError,
    );
    expect(
      () => AirHapticDispatcher(deduplicationWindow: Duration.zero),
      throwsArgumentError,
    );
  });

  test('closing during rate-limit wait cancels the timer without writing', () {
    fakeAsync((time) {
      final dispatcher = AirHapticDispatcher(
        minimumInterval: const Duration(hours: 1),
      );
      dispatcher.submit(command('first'));
      time.flushMicrotasks();
      AirHapticDispatchStatus? status;
      dispatcher
          .submit(command('waiting', lifetime: 7200))
          .then((r) => status = r.status);
      time.flushMicrotasks();
      dispatcher.close();
      time.flushMicrotasks();
      expect(status, AirHapticDispatchStatus.closed);
      expect(dispatcher.pendingCount, 0);
      expect(time.pendingTimers, isEmpty);
      expect(calls.length, 1);
    });
  });
}
