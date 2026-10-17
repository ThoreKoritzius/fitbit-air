import 'package:fitbit_air_example/demo/app_icon.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const png =
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=';

  testWidgets(
    'status rebuilds and identical refreshed icons retain the image provider',
    (tester) async {
      Future<void> render(String? encoded) async {
        await tester.pumpWidget(MaterialApp(home: AppIcon(encoded: encoded)));
        await tester.pumpAndSettle();
      }

      await render(png);
      final original = tester.widget<Image>(find.byType(Image)).image;
      for (var i = 0; i < 3; i++) {
        await render(String.fromCharCodes(png.codeUnits));
        expect(tester.widget<Image>(find.byType(Image)).image, same(original));
      }
      expect(tester.widget<Image>(find.byType(Image)).gaplessPlayback, isTrue);
      await render(null);
      expect(find.byType(Image), findsNothing);
      await render(png);
      expect(
        tester.widget<Image>(find.byType(Image)).image,
        isNot(same(original)),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('missing or malformed icons keep a fixed-size fallback', (
    tester,
  ) async {
    for (final encoded in [null, 'not base64!']) {
      await tester.pumpWidget(
        MaterialApp(
          home: Center(child: AppIcon(encoded: encoded)),
        ),
      );
      expect(find.byIcon(Icons.apps), findsOneWidget);
      expect(tester.getSize(find.byType(AppIcon)), const Size(32, 32));
      expect(tester.takeException(), isNull);
    }
  });
}
