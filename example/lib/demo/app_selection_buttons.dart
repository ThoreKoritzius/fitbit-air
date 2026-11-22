import 'package:flutter/material.dart';

import 'demo_controller.dart';

class AppSelectionButtons extends StatelessWidget {
  const AppSelectionButtons({
    super.key,
    required this.controller,
    required this.blocked,
    required this.perform,
  });

  final DemoController controller;
  final bool blocked;
  final void Function(Future<void> Function()) perform;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 8,
    children: [
      TextButton(
        onPressed:
            blocked ||
                controller.apps.isEmpty ||
                controller.selectedInstalledAppCount == controller.apps.length
            ? null
            : () => perform(() => controller.selectAllApps(true)),
        child: const Text('Select all'),
      ),
      TextButton(
        onPressed: blocked || controller.selectedApps.isEmpty
            ? null
            : () => perform(() => controller.selectAllApps(false)),
        child: const Text('Deselect all'),
      ),
    ],
  );
}
