import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter/material.dart';

void main() => runApp(const MaterialApp(home: TapProbe()));

class TapProbe extends StatefulWidget {
  const TapProbe({super.key});

  @override
  State<TapProbe> createState() => _TapProbeState();
}

class _TapProbeState extends State<TapProbe> {
  bool _busy = false;
  String _status = 'Pair and sync your Fitbit Air in Google Health first.';

  Future<void> _tap() async {
    setState(() {
      _busy = true;
      _status = 'Connecting to the Fitbit Air…';
    });
    try {
      final result = await FitbitAir.tap();
      if (mounted) {
        setState(
          () => _status = 'Tap verified at ${result.intensity.name} strength.',
        );
      }
    } on FitbitAirException catch (error) {
      if (mounted) setState(() => _status = '${error.code}: ${error.message}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Fitbit Air iOS probe')),
    body: Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            FilledButton(
              onPressed: _busy ? null : _tap,
              child: const Text('Tap Fitbit Air'),
            ),
            const SizedBox(height: 16),
            Text(_status, textAlign: TextAlign.center),
          ],
        ),
      ),
    ),
  );
}
