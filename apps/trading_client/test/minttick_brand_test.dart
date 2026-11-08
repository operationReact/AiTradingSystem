import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/brand/minttick_brand.dart';
import 'package:trading_client/src/theme/app_theme.dart';

void main() {
  test('MintTick canonical product identity stays consistent', () {
    expect(MintTickBrand.appName, 'MintTick');
    expect(MintTickBrand.productName, 'MintTick — AI Trading Platform');
  });

  test('One MintTick design system supports accessible dark and light modes', () {
    final dark = buildTheme(Brightness.dark);
    final light = buildTheme(Brightness.light);
    expect(dark.colorScheme.primary, const Color(0xFF78B6AF));
    expect(light.colorScheme.primary, MintTickPalette.readableGreen);
    expect(dark.colorScheme.surface, MintTickPalette.nearBlack);
    expect(dark.colorScheme.surfaceContainerLow, const Color(0xFF1B2732));
    expect(dark.colorScheme.surfaceContainerHigh, const Color(0xFF283845));
    expect(dark.colorScheme.onSurface, const Color(0xFFEDF2F4));
    expect(light.colorScheme.surface, MintTickPalette.ice);
    expect(dark.colorScheme.surface, isNot(light.colorScheme.surface));
    expect(dark.extension<TradingColors>()!.unsafe, isNot(dark.colorScheme.primary));
    expect(light.cardTheme.shape.runtimeType, dark.cardTheme.shape.runtimeType);
  });

  testWidgets('MintTick icon and wordmark render without network access', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body: Center(child: MintTickLockup())),
    ));
    expect(find.byType(MintTickMark), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-mark')), findsOneWidget);
    expect(find.byKey(const ValueKey('minttick-wordmark')), findsOneWidget);
  });
}
