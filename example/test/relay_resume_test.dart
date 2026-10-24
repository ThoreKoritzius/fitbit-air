import 'package:fitbit_air_example/demo/relay_panel.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('resumed entries explain why the relay came back', () {
    expect(
      resumedExplanation('resumed-restart-1700000000000'),
      contains('after your phone restarted'),
    );
    expect(
      resumedExplanation('resumed-update-1'),
      contains('after Air Relay was updated'),
    );
    expect(resumedExplanation(null), contains('after an interruption'));
    expect(resumedExplanation('resumed-listener-1'), contains('Tap Stop'));
  });
}
