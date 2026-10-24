import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'demo_controller.dart';
import 'rules_panel.dart';
import 'relay_settings.dart';
import 'relay_status.dart';
import 'relay_toggle_button.dart';
import 'air_status_strip.dart';
import 'setup_page.dart';
import 'status_style.dart';

class RelayPanel extends StatelessWidget {
  const RelayPanel({
    super.key,
    required this.controller,
    required this.perform,
    required this.testVibration,
    required this.exportReport,
    this.testFeedback,
    this.openRules,
    this.scrollController,
  });
  final DemoController controller;
  final void Function(Future<void> Function()) perform;
  final VoidCallback testVibration;
  final VoidCallback exportReport;
  final VoidCallback? openRules;
  final ScrollController? scrollController;

  /// Inline Test state: null (idle), 'sending' or 'sent'.
  final String? testFeedback;

  Future<void> _setup(
    BuildContext context,
    DemoController state, {
    int initialStep = 0,
  }) async {
    final completedBefore = state.setupCompleted;
    await showRelaySetup(context, state, initialStep: initialStep);
    if (!completedBefore &&
        state.setupCompleted &&
        scrollController?.hasClients == true) {
      scrollController!.jumpTo(0);
    }
  }

  /// Fixed-size so progress and confirmation never move the layout.
  Widget _testButton(BuildContext context, DemoController state) {
    final sending = testFeedback == 'sending';
    final sent = testFeedback == 'sent';
    final enabled =
        testFeedback == null &&
        state.bluetoothPermission &&
        state.bluetoothEnabled == true &&
        state.paired != false &&
        !state.busy &&
        !state.previewing &&
        !state.changingRelay &&
        !state.batteryReading &&
        state.relayTransition == null &&
        !state.relayStopping &&
        (!state.relayRunning || state.settings.silenceReason == null) &&
        (!state.relayRunning || state.relayReady);
    return Semantics(
      label: 'Test vibration',
      liveRegion: true,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 112),
        child: TextButton.icon(
          key: const ValueKey('test-vibration'),
          onPressed: enabled ? testVibration : null,
          // Keep progress and confirmation in the accent colour, not greyed.
          style: sending || sent
              ? TextButton.styleFrom(
                  disabledForegroundColor: Theme.of(
                    context,
                  ).colorScheme.primary,
                )
              : null,
          icon: sending
              ? SizedBox.square(
                  dimension: 16,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                )
              : Icon(sent ? Icons.check : Icons.vibration, size: 18),
          label: Text(
            sending
                ? 'Sending…'
                : sent
                ? 'Sent'
                : 'Try it',
          ),
        ),
      ),
    );
  }

  void _recover(BuildContext context, RelayRecovery recovery) {
    switch (recovery) {
      case RelayRecovery.bluetooth:
        perform(controller.openBluetoothSettings);
      case RelayRecovery.notifications:
        _setup(context, controller, initialStep: 1);
      case RelayRecovery.rules:
        openRules?.call();
      case RelayRecovery.setup:
        _setup(context, controller);
      case RelayRecovery.update:
        perform(controller.openReleases);
      case RelayRecovery.none:
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = controller;
    final status = RelayStatus.fromController(state);
    final colors = Theme.of(context).colorScheme;
    final transition = state.relayTransition;
    final transitioning = transition != null;
    final displayedRunning =
        transition == RelayTransition.stopping ||
        (transition == null && state.relayRunning);
    final needsSetup =
        !state.setupCompleted && !displayedRunning && !transitioning;
    final needsFix = status.recovery != RelayRecovery.none;
    final attention = needsFix || status.attention;
    final level = transitioning
        ? StatusLevel.busy
        : needsSetup
        ? StatusLevel.idle
        : status.level;
    final paused = displayedRunning && state.settings.silenceReason == 'paused';
    final title = needsSetup
        ? 'Finish setup'
        : transition == RelayTransition.starting
        ? 'Notifications off'
        : transition == RelayTransition.stopping
        ? (paused
              ? 'Paused'
              : state.settings.silenceReason == 'quiet_hours'
              ? 'Quiet hours'
              : 'Notifications on')
        : switch (status.title) {
            'Ready for notifications' => 'Notifications on',
            'Ready when you are' => 'Notifications off',
            'Taking a break' => 'Paused',
            _ => status.title,
          };
    final recent = state.history
        .where(
          (e) => !const {'detected', 'queued', 'merged'}.contains(e['stage']),
        )
        .take(3)
        .toList();
    final enabled =
        state.initialized &&
        !state.changingRelay &&
        (!state.batteryReading ||
            state.relayRunning ||
            state.canQueueRelayStart) &&
        !transitioning &&
        !state.relayStopping &&
        (state.relayRunning || !state.busy || state.canQueueRelayStart);
    final recoveryLabel = switch (status.recovery) {
      RelayRecovery.bluetooth => 'Turn on Bluetooth',
      RelayRecovery.notifications => 'Review notification access',
      RelayRecovery.rules => 'Choose apps',
      RelayRecovery.update => 'Check for updates',
      _ => 'Check setup',
    };
    return ListView(
      key: const ValueKey('relay-scroll'),
      controller: scrollController,
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 24),
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Column(
            children: [
              StatusEmblem(
                key: const ValueKey('relay-emblem'),
                level: level,
                paused: paused,
              ),
              const SizedBox(height: 12),
              Text(
                'Fitbit Air',
                style: Theme.of(context).textTheme.labelLarge?.copyWith(
                  color: colors.onSurfaceVariant,
                  letterSpacing: .3,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                title,
                key: const ValueKey('relay-status'),
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                  letterSpacing: -.7,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                needsSetup
                    ? 'Pick up where you left off.'
                    : state.startWaitMessage != null
                    ? state.startWaitMessage!
                    : attention && !transitioning
                    ? status.detail
                    : state.selectedAppsSummary,
                key: const ValueKey('relay-status-detail'),
                textAlign: TextAlign.center,
                style: TextStyle(
                  color:
                      (level == StatusLevel.warning ||
                              level == StatusLevel.problem) &&
                          !needsSetup
                      ? StatusStyle.of(context, level).foreground
                      : colors.onSurfaceVariant,
                ),
              ),
              AirStatusStrip(controller: state),
              if (paused)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    state.settings.pauseUntil == -1
                        ? 'Until you resume'
                        : 'Until ${TimeOfDay.fromDateTime(DateTime.fromMillisecondsSinceEpoch(state.settings.pauseUntil)).format(context)}',
                  ),
                ),
              if (state.relayRunning &&
                  state.settings.silenceReason == 'quiet_hours')
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    'Until ${formatMinute(context, state.settings.endMinute)}',
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        if (needsSetup)
          FilledButton(
            key: const ValueKey('guided-setup'),
            onPressed: enabled
                ? () => _setup(context, state, initialStep: state.setupStep)
                : null,
            child: const Text('Continue setup'),
          )
        else if (needsFix && !state.relayRunning && !transitioning)
          FilledButton(
            key: ValueKey(
              status.recovery == RelayRecovery.notifications
                  ? 'notification-access-settings'
                  : 'relay-recovery',
            ),
            onPressed: enabled
                ? () => _recover(context, status.recovery)
                : null,
            child: Text(recoveryLabel),
          )
        else if (paused && !transitioning)
          FilledButton.icon(
            key: const ValueKey('resume-relay'),
            onPressed: enabled ? () => perform(() => state.setPause(0)) : null,
            icon: const Icon(Icons.play_arrow),
            label: const Text('Resume'),
          )
        else
          RelayToggleButton(
            running: displayedRunning,
            transition: transition,
            onPressed: enabled
                ? () => perform(
                    displayedRunning ? state.stopRelay : state.startRelay,
                  )
                : null,
          ),
        if (state.setupError != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              state.setupError!,
              key: const ValueKey('relay-error'),
              style: TextStyle(color: colors.error),
            ),
          ),
        if (!needsSetup)
          Wrap(
            alignment: WrapAlignment.center,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 8,
            children: [
              if (!paused || transitioning)
                PopupMenuButton<int>(
                  key: const ValueKey('pause-relay'),
                  tooltip: 'Pause notifications',
                  enabled:
                      displayedRunning &&
                      !transitioning &&
                      !state.changingRelay,
                  onSelected: (minutes) =>
                      perform(() => state.setPause(minutes)),
                  itemBuilder: (_) => const [
                    PopupMenuItem(
                      value: 15,
                      child: Text('Pause for 15 minutes'),
                    ),
                    PopupMenuItem(value: 60, child: Text('Pause for 1 hour')),
                    PopupMenuItem(
                      value: -1,
                      child: Text('Pause until I resume'),
                    ),
                  ],
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.pause,
                          size: 18,
                          color: displayedRunning
                              ? colors.primary
                              : colors.onSurfaceVariant,
                        ),
                        const SizedBox(width: 6),
                        Text(
                          'Pause',
                          style: TextStyle(
                            color: displayedRunning
                                ? colors.primary
                                : colors.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              if (paused && !transitioning)
                TextButton(
                  key: const ValueKey('relay-toggle'),
                  onPressed: enabled
                      ? () {
                          unawaited(HapticFeedback.selectionClick());
                          perform(state.stopRelay);
                        }
                      : null,
                  child: const Text('Stop'),
                ),
              _testButton(context, state),
              if (needsFix && state.relayRunning)
                TextButton(
                  onPressed: () => _recover(context, status.recovery),
                  child: Text(recoveryLabel),
                ),
            ],
          ),
        if (state.notificationControlsAllowed == false && state.setupCompleted)
          ListTile(
            key: const ValueKey('missing-notification-controls'),
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.notifications_off_outlined),
            title: const Text('Relay controls are hidden'),
            trailing: TextButton(
              onPressed: () => _setup(context, state, initialStep: 1),
              child: const Text('Allow'),
            ),
          ),
        if (state.showFirmwareNotice) _firmwareNotice(context, state),
        const SizedBox(height: 16),
        const Divider(),
        ListTile(
          key: const ValueKey('home-rules'),
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.tune),
          title: const Text('Apps & people'),
          subtitle: Text(state.selectedAppsSummary),
          trailing: const Icon(Icons.chevron_right),
          onTap: openRules,
        ),
        const Divider(),
        const SizedBox(height: 16),
        Text('Recent activity', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        if (recent.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Text(
              'Your alerts will appear here.',
              style: TextStyle(color: colors.onSurfaceVariant),
            ),
          ),
        for (final entry in recent) _activity(context, entry),
        if (state.history.isNotEmpty)
          ExpansionTile(
            key: const ValueKey('activity-details'),
            tilePadding: EdgeInsets.zero,
            title: const Text('Activity details'),
            children: [
              if (state.history.isEmpty) const Text('No activity yet.'),
              for (final entry in state.history.take(100))
                _activity(context, entry, detailed: true),
              Wrap(
                spacing: 8,
                children: [
                  TextButton.icon(
                    key: const ValueKey('export-report'),
                    onPressed: exportReport,
                    icon: const Icon(Icons.copy, size: 18),
                    label: const Text('Copy diagnostic report'),
                  ),
                  TextButton(
                    key: const ValueKey('clear-history'),
                    onPressed: state.changingRelay
                        ? null
                        : () => perform(state.clearHistory),
                    child: const Text('Clear history'),
                  ),
                ],
              ),
            ],
          ),
      ],
    );
  }

  Widget _firmwareNotice(BuildContext context, DemoController state) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      key: const ValueKey('firmware-notice'),
      margin: const EdgeInsets.only(top: 16),
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
      decoration: BoxDecoration(
        color: colors.surfaceContainerHighest.withValues(alpha: .6),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.system_update_outlined, color: colors.primary),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Your Air has new firmware',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Air Relay hasn’t been tested with firmware ${state.firmware} yet. '
            'It usually keeps working. If alerts stop arriving, check for an update.',
          ),
          Align(
            alignment: Alignment.centerRight,
            child: Wrap(
              spacing: 4,
              children: [
                TextButton(
                  key: const ValueKey('dismiss-firmware-notice'),
                  onPressed: () => perform(state.dismissFirmwareNotice),
                  child: const Text('Got it'),
                ),
                TextButton(
                  onPressed: () => perform(state.openReleases),
                  child: const Text('Check for updates'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _activity(
    BuildContext context,
    Map<String, Object?> entry, {
    bool detailed = false,
  }) {
    final stage = entry['stage'] as String? ?? 'unknown';
    final title = switch (stage) {
      'detected' => 'Notification detected',
      'combined' => 'Combined into one vibration',
      'paused' => 'Quiet: notifications paused',
      'quiet_hours' => 'Quiet: scheduled quiet hours',
      'filtered' => 'Skipped by your rules',
      'merged' => 'Merged into next vibration',
      'queued' => 'Queued',
      'verified' => 'Command verified',
      'expired' => 'Expired before execution',
      'queue_full' => 'Dropped: queue full',
      'cancelled' => 'Cancelled before execution',
      'duplicate' => 'Duplicate suppressed',
      'interrupted' => 'Interrupted; delivery uncertain',
      'resumed' => 'Notifications resumed automatically',
      _ => 'Command failed',
    };
    final packageName = entry['sourcePackage'];
    final resumed = stage == 'resumed';
    final source = resumed
        ? 'Air Relay'
        : packageName == null
        ? 'Test vibration'
        : controller.apps
                  .where((app) => app['packageName'] == packageName)
                  .map((app) => app['label'] as String)
                  .firstOrNull ??
              'Selected app';
    final millis = entry['atMillis'] as int?;
    final time = millis == null
        ? ''
        : TimeOfDay.fromDateTime(
            DateTime.fromMillisecondsSinceEpoch(millis),
          ).format(context);
    final code = entry['errorCode'] as String?;
    // Vibration and message tone category; absent on older entries.
    final presetId = entry['preset'] as String?;
    final tone = switch (entry['tone']) {
      'urgent' => 'Urgent',
      'negative' => 'Negative',
      'neutral' => 'Neutral',
      _ => null,
    };
    final signal = [
      if (presetId != null) VibrationPreset.parse(presetId).label,
      ?tone,
    ].join(' · ');
    final signalPart = signal.isEmpty ? '' : ' · $signal';
    return ListTile(
      key: ValueKey(
        '${detailed ? "detail" : "activity"}-${entry['commandId']}-$stage',
      ),
      leading: Icon(
        resumed
            ? Icons.restart_alt
            : stage == 'verified'
            ? Icons.check_circle_outline
            : stage == 'failed' || stage == 'interrupted'
            ? Icons.error_outline
            : Icons.history,
      ),
      contentPadding: detailed ? null : EdgeInsets.zero,
      title: Text(detailed ? '$source · $title' : source),
      subtitle: Text(
        detailed
            ? '$time$signalPart${entry['latencyMillis'] == null ? "" : " · ${entry['latencyMillis']} ms total"}${code == null ? "" : "\n${DemoController.recoveryHint(code)}"}'
            : '${switch (stage) {
                'verified' => 'Sent',
                'failed' || 'interrupted' => 'Not confirmed',
                'combined' || 'merged' => 'Combined',
                'paused' || 'quiet_hours' => 'Quiet',
                'resumed' => 'Resumed automatically',
                _ => 'Skipped',
              }}$signalPart · $time',
      ),
      trailing: detailed ? null : const Icon(Icons.chevron_right, size: 18),
      onTap: detailed
          ? null
          : () => showModalBottomSheet<void>(
              context: context,
              useSafeArea: true,
              isScrollControlled: true,
              builder: (context) => SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      source,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8),
                    Text('$title$signalPart · $time'),
                    const SizedBox(height: 16),
                    Text(
                      resumed
                          ? resumedExplanation(entry['commandId'] as String?)
                          : code != null
                          ? DemoController.recoveryHint(code)
                          : stage == 'verified'
                          ? 'Your Air acknowledged the vibration command.'
                          : 'This alert was handled according to your notification rules.',
                    ),
                    const SizedBox(height: 24),
                    FilledButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('Done'),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}

/// Why the relay came back on its own, from the history entry's fixed label.
String resumedExplanation(String? commandId) {
  final reason = commandId?.split('-').elementAtOrNull(1);
  final cause = switch (reason) {
    'restart' => 'after your phone restarted',
    'update' => 'after Air Relay was updated',
    'listener' => 'after Android reconnected notification access',
    'process' => 'after Android closed Air Relay in the background',
    'watchdog' => 'after a stuck command',
    _ => 'after an interruption',
  };
  return 'You left notifications on, so Air Relay turned them back on $cause. Tap Stop if you want them off.';
}
