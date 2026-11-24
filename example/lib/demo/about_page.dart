import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_theme.dart';
import 'demo_controller.dart';
import 'settings_section.dart';

const _repositoryUrl = 'https://github.com/ThoreKoritzius/fitbit-air';

/// What Air Relay is, what to expect, compatibility, and where to get help.
class AboutPage extends StatelessWidget {
  const AboutPage({super.key, required this.controller, required this.perform});
  final DemoController controller;

  /// Runs an action and reports a failure the same way the rest of the app does.
  final Future<void> Function(Future<void> Function()) perform;

  Future<void> _openRepository(BuildContext context) async {
    try {
      await DemoController.channel.invokeMethod<void>('openRepository');
    } on PlatformException {
      await Clipboard.setData(const ClipboardData(text: _repositoryUrl));
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Link copied to the clipboard.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    final tested = FitbitAir.testedFirmware.join(', ');
    return Scaffold(
      appBar: AppBar(title: const Text('About')),
      body: ListView(
        key: const ValueKey('about-scroll'),
        padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
        children: [
          Center(
            child: Column(
              children: [
                const AirRelayMark(size: 72),
                const SizedBox(height: 12),
                Text(
                  'Air Relay',
                  style: theme.textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  'Version ${DemoController.appVersion}',
                  key: const ValueKey('about-version'),
                  style: muted,
                ),
                const SizedBox(height: 12),
                Text(
                  'Turns the notifications you choose into gentle vibrations on your Fitbit Air.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyLarge,
                ),
                const SizedBox(height: 4),
                Text(
                  'An independent project, not affiliated with Google or Fitbit.',
                  textAlign: TextAlign.center,
                  style: muted,
                ),
              ],
            ),
          ),
          const SizedBox(height: 28),
          const SettingsSection(
            title: 'Good to know',
            children: [
              _Fact(
                icon: Icons.schedule,
                title: 'A few seconds’ delay',
                body:
                    'Vibrations usually arrive a few seconds after the notification.',
              ),
              _Fact(
                icon: Icons.bluetooth,
                title: 'Keep the Air nearby',
                body:
                    'It needs to be in Bluetooth range. Missed alerts are not replayed.',
              ),
              _Fact(
                icon: Icons.system_update_outlined,
                title: 'Firmware updates',
                body:
                    'Air Relay uses an undocumented interface of the Air. A firmware update can stop it from working until Air Relay is updated.',
              ),
            ],
          ),
          SettingsSection(
            title: 'Your Air',
            children: [
              AnimatedBuilder(
                animation: controller,
                builder: (context, _) {
                  final firmware = controller.firmware;
                  final ok = controller.firmwareTested;
                  return ListTile(
                    key: const ValueKey('about-firmware'),
                    leading: Icon(
                      ok == false
                          ? Icons.warning_amber_rounded
                          : Icons.verified_outlined,
                      color: ok == false
                          ? theme.colorScheme.error
                          : theme.colorScheme.primary,
                    ),
                    title: Text(
                      firmware == null
                          ? 'Firmware not read yet'
                          : 'Firmware $firmware',
                    ),
                    subtitle: Text(
                      ok == true
                          ? 'Tested with this version'
                          : ok == false
                          ? 'Not tested yet. Tested with $tested.'
                          : 'Tested with $tested',
                    ),
                  );
                },
              ),
            ],
          ),
          SettingsSection(
            title: 'Help and links',
            children: [
              _Link(
                key: const ValueKey('about-updates'),
                icon: Icons.system_update_outlined,
                title: 'Check for updates',
                onTap: () => perform(controller.openReleases),
              ),
              _Link(
                key: const ValueKey('about-report'),
                icon: Icons.bug_report_outlined,
                title: 'Report a problem',
                onTap: () => perform(controller.openIssues),
              ),
              _Link(
                key: const ValueKey('about-privacy'),
                icon: Icons.privacy_tip_outlined,
                title: 'Privacy policy',
                subtitle: 'No internet access. Nothing leaves your phone.',
                onTap: () => perform(controller.openPrivacyPolicy),
              ),
              _Link(
                key: const ValueKey('about-repository'),
                icon: Icons.code,
                title: 'Source code',
                subtitle: 'github.com/ThoreKoritzius/fitbit-air',
                onTap: () => _openRepository(context),
              ),
              _Link(
                key: const ValueKey('about-licenses'),
                icon: Icons.description_outlined,
                title: 'Open-source licenses',
                external: false,
                onTap: () => showLicensePage(
                  context: context,
                  applicationName: 'Air Relay',
                  applicationVersion: DemoController.appVersion,
                  applicationIcon: const Padding(
                    padding: EdgeInsets.all(8),
                    child: AirRelayMark(size: 48),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Center(
            child: Semantics(
              key: const ValueKey('about-credit'),
              label: 'Made with love by Thore (The Centaurus)',
              excludeSemantics: true,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text('Made with ', style: muted),
                  const Icon(
                    Icons.favorite,
                    size: 16,
                    color: Color(0xffe25563),
                  ),
                  Text(' by Thore (The Centaurus)', style: muted),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A short statement with an icon; not tappable.
class _Fact extends StatelessWidget {
  const _Fact({required this.icon, required this.title, required this.body});
  final IconData icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) =>
      ListTile(leading: Icon(icon), title: Text(title), subtitle: Text(body));
}

/// A row that opens something; [external] ones leave the app.
class _Link extends StatelessWidget {
  const _Link({
    super.key,
    required this.icon,
    required this.title,
    required this.onTap,
    this.subtitle,
    this.external = true,
  });
  final IconData icon;
  final String title;
  final String? subtitle;
  final VoidCallback onTap;
  final bool external;

  @override
  Widget build(BuildContext context) => ListTile(
    leading: Icon(icon),
    title: Text(title),
    subtitle: subtitle == null ? null : Text(subtitle!),
    trailing: Icon(
      external ? Icons.open_in_new : Icons.chevron_right,
      size: 20,
    ),
    onTap: onTap,
  );
}
