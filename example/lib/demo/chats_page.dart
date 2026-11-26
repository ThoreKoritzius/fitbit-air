import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_icon.dart';
import 'demo_controller.dart';
import 'relay_settings.dart';
import 'settings_section.dart';
import 'vibration_editor.dart';

/// How long Add a chat waits for new messages; matches the native capture window.
const kChatListenMillis = 10 * 60 * 1000;

/// The Rules row that opens the Chats page.
class ChatsRow extends StatelessWidget {
  const ChatsRow({super.key, required this.controller});
  final DemoController controller;

  @override
  Widget build(BuildContext context) {
    final settings = controller.settings;
    final saved = settings.chats.length;
    final restricted = _chatApps(
      controller,
    ).where(settings.onlySavedChats.contains).toList();
    final summary = [
      if (saved == 1) '1 saved chat',
      if (saved > 1) '$saved saved chats',
      if (restricted.length == 1)
        'Only saved chats in ${controller.appLabel(restricted.single)}',
      if (restricted.length > 1)
        'Only saved chats in ${restricted.length} apps',
    ];
    return ListTile(
      key: const ValueKey('conversation-picker'),
      leading: const Icon(Icons.forum_outlined),
      title: const Text('Chats'),
      subtitle: Text(
        summary.isEmpty
            ? 'Give people and groups their own vibration'
            : summary.join(' · '),
      ),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => _ChatsPage(controller: controller),
        ),
      ),
    );
  }
}

/// Turned-on apps that hold chats: well-known chat apps, apps with a saved chat,
/// and apps already set to only saved chats.
List<String> _chatApps(DemoController state) {
  final candidates = {
    ...knownChatApps.keys,
    for (final chat in state.settings.chats) chat.packageName,
    ...state.settings.onlySavedChats,
  };
  return candidates.where(state.selectedApps.contains).toList()..sort(
    (a, b) => state
        .appLabel(a)
        .toLowerCase()
        .compareTo(state.appLabel(b).toLowerCase()),
  );
}

/// The app's vibration that a chat without its own setting uses.
VibrationPreset _appPreset(DemoController state, SavedChat chat) =>
    state.settings.apps[chat.packageName]?.preset ??
    state.settings.defaultPreset;

String _chatVibration(SavedChat chat) => switch (chat.preset) {
  VibrationPreset.mute => 'Muted',
  final VibrationPreset preset => preset.label,
  null => 'Same as app',
};

/// "WhatsApp · Two pulses", plus anything that needs attention.
String _chatDetails(DemoController state, SavedChat chat) => [
  state.appLabel(chat.packageName),
  _chatVibration(chat),
  if (!state.selectedApps.contains(chat.packageName)) 'App is off',
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
  initial: chat.preset,
  allowMute: true,
  inheritLabel: 'Same as ${state.appLabel(chat.packageName)}',
  inheritedPreset: _appPreset(state, chat),
  onSave: (value) => state.saveChat(chat.id, value),
);

class _ChatAvatar extends StatelessWidget {
  const _ChatAvatar(this.label, {this.muted = false});
  final String label;
  final bool muted;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final initial = label.trim().characters.firstOrNull?.toUpperCase();
    return CircleAvatar(
      backgroundColor: muted
          ? colors.surfaceContainerHighest
          : colors.primaryContainer,
      foregroundColor: muted
          ? colors.onSurfaceVariant
          : colors.onPrimaryContainer,
      child: muted
          ? const Icon(Icons.notifications_off_outlined, size: 20)
          : initial == null
          ? const Icon(Icons.person_outline)
          : Text(initial),
    );
  }
}

class _ChatsPage extends StatelessWidget {
  const _ChatsPage({required this.controller});
  final DemoController controller;

  Future<void> _remove(BuildContext context, SavedChat chat) async {
    final app = controller.appLabel(chat.packageName);
    final remove =
        await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text('Remove ${chat.label}?'),
            content: Text(
              'It goes back to the $app vibration. To add it again, it needs to send a new message.',
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
      await attemptSave(
        context,
        () => controller.removeChat(chat.id),
        failure: controller.setupError,
      );
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final state = controller;
      final settings = state.settings;
      final busy = state.changingRelay;
      final theme = Theme.of(context);
      final chatApps = _chatApps(state);
      return Scaffold(
        appBar: AppBar(title: const Text('Chats')),
        body: ListView(
          key: const ValueKey('chats-scroll'),
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
          children: [
            Text(
              'Give people and groups their own vibration, or mute them. '
              'Works with WhatsApp, Signal, Telegram and other chat apps.',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 24),
            SettingsSection(
              title: 'Your chats',
              footer: [
                'Chat names stay on this phone. A group’s setting covers everyone in it.',
                if (settings.chats.any((chat) => chat.nameMatch))
                  '“Matched by name”: the app gave no chat ID, so the exact chat name is used. Renaming the chat stops the match, and another chat with the same name also matches.',
              ].join('\n\n'),
              children: [
                if (settings.chats.isEmpty)
                  Padding(
                    key: const ValueKey('chats-empty'),
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
                    child: Text(
                      'No saved chats yet.',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                for (final chat in settings.chats)
                  ListTile(
                    key: ValueKey('chat-${chat.id}'),
                    leading: _ChatAvatar(
                      chat.label,
                      muted: chat.preset == VibrationPreset.mute,
                    ),
                    title: Text(chat.label),
                    subtitle: Text(_chatDetails(state, chat)),
                    onTap: busy
                        ? null
                        : () => _editVibration(context, state, chat),
                    trailing: IconButton(
                      key: ValueKey('remove-chat-${chat.id}'),
                      tooltip: 'Remove ${chat.label}',
                      icon: const Icon(Icons.close),
                      onPressed: busy ? null : () => _remove(context, chat),
                    ),
                  ),
                ListTile(
                  key: const ValueKey('add-chat'),
                  enabled: !busy,
                  leading: const Icon(Icons.add),
                  title: const Text('Add a chat'),
                  onTap: () => showModalBottomSheet<void>(
                    context: context,
                    isScrollControlled: true,
                    useSafeArea: true,
                    builder: (_) => FractionallySizedBox(
                      heightFactor: .85,
                      child: _AddChatSheet(controller: state),
                    ),
                  ),
                ),
              ],
            ),
            SettingsSection(
              title: 'Only saved chats buzz',
              footer:
                  'Turn this on for an app to keep everyone you haven’t saved quiet there.',
              children: [
                if (chatApps.isEmpty)
                  Padding(
                    key: const ValueKey('no-chat-apps'),
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      'Turn on a chat app under Apps first.',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                for (final pkg in chatApps)
                  _OnlySavedTile(controller: state, packageName: pkg),
              ],
            ),
          ],
        ),
      );
    },
  );
}

class _OnlySavedTile extends StatelessWidget {
  const _OnlySavedTile({required this.controller, required this.packageName});
  final DemoController controller;
  final String packageName;

  @override
  Widget build(BuildContext context) {
    final state = controller;
    final on = state.settings.onlySavedChats.contains(packageName);
    final saved = state.settings.chats
        .where((chat) => chat.packageName == packageName)
        .length;
    final icon = state.apps
        .where((app) => app['packageName'] == packageName)
        .map((app) => app['icon'] as String?)
        .firstOrNull;
    return SwitchListTile(
      key: ValueKey('only-saved-$packageName'),
      secondary: AppIcon(encoded: icon),
      title: Text(state.appLabel(packageName)),
      subtitle: Text(
        !on
            ? 'All chats buzz'
            : saved == 0
            ? 'Nobody saved yet, so nothing buzzes'
            : saved == 1
            ? 'Only your 1 saved chat'
            : 'Only your $saved saved chats',
      ),
      value: on,
      onChanged: state.changingRelay
          ? null
          : (value) => attemptSave(
              context,
              () => state.setOnlySavedChats(packageName, value),
              failure: state.setupError,
            ),
    );
  }
}

/// Lists chats from the turned-on apps' notifications and keeps listening for ten minutes.
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

  void _listen() => WidgetsBinding.instance.addPostFrameCallback(
    (_) => attemptSave(
      context,
      () => state.findChats(true),
      failure: state.setupError,
    ),
  );

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
      final theme = Theme.of(context);
      final saved = {for (final chat in state.settings.chats) chat.id: chat};
      final found = state.conversationCandidates
          .where(
            (chat) => chat.label.toLowerCase().contains(query.toLowerCase()),
          )
          .toList();
      final listening = state.captureRemainingMillis > 0;
      final seconds = (state.captureRemainingMillis / 1000).ceil();
      final remaining =
          '${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}';
      final stats = state.captureDiagnostics;
      return Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 8, 0),
            child: Row(
              children: [
                Expanded(
                  child: Text('Add a chat', style: theme.textTheme.titleLarge),
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
              padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
              children: [
                Text(
                  'Chats from your turned-on apps show up here when a message arrives, or while one is still unread.',
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 12),
                if (!_canListen) ...[
                  const Text(
                    'This needs notification access, because chats are found from notifications.',
                  ),
                  TextButton(
                    onPressed: state.openAccessSettings,
                    child: const Text('Turn on notification access'),
                  ),
                ] else
                  Card(
                    key: const ValueKey('find-status'),
                    color: theme.colorScheme.surfaceContainerLow,
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 8, 8),
                      child: Row(
                        children: [
                          if (listening)
                            SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                value:
                                    state.captureRemainingMillis /
                                    kChatListenMillis,
                              ),
                            )
                          else
                            const Icon(Icons.pause_circle_outline, size: 20),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Semantics(
                              liveRegion: true,
                              child: Text(
                                listening
                                    ? 'Waiting for new messages… $remaining'
                                    : 'Stopped waiting for messages.',
                              ),
                            ),
                          ),
                          if (!listening)
                            TextButton(
                              key: const ValueKey('listen-again'),
                              onPressed: state.changingRelay ? null : _listen,
                              child: const Text('Wait again'),
                            )
                          else
                            const SizedBox(height: 48),
                        ],
                      ),
                    ),
                  ),
                if (state.conversationCandidates.length > 8) ...[
                  const SizedBox(height: 12),
                  TextField(
                    key: const ValueKey('chat-search'),
                    decoration: const InputDecoration(
                      labelText: 'Search',
                      prefixIcon: Icon(Icons.search),
                    ),
                    onChanged: (value) => setState(() => query = value),
                  ),
                ],
                const SizedBox(height: 8),
                if (found.isEmpty && query.isNotEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: Text('Nobody with that name found.'),
                  ),
                for (final chat in found)
                  if (saved[chat.id] case final added?)
                    ListTile(
                      key: ValueKey('candidate-${chat.id}'),
                      contentPadding: EdgeInsets.zero,
                      leading: _ChatAvatar(chat.label),
                      title: Text(chat.label),
                      subtitle: Text('Added · ${_chatDetails(state, added)}'),
                      trailing: const Icon(Icons.check),
                      onTap: state.changingRelay
                          ? null
                          : () => _editVibration(context, state, added),
                    )
                  else
                    ListTile(
                      key: ValueKey('candidate-${chat.id}'),
                      contentPadding: EdgeInsets.zero,
                      leading: _ChatAvatar(chat.label),
                      title: Text(chat.label),
                      subtitle: Text(
                        [
                          state.appLabel(chat.packageName),
                          if (chat.nameMatch) 'Matched by name',
                        ].join(' · '),
                      ),
                      trailing: FilledButton.tonal(
                        key: ValueKey('add-${chat.id}'),
                        onPressed: state.changingRelay
                            ? null
                            : () => attemptSave(
                                context,
                                () => state.saveChat(chat.id, null),
                                failure: state.setupError,
                              ),
                        child: const Text('Add'),
                      ),
                    ),
                if (state.conversationCandidates.isEmpty && listening)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 16),
                    child: Text(
                      'Nobody yet. Ask someone to send you a message, or keep one of their messages unread.',
                    ),
                  ),
                if (state.conversationCandidates.isEmpty &&
                    stats.isNotEmpty &&
                    !listening)
                  ExpansionTile(
                    key: const ValueKey('capture-details'),
                    tilePadding: EdgeInsets.zero,
                    title: const Text('Nobody found?'),
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
      return 'No chat notifications arrived. Check that the chat app is turned on under Apps and its notifications are on, then wait again.';
    }
    if ((stats['unusable'] ?? 0) > 0) {
      return 'Chat notifications arrived without chat details. Check the app’s notification privacy settings and try a direct message.';
    }
    return 'Only summary or ongoing notifications arrived. Open the chat app, leave the chat, then receive a new message.';
  }
}
