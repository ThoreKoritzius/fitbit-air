import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:fitbit_air/fitbit_air.dart';
import 'app_icon.dart';
import 'demo_controller.dart';

Future<void> showRelaySetup(
  BuildContext context,
  DemoController controller, {
  int initialStep = 0,
}) => Navigator.of(context).push<void>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) =>
        RelaySetupPage(controller: controller, initialStep: initialStep),
  ),
);

class RelaySetupPage extends StatefulWidget {
  const RelaySetupPage({
    super.key,
    required this.controller,
    this.initialStep = 0,
  });
  final DemoController controller;
  final int initialStep;
  @override
  State<RelaySetupPage> createState() => _RelaySetupPageState();
}

class _RelaySetupPageState extends State<RelaySetupPage> {
  late int step = widget.initialStep;
  bool working = false, sent = false, felt = false;
  bool controlsRequested = false, skipControls = false;
  String query = '';
  String? error;
  DemoController get state => widget.controller;
  final scroll = ScrollController();

  @override
  void dispose() {
    scroll.dispose();
    super.dispose();
  }

  void go(int value) {
    setState(() {
      step = value;
      error = null;
    });
    if (scroll.hasClients) scroll.jumpTo(0);
    if (!state.setupCompleted && value < 4) {
      unawaited(state.rememberSetupStep(value));
    }
  }

  Future<void> run(Future<void> Function() action) async {
    if (working) return;
    setState(() {
      working = true;
      error = null;
    });
    try {
      await action();
    } catch (failure) {
      if (mounted) {
        setState(
          () => error = switch (failure) {
            PlatformException e => DemoController.recoveryHint(e.code),
            FitbitAirException e => DemoController.recoveryHint(e.code),
            _ => 'Could not finish. Check your Air and try again.',
          },
        );
      }
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Future<void> finish({required bool feltVibration}) async {
    if (state.bluetoothPermission &&
        state.bluetoothEnabled == true &&
        state.paired == null) {
      await state.checkPaired();
    }
    if (!state.setupRequirementsMet) {
      go(
        !state.bluetoothPermission ||
                state.bluetoothEnabled != true ||
                state.paired != true
            ? 0
            : !state.access || !state.listenerConnected
            ? 1
            : 2,
      );
      return;
    }
    await state.completeSetup();
    if (!mounted) return;
    felt = feltVibration;
    go(4);
  }

  Widget permissionRow(String title, String subtitle, bool granted) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 16),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Icon(
            granted ? Icons.check_circle : Icons.radio_button_unchecked,
            size: 22,
            color: granted
                ? Theme.of(context).colorScheme.primary
                : Theme.of(context).colorScheme.outline,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text(
                subtitle,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: state,
    builder: (context, _) {
      final colors = Theme.of(context).colorScheme;
      final connected =
          state.bluetoothPermission &&
          state.bluetoothEnabled == true &&
          state.paired == true;
      final access = state.access && state.listenerConnected;
      final controls = state.notificationControlsAllowed == true;
      final needsControls = step == 1 && access && !controls && !skipControls;
      final blocked =
          working ||
          state.busy ||
          state.previewing ||
          state.changingRelay ||
          state.relayStopping;
      final titles = [
        'Connect your Air',
        'Allow notifications',
        'Choose your apps',
        sent ? 'Did you feel it?' : 'Try a vibration',
        'Setup complete',
      ];
      final subtitles = [
        'Pair it in Google Health first, then check it here.',
        'Read alerts. Show relay controls.',
        'Only these apps will reach your wrist.',
        sent
            ? 'The command was verified. Did your band vibrate?'
            : 'Put on your Air. We’ll send one test.',
        felt
            ? 'Your test vibration is confirmed.'
            : 'You can test a vibration from Home anytime.',
      ];
      final apps =
          state.apps
              .where(
                (app) => '${app['label']} ${app['packageName']}'
                    .toLowerCase()
                    .contains(query.toLowerCase()),
              )
              .toList()
            ..sort(
              (a, b) => (a['label'] as String).toLowerCase().compareTo(
                (b['label'] as String).toLowerCase(),
              ),
            );
      final buttonKey = step == 0 && !connected
          ? 'setup-check-air'
          : step == 1 && !access
          ? 'notification-access-settings'
          : needsControls
          ? 'setup-notification-controls'
          : 'setup-next';
      final buttonLabel = step == 0 && !connected
          ? !state.bluetoothPermission
                ? 'Allow nearby devices'
                : state.bluetoothEnabled == false
                ? 'Turn on Bluetooth'
                : 'Check my Air'
          : step == 1 && !access
          ? state.access
                ? 'Check notification access'
                : 'Allow notification access'
          : needsControls
          ? controlsRequested
                ? 'Open notification settings'
                : 'Allow Air Relay notifications'
          : step < 3
          ? 'Continue'
          : step == 3
          ? sent
                ? 'Yes, I felt it'
                : 'Send test vibration'
          : 'Done';
      return PopScope(
        canPop: !working,
        child: Scaffold(
          appBar: AppBar(
            automaticallyImplyLeading: false,
            title: Text(step == 4 ? 'Air Relay' : 'Setup · ${step + 1} of 4'),
            actions: [
              IconButton(
                key: const ValueKey('close-setup'),
                tooltip: 'Close setup',
                onPressed: working ? null : () => Navigator.pop(context),
                icon: const Icon(Icons.close),
              ),
            ],
          ),
          body: SafeArea(
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 4, 24, 0),
                  child: Row(
                    children: List.generate(
                      4,
                      (index) => Expanded(
                        child: Padding(
                          padding: EdgeInsets.only(right: index == 3 ? 0 : 6),
                          child: Container(
                            height: 3,
                            decoration: BoxDecoration(
                              color: index <= step
                                  ? colors.primary
                                  : colors.outlineVariant,
                              borderRadius: BorderRadius.circular(4),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: ListView(
                    controller: scroll,
                    padding: const EdgeInsets.fromLTRB(24, 36, 24, 24),
                    children: [
                      Text(
                        titles[step],
                        style: Theme.of(context).textTheme.headlineMedium
                            ?.copyWith(
                              fontWeight: FontWeight.w600,
                              letterSpacing: -.6,
                            ),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        subtitles[step],
                        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                      const SizedBox(height: 24),
                      if (step == 0) ...[
                        permissionRow(
                          'Nearby devices',
                          state.bluetoothPermission
                              ? 'Allowed'
                              : 'Bluetooth access lets Air Relay reach your band.',
                          state.bluetoothPermission,
                        ),
                        const Divider(height: 1),
                        permissionRow(
                          'Paired Air',
                          state.paired == true
                              ? 'Found in your paired devices'
                              : state.paired == false
                              ? 'Not found. Finish pairing in Google Health.'
                              : 'We’ll check your paired devices.',
                          state.paired == true,
                        ),
                        if (!state.bluetoothPermission)
                          TextButton(
                            onPressed: blocked
                                ? null
                                : () => run(state.openAppSettings),
                            child: const Text('Open app settings'),
                          ),
                        if (connected)
                          TextButton(
                            onPressed: blocked
                                ? null
                                : () => run(state.checkPaired),
                            child: const Text('Check again'),
                          ),
                      ],
                      if (step == 1) ...[
                        permissionRow(
                          'Read notifications',
                          access
                              ? 'Allowed'
                              : state.access
                              ? 'Waiting for Android to connect'
                              : 'Find alerts from your chosen apps and WhatsApp people.',
                          access,
                        ),
                        const Divider(height: 1),
                        permissionRow(
                          'Show Air Relay notifications',
                          controls
                              ? 'Allowed'
                              : skipControls
                              ? 'Skipped for now'
                              : 'Keep pause and stop controls in your notification shade.',
                          controls,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'Android notification access covers all apps, including in the background. Air Relay matches your rules on this phone. Notifications are not uploaded; saved chat names stay here.',
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(color: colors.onSurfaceVariant),
                        ),
                        if (access && controls)
                          TextButton(
                            onPressed: blocked
                                ? null
                                : () => run(
                                    state.openNotificationControlsSettings,
                                  ),
                            child: const Text('Review Android settings'),
                          ),
                        if (state.access && !state.listenerConnected)
                          TextButton(
                            onPressed: blocked
                                ? null
                                : () => run(state.openAccessSettings),
                            child: const Text('Review notification access'),
                          ),
                      ],
                      if (step == 2) ...[
                        TextField(
                          key: const ValueKey('setup-app-search'),
                          decoration: const InputDecoration(
                            hintText: 'Search apps',
                            prefixIcon: Icon(Icons.search),
                          ),
                          onChanged: (value) => setState(() => query = value),
                        ),
                        const SizedBox(height: 12),
                        for (final app in apps)
                          SwitchListTile(
                            key: ValueKey('setup-app-${app['packageName']}'),
                            contentPadding: EdgeInsets.zero,
                            secondary: AppIcon(encoded: app['icon'] as String?),
                            title: Text(app['label']! as String),
                            value: state.selectedApps.contains(
                              app['packageName'],
                            ),
                            onChanged: blocked
                                ? null
                                : (value) => run(
                                    () => state.saveAppRule(
                                      app['packageName']! as String,
                                      value,
                                      state
                                          .settings
                                          .apps[app['packageName']]
                                          ?.preset,
                                    ),
                                  ),
                          ),
                        if (apps.isEmpty) const Text('No matching apps.'),
                        TextButton(
                          onPressed: blocked ? null : () => run(state.loadApps),
                          child: const Text('Refresh apps'),
                        ),
                      ],
                      if (step == 3) ...[
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 24),
                          child: Icon(
                            Icons.vibration,
                            size: 72,
                            color: colors.primary,
                          ),
                        ),
                        Center(
                          child: Text(
                            state.settings.defaultPreset.label,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ),
                        if (sent)
                          TextButton(
                            key: const ValueKey('setup-did-not-feel'),
                            onPressed: blocked
                                ? null
                                : () => setState(() {
                                    sent = false;
                                    error =
                                        'Check vibration is enabled in Google Health, then try again.';
                                  }),
                            child: const Text('I didn’t feel it'),
                          ),
                        if (state.relayRunning &&
                            state.settings.silenceReason != null)
                          const Text(
                            'Resume notifications on Home to send a test.',
                          ),
                      ],
                      if (step == 4) ...[
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 24),
                          child: Icon(
                            Icons.check_circle_outline,
                            size: 72,
                            color: colors.primary,
                          ),
                        ),
                        Text(
                          state.selectedAppsSummary,
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: 24),
                        const Text(
                          'Start notifications from Home. Change apps, people and vibrations in Rules.',
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'Early access: if Google Health sync stalls, stop notifications.',
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(color: colors.onSurfaceVariant),
                        ),
                      ],
                      if (error != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 16),
                          child: Semantics(
                            liveRegion: true,
                            child: Text(
                              error!,
                              style: TextStyle(color: colors.error),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 12, 24, 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      FilledButton(
                        key: ValueKey(buttonKey),
                        onPressed:
                            blocked ||
                                (step == 2 &&
                                    state.selectedInstalledAppCount == 0) ||
                                (step == 3 &&
                                    !sent &&
                                    state.relayRunning &&
                                    state.settings.silenceReason != null)
                            ? null
                            : () {
                                if (step == 0 && !connected) {
                                  run(
                                    state.bluetoothPermission &&
                                            state.bluetoothEnabled == false
                                        ? state.openBluetoothSettings
                                        : state.checkPaired,
                                  );
                                } else if (step == 1 && !access) {
                                  run(state.openAccessSettings);
                                } else if (needsControls) {
                                  run(() async {
                                    if (controlsRequested) {
                                      await state
                                          .openNotificationControlsSettings();
                                    } else {
                                      setState(() => controlsRequested = true);
                                      await state.allowNotificationControls();
                                    }
                                  });
                                } else if (step < 3) {
                                  go(step + 1);
                                } else if (step == 3 && !sent) {
                                  run(() async {
                                    await state.previewPreset(
                                      state.settings.defaultPreset,
                                    );
                                    if (mounted) setState(() => sent = true);
                                  });
                                } else if (step == 3) {
                                  run(() => finish(feltVibration: true));
                                } else {
                                  Navigator.pop(context);
                                }
                              },
                        child: working
                            ? const SizedBox.square(
                                dimension: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : Text(buttonLabel),
                      ),
                      if (needsControls)
                        TextButton(
                          key: const ValueKey('setup-skip-controls'),
                          onPressed: blocked
                              ? null
                              : () => setState(() => skipControls = true),
                          child: const Text('Not now'),
                        ),
                      if (step == 3)
                        TextButton(
                          key: const ValueKey('setup-skip-test'),
                          onPressed: blocked
                              ? null
                              : () => run(() => finish(feltVibration: false)),
                          child: const Text('Skip test'),
                        ),
                      if (step > 0 && step < 4)
                        TextButton(
                          onPressed: blocked ? null : () => go(step - 1),
                          child: const Text('Back'),
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}
