import 'dart:io';

import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('the version shown in the app matches pubspec.yaml', () {
    final pubspec = File('pubspec.yaml').readAsStringSync();
    final version = RegExp(
      r'^version:\s*([^+\s]+)',
      multiLine: true,
    ).firstMatch(pubspec)!.group(1);
    expect(DemoController.appVersion, version);
  });
}
