import 'package:flutter/material.dart';
import 'apps_page.dart';
import 'chats_page.dart';
import 'demo_controller.dart';
import 'keywords_page.dart';
import 'message_tone_page.dart';
import 'relay_settings.dart';
import 'settings_section.dart';
import 'vibration_editor.dart';

/// The Rules tab: an overview of what buzzes, how, and when it stays quiet.
/// Long lists (apps, chats, keyword rules) live on their own pages.
class RulesPanel extends StatelessWidget {
  const RulesPanel({
    super.key,
    required this.controller,
    required this.perform,
    this.scrollController,
  });
  final DemoController controller;
  final ScrollController? scrollController;
  final void Function(Future<void> Function()) perform;

  @override
  Widget build(BuildContext context) {
    final state = controller;
    final settings = state.settings;
    final theme = Theme.of(context);
    final busy = state.changingRelay;
    return ListView(
      key: const ValueKey('rules-scroll'),
      controller: scrollController,
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
      children: [
        Text(
          'Rules',
          style: theme.textTheme.headlineMedium?.copyWith(
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          'Choose what buzzes your Air, how it feels, and when it stays quiet.',
          style: theme.textTheme.bodyMedium?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 24),
        SettingsSection(
          title: 'What buzzes',
          children: [
            ListTile(
              key: const ValueKey('app-picker'),
              leading: const Icon(Icons.apps),
              title: const Text('Apps'),
              subtitle: Text(_appsSummary(state)),
              trailing: const Icon(Icons.chevron_right),
              onTap: () =>
                  _open(context, AppsPage(controller: state, perform: perform)),
            ),
            ChatsRow(controller: state),
            ListTile(
              key: const ValueKey('keyword-rules'),
              leading: const Icon(Icons.label_important_outline),
              title: const Text('Keywords'),
              subtitle: Text(keywordsSummary(settings)),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _open(context, KeywordsPage(controller: state)),
            ),
            if (settings.callAlertsSupported)
              ListTile(
                key: const ValueKey('call-vibration'),
                leading: const Icon(Icons.phone_in_talk_outlined),
                title: const Text('Incoming calls'),
                subtitle: Text(
                  settings.callAlertsEnabled ? callModeLabel(settings) : 'Off',
                ),
                trailing: Switch(
                  key: const ValueKey('incoming-calls'),
                  value: settings.callAlertsEnabled,
                  onChanged: busy
                      ? null
                      : (enabled) =>
                            perform(() => state.setCallAlertsEnabled(enabled)),
                ),
                onTap: busy
                    ? null
                    : settings.callAlertsEnabled
                    ? () => _editCallMode(context, state)
                    : () => perform(() => state.setCallAlertsEnabled(true)),
              ),
          ],
        ),
        SettingsSection(
          title: 'How it buzzes',
          footer:
              'The default is used unless an app, chat or keyword sets its own.',
          children: [
            ListTile(
              key: const ValueKey('default-vibration'),
              leading: const Icon(Icons.vibration),
              title: const Text('Default vibration'),
              subtitle: Text(settings.defaultPreset.label),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => editVibration(
                context,
                state,
                title: 'Default vibration',
                initial: settings.defaultPreset,
                allowDefault: false,
                onSave: (preset) => state.saveDefaultPreset(preset!),
              ),
            ),
            ListTile(
              key: const ValueKey('message-tone-row'),
              leading: const Icon(Icons.auto_awesome_outlined),
              title: const Text('Message tone'),
              subtitle: Text(messageToneSummary(settings)),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _open(context, MessageTonePage(controller: state)),
            ),
          ],
        ),
        SettingsSection(
          title: 'When it stays quiet',
          children: [
            ListTile(
              key: const ValueKey('quiet-hours'),
              leading: const Icon(Icons.bedtime_outlined),
              title: const Text('Quiet hours'),
              subtitle: Text(
                settings.quietEnabled
                    ? '${formatMinute(context, settings.startMinute)} to ${formatMinute(context, settings.endMinute)} · Every day'
                    : 'Off',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => editQuietHours(context, state),
            ),
            SwitchListTile(
              key: const ValueKey('only-while-asleep'),
              secondary: const Icon(Icons.screen_lock_portrait),
              title: const Text('Only when the screen is off'),
              subtitle: const Text(
                'Stay quiet for notifications while you’re using the phone. Calls still buzz.',
              ),
              value: settings.onlyWhileAsleep,
              onChanged: busy
                  ? null
                  : (enabled) =>
                        perform(() => state.setOnlyWhileAsleep(enabled)),
            ),
          ],
        ),
        Card(
          child: ExpansionTile(
            key: const ValueKey('rules-order'),
            leading: const Icon(Icons.help_outline),
            title: const Text('Which rule wins?'),
            childrenPadding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
            expandedCrossAxisAlignment: CrossAxisAlignment.start,
            children: const [
              _Step(
                1,
                'Apps that are off, muted chats, quiet hours and pauses never buzz.',
              ),
              _Step(2, 'A matching keyword picks the vibration.'),
              _Step(3, 'Otherwise message tone, if it’s on.'),
              _Step(
                4,
                'Otherwise the chat’s vibration, then the app’s, then the default.',
              ),
              SizedBox(height: 8),
              Text(
                'Messages that arrive close together share one vibration, so Google Health has time to sync.',
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// How an incoming call buzzes: a ring by default, or one of the vibrations once.
String callModeLabel(RelaySettings settings) => settings.callRing
    ? 'Rings until you answer'
    : '${settings.callPreset.label} · Once per call';

Future<void> _editCallMode(BuildContext context, DemoController state) async {
  final settings = state.settings;
  // null stands for ringing.
  final current = settings.callRing ? null : settings.callPreset;
  final chosen = await showModalBottomSheet<({VibrationPreset? preset})>(
    context: context,
    useSafeArea: true,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(8, 16, 8, 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Text(
                'Incoming calls',
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            RadioGroup<VibrationPreset?>(
              groupValue: current,
              onChanged: (value) => Navigator.pop(context, (preset: value)),
              child: Column(
                children: [
                  const RadioListTile<VibrationPreset?>(
                    key: ValueKey('call-mode-ring'),
                    value: null,
                    title: Text('Ring until you answer'),
                    subtitle: Text(
                      'About one pulse a second while the call rings',
                    ),
                  ),
                  for (final preset in VibrationPreset.values.where(
                    (p) => p != VibrationPreset.mute,
                  ))
                    RadioListTile<VibrationPreset?>(
                      key: ValueKey('call-mode-${preset.id}'),
                      value: preset,
                      title: Text(preset.label),
                      subtitle: const Text('Once per call'),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    ),
  );
  if (chosen == null || chosen.preset == current || !context.mounted) return;
  final preset = chosen.preset;
  await attemptSave(
    context,
    () => preset == null
        ? state.saveCallPreset(settings.callPreset, ring: true)
        : state.saveCallPreset(preset),
  );
}

void _open(BuildContext context, Widget page) =>
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));

String _appsSummary(DemoController state) {
  if (state.apps.isEmpty) {
    final count = state.selectedApps.length;
    return count == 0 ? 'None yet' : '$count on';
  }
  final names = [
    for (final app in state.apps)
      if (state.selectedApps.contains(app['packageName']))
        app['label']! as String,
  ];
  return switch (names.length) {
    0 => 'None yet · Pick the apps that should buzz',
    1 => names.single,
    2 => '${names[0]} and ${names[1]}',
    _ => '${names[0]}, ${names[1]} and ${names.length - 2} more',
  };
}

class _Step extends StatelessWidget {
  const _Step(this.number, this.text);
  final int number;
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CircleAvatar(
          radius: 11,
          child: Text('$number', style: Theme.of(context).textTheme.labelSmall),
        ),
        const SizedBox(width: 12),
        Expanded(child: Text(text)),
      ],
    ),
  );
}

String formatMinute(BuildContext context, int minute) =>
    TimeOfDay(hour: minute ~/ 60, minute: minute % 60).format(context);
Future<void> editQuietHours(
  BuildContext context,
  DemoController controller,
) async {
  var enabled = controller.settings.quietEnabled;
  var start = controller.settings.startMinute;
  var end = controller.settings.endMinute;
  var saving = false;
  String? error;
  await showDialog<void>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, update) => AlertDialog(
        title: const Text('Quiet hours'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SwitchListTile(
                key: const ValueKey('quiet-enabled'),
                title: const Text('Every day'),
                value: enabled,
                onChanged: saving ? null : (v) => update(() => enabled = v),
              ),
              for (final isStart in [true, false])
                ListTile(
                  title: Text(isStart ? 'From' : 'Until'),
                  trailing: Text(formatMinute(context, isStart ? start : end)),
                  onTap: saving
                      ? null
                      : () async {
                          final value = isStart ? start : end;
                          final time = await showTimePicker(
                            context: context,
                            initialTime: TimeOfDay(
                              hour: value ~/ 60,
                              minute: value % 60,
                            ),
                          );
                          if (time != null && context.mounted) {
                            update(() {
                              if (isStart) {
                                start = time.hour * 60 + time.minute;
                              } else {
                                end = time.hour * 60 + time.minute;
                              }
                            });
                          }
                        },
                ),
              const Text(
                'Uses your phone’s local time. Overnight schedules are supported. Missed notifications are not replayed.',
              ),
              if (error != null) Text(error!),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: saving ? null : () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('save-quiet-hours'),
            onPressed: saving
                ? null
                : () async {
                    if (enabled && start == end) {
                      update(
                        () => error = 'Choose different start and end times.',
                      );
                      return;
                    }
                    update(() {
                      saving = true;
                      error = null;
                    });
                    try {
                      await controller.saveQuietHours(enabled, start, end);
                      if (context.mounted) Navigator.pop(context);
                    } catch (_) {
                      if (context.mounted) {
                        update(() {
                          saving = false;
                          error = 'Could not save. Try again.';
                        });
                      }
                    }
                  },
            child: const Text('Save'),
          ),
        ],
      ),
    ),
  );
}
