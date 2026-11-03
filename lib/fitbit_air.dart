import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// A small Android bridge for directly controlling a paired Fitbit Air.
///
/// The Air must already be paired to this Android phone in Google Health.
/// This package does not use the retired Fitbit Web API or Google Health cloud
/// APIs. Wrist-tap detection is not yet implemented.
class FitbitAir {
  FitbitAir._();

  /// Air firmware versions this package version was verified with. Other
  /// versions may work; show users that they are untested, and suggest an
  /// update if commands keep failing with [AirFailureKind.compatibility].
  static const testedFirmware = {'67.20001.253.2'};

  /// Adds the licenses of the bundled native transport (Golden Gate, Mbed TLS
  /// and lwIP) to Flutter's license page. Their terms require apps that ship
  /// the binaries to include these texts; call this once, for example in
  /// `main()`.
  static void registerLicenses() {
    LicenseRegistry.addLicense(() async* {
      Future<String> load(String path) =>
          rootBundle.loadString('packages/fitbit_air/$path');
      yield LicenseEntryWithLineBreaks(
        const ['Golden Gate'],
        '${await load('third_party/golden-gate/NOTICE')}\n\n'
        '${await load('LICENSE-APACHE-2.0')}',
      );
      yield LicenseEntryWithLineBreaks(const [
        'Mbed TLS',
      ], await load('third_party/mbedtls/LICENSE'));
      yield LicenseEntryWithLineBreaks(const [
        'lwIP',
      ], await load('third_party/lwip/COPYING'));
    });
  }

  static const MethodChannel _channel = MethodChannel('fitbit_air');
  static const EventChannel _heartRateChannel = EventChannel(
    'fitbit_air/heart_rate',
  );
  static const EventChannel _connectionChannel = EventChannel(
    'fitbit_air/connection',
  );

  /// Streams measurements while Google Health heart-rate sharing is active.
  /// Omit [deviceId] to use the paired Air, or select an advertised sensor.
  /// Cancel the subscription to release Bluetooth before sending a haptic.
  static Stream<AirHeartRate> watchHeartRate({String? deviceId}) =>
      _heartRateChannel
          .receiveBroadcastStream({'deviceId': deviceId})
          .map(
            (value) =>
                AirHeartRate.fromMap(Map<String, Object?>.from(value as Map)),
          )
          .handleError((Object error) {
            if (error is PlatformException) {
              throw FitbitAirException(
                error.code,
                error.message ?? 'Heart-rate stream failed.',
              );
            }
            throw error;
          });

  /// Whether the Air's Bluetooth link is currently up.
  ///
  /// Read from Android's connection state; opens no connection. Google Health
  /// keeps the link while the Air is in range, so `false` usually means out of
  /// range, Bluetooth off, or the Air powered down.
  static Future<bool> isAirConnected() async {
    try {
      return await _channel.invokeMethod<bool>('isAirConnected') ?? false;
    } on PlatformException catch (error) {
      throw FitbitAirException(
        error.code,
        error.message ?? 'Could not check the connection.',
      );
    }
  }

  /// Emits the current link state, then every change.
  ///
  /// Passive: listens to Android's system connection events and opens no
  /// connection, so it can run for as long as your app is alive (for example
  /// to warn when the Air is left behind).
  static Stream<AirConnectionEvent> watchConnection() => _connectionChannel
      .receiveBroadcastStream()
      .map(
        (value) =>
            AirConnectionEvent.fromMap(Map<String, Object?>.from(value as Map)),
      )
      .handleError(_streamError('Connection stream failed.'));

  /// Reads the Bluetooth signal strength of the Air's link in dBm.
  ///
  /// Uses a brief connection without the secure channel, so it does not
  /// compete with Google Health. Values typically range from about -40 (very
  /// close) to -95 (edge of range); treat them as a rough proximity hint.
  static Future<int> readSignalStrength() async =>
      (await _invokeMap('readSignalStrength'))['rssi']! as int;

  /// Keeps a listen-only Bluetooth link to the Air open while [enabled].
  ///
  /// Useful for apps that send commands on demand, such as a notification
  /// relay: commands then skip connection setup and start without first
  /// observing the channel, about a second sooner. The link never writes on
  /// its own and does not compete with Google Health. Android reconnects it
  /// when the Air returns to range. Disable it when you no longer need it.
  static Future<void> keepLinkWarm(bool enabled) async {
    try {
      await _channel.invokeMethod<void>('keepLinkWarm', {'enabled': enabled});
    } on PlatformException catch (error) {
      throw FitbitAirException(
        error.code,
        error.message ?? 'Could not change the warm link.',
      );
    }
  }

  /// Reads the battery level the Air reports, which matches the percentage
  /// shown in Google Health.
  ///
  /// Opens a short secure session (about 3 s), which waits while Google Health
  /// is syncing and hands the channel back afterward. Avoid polling it often.
  static Future<AirBattery> readBattery() async =>
      AirBattery.fromMap(await _invokeMap('readBattery'));

  static void Function(Object) _streamError(String fallback) => (Object error) {
    if (error is PlatformException) {
      throw FitbitAirException(error.code, error.message ?? fallback);
    }
    throw error;
  };

  /// Cancels the active operation and closes its Bluetooth client.
  /// The pending read completes with a `CANCELLED` exception. Haptic write
  /// operations reject cancellation so their verification/restore can finish.
  static Future<void> cancelOperation() async {
    try {
      await _channel.invokeMethod<void>('cancelOperation');
    } on PlatformException catch (error) {
      throw FitbitAirException(
        error.code,
        error.message ?? 'Cancellation failed.',
      );
    }
  }

  /// Returns whether a Fitbit Air is present in Android's bonded-device list.
  /// Android may prompt for the Nearby devices permission on first use.
  static Future<bool> isAirPaired() async {
    try {
      return await _channel.invokeMethod<bool>('isAirPaired') ?? false;
    } on PlatformException catch (error) {
      throw FitbitAirException(
        error.code,
        error.message ?? 'Could not check paired devices.',
      );
    }
  }

  /// Discovers services and reads available standard device information.
  ///
  /// Uses GATT without opening a Golden Gate session. Missing or inaccessible
  /// fields remain null; [AirDeviceInfo.readErrors] explains rejected reads.
  static Future<AirDeviceInfo> readDeviceInfo() async =>
      AirDeviceInfo.fromMap(await _invokeMap('readDeviceInfo'));

  /// Waits for the next standard Bluetooth Heart Rate Measurement, then closes
  /// the connection. Wear the Air with good skin contact before calling.
  static Future<AirHeartRate> readHeartRate({String? deviceId}) async =>
      AirHeartRate.fromMap(
        await _invokeMap('readHeartRate', {'deviceId': deviceId}),
      );

  /// Scans for standard heart-rate advertisements for eight seconds.
  /// Enable sharing in Google Health, then explicitly select the desired Air.
  static Future<List<AirHeartRateDevice>> scanHeartRateDevices() async {
    final map = await _invokeMap('scanHeartRateDevices');
    return List.unmodifiable(
      (map['devices']! as List).map(
        (value) => AirHeartRateDevice.fromMap(
          Map<Object?, Object?>.from(value as Map),
        ),
      ),
    );
  }

  /// Reads the persistent vibration strength without changing it.
  static Future<HapticIntensity> getHapticIntensity() async {
    final result = await _invokeMap('getHapticIntensity');
    return HapticIntensity.fromWireValue(result['intensity']! as int);
  }

  /// Sets and verifies the persistent vibration strength.
  ///
  /// Changing the value causes one physical pulse on the Air. Requesting the
  /// value already in use is a no-op.
  static Future<HapticChangeResult> setHapticIntensity(
    HapticIntensity intensity,
  ) async => HapticChangeResult.fromMap(
    await _invokeMap('setHapticIntensity', {'intensity': intensity.wireValue}),
  );

  /// One pulse.
  ///
  /// Without [intensity] (or with the strength already set), the pulse uses
  /// the wearer's strength from Google Health, which is retained: the other
  /// strength and then the original are sent in a single settings message, and
  /// the Air supersedes the first change's pulse.
  ///
  /// With a different [intensity], the pulse uses that strength and the Air
  /// keeps it ([TapResult.changed] is true). The Air only pulses on strength
  /// changes, so it cannot pulse at one strength while keeping another.
  ///
  /// The setting is read back afterward and restored if verification fails,
  /// which throws [FitbitAirException] with `HAPTIC_TAP_FAILED`. Observed on
  /// one Air firmware; see the research notes.
  static Future<TapResult> tap({HapticIntensity? intensity}) async =>
      TapResult.fromMap(
        await _invokeMap('tap', {
          if (intensity != null) 'intensity': intensity.wireValue,
        }),
      );

  /// Pulses repeatedly at one strength until [stopRing], [maximum], or a lost
  /// link ends it, then restores the wearer's own strength.
  ///
  /// Unlike repeated [tap] calls, the secure session is opened once and held,
  /// so [gap] can be a few hundred milliseconds instead of the seconds a fresh
  /// session costs. That is what makes a ringing call feel like a ring.
  ///
  /// The Air plays one pulse per change it notices, so a [gap] shorter than a
  /// pulse merges buzzes together; below 250 ms is rejected outright. While a
  /// ring runs it holds the tracker channel, which delays Google Health.
  ///
  /// With [pulses], stops after that many instead of running until told to.
  /// A short fixed count is how a two-pulse pattern plays at a chosen
  /// strength: both pulses share one session, so they land close together.
  ///
  /// Returns how many pulses were posted and whether the original strength was
  /// verified restored. A posted pulse is not a promise one was felt.
  static Future<AirRingResult> ring({
    HapticIntensity? intensity,
    Duration gap = const Duration(milliseconds: 700),
    Duration maximum = const Duration(seconds: 45),
    int? pulses,
  }) async => AirRingResult.fromMap(
    await _invokeMap('ring', {
      if (intensity != null) 'intensity': intensity.wireValue,
      'gapMillis': gap.inMilliseconds,
      'maxMillis': maximum.inMilliseconds,
      'pulses': ?pulses,
    }),
  );

  /// Ends a running [ring]. Safe to call when nothing is ringing.
  static Future<void> stopRing() async {
    try {
      await _channel.invokeMethod<void>('stopRing');
    } on PlatformException catch (error) {
      throw FitbitAirException(
        error.code,
        error.message ?? 'Could not stop the ring.',
      );
    }
  }

  /// Produces one pulse by switching to the other vibration strength.
  ///
  /// The new strength intentionally remains active, so successive calls
  /// normally alternate between low and high. Google Health may independently
  /// reconcile its stored setting later. Prefer [tap] for one pulse that keeps
  /// the wearer's strength.
  static Future<HapticChangeResult> pulse() async =>
      HapticChangeResult.fromMap(await _invokeMap('pulse'));

  /// Sends a short, bounded haptic test and restores the previous intensity.
  ///
  /// Requires the Air to be paired with Google Health on this same phone.
  /// Throws [FitbitAirException] when Bluetooth permission, connection,
  /// transport negotiation, or the setting restore/verification fails.
  static Future<VibrationResult> vibrate() async {
    final result = await _invokeMap('vibrate');
    return VibrationResult.fromMap(result);
  }

  static Future<Map<String, Object?>> _invokeMap(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    try {
      final result = await _channel.invokeMapMethod<String, Object?>(
        method,
        arguments,
      );
      if (result == null) {
        throw const FitbitAirException('EMPTY_RESULT', 'No result returned.');
      }
      return result;
    } on PlatformException catch (error) {
      throw FitbitAirException(
        error.code,
        error.message ?? 'Fitbit Air operation failed.',
      );
    }
  }
}

class AirHeartRateDevice {
  AirHeartRateDevice.fromMap(Map<Object?, Object?> map)
    : deviceId = map['deviceId']! as String,
      name = map['name']! as String,
      rssi = map['rssi']! as int;

  /// Device address returned by Android; treat this identifier as private.
  final String deviceId;
  final String name;
  final int rssi;
}

/// Standard Bluetooth heart-rate measurement. Optional fields depend on device.
class AirHeartRate {
  AirHeartRate.fromMap(Map<String, Object?> map)
    : beatsPerMinute = map['beatsPerMinute']! as int,
      receivedAt = DateTime.fromMillisecondsSinceEpoch(
        map['receivedAtMillis']! as int,
      ),
      contactDetected = map['contactDetected'] as bool?,
      energyExpendedKj = map['energyExpendedKj'] as int?,
      rrIntervalsMillis = List.unmodifiable(
        (map['rrIntervalsMillis']! as List).map(
          (value) => (value as num).toDouble(),
        ),
      );

  final int beatsPerMinute;
  final DateTime receivedAt;

  /// Null when the sensor does not report contact status.
  final bool? contactDetected;
  final int? energyExpendedKj;
  final List<double> rrIntervalsMillis;
}

/// Information actually exposed by the connected Air's GATT services.
class AirDeviceInfo {
  AirDeviceInfo.fromMap(Map<String, Object?> map)
    : name = map['name']! as String,
      batteryPercent = map['batteryPercent'] as int?,
      model = map['model'] as String?,
      firmware = map['firmware'] as String?,
      hardware = map['hardware'] as String?,
      manufacturer = map['manufacturer'] as String?,
      connectionStatus = map['connectionStatus'] == null
          ? null
          : AirConnectionStatus.fromMap(
              Map<Object?, Object?>.from(map['connectionStatus']! as Map),
            ),
      connectionConfiguration = map['connectionConfiguration'] == null
          ? null
          : AirConnectionConfiguration.fromMap(
              Map<Object?, Object?>.from(
                map['connectionConfiguration']! as Map,
              ),
            ),
      services = List.unmodifiable(
        (map['services']! as List).map(
          (value) =>
              AirGattService.fromMap(Map<Object?, Object?>.from(value as Map)),
        ),
      ),
      readErrors = Map.unmodifiable({
        for (final entry in map.entries)
          if (entry.key.endsWith('Error')) entry.key: entry.value.toString(),
      });

  final String name;
  final int? batteryPercent;
  final String? model;
  final String? firmware;
  final String? hardware;
  final String? manufacturer;
  final AirConnectionStatus? connectionStatus;
  final AirConnectionConfiguration? connectionConfiguration;
  final List<AirGattService> services;
  final Map<String, String> readErrors;
}

class AirConnectionStatus {
  AirConnectionStatus.fromMap(Map<Object?, Object?> map)
    : bonded = map['bonded']! as bool,
      encrypted = map['encrypted']! as bool,
      dataLengthExtension = map['dataLengthExtension']! as bool,
      maxTxPayload = map['maxTxPayload']! as int,
      maxRxPayload = map['maxRxPayload']! as int;

  final bool bonded;
  final bool encrypted;
  final bool dataLengthExtension;
  final int maxTxPayload;
  final int maxRxPayload;
}

class AirConnectionConfiguration {
  AirConnectionConfiguration.fromMap(Map<Object?, Object?> map)
    : intervalMillis = (map['intervalMillis']! as num).toDouble(),
      peripheralLatency = map['peripheralLatency']! as int,
      supervisionTimeoutMillis = map['supervisionTimeoutMillis']! as int,
      mtu = map['mtu']! as int,
      mode = map['mode']! as String;

  final double intervalMillis;
  final int peripheralLatency;
  final int supervisionTimeoutMillis;
  final int mtu;
  final String mode;
}

class AirGattService {
  AirGattService.fromMap(Map<Object?, Object?> map)
    : uuid = map['uuid']! as String,
      characteristics = List.unmodifiable(
        (map['characteristics']! as List).map(
          (value) => AirGattCharacteristic.fromMap(
            Map<Object?, Object?>.from(value as Map),
          ),
        ),
      );

  final String uuid;
  final List<AirGattCharacteristic> characteristics;
}

class AirGattCharacteristic {
  AirGattCharacteristic.fromMap(Map<Object?, Object?> map)
    : uuid = map['uuid']! as String,
      properties = map['properties']! as int;

  final String uuid;
  final int properties;
  bool get canRead => properties & 0x02 != 0;
  bool get canNotify => properties & 0x10 != 0;
  bool get canIndicate => properties & 0x20 != 0;
}

/// Persistent haptic strength supported by the tested Air firmware.
enum HapticIntensity {
  low(2),
  high(3);

  const HapticIntensity(this.wireValue);

  final int wireValue;

  static HapticIntensity fromWireValue(int value) => switch (value) {
    2 => low,
    3 => high,
    _ => throw FitbitAirException(
      'UNSUPPORTED_INTENSITY',
      'Air returned unsupported haptic intensity $value.',
    ),
  };
}

/// Result of a persistent haptic change or a one-pulse toggle.
class HapticChangeResult {
  const HapticChangeResult({
    required this.previousIntensity,
    required this.currentIntensity,
    required this.changed,
    required this.verified,
  });

  final HapticIntensity previousIntensity;
  final HapticIntensity currentIntensity;
  final bool changed;
  final bool verified;

  factory HapticChangeResult.fromMap(Map<String, Object?> map) =>
      HapticChangeResult(
        previousIntensity: HapticIntensity.fromWireValue(
          map['previousIntensity']! as int,
        ),
        currentIntensity: HapticIntensity.fromWireValue(
          map['currentIntensity']! as int,
        ),
        changed: map['changed']! as bool,
        verified: map['verified']! as bool,
      );
}

/// Result of [FitbitAir.tap]: one verified pulse.
class TapResult {
  const TapResult({
    required this.intensity,
    required this.previousIntensity,
    required this.changed,
    required this.verified,
  });

  /// The strength the pulse used, which is now active on the Air.
  final HapticIntensity intensity;

  /// The strength before the tap.
  final HapticIntensity previousIntensity;

  /// True when a different strength was requested and is now kept.
  final bool changed;
  final bool verified;

  factory TapResult.fromMap(Map<String, Object?> map) {
    final intensity = HapticIntensity.fromWireValue(map['intensity']! as int);
    return TapResult(
      intensity: intensity,
      previousIntensity: map['previousIntensity'] == null
          ? intensity
          : HapticIntensity.fromWireValue(map['previousIntensity']! as int),
      changed: map['changed'] as bool? ?? false,
      verified: map['verified']! as bool,
    );
  }
}

/// A change in the Air's Bluetooth link state.
class AirConnectionEvent {
  const AirConnectionEvent({required this.connected, required this.receivedAt});

  final bool connected;

  /// When the phone observed the change, not when the link actually changed.
  final DateTime receivedAt;

  factory AirConnectionEvent.fromMap(Map<String, Object?> map) =>
      AirConnectionEvent(
        connected: map['connected']! as bool,
        receivedAt: DateTime.fromMillisecondsSinceEpoch(
          map['receivedAtMillis']! as int,
        ),
      );
}

/// Battery state reported by the Air.
class AirBattery {
  const AirBattery({
    required this.percent,
    this.onCharger,
    this.voltageMillivolts,
  });

  /// Battery level, 0–100; matches the percentage shown in Google Health.
  final int percent;

  /// Whether the Air is on its charger, when the Air reports it.
  final bool? onCharger;

  /// Battery voltage in millivolts, when reported.
  final int? voltageMillivolts;

  factory AirBattery.fromMap(Map<String, Object?> map) => AirBattery(
    percent: map['percent']! as int,
    onCharger: map['onCharger'] as bool?,
    voltageMillivolts: map['voltageMillivolts'] as int?,
  );
}

/// Result of a direct haptic test.
/// The outcome of a [FitbitAir.ring].
class AirRingResult {
  const AirRingResult({required this.pulses, required this.restored});

  /// Pulses the Air accepted. Dropped pulses are skipped, not retried, so this
  /// can be lower than the duration and gap suggest.
  final int pulses;

  /// Whether a follow-up read confirmed the wearer's own strength is back.
  final bool restored;

  factory AirRingResult.fromMap(Map<String, Object?> map) => AirRingResult(
    pulses: map['pulses']! as int,
    restored: map['restored']! as bool,
  );
}

class VibrationResult {
  const VibrationResult({
    required this.originalIntensity,
    required this.restored,
    required this.verified,
  });

  /// The prior intensity: 2 (low) or 3 (high), as reported by the Air.
  final int originalIntensity;

  /// Whether the plugin received an accepted response restoring that setting.
  final bool restored;

  /// Whether a follow-up read confirmed that the original setting was restored.
  final bool verified;

  factory VibrationResult.fromMap(Map<String, Object?> map) => VibrationResult(
    originalIntensity: map['originalIntensity']! as int,
    restored: map['restored']! as bool,
    verified: map['verified']! as bool,
  );
}

/// What a [FitbitAirException] means for the user.
enum AirFailureKind {
  /// Worth retrying later: the Air was busy, out of range, or Google Health
  /// was using the shared channel.
  temporary,

  /// The user needs to fix something: Bluetooth, a permission or pairing.
  setup,

  /// The Air or phone answered in a way this package version does not
  /// expect, for example after a firmware update or on an unsupported
  /// processor. A single occurrence can still be a glitch; when it repeats,
  /// suggest checking for an app update.
  compatibility,

  /// Not classified.
  unknown;

  /// Classifies a native error [code].
  static AirFailureKind of(String code) => switch (code) {
    'BUSY' ||
    'TIMEOUT' ||
    'CANCELLED' ||
    'HANDSHAKE_TIMEOUT' ||
    'GATT_CONNECT' ||
    'GATT_DISCONNECTED' ||
    'CHANNEL_CONNECT' ||
    'CHANNEL_DISCONNECTED' ||
    'CHANNEL_TIMEOUT' ||
    'HEART_RATE_TIMEOUT' ||
    'SCAN_FAILED' ||
    'RSSI' => temporary,
    'AIR_NOT_FOUND' ||
    'BLUETOOTH_OFF' ||
    'BLUETOOTH_PERMISSION' ||
    'HEART_RATE_UNAVAILABLE' => setup,
    'NATIVE_UNAVAILABLE' ||
    'GOLDENGATE' ||
    'GOLDENGATE_TX' ||
    'DTLS' ||
    'TRACKER_CHANNEL' ||
    'CHANNEL_UNAVAILABLE' ||
    'HAPTIC_READ_FAILED' ||
    'HAPTIC_WRITE_FAILED' ||
    'HAPTIC_TAP_FAILED' ||
    'HAPTIC_PULSE_FAILED' ||
    'HAPTIC_FAILED' ||
    'BATTERY_READ_FAILED' ||
    'HEART_RATE_FORMAT' => compatibility,
    _ => unknown,
  };
}

/// A device or transport error returned by the native Android implementation.
class FitbitAirException implements Exception {
  const FitbitAirException(this.code, this.message);

  final String code;
  final String message;

  /// What this error means for the user; see [AirFailureKind].
  AirFailureKind get kind => AirFailureKind.of(code);

  @override
  String toString() => 'FitbitAirException($code): $message';
}
