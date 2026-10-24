import 'package:flutter/material.dart';
import 'demo_controller.dart';
import 'status_style.dart';

/// Cached observations, clearly separate from the relay's on/off state.
class AirStatusStrip extends StatelessWidget {
  const AirStatusStrip({super.key, required this.controller});
  final DemoController controller;

  static String readingAge(DateTime? at) {
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
    final stale = state.batteryStale;
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
            Builder(
              builder: (context) {
                final connected = state.airConnected!;
                final color = connected
                    ? colors.primary
                    : StatusStyle.warningForeground(
                        Theme.of(context).brightness,
                      );
                return Row(
                  key: const ValueKey('air-connection'),
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      connected ? Icons.bluetooth_connected : Icons.link_off,
                      size: 16,
                      color: color,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      connected ? 'Connected' : 'Not connected',
                      style: Theme.of(
                        context,
                      ).textTheme.labelMedium?.copyWith(color: color),
                    ),
                  ],
                );
              },
            ),
          Tooltip(
            message:
                'Fitbit Air battery · ${readingAge(state.batteryReadAt)}${stale ? ' · Reading is out of date' : ''}${state.batteryPending ? ' · Updating' : ''}',
            child: TextButton.icon(
              key: const ValueKey('battery-status'),
              style: TextButton.styleFrom(foregroundColor: color),
              icon: SizedBox.square(
                dimension: 20,
                child: state.batteryReading
                    ? MediaQuery.disableAnimationsOf(context)
                          ? const Icon(
                              Icons.sync,
                              key: ValueKey('battery-refresh-progress'),
                              size: 18,
                            )
                          : CircularProgressIndicator(
                              key: const ValueKey('battery-refresh-progress'),
                              strokeWidth: 1.8,
                              color: color,
                            )
                    : Stack(
                        clipBehavior: Clip.none,
                        children: [
                          Center(
                            child: Icon(
                              battery?.onCharger == true
                                  ? Icons.battery_charging_full
                                  : battery == null
                                  ? Icons.battery_unknown
                                  : battery.percent <= 20
                                  ? Icons.battery_alert
                                  : Icons.battery_std,
                              size: 18,
                            ),
                          ),
                          if (stale)
                            Positioned(
                              right: -3,
                              bottom: -2,
                              child: DecoratedBox(
                                decoration: BoxDecoration(
                                  color: colors.surface,
                                  shape: BoxShape.circle,
                                ),
                                child: Padding(
                                  padding: const EdgeInsets.all(1),
                                  child: Icon(
                                    Icons.schedule,
                                    key: const ValueKey('battery-stale-clock'),
                                    size: 11,
                                    color: colors.onSurfaceVariant,
                                  ),
                                ),
                              ),
                            ),
                        ],
                      ),
              ),
              label: Text(
                battery != null
                    ? '${battery.percent}%'
                    : state.batteryPending
                    ? 'Checking battery…'
                    : state.batteryError != null
                    ? 'Battery · Retry'
                    : 'Battery',
              ),
              onPressed: () => showDetails(context, state),
            ),
          ),
        ],
      ),
    );
  }

  static Future<void> showDetails(
    BuildContext context,
    DemoController state,
  ) => showModalBottomSheet<void>(
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
                'Fitbit Air battery',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 16),
              Text(
                state.battery == null
                    ? 'No reading yet'
                    : '${state.battery!.percent}%',
                style: Theme.of(context).textTheme.displayMedium,
              ),
              Text(readingAge(state.batteryReadAt)),
              if (state.battery?.onCharger == true)
                const Text('On charger when checked'),
              const SizedBox(height: 16),
              Text(
                state.relayStopping
                    ? 'Waiting for notifications to stop.'
                    : state.batteryReadAt != null &&
                          DateTime.now().difference(state.batteryReadAt!) <
                              const Duration(minutes: 1)
                    ? 'Up to date. You can check again in a minute.'
                    : 'Updates automatically while this app is open. Battery checks share the notification queue.',
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
                  onPressed: state.canReadBattery ? state.refreshBattery : null,
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
  );
}
