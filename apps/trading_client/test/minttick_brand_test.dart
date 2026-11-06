import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/brand/minttick_brand.dart';
import 'package:trading_client/src/theme/app_theme.dart';

void main() {
  test('brand primary colors persist in both display styles', () {
    for (final style in DesignStyle.values) {
      final dark = buildTheme(style, Brightness.dark);
      final light = buildTheme(style, Brightness.light);
      expect(dark.colorScheme.primary, MintTickPalette.mint);
      expect(light.colorScheme.primary, MintTickPalette.readableGreen);
      expect(dark.colorScheme.surface, isNot(light.colorScheme.surface));
      expect(dark.extension<TradingColors>()!.unsafe, isNot(dark.colorScheme.primary));
    }
  });

  testWidgets('MintTick icon and wordmark render without network access', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(DesignStyle.neonFuturisticAi, Brightness.dark),
      home: const Scaffold(body: Center(child: MintTickLockup())),
    ));
    expect(find.byType(MintTickMark), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-mark')), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-wordmark')), findsOneWidget);
  });
}
