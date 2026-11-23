import 'package:flutter/material.dart';
import 'demo_controller.dart';
import 'app_icon.dart';
import 'app_selection_buttons.dart';
import 'conversation_picker.dart';
import 'relay_settings.dart';
import 'vibration_editor.dart';

class RulesPanel extends StatefulWidget {
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
  State<RulesPanel> createState() => _RulesPanelState();
}

class _RulesPanelState extends State<RulesPanel> {
  Set<String>? _selectedAtSort;

  @override
  void didUpdateWidget(covariant RulesPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      _selectedAtSort = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.controller;
    // Keep rows under the user's finger until they explicitly regroup or leave.
    if (state.apps.isNotEmpty) {
      _selectedAtSort ??= Set.of(state.selectedApps);
    }
    final selectedAtSort = _selectedAtSort ?? <String>{};
    final apps = state.filteredApps.toList()
      ..sort((a, b) {
        final selected =
            (selectedAtSort.contains(b['packageName']) ? 1 : 0) -
            (selectedAtSort.contains(a['packageName']) ? 1 : 0);
        if (selected != 0) return selected;
        final label = (a['label'] as String).toLowerCase().compareTo(
          (b['label'] as String).toLowerCase(),
        );
        return label != 0
            ? label
            : (a['packageName'] as String).compareTo(
                b['packageName'] as String,
              );
      });
    return ListView(
      key: const ValueKey('rules-scroll'),
      controller: widget.scrollController,
      padding: const EdgeInsets.all(20),
      children: [
        Text(
          'Rules',
          style: Theme.of(
            context,
          ).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 20),
        ConversationPicker(controller: state, perform: widget.perform),
        const SizedBox(height: 12),
        ListTile(
          contentPadding: EdgeInsets.zero,
          key: const ValueKey('default-vibration'),
          leading: const Icon(Icons.vibration),
          title: const Text('Default vibration'),
          subtitle: Text(state.settings.defaultPreset.label),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => editVibration(
            context,
            state,
            title: 'Default vibration',
            initial: state.settings.defaultPreset,
            allowDefault: false,
            onSave: (preset) => state.saveDefaultPreset(preset!),
          ),
        ),
        const SizedBox(height: 12),
        ListTile(
          contentPadding: EdgeInsets.zero,
          key: const ValueKey('quiet-hours'),
          leading: const Icon(Icons.bedtime_outlined),
          title: const Text('Quiet hours'),
          subtitle: Text(
            state.settings.quietEnabled
                ? '${formatMinute(context, state.settings.startMinute)} to ${formatMinute(context, state.settings.endMinute)} · Every day'
                : 'Off · Set a daily time to stay quiet',
          ),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => editQuietHours(context, state),
        ),
        const SizedBox(height: 12),
        SwitchListTile(
          key: const ValueKey('only-while-asleep'),
          contentPadding: EdgeInsets.zero,
          secondary: const Icon(Icons.screen_lock_portrait),
          title: const Text('Only relay while the phone is asleep'),
          subtitle: const Text('Skip notifications while the phone is awake.'),
          value: state.settings.onlyWhileAsleep,
          onChanged: state.changingRelay
              ? null
              : (enabled) => widget.perform(
                  () => state.setOnlyWhileAsleep(enabled),
                ),
        ),
        ..._toneRules(context, state, widget.perform),
        const SizedBox(height: 24),
        Text('Your apps', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        TextFormField(
          key: const ValueKey('app-search'),
          initialValue: state.appSearch,
          decoration: const InputDecoration(
            labelText: 'Search apps',
            prefixIcon: Icon(Icons.search),
          ),
          onChanged: state.searchApps,
        ),
        AppSelectionButtons(
          controller: state,
          blocked: state.changingRelay,
          perform: widget.perform,
          onSelectedFirst: () =>
              setState(() => _selectedAtSort = Set.of(state.selectedApps)),
        ),
        if (state.filteredApps.isEmpty)
          const Padding(
            padding: EdgeInsets.all(12),
            child: Text(
              'No matching apps. Try another search or refresh the list.',
            ),
          ),
        for (final app in apps)
          Builder(
            builder: (context) {
              final pkg = app['packageName']! as String;
              final rule = state.settings.apps[pkg];
              final enabled = state.selectedApps.contains(pkg);
              final preset = rule?.preset;
              final icon = app['icon'] as String?;
              return ListTile(
                key: ValueKey('app-$pkg'),
                contentPadding: EdgeInsets.zero,
                leading: AppIcon(encoded: icon),
                title: Text(app['label']! as String),
                subtitle: Text(
                  '${enabled ? "On" : "Off"} · ${preset?.label ?? "Default: ${state.settings.defaultPreset.label}"}',
                ),
                trailing: Switch(
                  key: ValueKey('app-enabled-$pkg'),
                  value: enabled,
                  onChanged: state.changingRelay
                      ? null
                      : (value) => widget.perform(
                          () => state.saveAppRule(pkg, value, preset),
                        ),
                ),
                onTap: () => editVibration(
                  context,
                  state,
                  title: app['label']! as String,
                  initial: preset,
                  onSave: (value) => state.saveAppRule(pkg, enabled, value),
                ),
              );
            },
          ),
        TextButton(
          key: const ValueKey('refresh-apps'),
          onPressed: () => widget.perform(state.loadApps),
          child: const Text('Refresh app list'),
        ),
        const SizedBox(height: 16),
        const ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: Text('How alerts work'),
          children: [
            Padding(
              padding: EdgeInsets.only(bottom: 16),
              child: Text(
                'Nearby alerts can share one vibration to leave time for Google Health to sync. A saved person’s vibration takes priority, unless message tone marks another alert as urgent.',
              ),
            ),
          ],
        ),
      ],
    );
  }
}

List<Widget> _toneRules(
  BuildContext context,
  DemoController state,
  void Function(Future<void> Function()) perform,
) {
  final s = state.settings;
  Future<void> save({
    bool? enabled,
    VibrationPreset? Function()? urgent,
    VibrationPreset? Function()? negative,
    VibrationPreset? Function()? neutral,
  }) => state.saveToneRules(
    enabled: enabled ?? s.toneEnabled,
    urgent: urgent != null ? urgent() : s.toneUrgent,
    negative: negative != null ? negative() : s.toneNegative,
    neutral: neutral != null ? neutral() : s.toneNeutral,
  );
  Widget row(
    String key,
    String title,
    VibrationPreset? preset,
    Future<void> Function(VibrationPreset?) onSave,
  ) => ListTile(
    key: ValueKey('tone-$key'),
    contentPadding: const EdgeInsets.only(left: 40),
    title: Text(title),
    subtitle: Text(preset?.label ?? 'Keep app or chat vibration'),
    trailing: const Icon(Icons.chevron_right),
    onTap: () => editVibration(
      context,
      state,
      title: title,
      initial: preset,
      allowMute: true,
      inheritLabel: 'Keep app or chat vibration',
      onSave: onSave,
    ),
  );
  final fresh =
      s.toneUrgent == null && s.toneNegative == null && s.toneNeutral == null;
  return [
    SwitchListTile(
      key: const ValueKey('message-tone'),
      contentPadding: EdgeInsets.zero,
      secondary: const Icon(Icons.priority_high),
      title: Wrap(
        spacing: 8,
        runSpacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [const Text('Message tone'), _experimentalLabel(context)],
      ),
      subtitle: const Text(
        'Reads message text on this phone to vary the vibration for urgent or negative messages. Text is never stored or shared.',
      ),
      value: s.toneEnabled,
      onChanged: state.changingRelay
          ? null
          : (value) async {
              if (value && !await _confirmMessageTone(context)) return;
              perform(
                () => state.saveToneRules(
                  enabled: value,
                  // Suggest a starting point the first time it is switched on.
                  urgent: value && fresh
                      ? VibrationPreset.doublePulse
                      : s.toneUrgent,
                  negative: value && fresh
                      ? VibrationPreset.high
                      : s.toneNegative,
                  neutral: s.toneNeutral,
                ),
              );
            },
    ),
    if (s.toneEnabled) ...[
      row(
        'urgent',
        'Urgent',
        s.toneUrgent,
        (value) => save(urgent: () => value),
      ),
      row(
        'negative',
        'Negative',
        s.toneNegative,
        (value) => save(negative: () => value),
      ),
      row(
        'neutral',
        'Everything else',
        s.toneNeutral,
        (value) => save(neutral: () => value),
      ),
    ],
  ];
}

Widget _experimentalLabel(BuildContext context) {
  final colors = Theme.of(context).colorScheme;
  return Container(
    key: const ValueKey('message-tone-experimental'),
    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
    decoration: BoxDecoration(
      border: Border.all(color: colors.outline),
      borderRadius: BorderRadius.circular(6),
    ),
    child: Text(
      'Experimental',
      style: Theme.of(
        context,
      ).textTheme.labelSmall?.copyWith(color: colors.onSurfaceVariant),
    ),
  );
}

/// Explicit consent each time message text reading is switched on.
Future<bool> _confirmMessageTone(BuildContext context) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const ValueKey('message-tone-consent'),
        title: const Text('Read message text?'),
        content: const Text(
          'Air Relay will read the text of notifications from your chosen apps to pick a vibration for urgent or negative messages.\n\n'
          'The text is checked on this phone, in memory, and is never saved or sent; only the category (urgent, negative or neutral) appears in your history. '
          'It’s experimental and will sometimes misjudge a message. You can turn it off anytime.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('message-tone-confirm'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Turn on'),
          ),
        ],
      ),
    ) ??
    false;

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
