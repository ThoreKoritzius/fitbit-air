/// Build-time switch for the Google Play build.
///
/// `flutter build appbundle --dart-define=AIR_RELAY_STORE=true` leaves out the
/// Developer tools (the research Lab and the wrist-presence trial) so the
/// store build only contains the notification relay. The GitHub APK and
/// debug builds keep them.
const bool kStoreBuild = bool.fromEnvironment('AIR_RELAY_STORE');
