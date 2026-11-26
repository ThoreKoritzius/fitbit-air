import 'package:flutter/material.dart';

import 'app_theme.dart';

class AirRelayTitle extends StatelessWidget {
  const AirRelayTitle({super.key});

  @override
  Widget build(BuildContext context) => const Row(
    children: [
      AirRelayMark(size: 34),
      SizedBox(width: 12),
      Flexible(child: Text('Air Relay', overflow: TextOverflow.ellipsis)),
    ],
  );
}

class AirRelayNavigation extends StatelessWidget {
  const AirRelayNavigation({
    super.key,
    required this.selectedIndex,
    required this.onDestinationSelected,
    this.home,
  });

  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final NavigationDestination? home;

  @override
  Widget build(BuildContext context) => NavigationBar(
    selectedIndex: selectedIndex,
    onDestinationSelected: onDestinationSelected,
    destinations: [
      home ??
          const NavigationDestination(
            key: ValueKey('nav-relay'),
            icon: Icon(Icons.notifications_outlined),
            selectedIcon: Icon(Icons.notifications_active),
            label: 'Home',
          ),
      const NavigationDestination(
        key: ValueKey('nav-rules'),
        icon: Icon(Icons.tune_outlined),
        selectedIcon: Icon(Icons.tune),
        label: 'Rules',
      ),
      const NavigationDestination(
        key: ValueKey('nav-device'),
        icon: Icon(Icons.watch_outlined),
        selectedIcon: Icon(Icons.watch),
        label: 'Device',
      ),
    ],
  );
}
