import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/theme/app_theme.dart';

void main() {
  for (final brightness in Brightness.values) {
    for (final style in DesignStyle.values) {
      test('${brightness.name}/${style.name} uses consistent readable typography', () {
        final theme = buildTheme(style, brightness);
        final typography = theme.textTheme;
        expect(typography.titleLarge!.fontSize, 20);
        expect(typography.titleMedium!.fontSize, 17);
        expect(typography.bodyLarge!.fontSize, 16);
        expect(typography.bodyMedium!.fontSize, 14);
        expect(typography.bodySmall!.fontSize, greaterThanOrEqualTo(13));
        expect(typography.labelMedium!.fontSize, greaterThanOrEqualTo(12));
        expect(theme.materialTapTargetSize, MaterialTapTargetSize.padded);
        expect(theme.visualDensity, VisualDensity.standard);
        expect(typography.bodyMedium!.color, theme.colorScheme.onSurface);
      });
    }
  }

  for (final width in [320.0, 390.0, 411.0]) {
    testWidgets('actions preserve 48dp tap targets at ${width.toInt()}dp', (tester) async {
      tester.view.physicalSize = Size(width, 740);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(DesignStyle.neonFuturisticAi, Brightness.dark),
        home: Scaffold(
          body: SafeArea(
            child: Column(children: [
              FilledButton(
                key: const ValueKey('primary-action'),
                onPressed: () {},
                child: const Text('Continue securely'),
              ),
              OutlinedButton(
                key: const ValueKey('secondary-action'),
                onPressed: () {},
                child: const Text('Explore Markets'),
              ),
            ]),
          ),
        ),
      ));
      await tester.pumpAndSettle();
      expect(tester.getSize(find.byKey(const ValueKey('primary-action'))).height,
          greaterThanOrEqualTo(48));
      expect(tester.getSize(find.byKey(const ValueKey('secondary-action'))).height,
          greaterThanOrEqualTo(48));
      expect(tester.takeException(), isNull);
    });
  }
}
