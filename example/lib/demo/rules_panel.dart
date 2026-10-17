import 'package:flutter/material.dart';
import 'demo_controller.dart';
import 'app_icon.dart';
import 'conversation_picker.dart';
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
        const SizedBox(height: 24),
        Wrap(
          spacing: 12,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text('Your apps', style: Theme.of(context).textTheme.titleLarge),
            TextButton.icon(
              key: const ValueKey('sort-selected-apps'),
              onPressed: () =>
                  setState(() => _selectedAtSort = Set.of(state.selectedApps)),
              icon: const Icon(Icons.vertical_align_top, size: 18),
              label: const Text('Selected first'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        TextFormField(
          key: const ValueKey('app-search'),
          initialValue: state.appSearch,
          decoration: const InputDecoration(
            labelText: 'Search apps',
            prefixIcon: Icon(Icons.search),
          ),
          onChanged: state.searchApps,
        ),
        const SizedBox(height: 12),
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
                'Nearby alerts can share one vibration to leave time for Google Health to sync. A saved person’s vibration takes priority.',
              ),
            ),
          ],
        ),
      ],
    );
  }
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
