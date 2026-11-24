import 'package:fitbit_air/fitbit_air.dart';

enum VibrationPreset {
  single('single', 'One pulse', 'Uses your current strength'),
  low('low', 'One pulse · Low', 'The band keeps low strength afterward'),
  high('high', 'One pulse · High', 'The band keeps high strength afterward'),
  doublePulse('double', 'Two pulses', 'Restores your original strength'),
  mute('mute', 'Silent', 'No vibration for this chat');

  const VibrationPreset(this.id, this.label, this.description);
  final String id;
  final String label;
  final String description;
  HapticIntensity? get intensity => switch (this) {
    low => HapticIntensity.low,
    high => HapticIntensity.high,
    _ => null,
  };
  static VibrationPreset parse(Object? value) =>
      values.firstWhere((item) => item.id == value, orElse: () => single);
}

class AppNotificationRule {
  const AppNotificationRule({required this.enabled, this.preset});
  final bool enabled;
  final VibrationPreset? preset;
}

/// Names for common chat apps, used until the phone's app list has loaded. Chat
/// rules also work for apps not listed here.
const knownChatApps = {
  'com.whatsapp': 'WhatsApp',
  'com.whatsapp.w4b': 'WhatsApp Business',
  'org.thoughtcrime.securesms': 'Signal',
  'org.telegram.messenger': 'Telegram',
  'org.telegram.messenger.web': 'Telegram',
  'com.facebook.orca': 'Messenger',
  'com.google.android.apps.messaging': 'Messages',
  'com.google.android.apps.dynamite': 'Google Chat',
  'com.Slack': 'Slack',
  'com.discord': 'Discord',
  'com.microsoft.teams': 'Teams',
  'ch.threema.app': 'Threema',
  'com.viber.voip': 'Viber',
  'im.vector.app': 'Element',
  'com.instagram.android': 'Instagram',
  'jp.naver.line.android': 'LINE',
  'com.tencent.mm': 'WeChat',
  'com.samsung.android.messaging': 'Samsung Messages',
};

/// A chat saved on this phone, in any chat app. [preset] null uses the app vibration.
class SavedChat {
  const SavedChat({
    required this.id,
    required this.label,
    required this.packageName,
    this.nameMatch = false,
    this.preset,
  });
  final String id;
  final String label;
  final String packageName;

  /// Matched by exact chat name because the app gave no conversation ID.
  final bool nameMatch;
  final VibrationPreset? preset;

  factory SavedChat.fromMap(Map map) => SavedChat(
    id: map['id'] as String,
    label: map['label'] as String? ?? 'Chat',
    packageName: map['packageName'] as String? ?? 'com.whatsapp',
    nameMatch: map['kind'] == 'name',
    preset: map['preset'] == null ? null : VibrationPreset.parse(map['preset']),
  );
}

/// Messages containing any of [words] get [preset]; [VibrationPreset.mute] silences them.
class KeywordRule {
  const KeywordRule({required this.words, required this.preset});
  final List<String> words;
  final VibrationPreset preset;

  /// Limits enforced by the phone as well.
  static const maxRules = 20, maxWords = 20, maxWordLength = 40;

  factory KeywordRule.fromMap(Map map) => KeywordRule(
    words: [for (final word in map['words'] as List? ?? const []) '$word'],
    preset: VibrationPreset.parse(map['preset']),
  );
  Map<String, Object?> toMap() => {'words': words, 'preset': preset.id};
}

class RelaySettings {
  const RelaySettings({
    this.defaultPreset = VibrationPreset.single,
    this.apps = const {},
    this.chats = const [],
    this.onlySavedChats = const {},
    this.pauseUntil = 0,
    this.quietEnabled = false,
    this.onlyWhileAsleep = false,
    this.startMinute = 1320,
    this.endMinute = 420,
    this.silenceReason,
    this.callAlertsSupported = false,
    this.callAlertsEnabled = false,
    this.callPreset = VibrationPreset.doublePulse,
    this.toneEnabled = false,
    this.toneUrgent,
    this.toneNegative,
    this.toneNeutral,
    this.keywordRules = const [],
  });
  final VibrationPreset defaultPreset;
  final Map<String, AppNotificationRule> apps;
  final List<SavedChat> chats;

  /// Apps where only saved chats buzz.
  final Set<String> onlySavedChats;
  final int pauseUntil;
  final bool quietEnabled;
  final bool onlyWhileAsleep;
  final int startMinute;
  final int endMinute;
  final String? silenceReason;

  final bool callAlertsSupported;
  final bool callAlertsEnabled;
  final VibrationPreset callPreset;

  /// Message tone presets; null keeps the app or chat vibration.
  final bool toneEnabled;
  final VibrationPreset? toneUrgent;
  final VibrationPreset? toneNegative;
  final VibrationPreset? toneNeutral;

  /// Checked in order before message tone; the first match decides.
  final List<KeywordRule> keywordRules;

  /// Whether message text is checked on the phone at all.
  bool get readsMessageText => toneEnabled || keywordRules.isNotEmpty;

  factory RelaySettings.fromMap(Map map) {
    final appMap = map['apps'] as Map? ?? {};
    VibrationPreset? optional(Object? value) =>
        value == null ? null : VibrationPreset.parse(value);
    return RelaySettings(
      defaultPreset: VibrationPreset.parse(map['defaultPreset']),
      apps: appMap.map((key, value) {
        final rule = value as Map;
        return MapEntry(
          key as String,
          AppNotificationRule(
            enabled: rule['enabled'] == true,
            preset: rule['preset'] == null
                ? null
                : VibrationPreset.parse(rule['preset']),
          ),
        );
      }),
      chats: [
        for (final chat in map['chats'] as List? ?? const [])
          SavedChat.fromMap(chat as Map),
      ],
      onlySavedChats: {
        for (final pkg in map['onlySavedChats'] as List? ?? const []) '$pkg',
      },
      pauseUntil: map['pauseUntil'] as int? ?? 0,
      quietEnabled: map['quietEnabled'] == true,
      onlyWhileAsleep: map['onlyWhileAsleep'] == true,
      startMinute: map['startMinute'] as int? ?? 1320,
      endMinute: map['endMinute'] as int? ?? 420,
      silenceReason: map['silenceReason'] as String?,
      callAlertsSupported: map['callAlertsSupported'] == true,
      callAlertsEnabled: map['callAlertsEnabled'] == true,
      callPreset: optional(map['callPreset']) ?? VibrationPreset.doublePulse,
      toneEnabled: map['toneEnabled'] == true,
      toneUrgent: optional(map['toneUrgent']),
      toneNegative: optional(map['toneNegative']),
      toneNeutral: optional(map['toneNeutral']),
      keywordRules: [
        for (final rule in map['keywordRules'] as List? ?? const [])
          KeywordRule.fromMap(rule as Map),
      ],
    );
  }
}
