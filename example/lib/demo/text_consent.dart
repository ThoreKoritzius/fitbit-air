import 'package:flutter/material.dart';

/// Explicit consent before message text is read. [tone] asks for message tone,
/// otherwise for keyword matching.
Future<bool> confirmReadingText(
  BuildContext context, {
  required bool tone,
}) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: ValueKey(tone ? 'message-tone-consent' : 'keyword-consent'),
        title: Text(tone ? 'Read message text?' : 'Check message text?'),
        content: Text(
          tone
              ? 'Air Relay will read the text of notifications from your chosen apps to pick a vibration for urgent or negative messages.\n\n'
                    'The text is checked on this phone, in memory, and is never saved or sent; only the category (urgent, negative or neutral) appears in your history. '
                    'It’s experimental and will sometimes misjudge a message. You can turn it off anytime.'
              : 'To find your keywords, Air Relay reads the text of notifications from your chosen apps.\n\n'
                    'The text is checked on this phone, in memory, and is never saved or sent. Your history only shows that a keyword matched, not the message. '
                    'Delete all keywords to stop.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: ValueKey(
              tone ? 'message-tone-confirm' : 'keyword-consent-confirm',
            ),
            onPressed: () => Navigator.pop(context, true),
            child: Text(tone ? 'Turn on' : 'Allow'),
          ),
        ],
      ),
    ) ??
    false;
