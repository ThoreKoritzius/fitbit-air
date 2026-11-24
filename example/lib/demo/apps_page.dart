import 'package:flutter/material.dart';

import 'app_icon.dart';
import 'app_selection_buttons.dart';
import 'demo_controller.dart';
import 'vibration_editor.dart';

/// Turns apps on or off and gives each its own vibration.
class AppsPage extends StatefulWidget {
  const AppsPage({super.key, required this.controller, required this.perform});
  final DemoController controller;
  final void Function(Future<void> Function()) perform;

  @override
  State<AppsPage> createState() => _AppsPageState();
}

class _AppsPageState extends State<AppsPage> {
  /// Keeps rows under the user's finger until they regroup or reopen the page.
  Set<String>? _selectedAtSort;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: widget.controller,
    builder: (context, _) {
      final state = widget.controller;
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
      final busy = state.changingRelay;
      final theme = Theme.of(context);
      return Scaffold(
        appBar: AppBar(title: const Text('Apps')),
        body: ListView(
          key: const ValueKey('apps-scroll'),
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
          children: [
            Text(
              'Turn on the apps that should buzz your Air. Tap an app to give it its own vibration.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 16),
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
              blocked: busy,
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
              )
            else
              Card(
                child: Column(
                  children: [
                    for (final app in apps) _appTile(context, state, app, busy),
                  ],
                ),
              ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                key: const ValueKey('refresh-apps'),
                onPressed: () => widget.perform(state.loadApps),
                icon: const Icon(Icons.refresh),
                label: const Text('Refresh app list'),
              ),
            ),
          ],
        ),
      );
    },
  );

  Widget _appTile(
    BuildContext context,
    DemoController state,
    Map<String, Object?> app,
    bool busy,
  ) {
    final pkg = app['packageName']! as String;
    final label = app['label']! as String;
    final enabled = state.selectedApps.contains(pkg);
    final preset = state.settings.apps[pkg]?.preset;
    return ListTile(
      key: ValueKey('app-$pkg'),
      leading: AppIcon(encoded: app['icon'] as String?),
      title: Text(label),
      subtitle: Text(
        !enabled
            ? 'Off'
            : preset?.label ??
                  'Default · ${state.settings.defaultPreset.label}',
      ),
      trailing: Switch(
        key: ValueKey('app-enabled-$pkg'),
        value: enabled,
        onChanged: busy
            ? null
            : (value) =>
                  widget.perform(() => state.saveAppRule(pkg, value, preset)),
      ),
      onTap: () => editVibration(
        context,
        state,
        title: label,
        initial: preset,
        onSave: (value) => state.saveAppRule(pkg, enabled, value),
      ),
    );
  }
}
