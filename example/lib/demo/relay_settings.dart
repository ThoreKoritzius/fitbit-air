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

class RelaySettings {
  const RelaySettings({
    this.defaultPreset = VibrationPreset.single,
    this.apps = const {},
    this.chats = const {},
    this.pauseUntil = 0,
    this.quietEnabled = false,
    this.startMinute = 1320,
    this.endMinute = 420,
    this.silenceReason,
  });
  final VibrationPreset defaultPreset;
  final Map<String, AppNotificationRule> apps;
  final Map<String, VibrationPreset> chats;
  final int pauseUntil;
  final bool quietEnabled;
  final int startMinute;
  final int endMinute;
  final String? silenceReason;

  factory RelaySettings.fromMap(Map map) {
    final appMap = map['apps'] as Map? ?? {};
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
      chats: (map['chats'] as Map? ?? {}).map(
        (key, value) => MapEntry(key as String, VibrationPreset.parse(value)),
      ),
      pauseUntil: map['pauseUntil'] as int? ?? 0,
      quietEnabled: map['quietEnabled'] == true,
      startMinute: map['startMinute'] as int? ?? 1320,
      endMinute: map['endMinute'] as int? ?? 420,
      silenceReason: map['silenceReason'] as String?,
    );
  }
}
