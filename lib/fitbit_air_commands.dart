import 'dart:async';
import 'dart:collection';

import 'package:clock/clock.dart';

import 'fitbit_air.dart';

/// The known setting-based haptic operations, not arbitrary waveforms.
enum AirHapticKind {
  /// Two setting changes; attempts to restore and verify the prior intensity.
  preview,

  /// One setting change; intentionally retains the alternate intensity.
  singlePulse,

  /// One pulse at the wearer's strength, which is retained; see [FitbitAir.tap].
  tap,
}

/// A host-authenticated command with an application-assigned unique ID.
///
/// Authenticate/authorize the sender before constructing this. The SDK does
/// not authenticate push payloads, request notification access, or wake an app.
class AirHapticCommand {
  AirHapticCommand({
    required this.id,
    required this.expiresAt,
    this.kind = AirHapticKind.preview,
    this.intensity,
  }) {
    if (intensity != null && kind != AirHapticKind.tap) {
      throw ArgumentError('An explicit intensity requires AirHapticKind.tap.');
    }
    if (id.trim().isEmpty || id.length > 128) {
      throw ArgumentError.value(id, 'id', 'Must contain 1–128 characters.');
    }
  }

  final String id;
  final DateTime expiresAt;
  final AirHapticKind kind;

  /// Optional persistent strength for a single tap; null keeps current strength.
  final HapticIntensity? intensity;
}

enum AirHapticDispatchStatus {
  /// The SDK completed verification. Not a physical-delivery receipt.
  completed,
  duplicate,
  expired,
  queueFull,
  closed,

  /// Includes BUSY, timeout, disconnect, and restore/verification failures.
  failed,
}

class AirHapticDispatchResult {
  const AirHapticDispatchResult(this.status, {this.error});
  final AirHapticDispatchStatus status;
  final FitbitAirException? error;
}

/// Bounded, in-memory notification-command dispatch in one Dart isolate.
///
/// Reuse one dispatcher. Accepted IDs are remembered even after failure: a lost
/// response can mean a command physically ran. No automatic retry is performed.
/// Host apps must persist deduplication across restarts and coordinate separate
/// isolates/processes. The Android transport additionally rejects simultaneous
/// operations across Flutter engines in the same process with BUSY.
class AirHapticDispatcher {
  AirHapticDispatcher({
    this.minimumInterval = const Duration(seconds: 2),
    this.deduplicationWindow = const Duration(minutes: 5),
    this.maxPending = 4,
    this.maxRemembered = 256,
  }) {
    if (minimumInterval.isNegative ||
        deduplicationWindow <= Duration.zero ||
        maxPending < 1 ||
        maxRemembered < maxPending) {
      throw ArgumentError('Invalid dispatcher limits.');
    }
  }

  final Duration minimumInterval;
  final Duration deduplicationWindow;
  final int maxPending;
  final int maxRemembered;
  final _queue = Queue<_PendingCommand>();
  final _seen = <String, DateTime>{};
  final _pendingIds = <String>{};
  DateTime? _lastFinished;
  bool _running = false;
  bool _closed = false;
  Timer? _delayTimer;
  Completer<void>? _delayWait;

  /// Includes the currently executing command.
  int get pendingCount => _pendingIds.length;

  Future<AirHapticDispatchResult> submit(AirHapticCommand command) {
    if (_closed) return _result(AirHapticDispatchStatus.closed);
    final now = clock.now();
    if (!now.isBefore(command.expiresAt)) {
      return _result(AirHapticDispatchStatus.expired);
    }
    _seen.removeWhere((_, until) => !now.isBefore(until));
    if (_pendingIds.contains(command.id) || _seen.containsKey(command.id)) {
      return _result(AirHapticDispatchStatus.duplicate);
    }
    if (_pendingIds.length >= maxPending) {
      return _result(AirHapticDispatchStatus.queueFull);
    }
    if (_seen.length >= maxRemembered) _seen.remove(_seen.keys.first);
    _seen[command.id] = now.add(deduplicationWindow);
    _pendingIds.add(command.id);
    final pending = _PendingCommand(command);
    _queue.add(pending);
    if (!_running) {
      _running = true;
      unawaited(_drain());
    }
    return pending.completer.future;
  }

  /// Rejects new work and drops queued work. An in-flight haptic is allowed to
  /// finish, including best-effort restoration; this does not cancel a write.
  void close() {
    _closed = true;
    _delayTimer?.cancel();
    final waiting = _delayWait;
    if (waiting != null && !waiting.isCompleted) waiting.complete();
    while (_queue.isNotEmpty) {
      final pending = _queue.removeFirst();
      _pendingIds.remove(pending.command.id);
      pending.completer.complete(
        const AirHapticDispatchResult(AirHapticDispatchStatus.closed),
      );
    }
  }

  Future<void> _drain() async {
    try {
      while (_queue.isNotEmpty) {
        final pending = _queue.removeFirst();
        final lastFinished = _lastFinished;
        if (lastFinished != null) {
          final wait = lastFinished
              .add(minimumInterval)
              .difference(clock.now());
          if (wait > Duration.zero) {
            final waiting = Completer<void>();
            _delayWait = waiting;
            _delayTimer = Timer(wait, waiting.complete);
            await waiting.future;
            _delayTimer = null;
            _delayWait = null;
          }
        }
        var result = const AirHapticDispatchResult(
          AirHapticDispatchStatus.closed,
        );
        if (!_closed) {
          if (!clock.now().isBefore(pending.command.expiresAt)) {
            result = const AirHapticDispatchResult(
              AirHapticDispatchStatus.expired,
            );
          } else {
            try {
              if (pending.command.kind == AirHapticKind.tap) {
                final tap = await FitbitAir.tap(
                  intensity: pending.command.intensity,
                );
                if (!tap.verified) {
                  throw const FitbitAirException(
                    'UNVERIFIED',
                    'Tap was not verified.',
                  );
                }
              } else if (pending.command.kind == AirHapticKind.singlePulse) {
                final change = await FitbitAir.pulse();
                if (!change.verified) {
                  throw const FitbitAirException(
                    'UNVERIFIED',
                    'Pulse was not verified.',
                  );
                }
              } else {
                final preview = await FitbitAir.vibrate();
                if (!preview.verified || !preview.restored) {
                  throw const FitbitAirException(
                    'UNVERIFIED',
                    'Restoration was not verified.',
                  );
                }
              }
              result = const AirHapticDispatchResult(
                AirHapticDispatchStatus.completed,
              );
            } on FitbitAirException catch (error) {
              result = AirHapticDispatchResult(
                AirHapticDispatchStatus.failed,
                error: error,
              );
            } catch (_) {
              result = const AirHapticDispatchResult(
                AirHapticDispatchStatus.failed,
                error: FitbitAirException(
                  'DISPATCH_FAILED',
                  'Haptic dispatch failed.',
                ),
              );
            } finally {
              _lastFinished = clock.now();
            }
          }
        }
        _pendingIds.remove(pending.command.id);
        pending.completer.complete(result);
      }
    } finally {
      _running = false;
    }
  }

  static Future<AirHapticDispatchResult> _result(
    AirHapticDispatchStatus status,
  ) => Future.value(AirHapticDispatchResult(status));
}

class _PendingCommand {
  _PendingCommand(this.command);
  final AirHapticCommand command;
  final completer = Completer<AirHapticDispatchResult>();
}
