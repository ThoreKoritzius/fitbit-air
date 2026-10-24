import 'dart:async';
import 'dart:convert';

import 'package:fitbit_air/fitbit_air.dart';
import 'package:fitbit_air/fitbit_air_diagnostics.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'relay_settings.dart';

enum RelayTransition { starting, stopping }

class DemoAction {
  const DemoAction(
    this.run, {
    this.streaming = false,
    this.parameters,
    this.exclusive = true,
  });
  final FutureOr<void> Function() run;
  final bool streaming;
  final bool exclusive;
  final Map<String, Object?> Function()? parameters;
}

/// Shared by UI and tests. Conversation labels stay local and out of reports.
class DemoController extends ChangeNotifier {
  static const channel = MethodChannel('fitbit_air_example/calendar');
  final actions = <String, DemoAction>{};
  Future<void> Function()? cancelCurrent;
  Map<String, Object?> transportDiagnostics = {};

  /// Warm-link recoveries in the relay's process, from [transportDiagnostics].
  AirLinkHealth get linkHealth => AirLinkHealth.fromMap(transportDiagnostics);
  RelaySettings settings = const RelaySettings();
  AirWristPresenceEvent? lastWristObservation;
  AirBattery? battery;
  DateTime? batteryReadAt;
  bool batteryReading = false;
  bool signalReading = false;
  int? signalRssi;
  DateTime? signalReadAt;
  DateTime? _lastSignalAttempt;
  String? signalError;
  bool _deviceVisible = false;
  bool get canReadSignal =>
      _deviceReadInFlight == null &&
      initialized &&
      !busy &&
      !previewing &&
      !changingRelay &&
      !batteryReading &&
      !signalReading &&
      !relayStopping &&
      (!relayRunning || relayReady) &&
      relayTransition == null &&
      bluetoothPermission &&
      bluetoothEnabled != false &&
      (_lastSignalAttempt == null ||
          DateTime.now().difference(_lastSignalAttempt!) >=
              const Duration(seconds: 15));

  void setDeviceVisible(bool visible) {
    _deviceVisible = visible;
    if (visible && _visible) unawaited(refreshSignalStrength());
  }

  Future<void> refreshSignalStrength({bool force = false}) {
    if (!canReadSignal ||
        (!force &&
            _lastSignalAttempt != null &&
            DateTime.now().difference(_lastSignalAttempt!) <
                const Duration(minutes: 2))) {
      return Future<void>.value();
    }
    final task = _readSignalStrength();
    _deviceReadInFlight = task;
    return task.whenComplete(() => _deviceReadInFlight = null);
  }

  Future<void> _readSignalStrength() async {
    signalReading = true;
    _lastSignalAttempt = DateTime.now();
    signalError = null;
    _changed();
    try {
      if (relayRunning) {
        final result = await channel
            .invokeMapMethod<String, Object?>('readRelaySignal')
            .timeout(const Duration(seconds: 30));
        signalRssi = result?['rssi'] as int?;
      } else {
        signalRssi = await FitbitAir.readSignalStrength();
      }
      if (signalRssi == null) throw StateError('No signal reading');
      signalReadAt = DateTime.now();
    } catch (_) {
      signalError = 'Could not check signal. Try again shortly.';
    } finally {
      signalReading = false;
      _changed();
    }
  }

  Future<void>? _deviceReadInFlight;
  Future<void>? _previewInFlight;
  bool get canQueueRelayStart =>
      (!busy || _previewInFlight != null) &&
      ((!batteryReading && !signalReading) || _deviceReadInFlight != null);
  String? get startWaitMessage =>
      _requestedRelayTransition != RelayTransition.starting
      ? null
      : _previewInFlight != null
      ? 'Finishing your test first…'
      : _deviceReadInFlight != null
      ? batteryReading
            ? 'Finishing battery check…'
            : 'Finishing device check…'
      : null;
  String? batteryError;
  String? batteryErrorCode;
  bool? airConnected;
  StreamSubscription<AirConnectionEvent>? _connection;
  bool _visible = false;
  Timer? _automaticBatteryTimer;
  bool get batteryPending => batteryReading || _automaticBatteryTimer != null;
  DateTime? _lastBatteryAttempt;
  static const batteryFreshness = Duration(hours: 1);
  static const batteryResumeFreshness = Duration(minutes: 15);
  DateTime? _batteryResumedAt;

  bool get batteryStale =>
      battery != null &&
      (batteryReadAt == null ||
          DateTime.now().difference(batteryReadAt!) >= batteryFreshness);

  bool get _batteryRefreshDue =>
      batteryReadAt == null ||
      DateTime.now().difference(batteryReadAt!) >= batteryFreshness ||
      (_batteryResumedAt != null &&
          _batteryResumedAt!.difference(batteryReadAt!) >=
              batteryResumeFreshness);

  bool get _shouldUpdateBattery =>
      !_disposed &&
      _visible &&
      setupCompleted &&
      canReadBattery &&
      _batteryRefreshDue &&
      (_lastBatteryAttempt == null ||
          DateTime.now().difference(_lastBatteryAttempt!) >=
              const Duration(minutes: 15));

  void _scheduleBatteryUpdate() {
    if (!_shouldUpdateBattery) {
      _automaticBatteryTimer?.cancel();
      _automaticBatteryTimer = null;
      _scheduleFirmwareCheck();
      return;
    }
    // Let the screen settle and give a user's Start tap priority.
    _automaticBatteryTimer ??= Timer(const Duration(seconds: 3), () {
      _automaticBatteryTimer = null;
      if (!_shouldUpdateBattery) return;
      unawaited(refreshBattery());
    });
  }

  bool get canReadBattery =>
      _deviceIdle &&
      (batteryReadAt == null ||
          DateTime.now().difference(batteryReadAt!) >=
              const Duration(minutes: 1));

  bool get _deviceIdle =>
      _deviceReadInFlight == null &&
      initialized &&
      !busy &&
      !previewing &&
      !changingRelay &&
      (!relayRunning || relayReady) &&
      !relayStopping &&
      relayTransition == null &&
      !batteryReading &&
      !signalReading &&
      bluetoothPermission &&
      bluetoothEnabled != false;

  Future<void> refreshBattery() {
    if (!canReadBattery) return Future<void>.value();
    final task = _readBattery();
    _deviceReadInFlight = task;
    return task.whenComplete(() => _deviceReadInFlight = null);
  }

  /// Air firmware from the last device read; checked at most daily, with a
  /// battery read when one is due, otherwise on its own while visible.
  String? firmware;
  DateTime? firmwareReadAt;
  String? dismissedFirmware;

  /// The firmware on which a battery read or vibration last succeeded here.
  /// That proves the secure session and settings still work on it.
  String? workingFirmware;
  DateTime? _lastFirmwareAttempt;
  Timer? _firmwareTimer;
  static const firmwareFreshness = Duration(days: 1);

  bool get _firmwareDue =>
      firmwareReadAt == null ||
      DateTime.now().difference(firmwareReadAt!) >= firmwareFreshness;

  bool get _shouldCheckFirmware =>
      !_disposed &&
      _visible &&
      setupCompleted &&
      _deviceIdle &&
      _firmwareDue &&
      (_lastFirmwareAttempt == null ||
          DateTime.now().difference(_lastFirmwareAttempt!) >=
              const Duration(minutes: 15));

  void _scheduleFirmwareCheck() {
    if (!_shouldCheckFirmware) {
      _firmwareTimer?.cancel();
      _firmwareTimer = null;
      return;
    }
    _firmwareTimer ??= Timer(const Duration(seconds: 5), () {
      _firmwareTimer = null;
      if (!_shouldCheckFirmware) return;
      final task = _readFirmwareIfDue().whenComplete(_changed);
      _deviceReadInFlight = task;
      unawaited(task.whenComplete(() => _deviceReadInFlight = null));
    });
  }

  /// Null until the firmware is known.
  bool? get firmwareTested =>
      firmware == null ? null : FitbitAir.testedFirmware.contains(firmware);

  bool get firmwareWorks => firmware != null && workingFirmware == firmware;

  /// An untested firmware that has not worked here yet and was not dismissed.
  bool get showFirmwareNotice =>
      firmwareTested == false &&
      !firmwareWorks &&
      dismissedFirmware != firmware;

  Future<void> _markFirmwareWorking() async {
    if (firmware == null || firmwareWorks) return;
    workingFirmware = firmware;
    _changed();
    try {
      await channel.invokeMethod<void>('saveDevice', {
        'workingFirmware': firmware,
      });
    } on PlatformException {
      // Re-confirmed by the next successful command.
    }
  }

  Future<void> dismissFirmwareNotice() async {
    dismissedFirmware = firmware;
    _changed();
    try {
      await channel.invokeMethod<void>('saveDevice', {
        'dismissedFirmware': firmware,
      });
    } on PlatformException {
      // The notice returns next launch; nothing else depends on it.
    }
  }

  Future<void> _readFirmwareIfDue() async {
    if (!_firmwareDue) return;
    _lastFirmwareAttempt = DateTime.now();
    try {
      final Object? value;
      if (relayRunning) {
        final info = await channel
            .invokeMapMethod<String, Object?>('readRelayDeviceInfo')
            .timeout(const Duration(seconds: 30));
        value = info?['firmware'];
      } else {
        value = (await FitbitAir.readDeviceInfo()).firmware;
      }
      if (value is! String || value.isEmpty || value.length > 64) return;
      firmware = value;
      firmwareReadAt = DateTime.now();
      await channel.invokeMethod<void>('saveDevice', {
        'firmware': value,
        'readAt': firmwareReadAt!.millisecondsSinceEpoch,
      });
    } catch (_) {
      // Retried with the next battery read; the firmware is informational.
    }
  }

  /// Consecutive unconfirmed alerts, newest first, ignoring skipped ones.
  List<Map<String, Object?>> get _recentFailures {
    final failures = <Map<String, Object?>>[];
    for (final entry in history) {
      final stage = entry['stage'];
      if (stage == 'verified') break;
      if (stage == 'failed' || stage == 'interrupted') failures.add(entry);
    }
    return failures;
  }

  /// Set when the last alerts did not reach the Air. One failure is normal
  /// (for example while Google Health syncs); two in a row are worth showing.
  AirFailureKind? get deliveryProblem {
    final failures = _recentFailures;
    if (failures.length < 2) return null;
    final kinds = failures
        .map((entry) => AirFailureKind.of(entry['errorCode'] as String? ?? ''))
        .toList();
    // Only a repeated compatibility failure suggests the Air has changed.
    if (kinds.take(3).length == 3 &&
        kinds.take(3).every((kind) => kind == AirFailureKind.compatibility)) {
      return AirFailureKind.compatibility;
    }
    return kinds.first == AirFailureKind.setup
        ? AirFailureKind.setup
        : AirFailureKind.temporary;
  }

  String? get lastFailureCode =>
      _recentFailures.firstOrNull?['errorCode'] as String?;

  Future<void> openReleases() =>
      channel.invokeMethod<void>('openRepository', {'page': 'releases'});
  Future<void> openIssues() =>
      channel.invokeMethod<void>('openRepository', {'page': 'issues'});
  Future<void> openPrivacyPolicy() =>
      channel.invokeMethod<void>('openRepository', {'page': 'privacy'});

  Future<void> _readBattery() async {
    _lastBatteryAttempt = DateTime.now();
    batteryReading = true;
    batteryError = null;
    batteryErrorCode = null;
    _changed();
    try {
      final value = relayRunning
          ? AirBattery.fromMap(
              Map<String, Object?>.from(
                (await channel
                        .invokeMapMethod<String, Object?>('readRelayBattery')
                        .timeout(const Duration(seconds: 60))) ??
                    (throw const FitbitAirException(
                      'EMPTY_RESULT',
                      'Battery read unavailable.',
                    )),
              ),
            )
          : await FitbitAir.readBattery();
      final at = DateTime.now();
      battery = value;
      batteryReadAt = at;
      try {
        await channel.invokeMethod<void>('saveBattery', {
          'percent': value.percent,
          'onCharger': value.onCharger,
          'readAt': at.millisecondsSinceEpoch,
        });
      } catch (_) {
        batteryError =
            'Updated, but could not save this reading for next time.';
      }
      await _readFirmwareIfDue();
      await _markFirmwareWorking();
    } on PlatformException catch (error) {
      batteryErrorCode = error.code;
      if (error.code == 'BUSY') {
        _lastBatteryAttempt = DateTime.now().subtract(
          const Duration(minutes: 14, seconds: 30),
        );
        batteryError =
            'Waiting for notification delivery. Will try again shortly.';
      } else {
        batteryError = 'Could not update. Tap to try again.';
      }
    } on FitbitAirException catch (error) {
      batteryErrorCode = error.code;
      batteryError = 'Could not update. Tap to try again.';
    } catch (_) {
      batteryErrorCode = 'READ_FAILED';
      batteryError = 'Could not update. Tap to try again.';
    } finally {
      batteryReading = false;
      _changed();
    }
  }

  bool previewing = false;
  bool initialized = false;
  bool setupCompleted = false;
  int setupStep = 0;
  bool relayRunning = false;
  bool relayReady = false;
  bool relayStopping = false;
  bool changingRelay = false;
  RelayTransition? _requestedRelayTransition;

  /// Holds the button's intent until the native worker confirms its state.
  RelayTransition? get relayTransition =>
      _requestedRelayTransition ??
      (relayStopping
          ? RelayTransition.stopping
          : relayRunning && !relayReady
          ? RelayTransition.starting
          : null);

  bool access = false;
  bool listenerConnected = false;
  bool? bluetoothEnabled;
  bool bluetoothPermission = false;
  bool? notificationControlsAllowed;
  bool? paired;
  String relayOutcome = 'Relay is off.';
  String? setupError;
  String appSearch = '';
  Set<String> selectedApps = {'com.google.android.calendar'};

  /// `double` (two pulses) or `single` (one tap); both keep the wearer's
  /// strength. Applies to the relay; persisted natively.
  String pulseStyle = 'single';
  List<Map<String, Object?>> apps = [];
  List<Map<String, Object?>> history = [];
  int captureRemainingMillis = 0;
  int unsupportedConversations = 0;
  Map<String, int> captureDiagnostics = {};

  /// Chats found in WhatsApp notifications while finding chats; saved ones are in [settings].
  List<SavedChat> conversationCandidates = [];
  int secureDurationSeconds = 30;
  Timer? _poll;
  bool _disposed = false;
  Future<void>? _refreshInFlight;
  bool _loadingApps = false;
  int _sequence = 0;
  final List<Map<String, Object?>> _trials = [];
  Map<String, Object?>? _activeTrial;
  final List<Map<String, Object?>> _observations = [];

  int get selectedInstalledAppCount =>
      apps.where((app) => selectedApps.contains(app['packageName'])).length;

  String get selectedAppsSummary {
    final count = apps.isEmpty
        ? selectedApps.length
        : selectedInstalledAppCount;
    return '$count ${count == 1 ? 'app' : 'apps'} selected';
  }

  bool get busy => _activeTrial != null;
  String? get operationId => _activeTrial?['operation'] as String?;
  bool get controlsBlocked =>
      busy || relayRunning || changingRelay || batteryReading || signalReading;
  List<Map<String, Object?>> get trials => List.unmodifiable(_trials);
  List<String> get actionIds => actions.keys.toList()..sort();

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  Future<void> initialize() async {
    await refresh();
    await loadApps();
    try {
      final cache = await channel.invokeMapMethod<String, Object?>(
        'batteryCache',
      );
      final percent = cache?['percent'];
      final readAt = cache?['readAt'];
      if (percent is int &&
          percent >= 0 &&
          percent <= 100 &&
          readAt is int &&
          readAt > 0 &&
          readAt <= DateTime.now().millisecondsSinceEpoch) {
        battery = AirBattery(
          percent: percent,
          onCharger: cache?['onCharger'] as bool?,
        );
        batteryReadAt = DateTime.fromMillisecondsSinceEpoch(readAt);
      }
    } catch (_) {
      // A missing or invalid cache never blocks setup.
    }
    try {
      final device = await channel.invokeMapMethod<String, Object?>(
        'deviceCache',
      );
      final readAt = device?['readAt'];
      firmware = device?['firmware'] as String?;
      if (firmware != null && readAt is int && readAt > 0) {
        firmwareReadAt = DateTime.fromMillisecondsSinceEpoch(readAt);
      }
      dismissedFirmware = device?['dismissedFirmware'] as String?;
      workingFirmware = device?['workingFirmware'] as String?;
    } catch (_) {
      // Unknown firmware only hides the compatibility notice.
    }
    try {
      final settings = await channel.invokeMapMethod<String, Object?>(
        'labSettings',
      );
      final duration = settings?['secureDurationSeconds'];
      if (duration is int && [30, 60, 90].contains(duration)) {
        secureDurationSeconds = duration;
      }
    } on PlatformException catch (error) {
      setupError = recoveryHint(error.code);
    } on MissingPluginException {
      // The status explains that this app needs Android.
    }
    initialized = true;
    _scheduleBatteryUpdate();
    _changed();
  }

  /// Only poll while a screen is visible. The native service owns background work.
  void setVisible(bool visible) {
    _poll?.cancel();
    _poll = null;
    if (visible && !_visible) _batteryResumedAt = DateTime.now();
    _visible = visible;
    if (!visible) {
      unawaited(_connection?.cancel());
      _connection = null;
      airConnected = null;
    }
    _scheduleBatteryUpdate();
    _watchConnectionIfAllowed();
    if (visible) {
      unawaited(refresh());
      _poll = Timer.periodic(
        const Duration(seconds: 2),
        (_) => unawaited(refresh()),
      );
    }
  }

  void _watchConnectionIfAllowed() {
    if (!_visible || _disposed || !bluetoothPermission || _connection != null) {
      return;
    }
    _connection = FitbitAir.watchConnection().listen(
      (event) {
        airConnected = event.connected;
        _scheduleBatteryUpdate();
        _changed();
      },
      onError: (Object error) {
        airConnected = null;
        _changed();
      },
    );
  }

  Future<void> refresh({bool force = false}) async {
    if (_disposed) return;
    final previous = _refreshInFlight;
    if (previous != null) {
      await previous;
      if (!force || _disposed) return;
    }
    final task = _readStatus();
    _refreshInFlight = task;
    try {
      await task;
    } finally {
      if (identical(_refreshInFlight, task)) _refreshInFlight = null;
    }
  }

  Future<void> _readStatus() async {
    try {
      transportDiagnostics =
          await FitbitAirDiagnostics.readTransportDiagnostics();
    } on PlatformException {
      transportDiagnostics = {'available': false};
    } on MissingPluginException {
      transportDiagnostics = {'available': false};
    }
    try {
      final state = await channel.invokeMapMethod<String, Object?>('status');
      if (state == null || _disposed) return;
      setupCompleted = state['setupCompleted'] == true;
      setupStep = (state['setupStep'] as int? ?? 0).clamp(0, 3);
      relayRunning = state['running'] == true;
      relayReady = state['ready'] == true;
      relayStopping = state['stopping'] == true;
      access = state['access'] == true;
      listenerConnected = state['connected'] == true;
      bluetoothEnabled = state['bluetoothEnabled'] as bool?;
      bluetoothPermission = state['bluetoothPermission'] == true;
      notificationControlsAllowed =
          state['notificationControlsAllowed'] as bool?;
      relayOutcome = state['outcome'] as String? ?? 'Relay is off.';
      final selected = state['selectedApps'] as List?;
      if (selected != null) selectedApps = selected.cast<String>().toSet();
      pulseStyle = state['pulseStyle'] == 'double' ? 'double' : 'single';
      settings = state['rules'] is Map
          ? RelaySettings.fromMap(state['rules'] as Map)
          : RelaySettings(defaultPreset: VibrationPreset.parse(pulseStyle));
      final conversations = Map<String, Object?>.from(
        state['conversations'] as Map? ?? const {},
      );
      captureRemainingMillis =
          conversations['captureRemainingMillis'] as int? ?? 0;
      unsupportedConversations = conversations['unsupported'] as int? ?? 0;
      const diagnosticKeys = {
        'seen',
        'existing',
        'ongoing',
        'summary',
        'missingId',
        'unusable',
        'nameOnly',
        'identified',
        'errors',
      };
      captureDiagnostics =
          Map<String, Object?>.from(
              conversations['diagnostics'] as Map? ?? const {},
            ).map((key, value) => MapEntry(key, value is int ? value : 0))
            ..removeWhere((key, value) => !diagnosticKeys.contains(key));
      conversationCandidates = [
        for (final item in conversations['candidates'] as List? ?? const [])
          SavedChat.fromMap(item as Map),
      ];
      history = (state['history'] as List? ?? [])
          .map((entry) => Map<String, Object?>.from(entry as Map))
          .toList();
      // A vibration confirmed after the firmware was read worked on it.
      final lastAttempt = history
          .where(
            (e) => const {
              'verified',
              'failed',
              'interrupted',
            }.contains(e['stage']),
          )
          .firstOrNull;
      final at = lastAttempt?['atMillis'];
      if (lastAttempt?['stage'] == 'verified' &&
          at is int &&
          firmwareReadAt != null &&
          at >= firmwareReadAt!.millisecondsSinceEpoch) {
        unawaited(_markFirmwareWorking());
      }
    } on PlatformException catch (error) {
      setupError = recoveryHint(error.code);
    } on MissingPluginException {
      setupError = 'This demo requires an Android phone.';
    } finally {
      _watchConnectionIfAllowed();
      _scheduleBatteryUpdate();
      if (_visible && _deviceVisible) unawaited(refreshSignalStrength());
      _changed();
    }
  }

  Future<void> loadApps() async {
    if (_loadingApps || _disposed) return;
    _loadingApps = true;
    try {
      final result = await channel.invokeListMethod<Object?>('apps');
      if (!_disposed) {
        apps = (result ?? [])
            .map((app) => Map<String, Object?>.from(app! as Map))
            .toList();
      }
    } on PlatformException catch (error) {
      setupError = recoveryHint(error.code);
    } on MissingPluginException {
      // Android support is explained by refresh().
    } finally {
      _loadingApps = false;
      _changed();
    }
  }

  List<Map<String, Object?>> get filteredApps {
    final query = appSearch.trim().toLowerCase();
    final result = apps
        .where(
          (app) => '${app['label']} ${app['packageName']}'
              .toLowerCase()
              .contains(query),
        )
        .toList();
    result.sort((a, b) {
      final selected =
          (selectedApps.contains(b['packageName']) ? 1 : 0) -
          (selectedApps.contains(a['packageName']) ? 1 : 0);
      return selected != 0
          ? selected
          : (a['label'] as String).toLowerCase().compareTo(
              (b['label'] as String).toLowerCase(),
            );
    });
    return result;
  }

  void searchApps(String query) {
    appSearch = query;
    _changed();
  }

  Future<void> selectApp(String packageName, bool selected) async {
    final packages = {...selectedApps};
    selected ? packages.add(packageName) : packages.remove(packageName);
    await _relayCall('selectApps', {'packages': packages.toList()});
  }

  Future<void> setPulseStyle(String style) async {
    if (style != 'double' && style != 'single') {
      throw ArgumentError.value(style, 'style');
    }
    await _relayCall('setPulseStyle', {'style': style});
  }

  Future<void> saveAppRule(
    String packageName,
    bool enabled,
    VibrationPreset? preset,
  ) => _relayCall('saveAppRule', {
    'packageName': packageName,
    'enabled': enabled,
    'preset': preset?.id,
  });

  /// Adds a found chat, or changes a saved chat's vibration. Applies while the relay runs.
  Future<void> saveChat(String id, VibrationPreset? preset) =>
      _relayCall('saveChat', {'id': id, 'preset': preset?.id});
  Future<void> removeChat(String id) => _relayCall('removeChat', {'id': id});
  Future<void> setEveryoneElseSilent(bool silent) =>
      _relayCall('setEveryoneElseSilent', {'silent': silent});

  /// Lists chats from current WhatsApp notifications and listens for new ones for a minute.
  Future<void> findChats(bool listen) =>
      _relayCall('captureConversations', {'enabled': listen});
  Future<void> saveDefaultPreset(VibrationPreset preset) =>
      _relayCall('saveDefaultPreset', {'preset': preset.id});
  Future<void> setPause(int minutes) =>
      _relayCall('setPause', {'minutes': minutes});
  Future<void> saveQuietHours(bool enabled, int start, int end) => _relayCall(
    'saveQuietHours',
    {'enabled': enabled, 'startMinute': start, 'endMinute': end},
  );

  Future<void> saveToneRules({
    required bool enabled,
    VibrationPreset? urgent,
    VibrationPreset? negative,
    VibrationPreset? neutral,
  }) => _relayCall('saveToneRules', {
    'enabled': enabled,
    'urgent': urgent?.id,
    'negative': negative?.id,
    'neutral': neutral?.id,
  });

  Future<void> previewPreset(VibrationPreset preset) {
    if (_previewInFlight != null) {
      return Future<void>.error(StateError('Wait for the current test.'));
    }
    final task = _previewPreset(preset);
    _previewInFlight = task;
    return task.whenComplete(() => _previewInFlight = null);
  }

  Future<void> _previewPreset(VibrationPreset preset) async {
    if (preset == VibrationPreset.mute) {
      throw ArgumentError('Silent rules cannot vibrate.');
    }
    if (busy ||
        changingRelay ||
        previewing ||
        batteryReading ||
        signalReading) {
      throw StateError('Wait for the current operation.');
    }
    if (relayRunning) {
      if (!relayReady || relayStopping) {
        throw StateError('Wait until the relay is ready.');
      }
      previewing = true;
      _changed();
      try {
        await channel.invokeMethod<void>('previewPreset', {
          'preset': preset.id,
        });
      } finally {
        previewing = false;
        await refresh();
        _changed();
      }
    } else {
      final id = 'preview-${preset.id}';
      actions[id] = DemoAction(() async {
        if (preset == VibrationPreset.doublePulse) {
          final result = await FitbitAir.vibrate();
          if (!result.verified || !result.restored) {
            throw const FitbitAirException(
              'UNVERIFIED',
              'Restoration was not verified.',
            );
          }
        } else {
          final result = await FitbitAir.tap(intensity: preset.intensity);
          if (!result.verified) {
            throw const FitbitAirException(
              'UNVERIFIED',
              'Tap was not verified.',
            );
          }
        }
      }, parameters: () => {'preset': preset.id});
      await runAction(id);
    }
  }

  void recordWristObservation(AirWristPresenceEvent observation) {
    lastWristObservation = observation;
    _changed();
  }

  Future<void> _relayCall(String method, [Object? arguments]) async {
    changingRelay = true;
    setupError = null;
    _changed();
    try {
      await channel.invokeMethod<void>(method, arguments);
      await refresh(force: true);
    } on PlatformException catch (error) {
      setupError = recoveryHint(error.code);
      rethrow;
    } finally {
      changingRelay = false;
      _changed();
    }
  }

  bool get setupRequirementsMet =>
      bluetoothPermission &&
      bluetoothEnabled == true &&
      paired == true &&
      access &&
      listenerConnected &&
      selectedInstalledAppCount > 0;

  Future<void> rememberSetupStep(int value) async {
    setupStep = value.clamp(0, 3);
    try {
      await channel.invokeMethod<void>('saveSetupStep', {'step': setupStep});
    } on PlatformException {
      // Setup remains usable if its resume position cannot be saved.
    } on MissingPluginException {
      // Older example hosts restart setup at the first step.
    }
  }

  Future<void> completeSetup() async {
    if (!setupRequirementsMet) {
      throw StateError(
        'Finish the connection, notification and app steps first.',
      );
    }
    await channel.invokeMethod<void>('completeSetup');
    setupCompleted = true;
    _changed();
  }

  Future<void> checkPaired() async {
    paired = await FitbitAir.isAirPaired();
    await refresh();
    _changed();
  }

  Future<void> startRelay() async {
    if (!canQueueRelayStart ||
        changingRelay ||
        _requestedRelayTransition != null) {
      throw StateError('Wait for the current operation.');
    }
    if (relayRunning) return;
    if (selectedApps.isEmpty) throw StateError('Select at least one app.');
    _requestedRelayTransition = RelayTransition.starting;
    changingRelay = true;
    setupError = null;
    _changed();
    try {
      final waiting = [
        _previewInFlight,
        _deviceReadInFlight,
      ].whereType<Future<void>>();
      await Future.wait(
        waiting.map(
          (task) => task.then<void>(
            (_) {},
            onError: (Object error, StackTrace stack) {},
          ),
        ),
      );
      if (_disposed) return;
      await checkPaired();
      if (paired != true) {
        setupError = 'Pair the Air in Google Health, then check again.';
        return;
      }
      await channel.invokeMethod<void>('start');
      await refresh(force: true);
    } on PlatformException catch (error) {
      setupError = recoveryHint(error.code);
    } on FitbitAirException catch (error) {
      setupError = recoveryHint(error.code);
    } finally {
      _requestedRelayTransition = null;
      changingRelay = false;
      _changed();
    }
  }

  Future<void> stopRelay() async {
    if (_requestedRelayTransition == RelayTransition.stopping ||
        relayStopping) {
      return;
    }
    if (_requestedRelayTransition == RelayTransition.starting) {
      throw StateError('Wait until the start request finishes.');
    }
    _requestedRelayTransition = RelayTransition.stopping;
    _changed();
    try {
      await _relayCall('stop');
    } finally {
      _requestedRelayTransition = null;
      _changed();
    }
  }

  Future<void> stopRelayAndWait() async {
    await stopRelay();
    final deadline = DateTime.now().add(const Duration(seconds: 55));
    while (relayRunning) {
      if (DateTime.now().isAfter(deadline)) {
        throw StateError(
          'The relay is still finishing a command. Wait for it to stop.',
        );
      }
      await Future<void>.delayed(const Duration(milliseconds: 250));
      await refresh();
    }
  }

  Future<void> testRelay() {
    if (!relayRunning || !relayReady) {
      throw StateError('Wait until the relay is listening.');
    }
    return _relayCall('testPulse');
  }

  Future<void> allowNotificationControls() async {
    await channel.invokeMethod<void>('allowNotificationControls');
    await refresh(force: true);
  }

  Future<void> openAppSettings() => channel.invokeMethod<void>('appSettings');

  Future<void> openNotificationControlsSettings() =>
      channel.invokeMethod<void>('notificationControlsSettings');

  Future<void> openAccessSettings() => channel.invokeMethod<void>('settings');
  Future<void> openBluetoothSettings() =>
      channel.invokeMethod<void>('bluetoothSettings');
  Future<void> clearHistory() => _relayCall('clearHistory');

  Future<void> saveDuration(int seconds) async {
    if (busy) throw StateError('Finish the active trial first.');
    if (![30, 60, 90].contains(seconds)) throw ArgumentError.value(seconds);
    await channel.invokeMethod<void>('setLabSettings', {
      'secureDurationSeconds': seconds,
    });
    secureDurationSeconds = seconds;
    _changed();
  }

  /// Same named operations for buttons and device integration tests.
  Future<void> runAction(String id) async {
    final auxiliary = actions[id];
    if (auxiliary != null && !auxiliary.exclusive) {
      await auxiliary.run();
      return;
    }
    if (busy ||
        changingRelay ||
        previewing ||
        batteryReading ||
        signalReading) {
      throw StateError('An operation is already running.');
    }
    if (relayRunning) {
      throw StateError('Stop the relay before a device operation.');
    }
    final action = actions[id];
    if (action == null) {
      throw ArgumentError.value(id, 'id', 'Unknown operation');
    }
    final trial = <String, Object?>{
      'id': 'trial-${++_sequence}',
      'operation': id,
      'parameters': action.parameters?.call() ?? <String, Object?>{},
      'startedAt': DateTime.now().toUtc().toIso8601String(),
      'outcome': 'running',
      'observations': <Map<String, Object?>>[],
    };
    _activeTrial = trial;
    _trials.insert(0, trial);
    if (_trials.length > 20) _trials.removeLast();
    _changed();
    try {
      await action.run();
      if (!action.streaming && identical(_activeTrial, trial)) {
        finishOperation();
      }
    } catch (error) {
      failOperation(error);
      finishOperation();
      rethrow;
    }
  }

  void observe(String kind, [Map<String, Object?> metadata = const {}]) {
    const allowed = {
      'phase',
      'eventIds',
      'eventNames',
      'observationMillis',
      'receivedPosts',
      'payloadLength',
      'marker',
      'model',
      'firmware',
    };
    final event = <String, Object?>{
      'kind': kind,
      'receivedAt': DateTime.now().toUtc().toIso8601String(),
      ...Map.fromEntries(
        metadata.entries.where((entry) => allowed.contains(entry.key)),
      ),
    };
    final trial = _activeTrial;
    if (trial != null) {
      final observations = trial['observations']! as List<Map<String, Object?>>;
      observations.add(event);
      if (observations.length > 100) observations.removeAt(0);
    } else {
      _observations.add(event);
      if (_observations.length > 100) _observations.removeAt(0);
    }
    _changed();
  }

  void mark(String marker) {
    const markers = {
      'felt_vibration',
      'off_wrist',
      'on_wrist',
      'tap_attempt',
      'led_seen',
      'alarm_started',
      'alarm_dismiss_attempt',
      'alarm_stopped',
    };
    if (!markers.contains(marker)) throw ArgumentError.value(marker);
    observe('manual_marker', {'marker': marker});
  }

  void setOutcome(String outcome) {
    if (_activeTrial == null) return;
    _activeTrial!['outcome'] = outcome;
    _changed();
  }

  void failOperation(Object error) {
    if (_activeTrial == null) return;
    final code = error is FitbitAirException
        ? error.code
        : error is PlatformException
        ? error.code
        : 'DEMO_ERROR';
    if (code == 'CANCELLED') {
      _activeTrial!['outcome'] = 'cancelled';
    } else if (_activeTrial!['outcome'] != 'interrupted' &&
        _activeTrial!['outcome'] != 'cancelled') {
      _activeTrial!['outcome'] = 'failed';
    }
    _activeTrial!['errorCode'] = code;
    _changed();
  }

  void finishOperation([String? outcome]) {
    final trial = _activeTrial;
    if (trial == null) return;
    if (outcome != null) trial['outcome'] = outcome;
    if (trial['outcome'] == 'running') trial['outcome'] = 'completed';
    trial['durationMillis'] = DateTime.now()
        .toUtc()
        .difference(DateTime.parse(trial['startedAt']! as String))
        .inMilliseconds;
    trial['endedAt'] = DateTime.now().toUtc().toIso8601String();
    _activeTrial = null;
    _changed();
  }

  Map<String, Object?> report() => {
    'schemaVersion': 1,
    'demoVersion': appVersion,
    'firmware': firmware,
    'firmwareTested': firmwareTested,
    'exportedAt': DateTime.now().toUtc().toIso8601String(),
    'battery': {
      'hasReading': battery != null,
      'readAt': batteryReadAt?.toUtc().toIso8601String(),
      'refreshing': batteryReading,
      'errorCode': batteryErrorCode,
      'connectionHint': airConnected,
    },
    'relay': {
      'running': relayRunning,
      // Message tone categories stay on screen; shared reports leave them out.
      'history': [
        for (final entry in history) {...entry}..remove('tone'),
      ],
      'everyoneElseSilent': settings.everyoneElseSilent,
      'savedChatCount': settings.chats.length,
      'captureDiagnostics': captureDiagnostics,
    },
    'transport': transportDiagnostics,
    'settings': {
      'defaultPreset': settings.defaultPreset.id,
      'appRuleCount': settings.apps.length,
      'chatOverrideCount': settings.chats.where((c) => c.preset != null).length,
      'silenceReason': settings.silenceReason,
      'quietEnabled': settings.quietEnabled,
      'toneEnabled': settings.toneEnabled,
    },
    'trials': _trials,
    'manualObservations': _observations,
  };
  String exportJson() => const JsonEncoder.withIndent('  ').convert(report());

  static const appVersion = '0.2.1';

  static String recoveryHint(String code) => switch (code) {
    'TIMEOUT' =>
      'The command timed out; delivery is uncertain. Check the Air and Google Health before testing again. No automatic retry.',
    'BUSY' =>
      'Another operation owns the device. Stop that operation and try again.',
    'NOTIFICATION_ACCESS' =>
      'Enable notification access and wait for the listener to connect.',
    'BLUETOOTH_DISABLED' ||
    'BLUETOOTH_OFF' => 'Turn on Bluetooth, then try again.',
    'AIR_NOT_FOUND' ||
    'PAIRING_REQUIRED' => 'Pair the Air in Google Health, then check again.',
    'HANDSHAKE_TIMEOUT' =>
      'The Air did not accept a secure session, probably because Google Health was using it. No vibration was sent; try again shortly.',
    'CANNOT_CANCEL_WRITE' =>
      'This haptic must finish verification and restoration. Wait for completion.',
    'GATT_CONNECT' || 'GATT_DISCONNECTED' =>
      'Keep the Air near the phone and check Google Health. Delivery may be uncertain; no automatic retry.',
    'HEART_RATE_UNAVAILABLE' || 'HEART_RATE_TIMEOUT' =>
      'Enable Share heart rate in Google Health before reading or streaming.',
    'SERVICE_STOPPED' =>
      'The relay stopped before reporting a result. Delivery is uncertain; no automatic retry.',
    'PERMISSION_DENIED' || 'BLUETOOTH_PERMISSION' =>
      'Grant Nearby devices permission using Check paired Air.',
    'NATIVE_UNAVAILABLE' =>
      'This phone’s processor isn’t supported by Air Relay yet.',
    _ when AirFailureKind.of(code) == AirFailureKind.compatibility =>
      'Your Air answered in an unexpected way ($code). If this keeps happening after a firmware update, check for an Air Relay update.',
    _ =>
      'Operation failed ($code). Check permissions, Bluetooth and the paired Air. No automatic retry.',
  };

  @override
  void dispose() {
    _disposed = true;
    _automaticBatteryTimer?.cancel();
    _firmwareTimer?.cancel();
    _poll?.cancel();
    unawaited(_connection?.cancel());
    super.dispose();
  }
}
