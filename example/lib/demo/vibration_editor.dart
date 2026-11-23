import 'package:flutter/material.dart';
import 'demo_controller.dart';
import 'relay_settings.dart';

Future<void> editVibration(
  BuildContext context,
  DemoController controller, {
  required String title,
  String? subtitle,
  required VibrationPreset? initial,
  required Future<void> Function(VibrationPreset?) onSave,
  bool allowDefault = true,
  bool allowMute = false,
  VibrationPreset? inheritedPreset,
  String inheritLabel = 'Use default vibration',
}) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  builder: (context) => _VibrationEditor(
    controller: controller,
    title: title,
    subtitle: subtitle,
    initial: initial,
    onSave: onSave,
    allowDefault: allowDefault,
    allowMute: allowMute,
    inheritedPreset: inheritedPreset,
    inheritLabel: inheritLabel,
  ),
);

class _VibrationEditor extends StatefulWidget {
  const _VibrationEditor({
    required this.controller,
    required this.title,
    required this.subtitle,
    required this.initial,
    required this.onSave,
    required this.allowDefault,
    required this.allowMute,
    required this.inheritedPreset,
    required this.inheritLabel,
  });
  final DemoController controller;
  final String title;
  final String? subtitle;
  final VibrationPreset? initial;
  final Future<void> Function(VibrationPreset?) onSave;
  final bool allowDefault, allowMute;
  final VibrationPreset? inheritedPreset;
  final String inheritLabel;
  @override
  State<_VibrationEditor> createState() => _VibrationEditorState();
}

class _VibrationEditorState extends State<_VibrationEditor> {
  late VibrationPreset? selected = widget.initial;
  bool working = false;
  bool saving = false;
  String? error;
  String? feedback;
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_refresh);
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    widget.controller.removeListener(_refresh);
    super.dispose();
  }

  Future<void> run(Future<void> Function() action, {bool save = false}) async {
    setState(() {
      working = true;
      saving = save;
      error = null;
      feedback = null;
    });
    try {
      await action();
      if (!mounted) return;
      if (save) {
        Navigator.pop(context);
        return;
      }
      setState(() => feedback = 'Command verified. Did you feel it?');
    } catch (_) {
      if (mounted) {
        setState(
          () => error =
              'Could not complete this action. Check your Air and relay status, then try again.',
        );
      }
    } finally {
      if (mounted)
        setState(() {
          working = false;
          saving = false;
        });
    }
  }

  @override
  Widget build(BuildContext context) {
    final inherited =
        widget.inheritedPreset ?? widget.controller.settings.defaultPreset;
    final effective = selected ?? inherited;
    final quiet =
        widget.controller.relayRunning &&
        widget.controller.settings.silenceReason != null;
    final canPreview =
        !working &&
        !widget.controller.busy &&
        !widget.controller.previewing &&
        !widget.controller.changingRelay &&
        !quiet &&
        effective != VibrationPreset.mute;
    return FractionallySizedBox(
      heightFactor: .9,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 16, 12, 8),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        widget.title,
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      if (widget.subtitle case final subtitle?)
                        Text(
                          subtitle,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: 'Close',
                  onPressed: working ? null : () => Navigator.pop(context),
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              children: [
                if (widget.allowDefault)
                  ListTile(
                    key: const ValueKey('preset-default'),
                    title: Text(widget.inheritLabel),
                    subtitle: Text(inherited.label),
                    leading: Icon(
                      selected == null
                          ? Icons.radio_button_checked
                          : Icons.radio_button_off,
                    ),
                    onTap: working
                        ? null
                        : () => setState(() => selected = null),
                  ),
                for (final preset in VibrationPreset.values.where(
                  (p) => widget.allowMute || p != VibrationPreset.mute,
                ))
                  ListTile(
                    key: ValueKey('preset-${preset.id}'),
                    title: Text(preset.label),
                    subtitle: Text(preset.description),
                    leading: Icon(
                      selected == preset
                          ? Icons.radio_button_checked
                          : Icons.radio_button_off,
                    ),
                    onTap: working
                        ? null
                        : () => setState(() => selected = preset),
                  ),
              ],
            ),
          ),
          const Divider(height: 1),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (effective.intensity != null)
                    Text(
                      'Low and high change the band’s vibration strength. It keeps that strength afterward, including for other apps.',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  if (quiet)
                    const Text(
                      'Resume notifications or leave quiet hours before trying a vibration.',
                    ),
                  if (feedback != null)
                    Semantics(liveRegion: true, child: Text(feedback!)),
                  if (error != null)
                    Semantics(
                      liveRegion: true,
                      child: Text(
                        error!,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  OverflowBar(
                    alignment: MainAxisAlignment.end,
                    spacing: 8,
                    overflowSpacing: 8,
                    children: [
                      OutlinedButton.icon(
                        key: const ValueKey('preview-rule'),
                        onPressed: canPreview
                            ? () => run(
                                () =>
                                    widget.controller.previewPreset(effective),
                              )
                            : null,
                        icon: working && !saving
                            ? const SizedBox.square(
                                dimension: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.vibration),
                        label: Text(
                          working && !saving ? 'Trying…' : 'Try vibration',
                        ),
                      ),
                      FilledButton(
                        key: const ValueKey('save-rule'),
                        onPressed: working
                            ? null
                            : () => run(
                                () => widget.onSave(selected),
                                save: true,
                              ),
                        child: Text(saving ? 'Saving…' : 'Save'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
