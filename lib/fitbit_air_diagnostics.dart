import 'package:flutter/services.dart';

import 'fitbit_air.dart';

/// Research tools whose events do not have a confirmed user-facing meaning.
class FitbitAirDiagnostics {
  FitbitAirDiagnostics._();

  static const _channel = EventChannel('fitbit_air/device_events');
  static const _methods = MethodChannel('fitbit_air');
  static const _secureChannel = EventChannel('fitbit_air/secure_events');
  static const _activityChannel = EventChannel('fitbit_air/channel_activity');

  /// How Google Health is using the Air's shared secure channel, observed
  /// passively: [AirChannelActivity.idle], [AirChannelActivity.polling]
  /// (Google Health open in the foreground) or [AirChannelActivity.syncing]
  /// (a data transfer). Emits the current state, then every change.
  ///
  /// Only timing and sizes of the Air's encrypted notifications are used; the
  /// stream never writes to the channel. Commands from this package also
  /// appear as activity while they run.
  static Stream<AirChannelActivity> watchChannelActivity() => _activityChannel
      .receiveBroadcastStream()
      .map(
        (value) => AirChannelActivity.values.byName(
          (value as Map)['state']! as String,
        ),
      )
      .handleError((Object error) {
        if (error is PlatformException) {
          throw FitbitAirException(
            error.code,
            error.message ?? 'Channel activity stream failed.',
          );
        }
        throw error;
      });

  /// Reads bounded command-cleanup history shared by engines in this process.
  /// Does not connect or require Bluetooth permission. `returned` means the
  /// cleanup call returned, not that the physical link or Health sync recovered.
  /// `appLeaseHeld` describes this app only. History resets after process exit;
  /// passive device-event monitor teardown is outside this history's scope.
  static Future<Map<String, Object?>> readTransportDiagnostics() async =>
      await _methods.invokeMapMethod<String, Object?>('transportDiagnostics') ??
      <String, Object?>{};

  /// How often the warm link needed recovery in this process; see
  /// [FitbitAir.keepLinkWarm]. Does not connect.
  static Future<AirLinkHealth> readLinkHealth() async =>
      AirLinkHealth.fromMap(await readTransportDiagnostics());

  /// Experimental, bounded wrist-presence events from the secure monitor.
  /// Opens the same bootstrap session as [watchSecureEvents], for up to 30s
  /// after ready. Cancel before another SDK operation. Repeated events are
  /// retained; no initial state or tap is inferred. Times are phone receipt
  /// times. Transport interruptions raise [FitbitAirException].
  static Stream<AirWristPresenceEvent> watchWristPresence() =>
      watchSecureEvents().expand((event) => event.wristPresenceEvents);

  /// Observes POST /events and /eventsSecure for 30–90 seconds after ready.
  /// Defaults to 30 seconds. Longer trials extend Bluetooth contention.
  /// Opens a bootstrap DTLS session and ACKs valid events on both routes with
  /// 2.04 (malformed bodies receive 4.00), but sends no
  /// setting, alarm, sync or reset command. May contend with Google Health.
  /// These are protocol events, NOT confirmed wrist taps. The total operation
  /// deadline is [durationSeconds] + 15 seconds; slow negotiation may truncate it.
  /// Other source-traced incoming routes are rejected with 4.04. No raw bodies are
  /// returned. An interrupted event precedes transport errors and
  /// reports actual observation time; zero means the monitor was never ready.
  static Stream<AirSecureEvent> watchSecureEvents({int durationSeconds = 30}) {
    if (durationSeconds < 30 || durationSeconds > 90) {
      throw ArgumentError.value(
        durationSeconds,
        'durationSeconds',
        'Must be 30–90',
      );
    }
    return _secureChannel
        .receiveBroadcastStream({'durationSeconds': durationSeconds})
        .map(
          (value) =>
              AirSecureEvent.fromMap(Map<String, Object?>.from(value as Map)),
        )
        .handleError((Object error) {
          if (error is PlatformException) {
            throw FitbitAirException(
              error.code,
              error.message ?? 'Secure event capture failed.',
            );
          }
          throw error;
        });
  }

  /// One secure session, four fixed source-traced GET requests. Never writes
  /// settings or returns raw bodies, alarm times, or activity measurements.
  /// This is research tooling, not a promise of access or sync coexistence.
  static Future<List<AirResourceAccessResult>> probeReadOnlyResources() async {
    try {
      final value = await _methods.invokeMapMethod<String, Object?>(
        'probeReadOnlyResources',
      );
      if (value == null) {
        throw const FitbitAirException(
          'EMPTY_RESPONSE',
          'Resource probe returned no result.',
        );
      }
      return List.unmodifiable(
        (value['resources']! as List).map(
          (entry) => AirResourceAccessResult.fromMap(
            Map<String, Object?>.from(entry as Map),
          ),
        ),
      );
    } on PlatformException catch (error) {
      throw FitbitAirException(
        error.code,
        error.message ?? 'Resource probe failed.',
      );
    }
  }

  /// Research-only GET of the device-info resource that includes battery data.
  /// Opens a Golden Gate session, so Google Health coexistence is NOT assured.
  /// Sends no setting writes. Returns response status and selected raw battery
  /// fields, never identifiers or the original body. Units are unverified;
  /// rawBatteryLevel must not be assumed to be a percentage.
  static Future<AirBatteryAccessResult> probeBatteryAccess() async {
    try {
      final value = await _methods.invokeMapMethod<String, Object?>(
        'probeBatteryAccess',
      );
      if (value == null) {
        throw const FitbitAirException(
          'EMPTY_RESPONSE',
          'Battery probe returned no result.',
        );
      }
      return AirBatteryAccessResult.fromMap(value);
    } on PlatformException catch (error) {
      throw FitbitAirException(
        error.code,
        error.message ?? 'Battery probe failed.',
      );
    }
  }

  /// Watches notification characteristics in the Air's link-status and
  /// unclassified services. Excludes the Goldengate tracker channel and HR.
  ///
  /// These are **not confirmed tap events**. Payloads may contain sensitive
  /// device data; keep them local unless the user explicitly chooses to share.
  /// Cancel the subscription before another SDK operation.
  static Stream<AirDeviceEvent> watchDeviceEvents() => _channel
      .receiveBroadcastStream()
      .map(
        (value) =>
            AirDeviceEvent.fromMap(Map<String, Object?>.from(value as Map)),
      )
      .handleError((Object error) {
        if (error is PlatformException) {
          throw FitbitAirException(
            error.code,
            error.message ?? 'Device event monitor failed.',
          );
        }
        throw error;
      });
}

/// Bounded CoAP event metadata; unknown IDs are not interpreted as gestures.
class AirSecureEvent {
  AirSecureEvent.fromMap(Map<String, Object?> map)
    : kind = map['kind']! as String,
      receivedAt = DateTime.fromMillisecondsSinceEpoch(
        map['receivedAtMillis']! as int,
      ),
      eventIds = List.unmodifiable(
        (map['eventIds'] as List? ?? []).cast<int>(),
      ),
      eventNames = List.unmodifiable(
        (map['eventNames'] as List? ?? []).cast<String>(),
      ),
      receivedPosts = map['receivedPosts'] as int?,
      droppedPosts = map['droppedPosts'] as int?,
      durationSeconds = map['durationSeconds'] as int?,
      path = map['path'] as String?,
      method = map['method'] as String?,
      bodyLength = map['bodyLength'] as int?,
      observationMillis = map['observationMillis'] as int?,
      otherRequests = map['otherRequests'] as int?,
      droppedOtherRequests = map['droppedOtherRequests'] as int?,
      errorCode = map['errorCode'] as String?,
      eventResponseCode = map['eventResponseCode'] as String?;
  final String kind;
  final DateTime receivedAt;
  final List<int> eventIds;
  final List<String> eventNames;
  final int? receivedPosts;
  final int? droppedPosts;
  final int? durationSeconds;
  final String? path;
  final String? method;
  final int? bodyLength;
  final int? observationMillis;
  final int? otherRequests;
  final int? droppedOtherRequests;
  final String? errorCode;
  final String? eventResponseCode;

  /// Only the two source-traced EventSet routes can yield presence events.
  List<AirWristPresenceEvent> get wristPresenceEvents {
    if ((kind != 'device_event' && kind != 'unhandled_request') ||
        (path != 'events' && path != 'eventsSecure')) {
      return const [];
    }
    return List.unmodifiable([
      for (final id in eventIds)
        if (id == 2003989291 || id == 2003989293)
          AirWristPresenceEvent._(
            id == 2003989291
                ? AirWristPresence.onWrist
                : AirWristPresence.offWrist,
            receivedAt,
            path!,
          ),
    ]);
  }
}

/// Source-traced tracker wrist-presence state; independent of tap recognition.
enum AirWristPresence { onWrist, offWrist }

/// Device-reported presence with phone receipt time and originating route.
class AirWristPresenceEvent {
  const AirWristPresenceEvent._(this.state, this.receivedAt, this.sourcePath);
  final AirWristPresence state;
  final DateTime receivedAt;
  final String sourcePath;
}

/// Access metadata only; a successful response is not a decoded stats API.
class AirResourceAccessResult {
  AirResourceAccessResult.fromMap(Map<String, Object?> map)
    : path = map['path']! as String,
      responseClass = map['responseClass'] as int?,
      detail = map['detail'] as int?,
      bodyLength = map['bodyLength'] as int?,
      errorCode = map['errorCode'] as String?;
  final String path;
  final int? responseClass;
  final int? detail;
  final int? bodyLength;
  final String? errorCode;
  bool get granted => responseClass == 2;
  String? get responseCode => responseClass == null || detail == null
      ? null
      : '$responseClass.${detail.toString().padLeft(2, '0')}';
}

/// Access status and selected battery fields with unverified scale/units.
class AirBatteryAccessResult {
  AirBatteryAccessResult.fromMap(Map<String, Object?> map)
    : responseClass = map['responseClass']! as int,
      detail = map['detail']! as int,
      bodyLength = map['bodyLength']! as int,
      rawBatteryLevel = map['rawBatteryLevel'] as int?,
      rawVoltage = map['rawVoltage'] as int?,
      rawOnCharger = map['rawOnCharger'] as int?,
      decodeError = map['decodeError'] as String?;
  final int responseClass;
  final int detail;
  final int bodyLength;
  final int? rawBatteryLevel;
  final int? rawVoltage;
  final int? rawOnCharger;
  final String? decodeError;
  bool get granted => responseClass == 2;
  String get responseCode =>
      '$responseClass.${detail.toString().padLeft(2, '0')}';
}

/// A subscription result, monitor readiness message, or unclassified BLE value.
class AirDeviceEvent {
  AirDeviceEvent.fromMap(Map<String, Object?> map)
    : kind = map['kind']! as String,
      receivedAt = DateTime.fromMillisecondsSinceEpoch(
        map['receivedAtMillis']! as int,
      ),
      serviceUuid = map['serviceUuid'] as String?,
      characteristicUuid = map['characteristicUuid'] as String?,
      status = map['status'] as int?,
      subscribedCount = map['subscribed'] as int?,
      payload = Uint8List.fromList(
        (map['payload'] as Uint8List?) ?? Uint8List(0),
      );

  final String kind;
  final DateTime receivedAt;
  final String? serviceUuid;
  final String? characteristicUuid;
  final int? status;
  final int? subscribedCount;
  final Uint8List payload;

  bool get isNotification => kind == 'notification';
}

/// Google Health's use of the Air's shared secure channel.
enum AirChannelActivity {
  /// No traffic: a command now will not interrupt Google Health.
  idle,

  /// Google Health is open and checking in about once a second.
  polling,

  /// A data transfer is in progress; commands wait for it to finish.
  syncing,
}

/// Warm-link recoveries since the app process started.
class AirLinkHealth {
  const AirLinkHealth({
    this.silentFallbacks = 0,
    this.rebuilds = const {},
    this.lastRecoveryAt,
  });

  factory AirLinkHealth.fromMap(Map<String, Object?> map) {
    final counts = <String, int>{};
    final raw = map['linkRecoveries'];
    if (raw is Map) {
      raw.forEach((key, value) {
        if (key is String && value is int) counts[key] = value;
      });
    }
    final last = map['lastLinkRecoveryAtMillis'];
    return AirLinkHealth(
      silentFallbacks: counts.remove('silent_fallback') ?? 0,
      rebuilds: {
        for (final entry in counts.entries)
          if (entry.key.startsWith('rebuilt_'))
            entry.key.substring('rebuilt_'.length): entry.value,
      },
      lastRecoveryAt: last is int && last > 0
          ? DateTime.fromMillisecondsSinceEpoch(last)
          : null,
    );
  }

  /// Commands that found the warm link deaf and continued on a fresh
  /// connection.
  final int silentFallbacks;

  /// Warm-link rebuilds by reason: `after_fallback`, `bluetooth_on`,
  /// `stuck` or `missing`.
  final Map<String, int> rebuilds;
  final DateTime? lastRecoveryAt;

  int get totalRecoveries =>
      silentFallbacks + rebuilds.values.fold(0, (sum, n) => sum + n);
}
