import 'package:flutter/material.dart';

import 'demo_controller.dart';

class AppSelectionButtons extends StatelessWidget {
  const AppSelectionButtons({
    super.key,
    required this.controller,
    required this.blocked,
    required this.perform,
    this.onSelectedFirst,
  });

  final DemoController controller;
  final bool blocked;
  final void Function(Future<void> Function()) perform;
  final VoidCallback? onSelectedFirst;

  @override
  Widget build(BuildContext context) {
    final buttonStyle = TextButton.styleFrom(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      minimumSize: const Size(0, 48),
    );
    return Wrap(
      children: [
        TextButton(
          style: buttonStyle,
          onPressed:
              blocked ||
                  controller.apps.isEmpty ||
                  controller.selectedInstalledAppCount == controller.apps.length
              ? null
              : () => perform(() => controller.selectAllApps(true)),
          child: const Text('Select all'),
        ),
        TextButton(
          style: buttonStyle,
          onPressed: blocked || controller.selectedApps.isEmpty
              ? null
              : () => perform(() => controller.selectAllApps(false)),
          child: const Text('Deselect all'),
        ),
        if (onSelectedFirst != null)
          TextButton(
            key: const ValueKey('sort-selected-apps'),
            style: buttonStyle,
            onPressed: onSelectedFirst,
            child: const Text('Selected first'),
          ),
      ],
    );
  }
}
