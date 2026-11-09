import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/brand/minttick_brand.dart';
import 'package:trading_client/src/theme/app_theme.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_widgets.dart';

void main() {
  test('dark and light themes share a single brand accent', () {
    final dark = buildTheme(Brightness.dark).colorScheme;
    final light = buildTheme(Brightness.light).colorScheme;
    expect(dark.primary, MintTickPalette.mint);
    expect(dark.secondary, MintTickPalette.mint);
    expect(dark.tertiary, MintTickPalette.mint);
    expect(light.primary, MintTickPalette.readableGreen);
    expect(dark.surface, MintTickPalette.nearBlack);
    expect(dark.surfaceContainer, MintTickPalette.darkCard);
    expect(dark.outlineVariant, MintTickPalette.darkBorder);
    expect(dark.onSurfaceVariant, MintTickPalette.mutedSilver);
    expect(MintTickPalette.mint, const Color(0xFF00A83B));
    expect(buildTheme(Brightness.dark).navigationBarTheme.indicatorColor,
        Colors.transparent);
    expect(buildTheme(Brightness.dark).cardTheme.elevation, 0);
  });

  test('Mintick Mind reuses the canonical matte surfaces and green', () {
    expect(MindPalette.mint, MintTickPalette.mint);
    expect(MindPalette.green, MintTickPalette.mint);
    expect(MindPalette.background, MintTickPalette.nearBlack);
    expect(MindPalette.border, MintTickPalette.darkBorder);
    expect(MindPalette.card, MintTickPalette.darkCard);
  });

  testWidgets('all MindPanel variants use flat fills and zero gradients', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: const Scaffold(body: Column(children: [
        MindPanel(child: Text('Regular')),
        MindPanel(highlight: true, child: Text('Selected')),
      ])),
    ));
    final panels = tester.widgetList<Material>(
      find.descendant(of: find.byType(MindPanel), matching: find.byType(Material)),
    ).toList();
    expect(panels, hasLength(2));
    expect(panels.map((panel) => panel.color), contains(MindPalette.card));
    expect(panels.map((panel) => panel.color),
        contains(const Color(0xFF18231A)));
    for (final panel in panels) {
      expect(panel.shape, isA<RoundedRectangleBorder>());
      expect(panel.elevation, 0);
    }
    expect(tester.takeException(), isNull);
  });
}
