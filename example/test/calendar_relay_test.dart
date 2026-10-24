import 'dart:async';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:fitbit_air_example/demo/relay_settings.dart';
import 'package:fitbit_air_example/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = DemoController.channel;
  const device = MethodChannel('fitbit_air');
  var access = false;
  var running = false;
  var bluetoothOn = true;
  bool? readyOverride;
  var stopping = false;
  var failStart = false;
  Completer<void>? startAcknowledgement;
  Completer<void>? tapAcknowledgement;
  var setupCompleted = true;
  var setupStep = 0;
  var controlsAllowed = true;
  var denyControls = false;
  var calendarInstalled = true;
  var paired = true;
  var deferStop = false;
  var nameCandidate = false;
  var pulseStyle = 'single';
  Map<Object?, Object?>? lastSelection;
  Object? lastTapArguments;
  late List<Map<String, Object?>> history;
  late List<String> calls;
  late List<String> selected;
  late DemoController controller;
  late Map<String, Object?> conversations;
  late Map<String, Object?> rules;

  setUp(() {
    bluetoothOn = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('fitbit_air/connection'),
          (call) async => null,
        );

    access = false;
    running = false;
    readyOverride = null;
    stopping = false;
    failStart = false;
    startAcknowledgement = null;
    tapAcknowledgement = null;
    setupCompleted = true;
    setupStep = 0;
    controlsAllowed = true;
    denyControls = false;
    calendarInstalled = true;
    paired = true;
    deferStop = false;
    nameCandidate = false;
    pulseStyle = 'single';
    rules = {
      'defaultPreset': 'single',
      'apps': <String, Object?>{},
      'chats': <Map<String, Object?>>[],
      'everyoneElseSilent': false,
      'pauseUntil': 0,
      'quietEnabled': false,
      'startMinute': 1320,
      'endMinute': 420,
    };
    lastSelection = null;
    lastTapArguments = null;
    conversations = {
      'captureRemainingMillis': 0,
      'unsupported': 0,
      'candidates': <Map<String, Object?>>[],
    };
    history = [];
    calls = [];
    selected = ['com.google.android.calendar'];
    controller = DemoController();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          switch (call.method) {
            case 'status':
              return {
                'setupCompleted': setupCompleted,
                'setupStep': setupStep,
                'notificationControlsAllowed': controlsAllowed,
                'access': access,
                'connected': access,
                'running': running,
                'ready': readyOverride ?? running,
                'stopping': stopping && running,
                'selectedApps': selected,
                'pulseStyle': pulseStyle,
                'rules': {...rules, 'defaultPreset': pulseStyle},
                'bluetoothEnabled': bluetoothOn,
                'bluetoothPermission': true,
                'history': history,
                'conversations': conversations,
              };
            case 'saveSetupStep':
              setupStep = (call.arguments as Map)['step'] as int;
              return null;
            case 'allowNotificationControls':
              if (!denyControls) controlsAllowed = true;
              return null;
            case 'notificationControlsSettings':
              return null;
            case 'completeSetup':
              setupCompleted = true;
              return null;
            case 'setEveryoneElseSilent':
              rules['everyoneElseSilent'] = (call.arguments as Map)['silent'];
              return null;
            case 'captureConversations':
              conversations['captureRemainingMillis'] =
                  (call.arguments as Map)['enabled'] == true ? 60000 : 0;
              conversations['candidates'] = [
                {
                  'id': 'abcdef012345',
                  'label': 'Private Alice',
                  'packageName': 'com.whatsapp',
                  'kind': nameCandidate ? 'name' : 'shortcut',
                },
              ];
              return null;
            case 'saveChat':
              final args = call.arguments as Map;
              lastSelection = Map<Object?, Object?>.from(args);
              final chats = rules['chats'] as List;
              final index = chats.indexWhere(
                (chat) => (chat as Map)['id'] == args['id'],
              );
              if (index >= 0) {
                (chats[index] as Map)['preset'] = args['preset'];
              } else {
                final found = (conversations['candidates'] as List)
                    .cast<Map>()
                    .firstWhere((chat) => chat['id'] == args['id']);
                chats.add(
                  Map<String, Object?>.from(found)..['preset'] = args['preset'],
                );
              }
              return null;
            case 'removeChat':
              (rules['chats'] as List).removeWhere(
                (chat) => (chat as Map)['id'] == (call.arguments as Map)['id'],
              );
              return null;
            case 'apps':
              return [
                if (calendarInstalled)
                  {
                    'packageName': 'com.google.android.calendar',
                    'label': 'Google Calendar',
                  },
                {'packageName': 'com.whatsapp', 'label': 'WhatsApp'},
                {
                  'packageName': 'org.thoughtcrime.securesms',
                  'label': 'Signal',
                },
              ];
            case 'saveAppRule':
              final args = call.arguments as Map;
              final pkg = args['packageName'] as String;
              (rules['apps'] as Map)[pkg] = {
                'enabled': args['enabled'],
                'preset': args['preset'],
              };
              if (args['enabled'] == true) {
                selected = {...selected, pkg}.toList();
              } else {
                selected.remove(pkg);
              }
              return null;
            case 'saveDefaultPreset':
              pulseStyle = (call.arguments as Map)['preset'] as String;
              return null;
            case 'setPause':
              final minutes = (call.arguments as Map)['minutes'] as int;
              rules['pauseUntil'] = minutes == -1
                  ? -1
                  : minutes == 0
                  ? 0
                  : DateTime.now().millisecondsSinceEpoch + minutes * 60000;
              rules['silenceReason'] = minutes == 0 ? null : 'paused';
              return null;
            case 'saveToneRules':
              final args = call.arguments as Map;
              rules.addAll({
                'toneEnabled': args['enabled'],
                'toneUrgent': args['urgent'],
                'toneNegative': args['negative'],
                'toneNeutral': args['neutral'],
              });
              return null;
            case 'saveQuietHours':
              final args = call.arguments as Map;
              rules.addAll({
                'quietEnabled': args['enabled'],
                'startMinute': args['startMinute'],
                'endMinute': args['endMinute'],
              });
              return null;
            case 'previewPreset':
              return null;
            case 'setPulseStyle':
              pulseStyle = (call.arguments as Map)['style'] as String;
              return null;
            case 'selectApps':
              selected = List<String>.from(
                (call.arguments as Map)['packages'] as List,
              );
              return null;
            case 'labSettings':
              return {'secureDurationSeconds': 60};
            case 'setLabSettings':
              return null;
            case 'start':
              if (failStart) {
                throw PlatformException(code: 'BLUETOOTH_PERMISSION');
              }
              running = true;
              await startAcknowledgement?.future;
              return null;
            case 'stop':
              stopping = deferStop;
              if (!deferStop) running = false;
              return null;
            case 'settings':
            case 'openRepository':
            case 'bluetoothSettings':
            case 'clearHistory':
            case 'testPulse':
              return null;
          }
          throw MissingPluginException();
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(device, (call) async {
          calls.add(call.method);
          if (call.method == 'isAirPaired') return paired;
          if (call.method == 'pulse') {
            return {
              'previousIntensity': 2,
              'currentIntensity': 3,
              'changed': true,
              'verified': true,
            };
          }
          if (call.method == 'vibrate') {
            return {'originalIntensity': 2, 'restored': true, 'verified': true};
          }
          if (call.method == 'tap') {
            await tapAcknowledgement?.future;
            lastTapArguments = call.arguments;
            return {'intensity': 3, 'verified': true};
          }
          throw MissingPluginException();
        });
  });

  tearDown(() {
    controller.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(device, null);
  });

  Future<void> mount(WidgetTester tester) async {
    await tester.pumpWidget(DemoApp(controller: controller));
    await tester.pumpAndSettle();
  }

  Future<void> tap(WidgetTester tester, String key) async {
    if (key == 'nav-lab') {
      await tester.tap(find.byKey(const ValueKey('nav-device')));
      await tester.pumpAndSettle();
    }
    if (key == 'app-picker' ||
        key == 'conversation-picker' ||
        (key == 'add-chat' &&
            find.byKey(const ValueKey('everyone-else')).evaluate().isEmpty)) {
      await tester.tap(find.byKey(const ValueKey('nav-rules')));
      await tester.pumpAndSettle();
      if (key == 'app-picker') return;
      if (key == 'add-chat') {
        await tester.tap(find.byKey(const ValueKey('conversation-picker')));
        await tester.pumpAndSettle();
      }
    }
    final target = find.byKey(ValueKey(key));
    if (target.evaluate().isEmpty) {
      await tester.scrollUntilVisible(
        target,
        200,
        scrollable: find.byType(BottomSheet).evaluate().isNotEmpty
            ? find
                  .descendant(
                    of: find.byType(BottomSheet),
                    matching: find.byType(Scrollable),
                  )
                  .first
            : find.byType(Scrollable).first,
      );
    }
    await tester.ensureVisible(target);
    await tester.pumpAndSettle();
    await tester.tap(target);
    await tester.pumpAndSettle();
  }

  testWidgets('app header fits narrow screens with large system text', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 720);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.6;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await mount(tester);
    expect(
      find.byKey(const ValueKey('about-air-relay')).hitTestable(),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
    await tap(tester, 'about-air-relay');
    expect(
      find.textContaining('independent notification companion'),
      findsOneWidget,
    );
    expect(find.text('github.com/ThoreKoritzius/fitbit-air'), findsOneWidget);
    expect(
      find.bySemanticsLabel('Made with love by Thore (The Centaurus)'),
      findsOneWidget,
    );
    await tester.ensureVisible(find.byKey(const ValueKey('about-repository')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('about-repository')));
    await tester.pump();
    expect(calls, contains('openRepository'));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('requires notification access and keeps setup actionable', (
    tester,
  ) async {
    await mount(tester);
    expect(find.byKey(const ValueKey('relay-toggle')), findsNothing);
    await tap(tester, 'notification-access-settings');
    await tester.pumpAndSettle();
    expect(find.text('Read notifications'), findsOneWidget);
    expect(calls, isNot(contains('settings')));
    await tap(tester, 'notification-access-settings');
    expect(calls, contains('settings'));
    expect(calls, isNot(contains('start')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'guided setup checks pairing, explains access and confirms a felt test',
    (tester) async {
      setupCompleted = false;
      await mount(tester);
      expect(find.text('Connect your Air'), findsOneWidget);
      expect(find.byKey(const ValueKey('setup-next')), findsNothing);
      await tap(tester, 'setup-check-air');
      expect(calls, contains('isAirPaired'));
      await tap(tester, 'setup-next');
      expect(find.text('Read notifications'), findsOneWidget);
      expect(calls, isNot(contains('settings')));
      expect(find.byKey(const ValueKey('setup-next')), findsNothing);
      await tap(tester, 'notification-access-settings');
      access = true;
      await controller.refresh();
      await tester.pumpAndSettle();
      await tap(tester, 'setup-next');
      expect(find.byKey(const ValueKey('setup-app-search')), findsOneWidget);
      await tap(tester, 'setup-app-com.whatsapp');
      expect(selected, contains('com.whatsapp'));
      await tap(tester, 'setup-next');
      await tap(tester, 'setup-next');
      expect(find.text('Did you feel it?'), findsOneWidget);
      expect(calls, isNot(contains('completeSetup')));
      expect(calls.where((call) => call == 'tap'), hasLength(1));
      await tap(tester, 'setup-next');
      expect(find.text('Setup complete'), findsOneWidget);
      expect(setupCompleted, isTrue);
      expect(calls, isNot(contains('start')));
      await tap(tester, 'setup-next');
      expect(find.byKey(const ValueKey('guided-setup')), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('setup can finish without a test and never claims one was felt', (
    tester,
  ) async {
    access = true;
    setupCompleted = false;
    await mount(tester);
    await tap(tester, 'setup-check-air');
    await tap(tester, 'setup-next');
    await tap(tester, 'setup-next');
    await tap(tester, 'setup-next');
    await tap(tester, 'setup-skip-test');
    expect(find.text('Setup complete'), findsOneWidget);
    expect(
      find.textContaining('You can test a vibration from Home'),
      findsOneWidget,
    );
    expect(calls, isNot(contains('tap')));
    expect(calls, isNot(contains('start')));
    expect(setupCompleted, isTrue);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('closing setup never marks it complete or starts notifications', (
    tester,
  ) async {
    setupCompleted = false;
    await mount(tester);
    await tap(tester, 'close-setup');
    expect(setupCompleted, isFalse);
    expect(calls, isNot(contains('completeSetup')));
    expect(calls, isNot(contains('start')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'first launch requests the separate app notification permission before continuing',
    (tester) async {
      setupCompleted = false;
      controlsAllowed = false;
      access = true;
      await mount(tester);
      expect(find.byKey(const ValueKey('nav-relay')), findsNothing);
      await tap(tester, 'setup-check-air');
      await tap(tester, 'setup-next');
      expect(find.byKey(const ValueKey('setup-next')), findsNothing);
      expect(find.text('Show Air Relay notifications'), findsOneWidget);
      await tap(tester, 'setup-notification-controls');
      expect(calls, contains('allowNotificationControls'));
      expect(controller.notificationControlsAllowed, isTrue);
      await tap(tester, 'setup-next');
      expect(find.byKey(const ValueKey('setup-app-search')), findsOneWidget);
      expect(calls, isNot(contains('start')));
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('denied app notifications offer settings or an explicit skip', (
    tester,
  ) async {
    setupCompleted = false;
    controlsAllowed = false;
    denyControls = true;
    access = true;
    await mount(tester);
    await tap(tester, 'setup-check-air');
    await tap(tester, 'setup-next');
    await tap(tester, 'setup-notification-controls');
    expect(find.text('Open notification settings'), findsOneWidget);
    await tap(tester, 'setup-notification-controls');
    expect(calls, contains('notificationControlsSettings'));
    expect(
      calls.where((call) => call == 'allowNotificationControls'),
      hasLength(1),
    );
    await tap(tester, 'setup-skip-controls');
    await tap(tester, 'setup-next');
    expect(controller.notificationControlsAllowed, isFalse);
    expect(find.byKey(const ValueKey('setup-app-search')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'unfinished setup resumes on relaunch and does not reopen on polls',
    (tester) async {
      setupCompleted = false;
      await mount(tester);
      await tap(tester, 'setup-check-air');
      await tap(tester, 'setup-next');
      expect(setupStep, 1);
      await tap(tester, 'close-setup');
      await controller.refresh();
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('guided-setup')), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      await mount(tester);
      expect(find.text('Read notifications'), findsOneWidget);
      expect(find.byKey(const ValueKey('nav-relay')), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('setup requires a selected app that is actually installed', (
    tester,
  ) async {
    setupCompleted = false;
    access = true;
    calendarInstalled = false;
    await mount(tester);
    await tap(tester, 'setup-check-air');
    await tap(tester, 'setup-next');
    await tap(tester, 'setup-next');
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('setup-next')))
          .onPressed,
      isNull,
    );
    await tap(tester, 'setup-app-com.whatsapp');
    expect(controller.selectedAppsSummary, '1 app selected');
    await tap(tester, 'setup-next');
    expect(find.text('Try a vibration'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'start and stop hold their heading, dimensions and colors until confirmed',
    (tester) async {
      access = true;
      readyOverride = false;
      startAcknowledgement = Completer<void>();
      await mount(tester);
      final toggle = find.byKey(const ValueKey('relay-toggle'));
      Color? background(Set<WidgetState> states) => tester
          .widget<FilledButton>(toggle)
          .style!
          .backgroundColor!
          .resolve(states);
      final initialRect = tester.getRect(toggle);
      final startColor = background({});
      expect(find.text('Notifications off'), findsOneWidget);
      await tester.tap(toggle);
      await tester.pump();
      expect(find.byKey(const ValueKey('relay-toggle-progress')), findsNothing);
      await tester.pump(const Duration(milliseconds: 150));
      expect(find.text('Notifications off'), findsOneWidget);
      expect(find.text('Starting…'), findsOneWidget);
      expect(tester.widget<FilledButton>(toggle).onPressed, isNull);
      expect(background({WidgetState.disabled}), startColor);
      expect(tester.getRect(toggle), initialRect);
      startAcknowledgement!.complete();
      await tester.pump();
      expect(
        find.byKey(const ValueKey('relay-toggle-progress')),
        findsOneWidget,
      );
      await tester.pump(const Duration(milliseconds: 150));
      expect(controller.changingRelay, isFalse);
      expect(controller.relayTransition, RelayTransition.starting);
      expect(find.text('Notifications off'), findsOneWidget);
      expect(find.text('Starting…'), findsOneWidget);
      expect(calls.where((call) => call == 'start'), hasLength(1));
      readyOverride = true;
      await controller.refresh();
      await tester.pumpAndSettle();
      expect(find.text('Notifications on'), findsOneWidget);
      expect(find.byKey(const ValueKey('relay-toggle-progress')), findsNothing);
      expect(tester.getRect(toggle), initialRect);
      final stopColor = background({});
      expect(stopColor, isNot(startColor));
      deferStop = true;
      await tester.tap(toggle);
      await tester.pump();
      expect(find.byKey(const ValueKey('relay-toggle-progress')), findsNothing);
      await tester.pump(const Duration(milliseconds: 150));
      expect(find.text('Notifications on'), findsOneWidget);
      expect(find.text('Stopping…'), findsOneWidget);
      expect(background({WidgetState.disabled}), stopColor);
      expect(tester.getRect(toggle), initialRect);
      expect(tester.widget<FilledButton>(toggle).onPressed, isNull);
      running = false;
      stopping = false;
      readyOverride = false;
      await controller.refresh();
      await tester.pumpAndSettle();
      expect(find.text('Notifications off'), findsOneWidget);
      expect(tester.getRect(toggle), initialRect);
      expect(background({}), startColor);
      expect(calls.where((call) => call == 'stop'), hasLength(1));
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'Start accepts a tap during Try it and waits for its cleanup before starting once',
    (tester) async {
      access = true;
      tapAcknowledgement = Completer<void>();
      await mount(tester);
      await tester.tap(find.byKey(const ValueKey('test-vibration')));
      await tester.pump();
      expect(find.text('Sending…'), findsOneWidget);
      final toggle = find.byKey(const ValueKey('relay-toggle'));
      expect(tester.widget<FilledButton>(toggle).onPressed, isNotNull);
      await tester.tap(toggle);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 150));
      expect(find.text('Starting…'), findsOneWidget);
      expect(find.text('Finishing your test first…'), findsOneWidget);
      expect(calls.where((c) => c == 'start'), isEmpty);
      await tester.tap(toggle);
      tapAcknowledgement!.complete();
      await tester.pumpAndSettle();
      expect(find.text('Notifications on'), findsOneWidget);
      expect(calls.where((c) => c == 'start'), hasLength(1));
      expect(find.text('Finishing your test first…'), findsNothing);
      expect(find.byKey(const ValueKey('error-bar')), findsNothing);
    },
  );

  testWidgets('failed start returns to a usable off state without retrying', (
    tester,
  ) async {
    access = true;
    failStart = true;
    await mount(tester);
    await tap(tester, 'relay-toggle');
    expect(find.text('Notifications off'), findsOneWidget);
    expect(controller.relayTransition, isNull);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const ValueKey('relay-toggle')))
          .onPressed,
      isNotNull,
    );
    expect(find.byKey(const ValueKey('relay-error')), findsOneWidget);
    expect(calls.where((call) => call == 'start'), hasLength(1));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('default preset can change while relay is running', (
    tester,
  ) async {
    access = true;
    running = true;
    await mount(tester);
    await tap(tester, 'nav-rules');
    await tap(tester, 'default-vibration');
    await tap(tester, 'preset-double');
    await tap(tester, 'save-rule');
    expect(controller.settings.defaultPreset, VibrationPreset.doublePulse);
    expect(running, isTrue);
    expect(calls, isNot(contains('stop')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('does not start without a paired Air', (tester) async {
    access = true;
    paired = false;
    await mount(tester);
    await tap(tester, 'relay-toggle');
    expect(calls, isNot(contains('start')));
    expect(controller.setupError, contains('Pair the Air'));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('refreshes access after returning from settings', (tester) async {
    access = true;
    running = true;
    await mount(tester);
    expect(controller.relayRunning, isTrue);
    access = false;
    running = false;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(controller.relayRunning, isFalse);
    await tester.scrollUntilVisible(
      find.text('Allow notification access'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('Allow notification access'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('switching to Lab never silently stops the relay', (
    tester,
  ) async {
    access = true;
    running = true;
    await mount(tester);
    await tap(tester, 'nav-lab');
    expect(controller.secureDurationSeconds, 60);
    expect(find.byKey(const ValueKey('secure-duration')), findsOneWidget);
    expect(calls, isNot(contains('stop')));
    expect(controller.relayRunning, isTrue);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('exclusive actions require an explicit relay stop', (
    tester,
  ) async {
    access = true;
    running = true;
    await mount(tester);
    await tap(tester, 'nav-device');
    await tap(tester, 'action-pulse');
    expect(find.byKey(const ValueKey('keep-relay')), findsOneWidget);
    expect(calls, isNot(contains('pulse')));
    await tap(tester, 'keep-relay');
    expect(running, isTrue);
    await tap(tester, 'action-pulse');
    await tap(tester, 'stop-relay-for-operation');
    expect(calls.indexOf('stop'), lessThan(calls.indexOf('pulse')));
    expect(controller.trials.first['outcome'], 'completed');
    expect(running, isFalse);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('app picker supports search and saved selections', (
    tester,
  ) async {
    await mount(tester);
    await tap(tester, 'app-picker');
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('app-search')),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.ensureVisible(find.byKey(const ValueKey('app-search')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const ValueKey('app-search')), 'whats');
    await tester.pumpAndSettle();
    expect(controller.filteredApps.single['packageName'], 'com.whatsapp');
    await tap(tester, 'app-enabled-com.whatsapp');
    expect(
      selected,
      containsAll(['com.google.android.calendar', 'com.whatsapp']),
    );
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'app toggles keep rows stable until regrouping or reopening Rules',
    (tester) async {
      tester.view.physicalSize = const Size(900, 1800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await mount(tester);
      await tap(tester, 'nav-rules');
      final whatsapp = find.byKey(const ValueKey('app-com.whatsapp'));
      final signal = find.byKey(
        const ValueKey('app-org.thoughtcrime.securesms'),
      );
      double y(Finder row) => tester.getTopLeft(row).dy;
      expect(y(whatsapp), greaterThan(y(signal)));
      final before = y(whatsapp);
      await tap(tester, 'app-enabled-com.whatsapp');
      expect(selected, contains('com.whatsapp'));
      expect(y(whatsapp), before);
      await tap(tester, 'app-enabled-com.whatsapp');
      expect(selected, isNot(contains('com.whatsapp')));
      expect(y(whatsapp), before);
      await tap(tester, 'app-enabled-com.whatsapp');
      await tap(tester, 'sort-selected-apps');
      expect(y(whatsapp), lessThan(y(signal)));
      await tap(tester, 'app-enabled-com.whatsapp');
      expect(y(whatsapp), lessThan(y(signal)));
      await tap(tester, 'nav-relay');
      await tap(tester, 'nav-rules');
      expect(y(whatsapp), greaterThan(y(signal)));
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('requires a nonempty allowlist before starting', (tester) async {
    access = true;
    selected = [];
    await mount(tester);
    expect(find.byKey(const ValueKey('relay-recovery')), findsOneWidget);
    expect(calls, isNot(contains('start')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'relay test uses the running worker rather than another connection',
    (tester) async {
      access = true;
      running = true;
      await mount(tester);
      await tap(tester, 'test-vibration');
      expect(calls, contains('previewPreset'));
      expect(calls, isNot(contains('vibrate')));
      expect(running, isTrue);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('test vibration follows the selected style when relay is off', (
    tester,
  ) async {
    access = true;
    await mount(tester);
    await tap(tester, 'test-vibration');
    expect(calls, contains('tap'));
    expect(calls, isNot(contains('vibrate')));
    expect(find.text('Sent'), findsOneWidget);
    expect(find.byKey(const ValueKey('operation-owner')), findsNothing);
    await tester.pump(const Duration(seconds: 3));
    expect(find.text('Try it'), findsOneWidget);
    await controller.setPulseStyle('double');
    await tester.pumpAndSettle();
    calls.clear();
    await tap(tester, 'test-vibration');
    expect(calls, contains('vibrate'));
    expect(calls, isNot(contains('tap')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'device controls use the same named actions as the test harness',
    (tester) async {
      await mount(tester);
      await controller.runAction('preview');
      expect(calls.where((value) => value == 'vibrate'), hasLength(1));
      expect(controller.trials.single['operation'], 'preview');
      expect(controller.trials.single['outcome'], 'completed');
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('exclusive operation waits for in-flight relay cleanup', (
    tester,
  ) async {
    access = true;
    running = true;
    deferStop = true;
    await mount(tester);
    await tap(tester, 'nav-device');
    await tap(tester, 'action-pulse');
    await tap(tester, 'stop-relay-for-operation');
    expect(calls, contains('stop'));
    expect(calls, isNot(contains('pulse')));
    running = false;
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(calls, contains('pulse'));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('history distinguishes verification and uncertain failure', (
    tester,
  ) async {
    history = [
      {
        'commandId': 'one',
        'sourcePackage': 'com.whatsapp',
        'stage': 'verified',
        'atMillis': 1000,
        'latencyMillis': 125,
        'preset': 'double',
        'tone': 'urgent',
      },
      {
        'commandId': 'two',
        'sourcePackage': 'com.whatsapp',
        'stage': 'failed',
        'atMillis': 2000,
        'errorCode': 'TIMEOUT',
      },
    ];
    await mount(tester);
    final target = find.byKey(const ValueKey('activity-one-verified'));
    await tester.scrollUntilVisible(
      target,
      300,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
    expect(
      find.descendant(of: target, matching: find.text('WhatsApp')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: target,
        matching: find.textContaining('Sent · Two pulses · Urgent'),
      ),
      findsOneWidget,
    );
    expect(controller.history, hasLength(2));
    expect(controller.exportJson(), contains('TIMEOUT'));
    expect(controller.exportJson(), contains('"preset": "double"'));
    expect(controller.exportJson(), isNot(contains('urgent')));
    expect(controller.history.first['tone'], 'urgent');
    await tap(tester, 'activity-two-failed');
    expect(find.textContaining('delivery is uncertain'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets(
    'find and add chats while the relay runs without leaking report identities',
    (tester) async {
      access = true;
      running = true;
      selected.add('com.whatsapp');
      await mount(tester);
      await tap(tester, 'add-chat');
      expect(controller.captureRemainingMillis, 60000);
      expect(find.text('Listening for new messages…'), findsOneWidget);
      await tap(tester, 'add-abcdef012345');
      expect(controller.settings.chats.single.label, 'Private Alice');
      expect(find.textContaining('Added · App vibration'), findsOneWidget);
      expect(controller.exportJson(), isNot(contains('Private Alice')));
      expect(controller.exportJson(), isNot(contains('abcdef012345')));
      expect(controller.report()['relay'], containsPair('savedChatCount', 1));
      await tap(tester, 'close-add-chat');
      expect(controller.captureRemainingMillis, 0);
      expect(find.byKey(const ValueKey('chat-abcdef012345')), findsOneWidget);
      // Edits apply live: the relay was never stopped.
      expect(running, isTrue);
      expect(calls, isNot(contains('stop')));
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('everyone else can be silenced without stopping the relay', (
    tester,
  ) async {
    access = true;
    running = true;
    await mount(tester);
    await tap(tester, 'conversation-picker');
    expect(find.text('Vibrates with the app vibration'), findsOneWidget);
    await tap(tester, 'everyone-else');
    expect(controller.settings.everyoneElseSilent, isTrue);
    expect(find.textContaining('No WhatsApp chat can vibrate'), findsOneWidget);
    expect(
      controller.report()['relay'],
      containsPair('everyoneElseSilent', true),
    );
    expect(running, isTrue);
    expect(calls, isNot(contains('stop')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('name-matched chats are added without a dialog and explained', (
    tester,
  ) async {
    access = true;
    nameCandidate = true;
    await mount(tester);
    await tap(tester, 'add-chat');
    expect(find.text('Matched by name'), findsOneWidget);
    await tap(tester, 'add-abcdef012345');
    expect(lastSelection, {'id': 'abcdef012345', 'preset': null});
    expect(controller.settings.chats.single.nameMatch, isTrue);
    await tap(tester, 'close-add-chat');
    expect(find.textContaining('"Matched by name" means'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('removing a saved chat asks first', (tester) async {
    access = true;
    rules['chats'] = [
      {
        'id': 'private-chat',
        'label': 'Alice',
        'packageName': 'com.whatsapp',
        'kind': 'shortcut',
        'preset': 'high',
      },
    ];
    await mount(tester);
    await tap(tester, 'conversation-picker');
    expect(find.textContaining('One pulse · High'), findsOneWidget);
    await tap(tester, 'remove-chat-private-chat');
    await tap(tester, 'confirm-remove-chat');
    expect(controller.settings.chats, isEmpty);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('Home pause and resume preserve the running relay', (
    tester,
  ) async {
    access = true;
    running = true;
    await mount(tester);
    await tap(tester, 'pause-relay');
    await tester.tap(find.text('Pause for 15 minutes'));
    await tester.pumpAndSettle();
    expect(controller.settings.silenceReason, 'paused');
    expect(running, isTrue);
    await tap(tester, 'resume-relay');
    expect(controller.settings.silenceReason, isNull);
    expect(calls, isNot(contains('stop')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'preset preview does not save and explicit strength is disclosed',
    (tester) async {
      access = true;
      running = true;
      await mount(tester);
      await tap(tester, 'nav-rules');
      await tap(tester, 'default-vibration');
      await tap(tester, 'preset-low');
      expect(
        find.textContaining('It keeps that strength afterward'),
        findsOneWidget,
      );
      await tap(tester, 'preview-rule');
      expect(calls, contains('previewPreset'));
      expect(calls, isNot(contains('saveDefaultPreset')));
      expect(controller.settings.defaultPreset, VibrationPreset.single);
      expect(find.text('Command verified. Did you feel it?'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('a chat inherits its app preset when previewed without saving', (
    tester,
  ) async {
    access = true;
    selected.add('com.whatsapp');
    (rules['apps'] as Map)['com.whatsapp'] = {'enabled': true, 'preset': 'low'};
    rules['chats'] = [
      {'id': 'private-chat', 'label': 'Alice', 'packageName': 'com.whatsapp'},
    ];
    await mount(tester);
    await tap(tester, 'conversation-picker');
    await tap(tester, 'chat-private-chat');
    await tap(tester, 'preview-rule');
    expect(lastTapArguments, {'intensity': 2});
    expect(calls, isNot(contains('saveChat')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('quiet hours save without stopping the relay', (tester) async {
    access = true;
    running = true;
    await mount(tester);
    await tap(tester, 'nav-rules');
    await tap(tester, 'quiet-hours');
    await tap(tester, 'quiet-enabled');
    await tap(tester, 'save-quiet-hours');
    expect(controller.settings.quietEnabled, isTrue);
    expect(running, isTrue);
    expect(calls, isNot(contains('stop')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('zero-event diagnostics remain visible after capture ends', (
    tester,
  ) async {
    access = true;
    conversations['diagnostics'] = {'seen': 0, 'privateLabel': 'SECRET'};
    await mount(tester);
    await tap(tester, 'add-chat');
    conversations['candidates'] = <Map<String, Object?>>[];
    conversations['captureRemainingMillis'] = 0;
    await controller.refresh();
    await tester.pumpAndSettle();
    await tap(tester, 'capture-details');
    expect(
      find.textContaining('No WhatsApp notifications arrived'),
      findsOneWidget,
    );
    expect(controller.captureDiagnostics, {'seen': 0});
    expect(controller.exportJson(), isNot(contains('SECRET')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('message tone asks before reading message text', (tester) async {
    access = true;
    await mount(tester);
    await tap(tester, 'nav-rules');
    await tester.scrollUntilVisible(
      find.byKey(const ValueKey('message-tone')),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(
      find.byKey(const ValueKey('message-tone-experimental')),
      findsOneWidget,
    );
    await tap(tester, 'message-tone');
    expect(find.byKey(const ValueKey('message-tone-consent')), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(calls, isNot(contains('saveToneRules')));
    expect(controller.settings.toneEnabled, isFalse);
    await tap(tester, 'message-tone');
    await tap(tester, 'message-tone-confirm');
    expect(calls, contains('saveToneRules'));
    expect(controller.settings.toneEnabled, isTrue);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('Home tab carries a dot only when something needs attention', (
    tester,
  ) async {
    access = true;
    await mount(tester);
    expect(find.byKey(const ValueKey('nav-relay-badge')), findsNothing);
    bluetoothOn = false;
    await controller.refresh(force: true);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('nav-relay-badge')), findsWidgets);
    expect(find.byKey(const ValueKey('status-badge-problem')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
}
