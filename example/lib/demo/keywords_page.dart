import 'package:flutter/material.dart';

import 'demo_controller.dart';
import 'relay_settings.dart';
import 'settings_section.dart';
import 'text_consent.dart';

/// Summary for the Rules row.
String keywordsSummary(RelaySettings settings) =>
    switch (settings.keywordRules.length) {
      0 => 'None yet',
      1 => '1 rule',
      final count => '$count rules',
    };

/// Short vibration names for keyword rules.
String _presetName(VibrationPreset preset) => switch (preset) {
  VibrationPreset.single => 'One pulse',
  VibrationPreset.doublePulse => 'Two pulses',
  VibrationPreset.low => 'Low pulse',
  VibrationPreset.high => 'High pulse',
  VibrationPreset.mute => 'Silent',
};

/// Messages containing chosen words or emoji get their own vibration, or none.
class KeywordsPage extends StatelessWidget {
  const KeywordsPage({super.key, required this.controller});
  final DemoController controller;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final state = controller;
      final rules = state.settings.keywordRules;
      final busy = state.changingRelay;
      final theme = Theme.of(context);
      final muted = theme.textTheme.bodyMedium?.copyWith(
        color: theme.colorScheme.onSurfaceVariant,
      );
      return Scaffold(
        appBar: AppBar(title: const Text('Keywords')),
        body: ListView(
          key: const ValueKey('keywords-scroll'),
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
          children: [
            Text(
              'Choose how messages with certain words or emoji buzz.',
              style: muted,
            ),
            const SizedBox(height: 24),
            if (rules.isEmpty)
              Card(
                key: const ValueKey('keyword-rules-empty'),
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    children: [
                      Icon(
                        Icons.label_important_outline,
                        size: 40,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(height: 12),
                      Text(
                        'No keywords yet',
                        style: theme.textTheme.titleMedium,
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'For example, buzz twice when a message says “urgent” or 🚨.',
                        textAlign: TextAlign.center,
                        style: muted,
                      ),
                      const SizedBox(height: 16),
                      FilledButton.icon(
                        key: const ValueKey('add-keyword-rule'),
                        onPressed: busy
                            ? null
                            : () => _editRule(context, state, null),
                        icon: const Icon(Icons.add),
                        label: const Text('Add keyword'),
                      ),
                    ],
                  ),
                ),
              )
            else
              SettingsSection(
                title: 'Your keywords',
                footer: rules.length > 1
                    ? 'If more than one matches, the top one wins. Drag to reorder.'
                    : null,
                children: [
                  ReorderableListView.builder(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    buildDefaultDragHandles: false,
                    itemCount: rules.length,
                    onReorderItem: (from, to) {
                      if (busy) return;
                      final reordered = [...rules];
                      reordered.insert(to, reordered.removeAt(from));
                      attemptSave(
                        context,
                        () => state.saveKeywordRules(reordered),
                      );
                    },
                    itemBuilder: (context, index) => _ruleTile(
                      context,
                      state,
                      index,
                      rules[index],
                      draggable: rules.length > 1 && !busy,
                    ),
                  ),
                  if (rules.length < KeywordRule.maxRules)
                    ListTile(
                      key: const ValueKey('add-keyword-rule'),
                      enabled: !busy,
                      leading: const Icon(Icons.add),
                      title: const Text('Add keyword'),
                      onTap: () => _editRule(context, state, null),
                    ),
                ],
              ),
            const SizedBox(height: 8),
            Text(
              'Messages are checked on this phone only, and never saved or sent.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      );
    },
  );

  Widget _ruleTile(
    BuildContext context,
    DemoController state,
    int index,
    KeywordRule rule, {
    required bool draggable,
  }) {
    final silent = rule.preset == VibrationPreset.mute;
    return ListTile(
      key: ValueKey('keyword-rule-$index'),
      leading: Icon(
        silent ? Icons.notifications_off_outlined : Icons.vibration,
      ),
      title: Text(
        rule.words.join(', '),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(_presetName(rule.preset)),
      onTap: state.changingRelay
          ? null
          : () => _editRule(context, state, index),
      trailing: draggable
          ? ReorderableDragStartListener(
              index: index,
              child: const Padding(
                padding: EdgeInsets.all(8),
                child: Icon(Icons.drag_handle),
              ),
            )
          : const Icon(Icons.chevron_right),
    );
  }
}

/// Opens the editor for rule [index], or for a new rule when null.
Future<void> _editRule(
  BuildContext context,
  DemoController controller,
  int? index,
) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  builder: (_) => _KeywordSheet(controller: controller, index: index),
);

class _KeywordSheet extends StatefulWidget {
  const _KeywordSheet({required this.controller, required this.index});
  final DemoController controller;
  final int? index;

  @override
  State<_KeywordSheet> createState() => _KeywordSheetState();
}

class _KeywordSheetState extends State<_KeywordSheet> {
  late final KeywordRule? initial = widget.index == null
      ? null
      : widget.controller.settings.keywordRules[widget.index!];
  late final List<String> words = [...?initial?.words];
  late VibrationPreset preset = initial?.preset ?? VibrationPreset.doublePulse;
  final input = TextEditingController();
  bool saving = false;
  String? error;

  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  /// Adds the typed words; commas separate several. Returns false on an invalid word.
  bool _addInput() {
    final typed = input.text
        .split(',')
        .map((word) => word.trim())
        .where((word) => word.isNotEmpty);
    for (final word in typed) {
      if (word.length > KeywordRule.maxWordLength) {
        setState(
          () => error =
              'Keep each keyword under ${KeywordRule.maxWordLength} characters.',
        );
        return false;
      }
      if (words.any((w) => w.toLowerCase() == word.toLowerCase())) continue;
      if (words.length >= KeywordRule.maxWords) {
        setState(
          () => error = 'Up to ${KeywordRule.maxWords} words per keyword rule.',
        );
        return false;
      }
      words.add(word);
    }
    input.clear();
    setState(() => error = null);
    return true;
  }

  Future<void> _save() async {
    if (!_addInput()) return;
    if (words.isEmpty) {
      setState(() => error = 'Add a word, phrase or emoji first.');
      return;
    }
    final state = widget.controller;
    // Asks once, when message text starts being checked at all.
    if (!state.settings.readsMessageText &&
        !await confirmReadingText(context, tone: false)) {
      return;
    }
    final rules = [...state.settings.keywordRules];
    final rule = KeywordRule(words: List.unmodifiable(words), preset: preset);
    if (widget.index case final index?) {
      rules[index] = rule;
    } else {
      rules.add(rule);
    }
    await _commit(rules);
  }

  Future<void> _commit(List<KeywordRule> rules) async {
    setState(() {
      saving = true;
      error = null;
    });
    try {
      await widget.controller.saveKeywordRules(rules);
      if (mounted) Navigator.pop(context);
    } catch (_) {
      if (mounted) {
        setState(() {
          saving = false;
          error = 'Could not save. Try again.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final index = widget.index;
    return FractionallySizedBox(
      heightFactor: .85,
      child: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  index == null ? 'New keyword' : 'Edit keyword',
                  style: theme.textTheme.headlineSmall,
                ),
              ),
              IconButton(
                tooltip: 'Close',
                onPressed: saving ? null : () => Navigator.pop(context),
                icon: const Icon(Icons.close),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Text('When a message contains', style: theme.textTheme.titleSmall),
          const SizedBox(height: 8),
          TextField(
            key: const ValueKey('keyword-input'),
            controller: input,
            enabled: !saving,
            maxLength: KeywordRule.maxWordLength,
            textInputAction: TextInputAction.done,
            decoration: InputDecoration(
              hintText: 'A word, phrase or emoji',
              helperText: 'Separate several with commas',
              suffixIcon: IconButton(
                key: const ValueKey('keyword-add'),
                tooltip: 'Add',
                icon: const Icon(Icons.add),
                onPressed: saving ? null : _addInput,
              ),
            ),
            onSubmitted: (_) => _addInput(),
          ),
          if (words.isNotEmpty)
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final word in words)
                  InputChip(
                    key: ValueKey('keyword-chip-$word'),
                    label: Text(word),
                    onDeleted: saving
                        ? null
                        : () => setState(() => words.remove(word)),
                  ),
              ],
            ),
          const SizedBox(height: 24),
          Text('Then', style: theme.textTheme.titleSmall),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final option in VibrationPreset.values)
                ChoiceChip(
                  key: ValueKey('keyword-preset-${option.id}'),
                  label: Text(_presetName(option)),
                  selected: preset == option,
                  onSelected: saving
                      ? null
                      : (_) => setState(() => preset = option),
                ),
            ],
          ),
          if (preset.intensity != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                'Low and high also set the band’s strength for later vibrations.',
                style: theme.textTheme.bodySmall,
              ),
            ),
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Semantics(
                liveRegion: true,
                child: Text(
                  error!,
                  style: TextStyle(color: theme.colorScheme.error),
                ),
              ),
            ),
          const SizedBox(height: 24),
          FilledButton(
            key: const ValueKey('save-keyword-rule'),
            onPressed: saving ? null : _save,
            child: const Text('Save'),
          ),
          if (index != null) ...[
            const SizedBox(height: 8),
            TextButton.icon(
              key: const ValueKey('delete-keyword-rule'),
              style: TextButton.styleFrom(
                foregroundColor: theme.colorScheme.error,
              ),
              onPressed: saving
                  ? null
                  : () => _commit(
                      [...widget.controller.settings.keywordRules]
                        ..removeAt(index),
                    ),
              icon: const Icon(Icons.delete_outline),
              label: const Text('Delete'),
            ),
          ],
          const SizedBox(height: 16),
          Text(
            'Words match whole words in any case; end one with * to match its forms (call* also matches calling). Emoji match anywhere.',
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          SizedBox(height: MediaQuery.viewInsetsOf(context).bottom + 16),
        ],
      ),
    );
  }
}
