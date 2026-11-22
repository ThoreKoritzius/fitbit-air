import 'dart:async';
import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'demo_controller.dart';
import 'relay_settings.dart';
import 'proximity_gauge.dart';

class FinderPage extends StatefulWidget {
  const FinderPage({super.key, required this.controller});
  final DemoController controller;
  @override
  State<FinderPage> createState() => _FinderPageState();
}

class _FinderPageState extends State<FinderPage> with WidgetsBindingObserver {
  StreamSubscription<AirFinderReading>? _subscription;
  AirFinderReading? _reading;
  Timer? _clock;
  String? _error;
  bool _buzzing = false;
  int _samples = 0;
  int? _minimum, _maximum;
  final _history = <double>[];
  final _report = <String>[];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _start();
    _clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  void _start() {
    if (_subscription != null || !mounted) return;
    _subscription = FitbitAir.watchProximity().listen(
      (reading) {
        if (!mounted) return;
        setState(() {
          _reading = reading;
          _error = null;
          if (reading.hasSignal) {
            _samples++;
            final rssi = reading.rssi!;
            _minimum = _minimum == null || rssi < _minimum! ? rssi : _minimum;
            _maximum = _maximum == null || rssi > _maximum! ? rssi : _maximum;
            _history.add(reading.smoothedRssi!);
            if (_history.length > 20) _history.removeAt(0);
          } else {
            _history.clear();
          }
          _report.add(
            '${reading.receivedAt.toIso8601String()},${reading.rssi ?? ''},${reading.smoothedRssi?.toStringAsFixed(1) ?? ''}',
          );
          if (_report.length > 300) _report.removeAt(0);
        });
      },
      onError: (Object error) {
        if (mounted) {
          setState(() {
            _error = '$error';
            _reading = null;
          });
        }
      },
      onDone: () {
        _subscription = null;
      },
    );
  }

  Future<void> _stop() async {
    final subscription = _subscription;
    _subscription = null;
    await subscription?.cancel();
    if (mounted) {
      setState(() {
        _reading = null;
        _history.clear();
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _start();
    } else if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      unawaited(_stop());
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _clock?.cancel();
    unawaited(_subscription?.cancel());
    super.dispose();
  }

  Future<void> _buzz() async {
    setState(() {
      _buzzing = true;
      _error = null;
    });
    try {
      await widget.controller.previewPreset(VibrationPreset.doublePulse);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Vibration command verified. Listen for your Air.'),
          ),
        );
      }
    } catch (error) {
      if (mounted) setState(() => _error = '$error');
    } finally {
      if (mounted) setState(() => _buzzing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final reading = _reading;
    final fresh =
        reading != null &&
        reading.hasSignal &&
        DateTime.now().difference(reading.receivedAt) <
            const Duration(seconds: 5);
    final signal = fresh ? reading.smoothedRssi! : null;
    final strength = fresh ? reading.strength! : null;
    final label = signal == null
        ? 'Searching for your Air…'
        : signal >= -60
        ? 'Hot · Strong signal'
        : signal >= -80
        ? 'Warm · Keep looking'
        : 'Cold · Weak signal';
    final trend = _history.length < 6
        ? 'Walk slowly and watch the signal.'
        : _history.last - _history[_history.length - 6] > 3
        ? 'Signal getting stronger'
        : _history.last - _history[_history.length - 6] < -3
        ? 'Signal getting weaker'
        : 'Signal fairly steady';
    return Scaffold(
      appBar: AppBar(title: const Text('Find my Air')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Row(
            children: [
              Icon(
                Icons.watch_outlined,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Your Fitbit Air',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  fresh ? 'Live signal' : 'Searching',
                  style: Theme.of(context).textTheme.labelSmall,
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          Card(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 24, 20, 20),
              child: Column(
                children: [
                  Semantics(
                    liveRegion: true,
                    child: Text(
                      label,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 350),
                    child: ProximityGauge(
                      strength: strength,
                      rssi: fresh ? reading.rssi : null,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        'Cold · weaker',
                        style: Theme.of(context).textTheme.labelSmall,
                      ),
                      Text(
                        'Hot · stronger',
                        style: Theme.of(context).textTheme.labelSmall,
                      ),
                    ],
                  ),
                  const SizedBox(height: 20),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        fresh && _history.length >= 6
                            ? _history.last - _history[_history.length - 6] > 3
                                  ? Icons.trending_up
                                  : _history.last -
                                            _history[_history.length - 6] <
                                        -3
                                  ? Icons.trending_down
                                  : Icons.trending_flat
                            : Icons.explore_outlined,
                        size: 20,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          fresh ? trend : 'No signal yet. Keep exploring.',
                          textAlign: TextAlign.center,
                        ),
                      ),
                    ],
                  ),
                  if (fresh && _history.length >= 2) ...[
                    const SizedBox(height: 12),
                    SignalHistory(values: _history),
                    Text(
                      'Recent signal',
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 20),
          Text(
            fresh
                ? 'Move slowly. Follow the stronger signal.'
                : 'Try where you last had it. Your Air needs battery power and must be within Bluetooth range.',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          const SizedBox(height: 20),
          FilledButton.icon(
            key: const ValueKey('finder-vibrate'),
            onPressed: fresh && !_buzzing ? _buzz : null,
            icon: const Icon(Icons.vibration),
            label: Text(_buzzing ? 'Sending vibration…' : 'Vibrate my Air'),
          ),
          const SizedBox(height: 10),
          OutlinedButton.icon(
            onPressed: () => Navigator.pop(context),
            icon: const Icon(Icons.check),
            label: const Text('I found it'),
          ),
          if (_error != null) ...[
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
            TextButton(
              onPressed: () async {
                await _stop();
                _start();
              },
              child: const Text('Try again'),
            ),
          ],
          const SizedBox(height: 12),
          TextButton.icon(
            onPressed: () => showModalBottomSheet<void>(
              context: context,
              showDragHandle: true,
              builder: (_) => const SafeArea(
                child: SingleChildScrollView(
                  child: Padding(
                    padding: EdgeInsets.fromLTRB(24, 0, 24, 24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Follow the signal',
                          style: TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        SizedBox(height: 20),
                        Text(
                          '1. Walk slowly and keep your phone facing the same way.',
                        ),
                        SizedBox(height: 12),
                        Text(
                          '2. Pause after each move. If the signal weakens, return to where it was stronger and try another direction.',
                        ),
                        SizedBox(height: 12),
                        Text(
                          '3. When the signal is strong, vibrate your Air and listen nearby.',
                        ),
                        SizedBox(height: 20),
                        Text(
                          'Walls, furniture and your body affect Bluetooth. The gauge shows signal strength, not a direction or exact distance.',
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            icon: const Icon(Icons.help_outline, size: 18),
            label: const Text('How to find your Air'),
          ),
          ExpansionTile(
            title: const Text('Signal diagnostics'),
            children: [
              Text(
                'Samples: $_samples · range: ${_minimum ?? '—'} to ${_maximum ?? '—'} dBm\n'
                'Raw: ${fresh ? reading.rssi : '—'} · smoothed: ${signal?.toStringAsFixed(1) ?? '—'} dBm\n'
                'Updates about once a second. Readings expire after 5 seconds.',
              ),
              TextButton.icon(
                onPressed: () async {
                  await Clipboard.setData(
                    ClipboardData(
                      text: 'time,rawRssi,smoothedRssi\n${_report.join('\n')}',
                    ),
                  );
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('Signal report copied')),
                    );
                  }
                },
                icon: const Icon(Icons.copy),
                label: const Text('Copy signal report'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
