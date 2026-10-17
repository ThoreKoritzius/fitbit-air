import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'demo_controller.dart';
import 'vibration_editor.dart';

class ConversationPicker extends StatelessWidget {
  const ConversationPicker({
    super.key,
    required this.controller,
    required this.perform,
  });
  final DemoController controller;
  final void Function(Future<void> Function()) perform;

  @override
  Widget build(BuildContext context) => ListTile(
    contentPadding: EdgeInsets.zero,
    key: const ValueKey('conversation-picker'),
    leading: const Icon(Icons.people_outline),
    title: const Text('WhatsApp people'),
    subtitle: Text(
      controller.restrictedConversations
          ? '${controller.allowedConversations.length} selected chats'
          : 'All chats',
    ),
    trailing: const Icon(Icons.chevron_right),
    onTap: () => Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => _PeoplePage(controller: controller),
      ),
    ),
  );
}

class _PeoplePage extends StatefulWidget {
  const _PeoplePage({required this.controller});
  final DemoController controller;
  @override
  State<_PeoplePage> createState() => _PeoplePageState();
}

class _PeoplePageState extends State<_PeoplePage> {
  String query = '';
  bool working = false;
  DemoController get state => widget.controller;

  Future<void> perform(Future<void> Function() action) async {
    if (working) return;
    setState(() => working = true);
    try {
      await action();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Could not save. Check relay status and try again.'),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Future<bool> prepare() async {
    if (!state.relayRunning) return !state.changingRelay;
    final confirmed =
        await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('Stop relay to edit people?'),
            content: const Text(
              'Notifications will pause. Start the relay again when you finish.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Stop and continue'),
              ),
            ],
          ),
        ) ??
        false;
    if (!confirmed) return false;
    await state.stopRelayAndWait();
    return mounted;
  }

  Future<void> addPeople() async {
    if (!await prepare() || !mounted) return;
    try {
      if (state.access && state.listenerConnected) {
        await state.configureConversations('captureConversations', {
          'enabled': true,
        });
      }
      if (!mounted) return;
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        useSafeArea: true,
        builder: (context) => FractionallySizedBox(
          heightFactor: .9,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 8, 0),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        'Find WhatsApp people',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                    ),
                    IconButton(
                      key: const ValueKey('done-adding-people'),
                      tooltip: 'Done',
                      onPressed: () => Navigator.pop(context),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: AnimatedBuilder(
                  animation: state,
                  builder: (context, _) => SingleChildScrollView(
                    padding: const EdgeInsets.all(12),
                    child: _ChatCapture(
                      controller: state,
                      perform: (action) async {
                        try {
                          await action();
                        } catch (_) {
                          if (context.mounted) {
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(
                                content: Text(
                                  'Could not update this chat. Try again.',
                                ),
                              ),
                            );
                          }
                        }
                      },
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    } finally {
      if (!state.relayRunning && state.captureRemainingMillis > 0) {
        await state.configureConversations('captureConversations', {
          'enabled': false,
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: state,
    builder: (context, _) {
      final chats = state.allowedConversations
          .where(
            (chat) => (chat['label'] as String).toLowerCase().contains(
              query.toLowerCase(),
            ),
          )
          .toList();
      final enabled = state.selectedApps.any(
        (pkg) => pkg == 'com.whatsapp' || pkg == 'com.whatsapp.w4b',
      );
      return Scaffold(
        appBar: AppBar(title: const Text('WhatsApp people')),
        body: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              'Who can reach your wrist?',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 16),
            SegmentedButton<bool>(
              key: const ValueKey('restrict-conversations'),
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: false, label: Text('All chats')),
                ButtonSegment(value: true, label: Text('Selected')),
              ],
              selected: {state.restrictedConversations},
              onSelectionChanged: working || state.changingRelay
                  ? null
                  : (value) => perform(() async {
                      if (await prepare()) {
                        await state.configureConversations(
                          'restrictConversations',
                          {'enabled': value.single},
                        );
                      }
                    }),
            ),
            const SizedBox(height: 12),
            Text(
              state.restrictedConversations
                  ? 'Only people and groups saved here can vibrate. Everyone else stays quiet.'
                  : 'All WhatsApp chats can vibrate. Save people to give them a different vibration or mute them.',
            ),
            if (!enabled) ...[
              const SizedBox(height: 12),
              const Text('WhatsApp notifications are off in your app rules.'),
              for (final app in state.apps.where(
                (app) =>
                    app['packageName'] == 'com.whatsapp' ||
                    app['packageName'] == 'com.whatsapp.w4b',
              ))
                TextButton(
                  onPressed: working
                      ? null
                      : () => perform(
                          () => state.selectApp(
                            app['packageName']! as String,
                            true,
                          ),
                        ),
                  child: Text('Enable ${app['label']}'),
                ),
            ],
            const SizedBox(height: 20),
            FilledButton.icon(
              key: const ValueKey('capture-conversations'),
              onPressed: working || state.changingRelay
                  ? null
                  : () => perform(addPeople),
              icon: const Icon(Icons.person_add_outlined),
              label: const Text('Add people'),
            ),
            const SizedBox(height: 24),
            Text(
              'Saved people and groups',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            if (state.allowedConversations.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 20),
                child: Text(
                  'Nobody saved yet. Add a person or group from an unread notification.',
                ),
              ),
            if (state.allowedConversations.length > 5)
              TextField(
                key: const ValueKey('saved-chat-search'),
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  labelText: 'Search people',
                ),
                onChanged: (value) => setState(() => query = value),
              ),
            for (final chat in chats)
              ListTile(
                key: ValueKey('chat-rule-${chat['id']}'),
                contentPadding: EdgeInsets.zero,
                leading: const CircleAvatar(child: Icon(Icons.person_outline)),
                title: Text(chat['label']! as String),
                subtitle: Text(
                  '${state.settings.chats[chat['id']]?.label ?? 'Uses app vibration'}${chat['packageName'] == 'com.whatsapp.w4b' ? ' · Business' : ''}${chat['kind'] == 'name' ? ' · Name match' : ''}',
                ),
                onTap: () => editVibration(
                  context,
                  state,
                  title: chat['label']! as String,
                  initial: state.settings.chats[chat['id']],
                  allowMute: true,
                  inheritLabel: 'Use app vibration',
                  inheritedPreset:
                      state.settings.apps[chat['packageName']]?.preset ??
                      state.settings.defaultPreset,
                  onSave: (value) =>
                      state.saveChatRule(chat['id']! as String, value),
                ),
                trailing: IconButton(
                  tooltip: 'Remove person',
                  icon: const Icon(Icons.person_remove_outlined),
                  onPressed: working
                      ? null
                      : () => perform(() async {
                          final remove =
                              await showDialog<bool>(
                                context: context,
                                builder: (context) => AlertDialog(
                                  title: const Text('Remove saved chat?'),
                                  content: const Text(
                                    'Its custom vibration will be removed. In All chats mode it can still use the app vibration.',
                                  ),
                                  actions: [
                                    TextButton(
                                      onPressed: () =>
                                          Navigator.pop(context, false),
                                      child: const Text('Cancel'),
                                    ),
                                    FilledButton(
                                      onPressed: () =>
                                          Navigator.pop(context, true),
                                      child: const Text('Remove'),
                                    ),
                                  ],
                                ),
                              ) ??
                              false;
                          if (remove && await prepare()) {
                            await state.configureConversations(
                              'selectConversation',
                              {'id': chat['id'], 'enabled': false},
                            );
                          }
                        }),
                ),
              ),
            const SizedBox(height: 24),
            const Text(
              'A group rule applies to everyone in that group. Chat names stay on this phone.',
            ),
          ],
        ),
      );
    },
  );
}

class _ChatCapture extends StatefulWidget {
  const _ChatCapture({required this.controller, required this.perform});
  final DemoController controller;
  final void Function(Future<void> Function()) perform;

  @override
  State<_ChatCapture> createState() => _ConversationPickerState();
}

class _ConversationPickerState extends State<_ChatCapture> {
  bool _showAll = false;
  String query = '';

  void configure(String method, [Object? arguments]) => widget.perform(
    () => widget.controller.configureConversations(method, arguments),
  );

  Future<void> select(Map<String, Object?> chat, bool enabled) async {
    var allowNameMatch = false;
    if (enabled && chat['kind'] == 'name') {
      allowNameMatch =
          await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text('Match this chat by name?'),
              content: const Text(
                'WhatsApp did not provide a conversation ID. This rule will match the exact notification chat name instead. Other chats with the same name can also vibrate. Renaming a chat can stop matching. For a group, everyone in that group is allowed.',
              ),
              actions: [
                TextButton(
                  key: const ValueKey('cancel-name-match'),
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('Cancel'),
                ),
                FilledButton(
                  key: const ValueKey('confirm-name-match'),
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('Use exact name'),
                ),
              ],
            ),
          ) ??
          false;
      if (!allowNameMatch) return;
    }
    await widget.controller.configureConversations('selectConversation', {
      'id': chat['id'],
      'enabled': enabled,
      'allowNameMatch': allowNameMatch,
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.controller;
    final blocked = state.relayRunning || state.changingRelay;
    final allowedIds = state.allowedConversations
        .map((item) => item['id'])
        .toSet();
    final chats = [
      ...state.conversationCandidates.where(
        (item) => (item['label'] as String).toLowerCase().contains(
          query.toLowerCase(),
        ),
      ),
    ];
    final capturing = state.captureRemainingMillis > 0;
    final stats = state.captureDiagnostics;
    final colors = Theme.of(context).colorScheme;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 8),
            Text('Add people', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 4),
            const Text(
              'Leave a message unread, or ask someone to send one while their WhatsApp chat is closed.',
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              key: const ValueKey('capture-conversations'),
              onPressed: blocked || !state.access || !state.listenerConnected
                  ? null
                  : () => configure('captureConversations', {
                      'enabled': !capturing,
                    }),
              icon: Icon(
                capturing
                    ? Icons.stop_circle_outlined
                    : Icons.person_search_outlined,
              ),
              label: Text(capturing ? 'Stop listening' : 'Find chats'),
            ),
            if (!state.access || !state.listenerConnected)
              TextButton(
                onPressed: state.openAccessSettings,
                child: const Text('Enable notification access to find chats'),
              ),
            if (capturing) ...[
              const SizedBox(height: 8),
              LinearProgressIndicator(
                value: state.captureRemainingMillis / 60000,
              ),
              const SizedBox(height: 6),
              Text(
                'Listening · ${(state.captureRemainingMillis / 1000).ceil()} s left. Results stay available for 5 minutes.',
              ),
            ],
            if (stats.isNotEmpty) ...[
              const SizedBox(height: 12),
              DecoratedBox(
                key: const ValueKey('capture-diagnostics'),
                decoration: BoxDecoration(
                  color: colors.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text(
                    _diagnosticMessage(
                      stats,
                      state.conversationCandidates.length,
                    ),
                  ),
                ),
              ),
            ] else if (capturing)
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'No WhatsApp notification received yet. Keep the chat closed and check that WhatsApp notifications are enabled.',
                ),
              ),
            TextField(
              key: const ValueKey('chat-search'),
              decoration: const InputDecoration(
                labelText: 'Search found people',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: (value) => setState(() => query = value),
            ),
            if (chats.isEmpty && query.isNotEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: Text('No matching people found. Try another name.'),
              ),
            if (chats.isNotEmpty) ...[
              const Divider(height: 24),
              for (final chat in chats.take(_showAll ? 96 : 8))
                ListTile(
                  key: ValueKey('conversation-${chat['id']}'),
                  contentPadding: EdgeInsets.zero,
                  title: Text(chat['label']! as String),
                  subtitle: Text(
                    '${chat['packageName'] == 'com.whatsapp.w4b' ? 'Business · ' : ''}${chat['kind'] == 'name' ? 'Review name matching' : 'Ready to add'}',
                  ),
                  trailing: allowedIds.contains(chat['id'])
                      ? const Chip(label: Text('Added'))
                      : const Icon(Icons.person_add_outlined),
                  onTap: blocked || allowedIds.contains(chat['id'])
                      ? null
                      : () => widget.perform(() => select(chat, true)),
                ),
              if (chats.length > 8)
                TextButton(
                  onPressed: () => setState(() => _showAll = !_showAll),
                  child: Text(
                    _showAll ? 'Show fewer' : 'Show all ${chats.length} chats',
                  ),
                ),
            ],
            if (stats.isNotEmpty)
              ExpansionTile(
                key: const ValueKey('capture-details'),
                tilePadding: EdgeInsets.zero,
                title: const Text('Troubleshooting'),

                children: [
                  Text(
                    'Unread snapshot: ${stats['existing'] ?? 0} · Summaries: ${stats['summary'] ?? 0} · Ongoing: ${stats['ongoing'] ?? 0} · Unusable: ${stats['unusable'] ?? 0} · Errors: ${stats['errors'] ?? 0}',
                  ),
                  TextButton.icon(
                    key: const ValueKey('copy-capture-report'),
                    onPressed: () async {
                      await Clipboard.setData(
                        ClipboardData(text: state.exportJson()),
                      );
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Sanitized report copied'),
                          ),
                        );
                      }
                    },
                    icon: const Icon(Icons.copy_outlined),
                    label: const Text('Copy sanitized debug report'),
                  ),
                ],
              ),
            const SizedBox(height: 12),
            Text(
              'Direct chats: one person. Groups: everyone.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }

  String _diagnosticMessage(Map<String, int> stats, int chats) {
    if ((stats['errors'] ?? 0) > 0) {
      return 'Android could not expose some notification metadata. Try reconnecting notification access. Captured chats are shown below.';
    }
    if ((stats['nameOnly'] ?? 0) > 0) {
      return 'Found chat names without IDs. Select one below to review name matching.';
    }
    if (chats > 0) {
      return 'Chats found. Select who may vibrate, then start the relay.';
    }
    if ((stats['seen'] ?? 0) == 0) {
      return 'No WhatsApp notifications found. Leave a message unread and try again, or check notification access.';
    }
    if ((stats['unusable'] ?? 0) > 0) {
      return 'WhatsApp notifications arrived without usable chat metadata. Check WhatsApp notification privacy settings and try a direct message.';
    }
    return 'Only summary or ongoing notifications were found. Open WhatsApp, leave the chat, then receive a new message.';
  }
}
