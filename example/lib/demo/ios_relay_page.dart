import 'package:fitbit_air/fitbit_air.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_chrome.dart';
import 'app_theme.dart';
import 'relay_status.dart' show StatusLevel;
import 'status_style.dart';

class IosRelayPage extends StatefulWidget {
  const IosRelayPage({super.key});

  @override
  State<IosRelayPage> createState() => _IosRelayPageState();
}

class _IosRelayPageState extends State<IosRelayPage> {
  static const _platform = MethodChannel('fitbit_air');
  int _tab = 0;
  bool _sending = false;
  bool? _shortcutsAvailable;
  String? _result;
  bool _verified = false;

  @override
  void initState() {
    super.initState();
    _checkShortcuts();
  }

  Future<void> _checkShortcuts() async {
    bool available;
    try {
      available =
          await _platform.invokeMethod<bool>('iosNotificationAutomation') ??
          false;
    } on PlatformException {
      available = false;
    } on MissingPluginException {
      available = false;
    }
    if (mounted) setState(() => _shortcutsAvailable = available);
  }

  Future<void> _tap() async {
    setState(() {
      _sending = true;
      _verified = false;
      _result = null;
    });
    String feedback;
    bool verified = false;
    try {
      final result = await FitbitAir.tap();
      verified = result.verified;
      feedback = verified
          ? 'Vibration verified'
          : 'The Air did not confirm the vibration.';
    } on FitbitAirException catch (error) {
      feedback = error.message;
    } on MissingPluginException {
      feedback = 'The Fitbit connection is unavailable in this build.';
    }
    if (!mounted) return;
    setState(() {
      _sending = false;
      _verified = verified;
      _result = feedback;
    });
  }

  Future<void> _openShortcuts() async {
    bool opened;
    try {
      opened = await _platform.invokeMethod<bool>('openIosShortcuts') ?? false;
    } on PlatformException {
      opened = false;
    } on MissingPluginException {
      opened = false;
    }
    if (!opened && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Open Shortcuts from your Home Screen.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const AirRelayTitle(),
      actions: [
        IconButton(
          tooltip: 'About Air Relay',
          icon: const Icon(Icons.info_outline),
          onPressed: () => showAboutDialog(
            context: context,
            applicationName: 'Air Relay',
            applicationIcon: const AirRelayMark(size: 48),
            children: const [
              Text('An independent notification companion for Fitbit Air.'),
            ],
          ),
        ),
      ],
    ),
    body: SafeArea(
      bottom: false,
      child: ListView(
        key: PageStorageKey('ios-relay-tab-$_tab'),
        padding: const EdgeInsets.all(20),
        children: switch (_tab) {
          1 => _rules(context),
          2 => _device(context),
          _ => _home(context),
        },
      ),
    ),
    bottomNavigationBar: AirRelayNavigation(
      selectedIndex: _tab,
      onDestinationSelected: (tab) => setState(() => _tab = tab),
    ),
  );

  List<Widget> _home(BuildContext context) {
    final theme = Theme.of(context);
    return [
      Card(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            children: [
              StatusEmblem(
                level: _sending
                    ? StatusLevel.busy
                    : _verified
                    ? StatusLevel.active
                    : StatusLevel.idle,
              ),
              const SizedBox(height: 16),
              Text('Fitbit Air', style: theme.textTheme.titleSmall),
              const SizedBox(height: 6),
              Text('Notification relay', style: theme.textTheme.headlineSmall),
              const SizedBox(height: 8),
              Text(
                'Use Shortcuts to send selected iPhone alerts to your Air.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium,
              ),
              const SizedBox(height: 24),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: _sending ? null : _tap,
                  icon: const Icon(Icons.vibration),
                  label: Text(_sending ? 'Sending…' : 'Test vibration'),
                ),
              ),
              if (_result case final result?) ...[
                const SizedBox(height: 12),
                Semantics(
                  liveRegion: true,
                  child: Text(result, textAlign: TextAlign.center),
                ),
              ],
            ],
          ),
        ),
      ),
      const SizedBox(height: 16),
      Card(
        child: ListTile(
          leading: const Icon(Icons.tune),
          title: const Text('Notification rules'),
          subtitle: const Text('Set up iPhone alerts in Shortcuts'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => setState(() => _tab = 1),
        ),
      ),
    ];
  }

  List<Widget> _rules(BuildContext context) => [
    Text('Rules', style: Theme.of(context).textTheme.headlineMedium),
    const SizedBox(height: 16),
    Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Notification automation',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            if (_shortcutsAvailable == false)
              const Text('Notification automations require iOS 27.')
            else ...[
              const Text(
                '1. In Shortcuts, go to Automation and add a Notification trigger.\n\n'
                '2. Pick the app and any words you want to match. Choose Run Immediately.\n\n'
                '3. Add the Tap Fitbit Air action.',
              ),
              const SizedBox(height: 16),
              FilledButton.tonalIcon(
                onPressed: _shortcutsAvailable == true ? _openShortcuts : null,
                icon: const Icon(Icons.open_in_new),
                label: const Text('Open Shortcuts'),
              ),
            ],
          ],
        ),
      ),
    ),
  ];

  List<Widget> _device(BuildContext context) => [
    Text('Device', style: Theme.of(context).textTheme.headlineMedium),
    const SizedBox(height: 16),
    Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const StatusEmblem(level: StatusLevel.idle, size: 56),
                const SizedBox(width: 16),
                Expanded(
                  child: Text(
                    'Fitbit Air',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            const Text(
              'Pair your Air in Google Health, let a sync finish, then use '
              'Test vibration to check the connection.',
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: _sending
                  ? null
                  : () {
                      setState(() => _tab = 0);
                      _tap();
                    },
              icon: const Icon(Icons.vibration),
              label: const Text('Test vibration'),
            ),
          ],
        ),
      ),
    ),
  ];
}
