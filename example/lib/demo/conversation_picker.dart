import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'demo_controller.dart';
import 'relay_settings.dart';
import 'vibration_editor.dart';

const _whatsappPackages = {'com.whatsapp', 'com.whatsapp.w4b'};

class ConversationPicker extends StatelessWidget {
  const ConversationPicker({
    super.key,
    required this.controller,
    required this.perform,
  });
  final DemoController controller;
  final void Function(Future<void> Function()) perform;

  @override
  Widget build(BuildContext context) {
    final settings = controller.settings;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      key: const ValueKey('conversation-picker'),
      leading: const Icon(Icons.people_outline),
      title: const Text('People & chats'),
      subtitle: Text(
        settings.chats.isEmpty
            ? 'Give individual conversations their own vibration'
            : '${settings.chats.length} saved · Manage chat vibrations',
      ),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => _PeoplePage(controller: controller),
        ),
      ),
    );
  }
}

/// Runs [action] and shows a short message if the phone rejects it.
Future<void> _attempt(
  BuildContext context,
  DemoController controller,
  Future<void> Function() action,
) async {
  try {
    await action();
  } catch (_) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(controller.setupError ?? 'Could not save. Try again.'),
        ),
      );
    }
  }
}

String _details(SavedChat chat, String vibration) => [
  vibration,
  chat.appName,
  if (chat.nameMatch) 'Matched by name',
].join(' · ');

Future<void> _editVibration(
  BuildContext context,
  DemoController state,
  SavedChat chat,
) => editVibration(
  context,
  state,
  title: chat.label,
  subtitle: chat.appName,
  initial: chat.preset,
  allowMute: true,
  inheritLabel: 'App vibration',
  inheritedPreset:
      state.settings.apps[chat.packageName]?.preset ??
      state.settings.defaultPreset,
  onSave: (value) => state.saveChat(chat.id, value),
);

class _PeoplePage extends StatelessWidget {
  const _PeoplePage({required this.controller});
  final DemoController controller;

  Future<void> _remove(BuildContext context, SavedChat chat) async {
    final remove =
        await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text('Remove ${chat.label}?'),
            content: const Text(
              'To add this chat again, it needs to send a new notification.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel'),
              ),
              FilledButton(
                key: const ValueKey('confirm-remove-chat'),
                onPressed: () => Navigator.pop(context, true),
                child: const Text('Remove'),
              ),
            ],
          ),
        ) ??
        false;
    if (remove && context.mounted) {
      await _attempt(context, controller, () => controller.removeChat(chat.id));
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final state = controller;
      final settings = state.settings;
      final busy = state.changingRelay;
      final installedChatApps = state.apps.where(
        (app) => chatAppNames.containsKey(app['packageName']),
      );
      final disabledChatApps = installedChatApps.where(
        (app) =>
            !state.selectedApps.contains(app['packageName']) &&
            settings.chats.any(
              (chat) => chat.packageName == app['packageName'],
            ),
      );
      final otherChatApps = installedChatApps.where(
        (app) => !_whatsappPackages.contains(app['packageName']),
      );
      return Scaffold(
        appBar: AppBar(title: const Text('People & chats')),
        body: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            FilledButton.icon(
              key: const ValueKey('add-chat'),
              onPressed: busy
                  ? null
                  : () => showModalBottomSheet<void>(
                      context: context,
                      isScrollControlled: true,
                      useSafeArea: true,
                      showDragHandle: true,
                      builder: (_) => FractionallySizedBox(
                        heightFactor: .85,
                        child: _AddChatSheet(controller: state),
                      ),
                    ),
              icon: const Icon(Icons.person_add_outlined),
              label: const Text('Add chat'),
            ),
            const SizedBox(height: 24),
            Text('Saved chats', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            if (settings.chats.isEmpty) const Text('No saved chats yet.'),
            for (final chat in settings.chats)
              ListTile(
                key: ValueKey('chat-${chat.id}'),
                contentPadding: EdgeInsets.zero,
                leading: const CircleAvatar(child: Icon(Icons.person_outline)),
                title: Text(chat.label),
                subtitle: Text(
                  _details(chat, chat.preset?.label ?? 'App vibration'),
                ),
                onTap: busy ? null : () => _editVibration(context, state, chat),
                trailing: IconButton(
                  key: ValueKey('remove-chat-${chat.id}'),
                  tooltip: 'Remove chat',
                  icon: const Icon(Icons.close),
                  onPressed: busy ? null : () => _remove(context, chat),
                ),
              ),
            if (disabledChatApps.isNotEmpty) ...[
              const SizedBox(height: 8),
              const Text('Enable the source app to use its saved chat rules.'),
              for (final app in disabledChatApps)
                Align(
                  alignment: Alignment.centerLeft,
                  child: TextButton(
                    onPressed: busy
                        ? null
                        : () => _attempt(
                            context,
                            state,
                            () => state.selectApp(
                              app['packageName']! as String,
                              true,
                            ),
                          ),
                    child: Text('Turn on ${app['label']}'),
                  ),
                ),
            ],
            const Divider(height: 32),
            ExpansionTile(
              key: const ValueKey('unsaved-chat-settings'),
              tilePadding: EdgeInsets.zero,
              title: const Text('Unsaved chats'),
              subtitle: const Text('Choose which apps relay only saved chats'),
              children: [
                SwitchListTile(
                  key: const ValueKey('everyone-else'),
                  contentPadding: EdgeInsets.zero,
                  title: const Text('WhatsApp & WhatsApp Business'),
                  subtitle: Text(
                    settings.everyoneElseSilent
                        ? 'Only saved chats'
                        : 'All chats use the app rule',
                  ),
                  value: settings.everyoneElseSilent,
                  onChanged: busy
                      ? null
                      : (silent) => _attempt(
                          context,
                          state,
                          () => state.setEveryoneElseSilent(silent),
                        ),
                ),
                for (final app in otherChatApps)
                  SwitchListTile(
                    key: ValueKey('unsaved-chats-${app['packageName']}'),
                    contentPadding: EdgeInsets.zero,
                    title: Text(chatAppNames[app['packageName']]!),
                    subtitle: Text(
                      settings.silentChatApps.contains(app['packageName'])
                          ? 'Only saved chats'
                          : 'All chats use the app rule',
                    ),
                    value: settings.silentChatApps.contains(app['packageName']),
                    onChanged: busy
                        ? null
                        : (silent) => _attempt(
                            context,
                            state,
                            () => state.setOtherChatsSilent(
                              app['packageName']! as String,
                              silent,
                            ),
                          ),
                  ),
              ],
            ),
            const SizedBox(height: 24),
            Text(
              'A group rule applies to everyone in that group. Chat names stay on this phone.'
              '${settings.chats.any((chat) => chat.nameMatch) ? ' "Matched by name" means the app gave no chat ID, so the exact chat name is used: renaming the chat stops matching, and another chat with the same name matches too.' : ''}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      );
    },
  );
}

/// Lists chats from messaging notifications and keeps listening for a minute.
class _AddChatSheet extends StatefulWidget {
  const _AddChatSheet({required this.controller});
  final DemoController controller;
  @override
  State<_AddChatSheet> createState() => _AddChatSheetState();
}

class _AddChatSheetState extends State<_AddChatSheet> {
  String query = '';
  DemoController get state => widget.controller;
  bool get _canListen => state.access && state.listenerConnected;

  @override
  void initState() {
    super.initState();
    if (_canListen) _listen();
  }

  void _listen() => WidgetsBinding.instance.addPostFrameCallback((_) {
    if (mounted) _attempt(context, state, () => state.findChats(true));
  });

  @override
  void dispose() {
    // Stop listening when the sheet closes; found chats stay for a few minutes.
    // Deferred: notifying listeners while this sheet is torn down is not allowed.
    final controller = state;
    if (controller.captureRemainingMillis > 0) {
      Future.microtask(() => controller.findChats(false)).catchError((_) {});
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: state,
    builder: (context, _) {
      final saved = {for (final chat in state.settings.chats) chat.id: chat};
      final found = state.conversationCandidates
          .where(
            (chat) => '${chat.label} ${chat.appName}'.toLowerCase().contains(
              query.trim().toLowerCase(),
            ),
          )
          .toList();
      final listening = state.captureRemainingMillis > 0;
      final stats = state.captureDiagnostics;
      return Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 8, 0),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    'Find chats',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
                IconButton(
                  key: const ValueKey('close-add-chat'),
                  tooltip: 'Done',
                  onPressed: () => Navigator.pop(context),
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
              children: [
                if (!_canListen) ...[
                  const Text(
                    'Chats are found from messaging notifications, so this needs notification access.',
                  ),
                  TextButton(
                    onPressed: state.openAccessSettings,
                    child: const Text('Turn on notification access'),
                  ),
                ] else
                  Row(
                    key: const ValueKey('find-status'),
                    children: [
                      if (listening)
                        SizedBox.square(
                          dimension: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            value: state.captureRemainingMillis / 60000,
                          ),
                        )
                      else
                        const Icon(Icons.history, size: 18),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          listening
                              ? 'Listening for new messages…'
                              : 'Stopped listening.',
                        ),
                      ),
                      if (!listening)
                        TextButton(
                          key: const ValueKey('listen-again'),
                          onPressed: state.changingRelay ? null : _listen,
                          child: const Text('Listen again'),
                        ),
                    ],
                  ),
                if (_canListen && state.conversationCandidates.isEmpty) ...[
                  const SizedBox(height: 12),
                  const Card(
                    child: Padding(
                      padding: EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('No chats found yet'),
                          SizedBox(height: 4),
                          Text(
                            'Ask someone to send a message or leave one unread '
                            'in a supported messaging app.',
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
                const SizedBox(height: 12),
                if (state.conversationCandidates.length > 8)
                  TextField(
                    key: const ValueKey('chat-search'),
                    decoration: const InputDecoration(
                      labelText: 'Search chats or apps',
                      prefixIcon: Icon(Icons.search),
                    ),
                    onChanged: (value) => setState(() => query = value),
                  ),
                const SizedBox(height: 8),
                if (found.isEmpty && query.isNotEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: Text('No chats match your search.'),
                  ),
                for (final chat in found)
                  if (saved[chat.id] case final added?)
                    ListTile(
                      key: ValueKey('candidate-${chat.id}'),
                      contentPadding: EdgeInsets.zero,
                      title: Text(chat.label),
                      subtitle: Text(
                        _details(
                          added,
                          'Added · ${added.preset?.label ?? 'App vibration'}',
                        ),
                      ),
                      trailing: const Icon(Icons.check),
                      onTap: state.changingRelay
                          ? null
                          : () => _editVibration(context, state, added),
                    )
                  else
                    ListTile(
                      key: ValueKey('candidate-${chat.id}'),
                      contentPadding: EdgeInsets.zero,
                      title: Text(chat.label),
                      subtitle: Text(
                        [
                          chat.appName,
                          if (chat.nameMatch) 'Matched by name',
                        ].join(' · '),
                      ),
                      trailing: FilledButton.tonal(
                        key: ValueKey('add-${chat.id}'),
                        onPressed: state.changingRelay
                            ? null
                            : () => _attempt(
                                context,
                                state,
                                () => state.saveChat(chat.id, null),
                              ),
                        child: const Text('Add'),
                      ),
                    ),
                if (_canListen && state.conversationCandidates.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 12),
                    child: Text(
                      'Missing a chat? Receive a new message while listening.',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                if (state.conversationCandidates.isEmpty &&
                    stats.isNotEmpty &&
                    !listening)
                  ExpansionTile(
                    key: const ValueKey('capture-details'),
                    tilePadding: EdgeInsets.zero,
                    title: const Text('Help finding chats'),
                    children: [
                      Text(_diagnosticMessage(stats)),
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
              ],
            ),
          ),
        ],
      );
    },
  );

  String _diagnosticMessage(Map<String, int> stats) {
    if ((stats['errors'] ?? 0) > 0) {
      return 'Android could not read some notification details. Turn notification access off and on again.';
    }
    if ((stats['seen'] ?? 0) == 0) {
      return 'No chat notifications arrived. Check that notifications are on for the messaging app, then listen again.';
    }
    if ((stats['unusable'] ?? 0) > 0) {
      return 'Notifications arrived without chat details. Check the messaging app’s notification privacy settings and try a direct message.';
    }
    return 'Only summary or ongoing notifications arrived. Leave the chat, then receive a new message.';
  }
}
