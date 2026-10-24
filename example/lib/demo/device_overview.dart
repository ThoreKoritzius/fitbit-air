import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter/material.dart';
import 'air_status_strip.dart';
import 'demo_controller.dart';
import 'relay_status.dart';
import 'status_style.dart';

class DeviceOverview extends StatelessWidget {
  const DeviceOverview({
    super.key,
    required this.controller,
    this.model,
    this.firmware,
  });
  final DemoController controller;
  final String? model;
  final String? firmware;

  @override
  Widget build(BuildContext context) {
    final state = controller;
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final rssi = state.signalRssi;
    final signal = rssi == null
        ? 'Not checked'
        : rssi >= -60
        ? 'Strong'
        : rssi >= -80
        ? 'Good'
        : 'Weak';
    final connection = state.airConnected == true
        ? 'Connected'
        : state.airConnected == false
        ? 'Not connected'
        : 'Connection unknown';
    final level = state.airConnected == true
        ? StatusLevel.active
        : state.airConnected == false
        ? StatusLevel.warning
        : StatusLevel.idle;
    final battery = state.battery;
    return Container(
      key: const ValueKey('device-overview'),
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: colors.surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: colors.outlineVariant.withValues(alpha: .5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              StatusEmblem(
                key: const ValueKey('device-emblem'),
                level: level,
                size: 64,
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Your device',
                      style: text.labelSmall?.copyWith(
                        color: colors.onSurfaceVariant,
                      ),
                    ),
                    Text(
                      'Fitbit Air',
                      style: text.headlineSmall?.copyWith(
                        fontWeight: FontWeight.w600,
                        letterSpacing: -.6,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Icon(
                          Icons.circle,
                          size: 7,
                          color: StatusStyle.of(context, level).foreground,
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(connection, style: text.bodySmall),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          const Divider(height: 1),
          const SizedBox(height: 16),
          LayoutBuilder(
            builder: (context, constraints) {
              final batteryTile = _Metric(
                icon: battery?.onCharger == true
                    ? Icons.battery_charging_full
                    : Icons.battery_std,
                title: 'Battery',
                value: battery == null
                    ? state.batteryPending
                          ? 'Checking…'
                          : 'Not checked'
                    : '${battery.percent}%',
                detail: AirStatusStrip.readingAge(state.batteryReadAt),
                color: battery != null && battery.percent <= 20
                    ? colors.error
                    : null,
                onTap: () => AirStatusStrip.showDetails(context, state),
              );
              final signalTile = _Metric(
                icon: Icons.signal_cellular_alt,
                title: 'Signal',
                value: state.signalReading && rssi == null
                    ? 'Checking…'
                    : signal,
                detail: rssi == null
                    ? 'Nearby Bluetooth signal'
                    : '$rssi dBm · ${AirStatusStrip.readingAge(state.signalReadAt).toLowerCase()}',
              );
              return constraints.maxWidth < 280 ||
                      MediaQuery.textScalerOf(context).scale(1) > 1.3
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        batteryTile,
                        const SizedBox(height: 20),
                        signalTile,
                      ],
                    )
                  : Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(child: batteryTile),
                        const SizedBox(width: 20),
                        Expanded(child: signalTile),
                      ],
                    );
            },
          ),
          const SizedBox(height: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Signal is a rough proximity hint.',
                style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant),
              ),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  key: const ValueKey('refresh-device-signal'),
                  onPressed: state.canReadSignal
                      ? () => state.refreshSignalStrength(force: true)
                      : null,
                  icon: const Icon(Icons.refresh, size: 18),
                  label: const Text('Check signal'),
                ),
              ),
            ],
          ),
          if (state.signalError != null)
            Text(state.signalError!, style: text.bodySmall),
          if (state.linkHealth.totalRecoveries > 0)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(
                key: const ValueKey('link-recoveries'),
                children: [
                  Icon(Icons.autorenew, size: 16, color: colors.primary),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      'Connection recovered automatically ${state.linkHealth.totalRecoveries}× since the relay started'
                      '${state.linkHealth.lastRecoveryAt == null ? '' : ', last at ${TimeOfDay.fromDateTime(state.linkHealth.lastRecoveryAt!).format(context)}'}.',
                      style: text.bodySmall?.copyWith(
                        color: colors.onSurfaceVariant,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          if (model != null || firmware != null) ...[
            const SizedBox(height: 8),
            Text(
              [
                ?model,
                if (firmware != null)
                  'Firmware $firmware · ${FitbitAir.testedFirmware.contains(firmware)
                      ? 'tested'
                      : state.workingFirmware == firmware
                      ? 'not yet tested, works on your Air'
                      : 'not yet tested'}',
              ].join(' · '),
              key: const ValueKey('device-firmware'),
              style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant),
            ),
          ],
        ],
      ),
    );
  }
}

class _Metric extends StatelessWidget {
  const _Metric({
    required this.icon,
    required this.title,
    required this.value,
    required this.detail,
    this.onTap,
    this.color,
  });
  final IconData icon;
  final String title, value, detail;
  final VoidCallback? onTap;
  final Color? color;
  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    borderRadius: BorderRadius.circular(12),
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                icon,
                size: 18,
                color: color ?? Theme.of(context).colorScheme.onSurfaceVariant,
              ),
              const SizedBox(width: 6),
              Text(title, style: Theme.of(context).textTheme.labelMedium),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            value,
            style: Theme.of(context).textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            detail,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    ),
  );
}
