import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:fitbit_air/fitbit_air.dart';
import 'app_icon.dart';
import 'app_selection_buttons.dart';
import 'app_theme.dart';
import 'demo_controller.dart';
import 'relay_settings.dart';

/// Setup steps; the index is also the saved resume position.
abstract final class SetupStep {
  static const welcome = 0, air = 1, notifications = 2, apps = 3, ready = 4;
}

Future<void> showRelaySetup(
  BuildContext context,
  DemoController controller, {
  int initialStep = SetupStep.welcome,
}) => Navigator.of(context).push<void>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) =>
        RelaySetupPage(controller: controller, initialStep: initialStep),
  ),
);

/// Apps offered first in setup when installed; every app stays available.
const _suggestedApps = {
  ...knownChatApps,
  'com.google.android.calendar': 'Calendar',
  'com.google.android.gm': 'Gmail',
  'com.microsoft.office.outlook': 'Outlook',
};

class RelaySetupPage extends StatefulWidget {
  const RelaySetupPage({
    super.key,
    required this.controller,
    this.initialStep = SetupStep.welcome,
  });
  final DemoController controller;
  final int initialStep;
  @override
  State<RelaySetupPage> createState() => _RelaySetupPageState();
}

class _RelaySetupPageState extends State<RelaySetupPage> {
  // Someone reviewing a finished setup does not need the welcome again.
  late int step =
      widget.initialStep == SetupStep.welcome && state.setupCompleted
      ? SetupStep.air
      : widget.initialStep;
  bool working = false, controlsRequested = false, skipControls = false;
  bool showAllApps = false;

  /// Test buzz: null (not sent), 'sent', 'felt' or 'missed'.
  String? test;
  String query = '';
  String? error;
  DemoController get state => widget.controller;
  final scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    if (step == SetupStep.air) _autoCheckAir();
  }

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
    if (!state.setupCompleted) unawaited(state.rememberSetupStep(value));
    if (value == SetupStep.air) _autoCheckAir();
  }

  /// Looks for the Air right away when nothing needs to be asked first.
  void _autoCheckAir() {
    if (state.bluetoothPermission &&
        state.bluetoothEnabled == true &&
        state.paired != true) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) run(state.checkPaired);
      });
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

  /// Completes setup and closes it; [start] also turns notifications on.
  Future<void> finish({required bool start}) async {
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
            ? SetupStep.air
            : !state.access || !state.listenerConnected
            ? SetupStep.notifications
            : SetupStep.apps,
      );
      return;
    }
    await state.completeSetup();
    if (start && !state.relayRunning && state.canQueueRelayStart) {
      // Home shows the start's progress and any problem with it.
      unawaited(state.startRelay().then((_) {}, onError: (Object _) {}));
    }
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: state,
    builder: (context, _) {
      final blocked =
          working ||
          state.busy ||
          state.previewing ||
          state.changingRelay ||
          state.relayStopping;
      final page = switch (step) {
        SetupStep.welcome => _welcome(context),
        SetupStep.air => _air(context, blocked),
        SetupStep.notifications => _notifications(context, blocked),
        SetupStep.apps => _apps(context, blocked),
        _ => _ready(context, blocked),
      };
      final colors = Theme.of(context).colorScheme;
      return PopScope(
        canPop: !working,
        child: Scaffold(
          appBar: AppBar(
            automaticallyImplyLeading: false,
            leading: step > SetupStep.welcome
                ? IconButton(
                    key: const ValueKey('setup-back'),
                    tooltip: 'Back',
                    onPressed: blocked ? null : () => go(step - 1),
                    icon: const Icon(Icons.arrow_back),
                  )
                : null,
            title: step >= SetupStep.air && step <= SetupStep.apps
                ? _Progress(step: step)
                : null,
            centerTitle: true,
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
                Expanded(
                  child: AnimatedSwitcher(
                    duration: MediaQuery.disableAnimationsOf(context)
                        ? Duration.zero
                        : const Duration(milliseconds: 220),
                    child: ListView(
                      key: ValueKey('setup-step-$step'),
                      controller: scroll,
                      padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
                      children: [
                        ...page.body,
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
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 8, 24, 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (page.hint != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: Text(
                            page.hint!,
                            key: const ValueKey('setup-hint'),
                            textAlign: TextAlign.center,
                            style: Theme.of(context).textTheme.bodySmall
                                ?.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ),
                      FilledButton(
                        key: ValueKey(page.primaryKey),
                        onPressed: blocked ? null : page.onPrimary,
                        child: working
                            ? const SizedBox.square(
                                dimension: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : Text(page.primary),
                      ),
                      if (page.secondary != null)
                        TextButton(
                          key: ValueKey<String>(page.secondaryKey!),
                          onPressed: blocked ? null : page.onSecondary,
                          child: Text(page.secondary!),
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

  _Page _welcome(BuildContext context) => _Page(
    body: [
      const SizedBox(height: 16),
      const Center(child: AirRelayMark(size: 88)),
      const SizedBox(height: 24),
      const _Heading(
        'Your notifications, on your wrist',
        'Air Relay gives your Fitbit Air a gentle buzz when a notification you care about arrives.',
      ),
      const SizedBox(height: 24),
      const _Point(
        Icons.tune,
        'You choose what buzzes',
        'Apps, people, keywords and quiet hours.',
      ),
      const _Point(
        Icons.lock_outline,
        'Stays on your phone',
        'No internet access. Nothing is uploaded.',
      ),
      const _Point(
        Icons.timer_outlined,
        'Ready in about a minute',
        'Connect your Air, allow notifications, pick your apps.',
      ),
      const SizedBox(height: 16),
      Text(
        'An independent app, not affiliated with Google or Fitbit.',
        textAlign: TextAlign.center,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    ],
    primary: 'Get started',
    primaryKey: 'setup-get-started',
    onPrimary: () => go(SetupStep.air),
  );

  _Page _air(BuildContext context, bool blocked) {
    final permission = state.bluetoothPermission;
    final bluetoothOff = permission && state.bluetoothEnabled == false;
    final found = state.paired == true;
    final checking = working && permission && !bluetoothOff;
    return _Page(
      body: [
        const _Hero(Icons.watch_outlined),
        const _Heading(
          'Connect your Air',
          'Pair your Air in the Google Health app on this phone first. Air Relay then finds it on its own.',
        ),
        const SizedBox(height: 24),
        _StatusCard(
          rows: [
            _Status(
              'Bluetooth access',
              permission ? 'Allowed' : 'Needed to reach your Air',
              permission ? _State.done : _State.todo,
            ),
            if (bluetoothOff)
              const _Status('Bluetooth', 'Turned off', _State.problem),
            _Status(
              'Fitbit Air',
              found
                  ? 'Found and paired'
                  : checking
                  ? 'Looking for your Air…'
                  : state.paired == false
                  ? 'Not found. Pair it in Google Health, then check again.'
                  : 'Not checked yet',
              found
                  ? _State.done
                  : checking
                  ? _State.busy
                  : state.paired == false
                  ? _State.problem
                  : _State.todo,
            ),
          ],
        ),
      ],
      primary: !permission
          ? 'Allow Bluetooth access'
          : bluetoothOff
          ? 'Turn on Bluetooth'
          : !found
          ? 'Check again'
          : 'Continue',
      primaryKey: found ? 'setup-next' : 'setup-check-air',
      onPrimary: found
          ? () => go(SetupStep.notifications)
          : () => run(
              bluetoothOff ? state.openBluetoothSettings : state.checkPaired,
            ),
      secondary: !permission ? 'Open app settings' : null,
      secondaryKey: 'setup-app-settings',
      onSecondary: () => run(state.openAppSettings),
    );
  }

  _Page _notifications(BuildContext context, bool blocked) {
    final access = state.access && state.listenerConnected;
    final controls = state.notificationControlsAllowed == true;
    final needsControls = access && !controls && !skipControls;
    final colors = Theme.of(context).colorScheme;
    return _Page(
      body: [
        // No hero icon here: the disclosure below should be in view right away.
        const SizedBox(height: 16),
        const _Heading(
          'Allow notifications',
          'Air Relay needs to see your notifications to know when to buzz.',
        ),
        const SizedBox(height: 24),
        // Play User Data policy: prominent disclosure shown before the
        // request; tapping the button below is the consent.
        Container(
          key: const ValueKey('notification-disclosure'),
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
          decoration: BoxDecoration(
            color: colors.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'How Air Relay uses your notifications',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 8),
              const _Bullet(
                'Reads the notifications from all apps on this phone, including their app name, title and text, also in the background.',
              ),
              const _Bullet(
                'Uses them only to decide which ones buzz your Air.',
              ),
              const _Bullet(
                'Nothing leaves your phone: no internet access, and notification content is never stored, uploaded or shared.',
              ),
              const _Bullet(
                'Keeps only a short local history (app, time, result and, with Message tone or Keywords, a category) and the chat names and keywords you save.',
              ),
              TextButton(
                key: const ValueKey('setup-privacy-policy'),
                style: TextButton.styleFrom(padding: EdgeInsets.zero),
                onPressed: blocked ? null : () => run(state.openPrivacyPolicy),
                child: const Text('Privacy policy'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _StatusCard(
          rows: [
            _Status(
              'Read notifications',
              access
                  ? 'Allowed'
                  : state.access
                  ? 'Waiting for Android to connect'
                  : 'Needed to know when to buzz',
              access
                  ? _State.done
                  : state.access
                  ? _State.busy
                  : _State.todo,
            ),
            _Status(
              'Show Air Relay notifications',
              controls
                  ? 'Allowed'
                  : skipControls
                  ? 'Skipped. Pause and stop stay available on Home.'
                  : 'Optional: pause and stop from your notification shade',
              controls ? _State.done : _State.optional,
            ),
          ],
        ),
      ],
      primary: !access
          ? state.access
                ? 'Check notification access'
                : 'Allow notification access'
          : needsControls
          ? controlsRequested
                ? 'Open notification settings'
                : 'Allow Air Relay notifications'
          : 'Continue',
      primaryKey: !access
          ? 'notification-access-settings'
          : needsControls
          ? 'setup-notification-controls'
          : 'setup-next',
      onPrimary: !access
          ? () => run(state.openAccessSettings)
          : needsControls
          ? () => run(() async {
              if (controlsRequested) {
                await state.openNotificationControlsSettings();
              } else {
                setState(() => controlsRequested = true);
                await state.allowNotificationControls();
              }
            })
          : () => go(SetupStep.apps),
      secondary: needsControls ? 'Not now' : null,
      secondaryKey: 'setup-skip-controls',
      onSecondary: () => setState(() => skipControls = true),
    );
  }

  _Page _apps(BuildContext context, bool blocked) {
    int byLabel(Map<String, Object?> a, Map<String, Object?> b) =>
        (a['label'] as String).toLowerCase().compareTo(
          (b['label'] as String).toLowerCase(),
        );
    final suggested =
        state.apps
            .where(
              (app) =>
                  _suggestedApps.containsKey(app['packageName']) ||
                  state.selectedApps.contains(app['packageName']),
            )
            .toList()
          ..sort(byLabel);
    final suggestedPackages = {for (final app in suggested) app['packageName']};
    final all = showAllApps || suggested.isEmpty;
    final others =
        state.apps
            .where(
              (app) =>
                  !suggestedPackages.contains(app['packageName']) &&
                  '${app['label']} ${app['packageName']}'
                      .toLowerCase()
                      .contains(query.toLowerCase()),
            )
            .toList()
          ..sort(byLabel);
    Widget tile(Map<String, Object?> app) => SwitchListTile(
      key: ValueKey('setup-app-${app['packageName']}'),
      secondary: AppIcon(encoded: app['icon'] as String?),
      title: Text(app['label']! as String),
      value: state.selectedApps.contains(app['packageName']),
      onChanged: blocked
          ? null
          : (value) => run(
              () => state.saveAppRule(
                app['packageName']! as String,
                value,
                state.settings.apps[app['packageName']]?.preset,
              ),
            ),
    );
    final count = state.selectedInstalledAppCount;
    return _Page(
      body: [
        const _Hero(Icons.apps),
        const _Heading(
          'Choose your apps',
          'Only these apps will buzz your Air. You can change this anytime in Rules.',
        ),
        const SizedBox(height: 24),
        if (suggested.isNotEmpty) ...[
          const _Label('Suggested'),
          Card(
            child: Column(children: [for (final app in suggested) tile(app)]),
          ),
          const SizedBox(height: 16),
        ],
        if (!all)
          OutlinedButton.icon(
            key: const ValueKey('setup-show-all'),
            onPressed: () => setState(() => showAllApps = true),
            icon: const Icon(Icons.expand_more),
            label: Text('Show all apps (${state.apps.length})'),
          )
        else ...[
          if (suggested.isNotEmpty) const _Label('All apps'),
          TextField(
            key: const ValueKey('setup-app-search'),
            decoration: const InputDecoration(
              hintText: 'Search apps',
              prefixIcon: Icon(Icons.search),
            ),
            onChanged: (value) => setState(() => query = value),
          ),
          AppSelectionButtons(
            controller: state,
            blocked: blocked,
            perform: (action) => run(action),
          ),
          if (others.isEmpty)
            const Padding(
              padding: EdgeInsets.all(12),
              child: Text('No matching apps.'),
            )
          else
            Card(
              child: Column(children: [for (final app in others) tile(app)]),
            ),
          TextButton(
            onPressed: blocked ? null : () => run(state.loadApps),
            child: const Text('Refresh apps'),
          ),
        ],
      ],
      hint: count == 0
          ? 'Pick at least one app'
          : '${count == 1 ? '1 app' : '$count apps'} will buzz your Air',
      primary: 'Continue',
      primaryKey: 'setup-next',
      onPrimary: count == 0 ? null : () => go(SetupStep.ready),
    );
  }

  _Page _ready(BuildContext context, bool blocked) {
    final silenced = state.relayRunning && state.settings.silenceReason != null;
    final colors = Theme.of(context).colorScheme;
    final names = [
      for (final app in state.apps)
        if (state.selectedApps.contains(app['packageName']))
          app['label']! as String,
    ];
    final apps = switch (names.length) {
      0 => state.selectedAppsSummary,
      1 => names.single,
      2 => '${names[0]} and ${names[1]}',
      _ => '${names[0]}, ${names[1]} and ${names.length - 2} more',
    };
    return _Page(
      body: [
        const _Hero(Icons.check, done: true),
        _Heading(
          'You’re all set',
          'Your Air will buzz for $apps. Change apps, chats and vibrations anytime in Rules.',
        ),
        const SizedBox(height: 24),
        Card(
          key: const ValueKey('setup-test-card'),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.vibration, color: colors.primary),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Try it first',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Semantics(
                  liveRegion: true,
                  child: Text(switch (test) {
                    'sent' => 'Sent. Did your Air buzz?',
                    'felt' => 'It works.',
                    'missed' =>
                      'Check that vibration is on in Google Health and the Air is close to your phone, then try again.',
                    _ when silenced =>
                      'Resume notifications on Home to send a test.',
                    _ => 'Put on your Air and send a test buzz.',
                  }),
                ),
                const SizedBox(height: 12),
                if (test == 'sent')
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(
                          key: const ValueKey('setup-did-not-feel'),
                          onPressed: () => setState(() => test = 'missed'),
                          child: const Text('No'),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: FilledButton.tonal(
                          key: const ValueKey('setup-felt'),
                          onPressed: () => setState(() => test = 'felt'),
                          child: const Text('Yes'),
                        ),
                      ),
                    ],
                  )
                else if (test != 'felt')
                  OutlinedButton.icon(
                    key: const ValueKey('setup-test'),
                    onPressed: blocked || silenced
                        ? null
                        : () => run(() async {
                            await state.previewPreset(
                              state.settings.defaultPreset,
                            );
                            if (mounted) setState(() => test = 'sent');
                          }),
                    icon: const Icon(Icons.vibration),
                    label: Text(
                      test == 'missed' ? 'Send again' : 'Send test buzz',
                    ),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 16),
        Text(
          'Buzzes usually arrive a few seconds after a notification, while your Air is near your phone.',
          style: Theme.of(
            context,
          ).textTheme.bodySmall?.copyWith(color: colors.onSurfaceVariant),
        ),
      ],
      primary: state.relayRunning ? 'Done' : 'Start notifications',
      primaryKey: state.relayRunning ? 'setup-done' : 'setup-start-relay',
      onPrimary: () => run(() => finish(start: true)),
      secondary: state.relayRunning ? null : 'Not now',
      secondaryKey: 'setup-finish-later',
      onSecondary: () => run(() => finish(start: false)),
    );
  }
}

/// What one setup step shows: its content and the buttons at the bottom.
class _Page {
  const _Page({
    required this.body,
    required this.primary,
    required this.primaryKey,
    required this.onPrimary,
    this.hint,
    this.secondary,
    this.secondaryKey,
    this.onSecondary,
  });
  final List<Widget> body;
  final String primary, primaryKey;
  final VoidCallback? onPrimary;
  final String? hint, secondary, secondaryKey;
  final VoidCallback? onSecondary;
}

/// Three dots: the current step is wide, finished steps are filled.
class _Progress extends StatelessWidget {
  const _Progress({required this.step});
  final int step;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      label: 'Step $step of 3',
      excludeSemantics: true,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var i = SetupStep.air; i <= SetupStep.apps; i++)
            AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              margin: const EdgeInsets.symmetric(horizontal: 3),
              width: i == step ? 24 : 8,
              height: 8,
              decoration: BoxDecoration(
                color: i <= step ? colors.primary : colors.outlineVariant,
                borderRadius: BorderRadius.circular(4),
              ),
            ),
        ],
      ),
    );
  }
}

class _Hero extends StatelessWidget {
  const _Hero(this.icon, {this.done = false});
  final IconData icon;
  final bool done;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 24),
      child: Center(
        child: Container(
          width: 88,
          height: 88,
          decoration: BoxDecoration(
            color: done ? colors.primary : colors.primaryContainer,
            shape: BoxShape.circle,
          ),
          child: Icon(
            icon,
            size: 44,
            color: done ? colors.onPrimary : colors.onPrimaryContainer,
          ),
        ),
      ),
    );
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.title, this.subtitle);
  final String title, subtitle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: [
        Text(
          title,
          textAlign: TextAlign.center,
          style: theme.textTheme.headlineSmall?.copyWith(
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          subtitle,
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyLarge?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }
}

class _Point extends StatelessWidget {
  const _Point(this.icon, this.title, this.subtitle);
  final IconData icon;
  final String title, subtitle;

  @override
  Widget build(BuildContext context) => ListTile(
    contentPadding: EdgeInsets.zero,
    leading: CircleAvatar(
      backgroundColor: Theme.of(context).colorScheme.secondaryContainer,
      foregroundColor: Theme.of(context).colorScheme.onSecondaryContainer,
      child: Icon(icon, size: 20),
    ),
    title: Text(title),
    subtitle: Text(subtitle),
  );
}

class _Bullet extends StatelessWidget {
  const _Bullet(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('•  '),
        Expanded(child: Text(text)),
      ],
    ),
  );
}

class _Label extends StatelessWidget {
  const _Label(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(4, 0, 4, 8),
    child: Text(
      text,
      style: Theme.of(context).textTheme.titleSmall?.copyWith(
        color: Theme.of(context).colorScheme.primary,
        fontWeight: FontWeight.w600,
      ),
    ),
  );
}

enum _State { done, todo, busy, problem, optional }

class _Status {
  const _Status(this.title, this.subtitle, this.state);
  final String title, subtitle;
  final _State state;
}

/// Requirements, each marked done, to do, in progress or a problem.
class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.rows});
  final List<_Status> rows;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Card(
      child: Column(
        children: [
          for (final row in rows)
            ListTile(
              leading: switch (row.state) {
                _State.done => Icon(Icons.check_circle, color: colors.primary),
                _State.busy => const SizedBox.square(
                  dimension: 22,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                _State.problem => Icon(
                  Icons.error_outline,
                  color: colors.error,
                ),
                _State.optional => Icon(
                  Icons.radio_button_unchecked,
                  color: colors.outlineVariant,
                ),
                _State.todo => Icon(
                  Icons.radio_button_unchecked,
                  color: colors.outline,
                ),
              },
              title: Text(row.title),
              subtitle: Text(row.subtitle),
            ),
        ],
      ),
    );
  }
}
