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

/// A WhatsApp chat saved on this phone. [preset] null uses the app vibration.
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

  /// Matched by exact chat name because WhatsApp gave no conversation ID.
  final bool nameMatch;
  final VibrationPreset? preset;
  bool get business => packageName == 'com.whatsapp.w4b';

  factory SavedChat.fromMap(Map map) => SavedChat(
    id: map['id'] as String,
    label: map['label'] as String? ?? 'Chat',
    packageName: map['packageName'] as String? ?? 'com.whatsapp',
    nameMatch: map['kind'] == 'name',
    preset: map['preset'] == null ? null : VibrationPreset.parse(map['preset']),
  );
}

class RelaySettings {
  const RelaySettings({
    this.defaultPreset = VibrationPreset.single,
    this.apps = const {},
    this.chats = const [],
    this.everyoneElseSilent = false,
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
  });
  final VibrationPreset defaultPreset;
  final Map<String, AppNotificationRule> apps;
  final List<SavedChat> chats;

  /// Only saved WhatsApp chats may vibrate.
  final bool everyoneElseSilent;
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
      everyoneElseSilent: map['everyoneElseSilent'] == true,
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
    );
  }
}
