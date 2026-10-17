import 'package:fitbit_air/fitbit_air_diagnostics.dart';
import 'package:flutter/material.dart';

/// Historical research observation; never an authoritative current sensor state.
class WristPresenceCard extends StatelessWidget {
  const WristPresenceCard({super.key, this.observation});
  final AirWristPresenceEvent? observation;

  @override
  Widget build(BuildContext context) {
    final value = observation;
    String detail = 'Unknown · Run a manual trial in Developer tools.';
    if (value != null) {
      final received = value.receivedAt.toLocal();
      final elapsed = DateTime.now().difference(received);
      final age = elapsed.inMinutes < 1
          ? 'just now'
          : elapsed.inHours < 1
          ? '${elapsed.inMinutes} min ago'
          : elapsed.inDays < 1
          ? '${elapsed.inHours} hr ago'
          : '${elapsed.inDays} days ago';
      final date = MaterialLocalizations.of(context).formatShortDate(received);
      final time = TimeOfDay.fromDateTime(received).format(context);
      detail =
          'Last observed ${value.state == AirWristPresence.onWrist ? "on wrist" : "off wrist"} · $age\n$date, $time\nNot live · Never used to filter notifications.';
    }
    return Card(
      child: ListTile(
        key: const ValueKey('wrist-presence'),
        leading: const Icon(Icons.watch_outlined),
        title: const Text('Wrist presence'),
        subtitle: Text(detail),
      ),
    );
  }
}
