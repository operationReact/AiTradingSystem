import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/brand/minttick_brand.dart';
import 'package:trading_client/src/theme/app_theme.dart';

void main() {
  test('dark mode uses the same neutral near-black background across screens', () {
    final theme = buildTheme(Brightness.dark);
    expect(MintTickPalette.nearBlack, const Color(0xFF090B0A));
    expect(MintTickPalette.nearBlack, isNot(Colors.black));
    expect(theme.scaffoldBackgroundColor, MintTickPalette.nearBlack);
    expect(theme.canvasColor, MintTickPalette.nearBlack);
    expect(theme.appBarTheme.backgroundColor, MintTickPalette.nearBlack);
    expect(theme.colorScheme.surface, MintTickPalette.nearBlack);
    expect(theme.colorScheme.surfaceContainerLowest, MintTickPalette.nearBlack);
    expect(theme.bottomSheetTheme.backgroundColor, MintTickPalette.nearBlack);

    // Premium flat palette is shared by cards, tabs and page surfaces.
    expect(theme.colorScheme.primary, MintTickPalette.mint);
    expect(theme.cardTheme.color, MintTickPalette.darkCard);
  });

  test('light mode is unchanged', () {
    final theme = buildTheme(Brightness.light);
    expect(theme.scaffoldBackgroundColor, MintTickPalette.ice);
    expect(theme.colorScheme.surface, MintTickPalette.ice);
    expect(theme.colorScheme.surfaceContainerLowest, Colors.white);
  });
}
