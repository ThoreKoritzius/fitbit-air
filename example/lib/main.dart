import 'dart:async';

import 'package:fitbit_air/fitbit_air.dart';
import 'package:fitbit_air/fitbit_air_commands.dart';
import 'package:fitbit_air/fitbit_air_diagnostics.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'demo/demo_controller.dart';
import 'demo/app_theme.dart';
import 'demo/relay_panel.dart';
import 'demo/rules_panel.dart';
import 'demo/wrist_presence_card.dart';
import 'demo/setup_page.dart';

void main() => runApp(const DemoApp());

/// Runs in a separate service-owned engine without any Flutter screen.
@pragma('vm:entry-point')
Future<void> airHeadlessTest() async {
  WidgetsFlutterBinding.ensureInitialized();
  const resultChannel = MethodChannel('fitbit_air_example/headless_result');
  try {
    final operation = await resultChannel.invokeMethod<String>('getOperation');
    await Future<void>.delayed(const Duration(seconds: 15));
    if (operation == 'readDeviceInfo') {
      final info = await FitbitAir.readDeviceInfo();
      await resultChannel.invokeMethod('complete', {
        'success': info.model != null,
        'operation': operation,
        'hasModelNumber': info.model != null,
      });
    } else {
      final dispatcher = AirHapticDispatcher();
      final command = AirHapticCommand(
        id: 'headless-${DateTime.now().microsecondsSinceEpoch}',
        expiresAt: DateTime.now().add(const Duration(seconds: 30)),
      );
      final delivery = dispatcher.submit(command);
      // Simulate the same notification being delivered twice. Only one may run.
      final duplicate = await dispatcher.submit(command);
      final result = await delivery;
      dispatcher.close();
      await resultChannel.invokeMethod('complete', {
        'success': result.status == AirHapticDispatchStatus.completed,
        'status': result.status.name,
        'errorCode': result.error?.code,
        'error': result.error?.toString(),
        'duplicateDropped':
            duplicate.status == AirHapticDispatchStatus.duplicate,
      });
    }
  } catch (error) {
    await resultChannel.invokeMethod('complete', {
      'success': false,
      'error': error.toString(),
    });
  }
}

/// Service-owned worker. Calendar contents never cross this channel.
@pragma('vm:entry-point')
Future<void> airCalendarRelay() async {
  WidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('fitbit_air_example/calendar_worker');
  final dispatcher = AirHapticDispatcher();
  channel.setMethodCallHandler((call) async {
    if (call.method == 'close') {
      dispatcher.close();
      return null;
    }
    if (call.method != 'dispatch') throw MissingPluginException();
    final args = Map<String, Object?>.from(call.arguments as Map);
    final result = await dispatcher.submit(
      AirHapticCommand(
        id: args['id']! as String,
        expiresAt: DateTime.fromMillisecondsSinceEpoch(
          args['expiresAt']! as int,
        ),
        kind: args['kind'] == 'tap' ? AirHapticKind.tap : AirHapticKind.preview,
        intensity: args['intensity'] == null
            ? null
            : HapticIntensity.fromWireValue(args['intensity'] as int),
      ),
    );
    return {'status': result.status.name, 'errorCode': result.error?.code};
  });
  await channel.invokeMethod<void>('ready');
}

class DemoApp extends StatelessWidget {
  const DemoApp({super.key, this.controller});
  final DemoController? controller;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Air Relay',
    debugShowCheckedModeBanner: false,
    theme: airRelayTheme(Brightness.light),
    darkTheme: airRelayTheme(Brightness.dark),
    home: DemoPage(controller: controller),
  );
}

class DemoPage extends StatefulWidget {
  const DemoPage({super.key, this.controller});
  final DemoController? controller;

  @override
  State<DemoPage> createState() => _DemoPageState();
}

class _DemoPageState extends State<DemoPage> with WidgetsBindingObserver {
  late final DemoController _controller;
  int _destination = 0;
  bool _consideredSetup = false;
  bool _launchingSetup = false;
  bool get _busy => _controller.busy;

  /// Inline Test vibration state on the Relay tab: null, 'sending' or 'sent'.
  String? _testFeedback;
  Timer? _testFeedbackTimer;
  bool get _deviceControlsBlocked =>
      _controller.busy || _controller.changingRelay;

  @override
  void initState() {
    super.initState();
    _controller = widget.controller ?? DemoController();
    _controller.addListener(_redraw);
    _registerActions();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_controller.initialize());
    _controller.setVisible(true);
  }

  void _redraw() {
    if (!mounted) return;
    if (_controller.initialized && !_consideredSetup) {
      _consideredSetup = true;
      if (!_controller.setupCompleted) {
        _launchingSetup = true;
        WidgetsBinding.instance.addPostFrameCallback((_) async {
          if (!mounted) return;
          await showRelaySetup(
            context,
            _controller,
            initialStep: _controller.setupStep,
          );
          if (mounted) setState(() => _launchingSetup = false);
        });
      }
    }
    setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _controller.setVisible(state == AppLifecycleState.resumed);
    if (state == AppLifecycleState.resumed) unawaited(_controller.loadApps());
  }

  void _registerActions() {
    _controller.actions.addAll({
      'openLab': DemoAction(() {
        setState(() => _destination = 2);
      }, exclusive: false),
      'previewDefault': DemoAction(
        () => _controller.previewPreset(_controller.settings.defaultPreset),
        exclusive: false,
      ),
      'pause15': DemoAction(() => _controller.setPause(15), exclusive: false),
      'pause60': DemoAction(() => _controller.setPause(60), exclusive: false),
      'pauseUntilResumed': DemoAction(
        () => _controller.setPause(-1),
        exclusive: false,
      ),
      'resumeRelay': DemoAction(
        () => _controller.setPause(0),
        exclusive: false,
      ),
      'checkPaired': DemoAction(_check),
      'preview': DemoAction(_vibrate),
      'pulse': DemoAction(_pulse),
      'tap': DemoAction(_tap),
      'setLow': DemoAction(() => _setIntensity(HapticIntensity.low)),
      'setHigh': DemoAction(() => _setIntensity(HapticIntensity.high)),
      'readDeviceInfo': DemoAction(_readDeviceInfo),
      'scanHeartRate': DemoAction(_scanHeartRate),
      'watchHeartRate': DemoAction(_watchHeartRate, streaming: true),
      'watchDeviceEvents': DemoAction(_watchDeviceEvents, streaming: true),
      'watchSecureEvents': DemoAction(
        _watchSecureEvents,
        streaming: true,
        parameters: () => {
          'durationSeconds': _controller.secureDurationSeconds,
          'mode': 'secure_events',
        },
      ),
      'wristPresenceTrial': DemoAction(
        () => _watchSecureEvents(wristPresence: true),
        streaming: true,
        parameters: () => {'durationSeconds': 30, 'mode': 'wrist_presence'},
      ),
      'alarmTrial': DemoAction(
        () => _watchSecureEvents(alarm: true),
        streaming: true,
        parameters: () => {'durationSeconds': 90, 'mode': 'alarm_dismissal'},
      ),
      'probeBattery': DemoAction(_probeBattery),
      'probeResources': DemoAction(_probeResources),
      'headlessHaptic': DemoAction(
        _headlessTest,
        parameters: () => {'delaySeconds': 15},
      ),
      'headlessRead': DemoAction(
        () => _headlessTest(readOnly: true),
        parameters: () => {'delaySeconds': 15},
      ),
    });
    _controller.cancelCurrent = _cancelOperation;
  }

  /// Relay-tab test: progress stays in the button; failures use a snackbar.
  Future<void> _testVibration() async {
    _testFeedbackTimer?.cancel();
    setState(() => _testFeedback = 'sending');
    bool sent;
    sent = await _ui(
      () => _controller.previewPreset(_controller.settings.defaultPreset),
      retry: _testVibration,
    );
    if (!mounted) return;
    setState(() => _testFeedback = sent ? 'sent' : null);
    if (sent) {
      _testFeedbackTimer = Timer(const Duration(seconds: 2), () {
        if (mounted) setState(() => _testFeedback = null);
      });
    }
  }

  void _showError(String message, {VoidCallback? retry}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          key: const ValueKey('error-bar'),
          behavior: SnackBarBehavior.floating,
          content: Text(message),
          action: retry == null
              ? null
              : SnackBarAction(label: 'Retry', onPressed: retry),
        ),
      );
  }

  Future<void> _performAction(String id) async {
    if (_controller.relayRunning) {
      final stop = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Stop the relay for this operation?'),
          content: const Text(
            'An active haptic will finish restoration first. The relay stays off afterward; start it again when ready.',
          ),
          actions: [
            TextButton(
              key: const ValueKey('keep-relay'),
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Keep relay running'),
            ),
            FilledButton(
              key: const ValueKey('stop-relay-for-operation'),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Stop relay and continue'),
            ),
          ],
        ),
      );
      if (stop != true || !mounted) return;
      await _ui(_controller.stopRelayAndWait);
      if (_controller.relayRunning) return;
    }
    if (mounted) await _ui(() => _controller.runAction(id));
  }

  String _describeError(Object error) => error is FitbitAirException
      ? DemoController.recoveryHint(error.code)
      : error is PlatformException
      ? DemoController.recoveryHint(error.code)
      : error.toString();

  /// Runs [action], showing failures in a floating bar. Returns success.
  Future<bool> _ui(
    Future<void> Function() action, {
    VoidCallback? retry,
  }) async {
    try {
      await action();
      return true;
    } catch (error) {
      if (!mounted) return false;
      final message = error is FitbitAirException
          ? DemoController.recoveryHint(error.code)
          : error is PlatformException
          ? DemoController.recoveryHint(error.code)
          : _describeError(error);
      setState(() => _status = message);
      _showError(message, retry: retry);
      return false;
    }
  }

  Future<void> _copyReport() async {
    await Clipboard.setData(ClipboardData(text: _controller.exportJson()));
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Sanitized JSON report copied. No notification text or BLE identifiers.',
          ),
        ),
      );
    }
  }

  Future<void> _cancelOperation() async {
    try {
      if (_controller.operationId?.startsWith('headless') == true) return;
      if (_heartRateSubscription != null) {
        _controller.setOutcome('cancelled');
        await _heartRateSubscription!.cancel();
        _heartRateSubscription = null;
      } else if (_secureEventSubscription != null) {
        _controller.setOutcome('cancelled');
        _stopCaptureClock();
        await _secureEventSubscription!.cancel();
        _secureEventSubscription = null;
      } else if (_deviceEventSubscription != null) {
        _controller.setOutcome('cancelled');
        await _deviceEventSubscription!.cancel();
        _deviceEventSubscription = null;
      } else {
        await FitbitAir.cancelOperation();
        // One-shot futures finish cleanup before their controller action returns.
        return;
      }
      _controller.finishOperation('cancelled');
      if (mounted) {
        setState(
          () => _status =
              'Operation cancelled. Partial results remain in the report.',
        );
      }
    } on FitbitAirException catch (error) {
      if (mounted) {
        setState(() => _status = DemoController.recoveryHint(error.code));
      }
    }
  }

  AirDeviceInfo? _deviceInfo;
  List<AirHeartRateDevice> _heartRateDevices = [];
  StreamSubscription<AirHeartRate>? _heartRateSubscription;
  StreamSubscription<AirDeviceEvent>? _deviceEventSubscription;
  StreamSubscription<AirSecureEvent>? _secureEventSubscription;
  final List<String> _eventLog = [];
  int _notificationCount = 0;
  final _scrollController = ScrollController();
  final _deviceScrollController = ScrollController();
  final _homeScrollController = ScrollController();
  final _rulesScrollController = ScrollController();
  final _captureClock = Stopwatch();
  Timer? _captureTimer;
  int _captureSeconds = 0;
  int _captureDuration = 30;
  int _ledMarkers = 0;
  bool _captureReady = false;
  bool _wristPresenceTrial = false;
  bool _alarmTrial = false;
  String? _captureInterruption;

  void _stopCaptureClock() {
    _captureTimer?.cancel();
    _captureClock.stop();
    _captureReady = false;
  }

  void _markObservation(String observation) {
    if (!_captureReady ||
        _captureClock.elapsedMilliseconds >= _captureDuration * 1000) {
      return;
    }
    setState(() {
      _ledMarkers++;
      _controller.observe('manual_marker', {
        'marker': observation,
        'observationMillis': _captureClock.elapsedMilliseconds,
      });
      _eventLog.insert(
        0,
        '${DateTime.now()} · USER $observation '
        'at +${_captureClock.elapsedMilliseconds}ms (manual marker, not BLE detection)',
      );
      if (_eventLog.length > 20) _eventLog.removeLast();
    });
  }

  void _watchSecureEvents({bool wristPresence = false, bool alarm = false}) {
    _stopCaptureClock();
    setState(() {
      _eventLog.clear();
      _ledMarkers = 0;
      _captureSeconds = 0;
      _wristPresenceTrial = wristPresence;
      _alarmTrial = alarm;
      _captureDuration = alarm
          ? 90
          : wristPresence
          ? 30
          : _controller.secureDurationSeconds;
      _captureInterruption = null;
      _status = 'Opening secure event capture. Keep the Air on until READY.';
    });
    if (_scrollController.hasClients) {
      _scrollController.animateTo(
        0,
        duration: const Duration(milliseconds: 250),
        curve: Curves.easeOut,
      );
    }
    _secureEventSubscription =
        FitbitAirDiagnostics.watchSecureEvents(
          durationSeconds: _captureDuration,
        ).listen(
          (event) {
            if (!mounted) return;
            setState(() {
              _eventLog.insert(
                0,
                '${event.receivedAt.toLocal()} · CoAP ${event.kind} '
                '${event.path ?? ""} ${event.method ?? ""} '
                '${event.bodyLength == null ? "" : "${event.bodyLength} bytes "}'
                '${event.eventNames} ${event.eventIds}'
                '${event.observationMillis == null ? "" : " · observed ${event.observationMillis}ms"}',
              );
              if (_eventLog.length > 20) _eventLog.removeLast();
              for (final observation in event.wristPresenceEvents) {
                _controller.recordWristObservation(observation);
              }
              _controller.observe('secure_event', {
                'phase': event.kind,
                'eventIds': event.eventIds,
                'eventNames': event.eventNames,
                if (event.observationMillis != null)
                  'observationMillis': event.observationMillis,
                if (event.receivedPosts != null)
                  'receivedPosts': event.receivedPosts,
              });
              if (event.kind == 'ready') {
                _captureReady = true;
                _captureClock.reset();
                _captureClock.start();
                _captureTimer = Timer.periodic(
                  const Duration(milliseconds: 250),
                  (_) {
                    if (!mounted) return;
                    setState(() {
                      _captureSeconds = _captureClock.elapsed.inSeconds;
                      if (_captureSeconds >= _captureDuration) {
                        _stopCaptureClock();
                      }
                    });
                  },
                );
                _status =
                    'Secure event monitor READY. Follow the timed instructions below. '
                    'Event acknowledgment: ${event.eventResponseCode ?? "unknown"}. No vibration commands.';
              } else if (event.kind == 'complete') {
                _stopCaptureClock();
                _status =
                    'Secure observation complete: ${event.receivedPosts} incoming POSTs, '
                    '${event.otherRequests ?? 0} other requests; '
                    '${event.droppedPosts} over diagnostic limit; '
                    '$_ledMarkers manual markers. Gesture detection remains unconfirmed.';
              } else if (event.kind == 'interrupted') {
                _controller.setOutcome('interrupted');
                _stopCaptureClock();
                _captureInterruption =
                    'Incomplete trial: ${event.observationMillis ?? 0}ms observed, '
                    '${event.receivedPosts ?? 0} event POSTs, '
                    '${event.otherRequests ?? 0} other requests. '
                    'This is not a negative gesture result.';
              }
            });
          },
          onError: (Object error) {
            _controller.failOperation(error);
            _stopCaptureClock();
            if (mounted) {
              setState(() => _status = '$error\n${_captureInterruption ?? ""}');
            }
          },
          onDone: () {
            _stopCaptureClock();
            _secureEventSubscription = null;
            if (mounted) setState(() => _controller.finishOperation());
          },
        );
  }

  Future<void> _probeResources() =>
      _run('Checking four read-only resources in one session…', () async {
        final results = await FitbitAirDiagnostics.probeReadOnlyResources();
        return results
            .map(
              (r) =>
                  '${r.path}: ${r.responseCode ?? r.errorCode} '
                  '(${r.bodyLength ?? 0} bytes)',
            )
            .join('\n');
      });

  Future<void> _headlessTest({bool readOnly = false}) async {
    var receivedOutcome = false;
    try {
      // Obtain runtime permission with the screen present, before service use.
      if (!await FitbitAir.isAirPaired()) {
        _controller.failOperation(
          const FitbitAirException('PAIRING_REQUIRED', 'Pair an Air first.'),
        );
        if (mounted) {
          setState(() => _status = 'Pair an Air in Google Health first.');
        }
        return;
      }
      await const MethodChannel(
        'fitbit_air_example/background',
      ).invokeMethod<void>('startTest', {
        'operation': readOnly ? 'readDeviceInfo' : 'vibrate',
      });
      if (mounted) {
        setState(
          () => _status =
              'Headless test scheduled. Press Home or lock the '
              'phone now. Operation starts in 15 seconds. Avoid other SDK operations '
              'until the test notification disappears.',
        );
      }
      for (var second = 0; second < 67; second++) {
        await Future<void>.delayed(const Duration(seconds: 1));
        if (!mounted) return;
        final outcome = await const MethodChannel(
          'fitbit_air_example/background',
        ).invokeMethod<String>('getLastResult');
        if (outcome != null) {
          receivedOutcome = true;
          if (!outcome.contains('succeeded')) {
            _controller.failOperation(
              const FitbitAirException(
                'HEADLESS_FAILED',
                'Headless trial failed.',
              ),
            );
          }
          _controller.observe('headless_result', {
            'phase': outcome.contains('succeeded') ? 'verified' : 'failed',
          });
          setState(() => _status = outcome);
          break;
        }
      }
      if (!receivedOutcome) {
        _controller.failOperation(
          const FitbitAirException(
            'HEADLESS_TIMEOUT',
            'No result before deadline.',
          ),
        );
        if (mounted) {
          setState(
            () => _status =
                'Headless result unavailable; delivery is uncertain. No automatic retry.',
          );
        }
      }
    } catch (error) {
      _controller.failOperation(error);
      if (mounted) setState(() => _status = _describeError(error));
    } finally {
      if (mounted) setState(() => _controller.finishOperation());
    }
  }

  Future<void>
  _probeBattery() => _run('Probing read-only battery resource access…', () async {
    final result = await FitbitAirDiagnostics.probeBatteryAccess();
    return 'Battery access: CoAP ${result.responseCode}, ${result.bodyLength} response bytes.\n'
        '${result.granted ? "Raw level: ${result.rawBatteryLevel}; raw voltage: ${result.rawVoltage}; raw charger: ${result.rawOnCharger}. Units/scale unverified." : "Not accessible through this session."}'
        '${result.decodeError == null ? "" : "\n${result.decodeError}"}';
  });

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.removeListener(_redraw);
    _controller.setVisible(false);
    if (widget.controller == null) _controller.dispose();
    _stopCaptureClock();
    _testFeedbackTimer?.cancel();
    _scrollController.dispose();
    _deviceScrollController.dispose();
    _homeScrollController.dispose();
    _rulesScrollController.dispose();
    _heartRateSubscription?.cancel();
    _deviceEventSubscription?.cancel();
    _secureEventSubscription?.cancel();
    super.dispose();
  }

  void _watchDeviceEvents() {
    setState(() {
      _eventLog.clear();
      _notificationCount = 0;
      _status = 'Subscribing to diagnostic events…';
    });
    _deviceEventSubscription = FitbitAirDiagnostics.watchDeviceEvents().listen(
      (event) {
        if (!mounted) return;
        setState(() {
          _controller.observe('device_event', {
            'phase': event.kind,
            'payloadLength': event.payload.length,
          });
          if (event.isNotification) _notificationCount++;
          _eventLog.insert(
            0,
            '${event.receivedAt.toLocal()} · ${event.kind} '
            '${event.characteristicUuid ?? ""} '
            '${event.isNotification ? "${event.payload.length} bytes" : "status=${event.status ?? event.subscribedCount}"}',
          );
          if (_eventLog.length > 20) _eventLog.removeLast();
          if (event.kind == 'ready' || event.isNotification) {
            _status =
                'Monitoring device events. $_notificationCount notifications received. '
                'These events are not confirmed wrist taps.';
          }
        });
      },
      onError: (Object error) {
        _controller.failOperation(error);
        if (mounted) setState(() => _status = _describeError(error));
      },
      onDone: () {
        _deviceEventSubscription = null;
        if (mounted) setState(() => _controller.finishOperation());
      },
    );
  }

  void _watchHeartRate([AirHeartRateDevice? device]) {
    setState(() {
      _status = 'Connecting to ${device?.name ?? "paired Air"}…';
    });
    _heartRateSubscription =
        FitbitAir.watchHeartRate(deviceId: device?.deviceId).listen(
          (heart) {
            _controller.observe('heart_rate', {
              'phase': 'measurement_received',
            });
            if (mounted) {
              setState(
                () => _status =
                    '${heart.beatsPerMinute} bpm\n'
                    'Updated: ${heart.receivedAt.toLocal()}\n'
                    'Skin contact: ${heart.contactDetected ?? "Not reported"}',
              );
            }
          },
          onError: (Object error) {
            _controller.failOperation(error);
            if (mounted) setState(() => _status = _describeError(error));
          },
          onDone: () {
            _heartRateSubscription = null;
            if (mounted) setState(() => _controller.finishOperation());
          },
        );
  }

  String _status = 'Check for an Air paired to this phone.';

  Future<void> _run(String pending, Future<String> Function() operation) async {
    setState(() {
      _status = pending;
    });
    try {
      final status = await operation();
      if (mounted) setState(() => _status = status);
    } on FitbitAirException catch (error) {
      _controller.failOperation(error);
      if (mounted) setState(() => _status = _describeError(error));
    } finally {
      if (mounted) setState(() => _controller.finishOperation());
    }
  }

  Future<void> _check() async {
    setState(() {
      _status = 'Checking Android paired devices…';
    });
    try {
      await _controller.checkPaired();
      final paired = _controller.paired == true;
      if (!mounted) return;
      setState(() {
        _status = paired
            ? 'Fitbit Air is paired to this phone.'
            : 'No paired Fitbit Air found. Pair it in Google Health first.';
      });
    } on FitbitAirException catch (error) {
      _controller.failOperation(error);
      if (mounted) setState(() => _status = _describeError(error));
    } finally {
      if (mounted) setState(() => _controller.finishOperation());
    }
  }

  Future<void> _vibrate() async {
    await _run('Sending a restore-safe two-pulse preview…', () async {
      final result = await FitbitAir.vibrate();
      if (!result.verified || !result.restored) {
        throw const FitbitAirException(
          'UNVERIFIED',
          'Haptic restoration was not verified.',
        );
      }
      return 'Two-pulse preview complete. Prior intensity '
          '${result.originalIntensity}; restored and verified: '
          '${result.restored && result.verified}.';
    });
  }

  Future<void> _tap() async {
    await _run('Sending one haptic tap…', () async {
      final result = await FitbitAir.tap();
      if (!result.verified) {
        throw const FitbitAirException('UNVERIFIED', 'Tap was not verified.');
      }
      return 'One tap sent at ${result.intensity.name} strength; strength kept.';
    });
  }

  Future<void> _pulse() async {
    await _run('Sending one haptic pulse…', () async {
      final result = await FitbitAir.pulse();
      if (!result.verified) {
        throw const FitbitAirException('UNVERIFIED', 'Pulse was not verified.');
      }
      return 'One pulse sent: ${result.previousIntensity.name} → '
          '${result.currentIntensity.name}; verified: ${result.verified}.';
    });
  }

  Future<void> _readDeviceInfo() async {
    await _run('Reading device information…', () async {
      final info = await FitbitAir.readDeviceInfo();
      if (mounted) setState(() => _deviceInfo = info);
      _controller.observe('device_info', {
        'model': info.model,
        'firmware': info.firmware,
      });
      return '${info.name}\n'
          'Battery: ${info.batteryPercent == null ? "Not exposed" : "${info.batteryPercent}%"}\n'
          'Model: ${info.model ?? "Not exposed"}\n'
          'Firmware: ${info.firmware ?? "Not exposed"}\n'
          'Manufacturer: ${info.manufacturer ?? "Not exposed"}\n'
          'Link encrypted: ${info.connectionStatus?.encrypted ?? "Not reported"}\n'
          'MTU: ${info.connectionConfiguration?.mtu ?? "Not reported"}\n'
          'Interval: ${info.connectionConfiguration?.intervalMillis ?? "Not reported"} ms\n'
          '${info.services.length} BLE services discovered\n'
          '${info.readErrors.entries.map((e) => "${e.key}: ${e.value}").join("\n")}';
    });
  }

  Future<void> _scanHeartRate() async {
    await _run('Scanning for heart-rate sensors…', () async {
      final devices = await FitbitAir.scanHeartRateDevices();
      if (mounted) setState(() => _heartRateDevices = devices);
      for (var i = 0; i < devices.length; i++) {
        final device = devices[i];
        _controller.actions['readHeartRate$i'] = DemoAction(
          () => _readHeartRate(deviceId: device.deviceId),
        );
        _controller.actions['watchHeartRate$i'] = DemoAction(
          () => _watchHeartRate(device),
          streaming: true,
        );
      }
      return devices.isEmpty
          ? 'No heart-rate advertisements found. Open Share heart rate in Google Health, then scan again.'
          : 'Select your Air below to receive heart rate.';
    });
  }

  Future<void> _readHeartRate({required String deviceId}) async {
    await _run('Waiting for heart rate… Wear the Air on your wrist.', () async {
      final heart = await FitbitAir.readHeartRate(deviceId: deviceId);
      return '${heart.beatsPerMinute} bpm\n'
          'Skin contact: ${heart.contactDetected ?? "Not reported"}\n'
          'Received: ${heart.receivedAt.toLocal()}\n'
          'RR intervals: ${heart.rrIntervalsMillis.join(", ")} ms';
    });
  }

  Future<void> _setIntensity(HapticIntensity intensity) async {
    await _run('Setting ${intensity.name} vibration strength…', () async {
      final result = await FitbitAir.setHapticIntensity(intensity);
      return result.changed
          ? 'Intensity changed to ${intensity.name} with one pulse and verified.'
          : 'Intensity was already ${intensity.name}; no pulse was sent.';
    });
  }

  Widget _action(String id, String label, IconData icon) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: FilledButton.icon(
      key: ValueKey('action-$id'),
      onPressed: _deviceControlsBlocked ? null : () => _performAction(id),
      icon: Icon(icon),
      label: Text(label),
    ),
  );

  Widget _button({
    required VoidCallback? onPressed,
    required IconData icon,
    required String label,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: OutlinedButton.icon(
      key: ValueKey('marker-$label'),
      onPressed: onPressed,
      icon: Icon(icon),
      label: Text(label),
    ),
  );

  List<Widget> _deviceChildren() => [
    Text('Device', style: Theme.of(context).textTheme.headlineMedium),
    const SizedBox(height: 12),
    ListTile(
      key: const ValueKey('review-setup'),
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.verified_user_outlined),
      title: const Text('Permissions & setup'),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => showRelaySetup(context, _controller),
    ),
    const Divider(),
    const Text(
      'Check your band and try vibrations. Heart rate tools need sharing enabled in Google Health.',
    ),
    const SizedBox(height: 16),
    Text(_status, key: const ValueKey('device-status')),
    const SizedBox(height: 16),
    _action('checkPaired', 'Check paired Air', Icons.bluetooth_searching),
    _action('preview', 'Two-pulse preview + restore', Icons.vibration),
    _action('tap', 'Single tap (keeps strength)', Icons.touch_app),
    _action('pulse', 'Single pulse (alternates strength)', Icons.swap_vert),
    Row(
      children: [
        Expanded(child: _action('setLow', 'Set low', Icons.volume_down)),
        const SizedBox(width: 12),
        Expanded(child: _action('setHigh', 'Set high', Icons.volume_up)),
      ],
    ),
    _action(
      'readDeviceInfo',
      'Read device info + services',
      Icons.info_outline,
    ),
    _action('scanHeartRate', 'Scan heart-rate sensors', Icons.radar),
    _action(
      'watchHeartRate',
      'Live heart rate (paired Air)',
      Icons.monitor_heart,
    ),
    for (var i = 0; i < _heartRateDevices.length; i++) ...[
      _action(
        'readHeartRate$i',
        '${_heartRateDevices[i].name} · read once',
        Icons.favorite,
      ),
      _action(
        'watchHeartRate$i',
        '${_heartRateDevices[i].name} · live stream',
        Icons.monitor_heart,
      ),
    ],
    if (_deviceInfo != null)
      ExpansionTile(
        title: const Text('Bluetooth service details'),
        children: [
          for (final service in _deviceInfo!.services)
            ListTile(
              title: Text(service.uuid, style: const TextStyle(fontSize: 12)),
              subtitle: Text(
                service.characteristics
                    .map(
                      (c) =>
                          '${c.uuid}\nread=${c.canRead}, notify=${c.canNotify}',
                    )
                    .join('\n'),
                style: const TextStyle(fontSize: 11),
              ),
            ),
        ],
      ),

    const Text(
      'Single pulse changes and retains vibration strength. The two-pulse preview restores the original strength.',
    ),
  ];

  List<Widget> _labChildren() => [
    Text('Lab', style: Theme.of(context).textTheme.headlineSmall),
    const Text(
      'Research tools remain available here. Manual observations are distinct from tracker events. Opening Lab does not stop the relay.',
    ),
    const SizedBox(height: 16),
    Text(_status, key: const ValueKey('lab-status')),
    const SizedBox(height: 12),
    const Text('Secure event observation duration'),
    DropdownButton<int>(
      key: const ValueKey('secure-duration'),
      value: _controller.secureDurationSeconds,
      items: [
        for (final seconds in [30, 60, 90])
          DropdownMenuItem(value: seconds, child: Text('$seconds seconds')),
      ],
      onChanged: _busy
          ? null
          : (value) => _ui(() => _controller.saveDuration(value!)),
    ),
    const Text(
      'Wrist-presence trials use 30 seconds; alarm trials use 90 seconds. Settings are saved on this phone.',
    ),
    const SizedBox(height: 16),
    if (_captureReady) ...[
      Text(
        _alarmTrial
            ? 'Wait for the alarm, mark its start, then double-tap to dismiss. '
                  'Mark the attempt and whether vibration stopped. Then stay still until capture ends. ${_captureDuration - _captureSeconds}s left.'
            : _captureSeconds < 5
            ? 'Baseline: keep the Air still (${5 - _captureSeconds}s).'
            : _wristPresenceTrial
            ? (_captureSeconds < 15
                  ? 'Remove the Air now and mark it below. Keep it off until the next prompt.'
                  : 'Put the Air back on and mark it below. ${30 - _captureSeconds}s left.')
            : _captureSeconds < 25
            ? 'Double-tap, then mark the attempt below. Mark the LED separately if it flashes. '
                  'Space attempts at least 5 seconds apart. ${30 - _captureSeconds}s left.'
            : 'Keep the Air still for the final ${30 - _captureSeconds}s.',
        textAlign: TextAlign.center,
      ),
      if (_alarmTrial) ...[
        _button(
          onPressed: () => _markObservation('alarm vibration started'),
          icon: Icons.alarm,
          label: 'Mark: alarm started',
        ),
        _button(
          onPressed: () =>
              _markObservation('attempted alarm-dismissal double-tap'),
          icon: Icons.touch_app,
          label: 'Mark: dismissal double-tap attempted',
        ),
        _button(
          onPressed: () =>
              _markObservation('alarm vibration stopped after tap'),
          icon: Icons.alarm_off,
          label: 'Mark: alarm stopped after tap',
        ),
      ] else if (_wristPresenceTrial) ...[
        _button(
          onPressed: _captureSeconds >= 5
              ? () => _markObservation('removed Air from wrist')
              : null,
          icon: Icons.watch_off,
          label: 'Mark: Air is off my wrist',
        ),
        _button(
          onPressed: _captureSeconds >= 5
              ? () => _markObservation('put Air back on wrist')
              : null,
          icon: Icons.watch,
          label: 'Mark: Air is back on my wrist',
        ),
      ] else ...[
        _button(
          onPressed: _captureSeconds >= 5 && _captureSeconds < 25
              ? () => _markObservation('attempted double-tap')
              : null,
          icon: Icons.touch_app,
          label: 'Mark: double-tap attempted',
        ),
        _button(
          onPressed: _captureSeconds >= 5 && _captureSeconds < 25
              ? () => _markObservation('observed LED flash')
              : null,
          icon: Icons.lightbulb_outline,
          label: 'LED flashed: mark observed double-tap',
        ),
      ],
      Text('$_ledMarkers manual markers; these are not detected gestures.'),
    ],

    _action(
      'watchDeviceEvents',
      'Diagnostic device-event monitor',
      Icons.sensors,
    ),
    _action('watchSecureEvents', 'Research: secure events', Icons.hub_outlined),
    _action(
      'wristPresenceTrial',
      'Research: wrist-presence trial (30s)',
      Icons.watch_outlined,
    ),
    _action('alarmTrial', 'Research: alarm dismissal (90s)', Icons.alarm),
    _action(
      'probeBattery',
      'Research: battery access (secure session)',
      Icons.battery_unknown,
    ),
    _action(
      'probeResources',
      'Research: read-only resource access',
      Icons.science_outlined,
    ),
    _action(
      'headlessHaptic',
      'Headless test (two pulses after 15s)',
      Icons.notifications_active,
    ),
    _action(
      'headlessRead',
      'Headless read-only test (after 15s)',
      Icons.info_outline,
    ),
    if (_eventLog.isNotEmpty)
      ExpansionTile(
        initiallyExpanded: true,
        title: const Text('Diagnostic event log (local only)'),
        children: [
          for (final event in _eventLog)
            ListTile(title: Text(event, style: const TextStyle(fontSize: 11))),
        ],
      ),

    Text('Trial results', style: Theme.of(context).textTheme.titleLarge),
    if (_controller.trials.isEmpty)
      const Text(
        'No trials yet. Named actions, timestamps and bounded observations appear here.',
      ),
    for (final trial in _controller.trials)
      ListTile(
        key: ValueKey('trial-${trial['id']}'),
        title: Text('${trial['operation']} · ${trial['outcome']}'),
        subtitle: Text('${trial['startedAt']}'),
      ),
    OutlinedButton.icon(
      key: const ValueKey('lab-export'),
      onPressed: _copyReport,
      icon: const Icon(Icons.copy),
      label: const Text('Copy sanitized report'),
    ),
    OutlinedButton(
      key: const ValueKey('lab-marker-felt-vibration'),
      onPressed: () => _controller.mark('felt_vibration'),
      child: const Text('Mark: I felt vibration'),
    ),
  ];

  @override
  Widget build(BuildContext context) =>
      !_controller.initialized || _launchingSetup
      ? const Scaffold(body: Center(child: CircularProgressIndicator()))
      : PopScope(
          canPop: _destination != 2,
          onPopInvokedWithResult: (didPop, result) {
            if (!didPop && _destination == 2) setState(() => _destination = 1);
          },
          child: Scaffold(
            appBar: AppBar(
              leading: _destination == 2
                  ? BackButton(
                      onPressed: () => setState(() => _destination = 1),
                    )
                  : null,
              title: const Row(
                children: [
                  AirRelayMark(size: 34),
                  SizedBox(width: 12),
                  Flexible(
                    child: Text('Air Relay', overflow: TextOverflow.ellipsis),
                  ),
                ],
              ),
              actions: [
                IconButton(
                  key: const ValueKey('about-air-relay'),
                  tooltip: 'About Air Relay',
                  icon: const Icon(Icons.info_outline),
                  onPressed: () => showAboutDialog(
                    context: context,
                    applicationName: 'Air Relay',
                    applicationVersion:
                        _controller.report()['demoVersion'] as String?,
                    applicationIcon: const AirRelayMark(size: 48),
                    children: const [
                      Text(
                        'An independent notification companion for Fitbit Air. Not affiliated with Google or Fitbit.',
                      ),
                      SizedBox(height: 12),
                      Text(
                        'Early access. Google Health sync compatibility is still being tested.',
                      ),
                    ],
                  ),
                ),
              ],
            ),
            body: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 640),
                child: Column(
                  children: [
                    // The Relay tab shows its own test progress inline; other work
                    // started elsewhere still gets the owner banner.
                    if (_destination == 2 || (_busy && _testFeedback == null))
                      Semantics(
                        liveRegion: true,
                        child: ListTile(
                          key: const ValueKey('operation-owner'),
                          leading: Icon(
                            _busy
                                ? Icons.science
                                : _controller.relayRunning
                                ? Icons.notifications_active
                                : Icons.check_circle_outline,
                          ),
                          title: Text(
                            _busy
                                ? 'In progress: ${_controller.operationId}'
                                : _controller.relayRunning
                                ? 'Relay running'
                                : 'Ready',
                          ),
                          subtitle: _controller.relayStopping
                              ? const Text(
                                  'Finishing an active command before stopping.',
                                )
                              : null,
                          trailing: _busy
                              ? TextButton(
                                  key: const ValueKey('cancel-operation'),
                                  onPressed:
                                      _controller.operationId?.startsWith(
                                            'headless',
                                          ) ==
                                          true
                                      ? null
                                      : () => _ui(_cancelOperation),
                                  child: const Text('Cancel operation'),
                                )
                              : null,
                        ),
                      ),
                    Expanded(
                      child: _destination == 0
                          ? RelayPanel(
                              controller: _controller,
                              scrollController: _homeScrollController,
                              perform: (action) => _ui(action),
                              // Matches what the relay sends for the selected style.
                              testVibration: _testVibration,
                              testFeedback: _testFeedback,
                              exportReport: _copyReport,
                              openRules: () => setState(() => _destination = 3),
                            )
                          : _destination == 3
                          ? RulesPanel(
                              controller: _controller,
                              scrollController: _rulesScrollController,
                              perform: (action) => _ui(action),
                            )
                          : ListView(
                              key: ValueKey(
                                _destination == 1
                                    ? 'device-scroll'
                                    : 'lab-scroll',
                              ),
                              controller: _destination == 1
                                  ? _deviceScrollController
                                  : _scrollController,
                              padding: const EdgeInsets.all(20),
                              children: _destination == 1
                                  ? [
                                      ..._deviceChildren(),
                                      const SizedBox(height: 24),
                                      WristPresenceCard(
                                        observation:
                                            _controller.lastWristObservation,
                                      ),
                                      const SizedBox(height: 12),
                                      OutlinedButton.icon(
                                        key: const ValueKey('nav-lab'),
                                        onPressed: () =>
                                            setState(() => _destination = 2),
                                        icon: const Icon(
                                          Icons.science_outlined,
                                        ),
                                        label: const Text('Developer tools'),
                                      ),
                                    ]
                                  : _labChildren(),
                            ),
                    ),
                  ],
                ),
              ),
            ),
            bottomNavigationBar: NavigationBar(
              selectedIndex: _destination == 0
                  ? 0
                  : _destination == 3
                  ? 1
                  : 2,
              onDestinationSelected: (index) =>
                  setState(() => _destination = [0, 3, 1][index]),
              destinations: const [
                NavigationDestination(
                  key: ValueKey('nav-relay'),
                  icon: Icon(Icons.notifications_outlined),
                  selectedIcon: Icon(Icons.notifications_active),
                  label: 'Home',
                ),
                NavigationDestination(
                  key: ValueKey('nav-rules'),
                  icon: Icon(Icons.tune_outlined),
                  selectedIcon: Icon(Icons.tune),
                  label: 'Rules',
                ),
                NavigationDestination(
                  key: ValueKey('nav-device'),
                  icon: Icon(Icons.watch_outlined),
                  selectedIcon: Icon(Icons.watch),
                  label: 'Device',
                ),
              ],
            ),
          ),
        );
}
