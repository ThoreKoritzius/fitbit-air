import 'package:flutter/material.dart';

import 'demo_controller.dart';
import 'relay_settings.dart';
import 'settings_section.dart';
import 'text_consent.dart';
import 'vibration_editor.dart';

/// Summary for the Rules row.
String messageToneSummary(RelaySettings settings) => !settings.toneEnabled
    ? 'Off'
    : settings.toneUrgent == null
    ? 'On'
    : 'On · Urgent: ${_presetName(settings.toneUrgent!)}';

String _presetName(VibrationPreset preset) =>
    preset == VibrationPreset.mute ? 'Silent' : preset.label;

const _keep = 'Keep app or chat vibration';

/// The optional, experimental automatic tone detection.
class MessageTonePage extends StatelessWidget {
  const MessageTonePage({super.key, required this.controller});
  final DemoController controller;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final state = controller;
      final s = state.settings;
      final theme = Theme.of(context);
      Future<void> save({
        VibrationPreset? Function()? urgent,
        VibrationPreset? Function()? negative,
        VibrationPreset? Function()? neutral,
      }) => state.saveToneRules(
        enabled: s.toneEnabled,
        urgent: urgent != null ? urgent() : s.toneUrgent,
        negative: negative != null ? negative() : s.toneNegative,
        neutral: neutral != null ? neutral() : s.toneNeutral,
      );
      Widget row(
        String key,
        IconData icon,
        String title,
        VibrationPreset? preset,
        Future<void> Function(VibrationPreset?) onSave,
      ) => ListTile(
        key: ValueKey('tone-$key'),
        leading: Icon(icon),
        title: Text(title),
        subtitle: Text(preset == null ? _keep : _presetName(preset)),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => editVibration(
          context,
          state,
          title: '$title messages',
          initial: preset,
          allowMute: true,
          inheritLabel: _keep,
          onSave: onSave,
        ),
      );
      final fresh =
          s.toneUrgent == null &&
          s.toneNegative == null &&
          s.toneNeutral == null;
      return Scaffold(
        appBar: AppBar(title: const Text('Message tone')),
        body: ListView(
          key: const ValueKey('message-tone-scroll'),
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
          children: [
            Card(
              child: SwitchListTile(
                key: const ValueKey('message-tone'),
                title: Wrap(
                  spacing: 8,
                  runSpacing: 4,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: const [
                    Text('Detect message tone'),
                    SettingsTag(
                      'Experimental',
                      key: ValueKey('message-tone-experimental'),
                    ),
                  ],
                ),
                subtitle: const Text(
                  'Spots urgent and negative messages in English and German.',
                ),
                value: s.toneEnabled,
                onChanged: state.changingRelay
                    ? null
                    : (value) async {
                        if (value &&
                            !await confirmReadingText(context, tone: true)) {
                          return;
                        }
                        if (!context.mounted) return;
                        await attemptSave(
                          context,
                          () => state.saveToneRules(
                            enabled: value,
                            // A starting point the first time it is switched on.
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
            ),
            const SizedBox(height: 24),
            if (s.toneEnabled)
              SettingsSection(
                title: 'Vibrations',
                footer:
                    'Keywords come first. Tone will sometimes misjudge a message.',
                children: [
                  row(
                    'urgent',
                    Icons.priority_high,
                    'Urgent',
                    s.toneUrgent,
                    (value) => save(urgent: () => value),
                  ),
                  row(
                    'negative',
                    Icons.sentiment_dissatisfied_outlined,
                    'Negative',
                    s.toneNegative,
                    (value) => save(negative: () => value),
                  ),
                  row(
                    'neutral',
                    Icons.chat_bubble_outline,
                    'Everything else',
                    s.toneNeutral,
                    (value) => save(neutral: () => value),
                  ),
                ],
              )
            else
              Text(
                'Message text is checked on this phone only, and never saved or sent.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
          ],
        ),
      );
    },
  );
}
