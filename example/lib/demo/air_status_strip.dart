import 'package:flutter/material.dart';
import 'demo_controller.dart';

/// Cached observations, clearly separate from the relay's on/off state.
class AirStatusStrip extends StatelessWidget {
  const AirStatusStrip({super.key, required this.controller});
  final DemoController controller;

  String get age {
    final at = controller.batteryReadAt;
    if (at == null) return 'No reading yet';
    final elapsed = DateTime.now().difference(at);
    if (elapsed.inMinutes < 1) return 'Checked just now';
    if (elapsed.inHours < 1) return 'Checked ${elapsed.inMinutes} min ago';
    if (elapsed.inDays < 1) return 'Checked ${elapsed.inHours} h ago';
    return 'Checked ${elapsed.inDays} d ago';
  }

  @override
  Widget build(BuildContext context) {
    final state = controller;
    final colors = Theme.of(context).colorScheme;
    final battery = state.battery;
    final stale =
        state.batteryReadAt == null ||
        DateTime.now().difference(state.batteryReadAt!) >=
            const Duration(hours: 6);
    final color = battery != null && battery.percent <= 20
        ? colors.error
        : colors.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Wrap(
        alignment: WrapAlignment.center,
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: 12,
        children: [
          if (state.airConnected != null)
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  state.airConnected!
                      ? Icons.bluetooth_connected
                      : Icons.bluetooth_disabled,
                  size: 16,
                  color: colors.onSurfaceVariant,
                ),
                const SizedBox(width: 4),
                Text(
                  state.airConnected! ? 'Connected' : 'Disconnected',
                  style: Theme.of(context).textTheme.labelMedium,
                ),
              ],
            ),
          TextButton.icon(
            key: const ValueKey('battery-status'),
            style: TextButton.styleFrom(foregroundColor: color),
            icon: Icon(
              battery?.onCharger == true
                  ? Icons.battery_charging_full
                  : battery == null
                  ? Icons.battery_unknown
                  : battery.percent <= 20
                  ? Icons.battery_alert
                  : Icons.battery_std,
              size: 18,
            ),
            label: Text(
              battery == null
                  ? 'Battery'
                  : '${battery.percent}%${stale ? ' · Saved' : ''}',
            ),
            onPressed: () => showModalBottomSheet<void>(
              context: context,
              showDragHandle: true,
              builder: (_) => AnimatedBuilder(
                animation: state,
                builder: (context, _) => SafeArea(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Air battery',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          state.battery == null
                              ? 'No reading yet'
                              : '${state.battery!.percent}%',
                          style: Theme.of(context).textTheme.displayMedium,
                        ),
                        Text(age),
                        if (state.battery?.onCharger == true)
                          const Text('On charger when checked'),
                        const SizedBox(height: 16),
                        Text(
                          state.relayRunning || state.relayStopping
                              ? 'Stop notifications to update the battery.'
                              : state.batteryReadAt != null &&
                                    DateTime.now().difference(
                                          state.batteryReadAt!,
                                        ) <
                                        const Duration(minutes: 1)
                              ? 'Up to date. You can check again in a minute.'
                              : 'Updates briefly connect to your Air. Saved readings stay available between visits.',
                        ),
                        if (state.batteryError != null) ...[
                          const SizedBox(height: 8),
                          Text(state.batteryError!),
                        ],
                        const SizedBox(height: 20),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton(
                            key: const ValueKey('refresh-battery'),
                            onPressed: state.canReadBattery
                                ? state.refreshBattery
                                : null,
                            child: Text(
                              state.batteryReading
                                  ? 'Checking battery…'
                                  : 'Update battery',
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
