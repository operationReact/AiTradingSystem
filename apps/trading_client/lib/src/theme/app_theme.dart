import 'package:flutter/material.dart';

import '../brand/minttick_brand.dart';
import 'minttick_typography.dart';

/// One MintTick design system. Brightness changes appearance, not layout.
///
/// Dark and light are the only visual variations; never reintroduce
/// separate product-wide design profiles.
@immutable
class TradingColors extends ThemeExtension<TradingColors> {
  const TradingColors({
    required this.healthy,
    required this.degraded,
    required this.unknown,
    required this.halted,
    required this.unsafe,
    required this.panel,
  });

  final Color healthy;
  final Color degraded;
  final Color unknown;
  final Color halted;
  final Color unsafe;
  final Color panel;

  // Transitional aliases retained for existing operational components.
  // All resolve to MintTick brand tokens, not a second visual design.
  Color get neonCyan => MintTickPalette.mint;
  Color get neonViolet => MintTickPalette.teal;
  Color get neonMagenta => MintTickPalette.teal;
  Color get neonNavy => MintTickPalette.navy;

  @override
  TradingColors copyWith({
    Color? healthy,
    Color? degraded,
    Color? unknown,
    Color? halted,
    Color? unsafe,
    Color? panel,
  }) =>
      TradingColors(
        healthy: healthy ?? this.healthy,
        degraded: degraded ?? this.degraded,
        unknown: unknown ?? this.unknown,
        halted: halted ?? this.halted,
        unsafe: unsafe ?? this.unsafe,
        panel: panel ?? this.panel,
      );

  @override
  TradingColors lerp(covariant TradingColors? other, double t) {
    if (other == null) return this;
    return TradingColors(
      healthy: Color.lerp(healthy, other.healthy, t)!,
      degraded: Color.lerp(degraded, other.degraded, t)!,
      unknown: Color.lerp(unknown, other.unknown, t)!,
      halted: Color.lerp(halted, other.halted, t)!,
      unsafe: Color.lerp(unsafe, other.unsafe, t)!,
      panel: Color.lerp(panel, other.panel, t)!,
    );
  }
}

/// The single shared MintTick Material 3 component theme.
ThemeData buildTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final scheme = ColorScheme.fromSeed(
    seedColor: MintTickPalette.mint,
    brightness: brightness,
  ).copyWith(
    primary: dark ? MintTickPalette.mint : MintTickPalette.readableGreen,
    onPrimary: Colors.white,
    primaryContainer: dark ? const Color(0xFF142117) : const Color(0xFFDFF2E4),
    onPrimaryContainer: dark ? const Color(0xFFD5F4DE) : const Color(0xFF133A21),
    secondary: dark ? MintTickPalette.mint : MintTickPalette.readableGreen,
    onSecondary: Colors.white,
    secondaryContainer: dark ? const Color(0xFF18241A) : const Color(0xFFE7F3E9),
    onSecondaryContainer: dark ? const Color(0xFFD5F4DE) : const Color(0xFF133A21),
    tertiary: dark ? MintTickPalette.mint : MintTickPalette.readableGreen,
    surface: dark ? MintTickPalette.nearBlack : MintTickPalette.ice,
    surfaceContainerLowest: dark ? MintTickPalette.nearBlack : Colors.white,
    surfaceContainerLow: dark ? const Color(0xFF111512) : const Color(0xFFF4F6F3),
    surfaceContainer: dark ? MintTickPalette.darkCard : const Color(0xFFEEF1EC),
    surfaceContainerHigh: dark ? MintTickPalette.darkElevated : const Color(0xFFE8ECE6),
    surfaceContainerHighest: dark ? const Color(0xFF232923) : const Color(0xFFE1E7DF),
    onSurface: dark ? const Color(0xFFF5F6F4) : const Color(0xFF151A16),
    onSurfaceVariant: dark ? MintTickPalette.mutedSilver : const Color(0xFF515D53),
    outline: dark ? const Color(0xFF525A53) : const Color(0xFF7F8A81),
    outlineVariant: dark ? MintTickPalette.darkBorder : const Color(0xFFD2DBD1),
    error: dark ? const Color(0xFFFF9292) : const Color(0xFFAE263A),
    surfaceTint: Colors.transparent,
  );

  final panel = dark ? MintTickPalette.darkCard : const Color(0xFFFFFFFF);
  final statuses = dark
      ? const (
          healthy: Color(0xFF53D69D),
          degraded: Color(0xFFFFB84D),
          unknown: Color(0xFFB8C0CC),
          halted: Color(0xFFFFB15A),
          unsafe: Color(0xFFFF6B7A),
        )
      : const (
          healthy: Color(0xFF006B49),
          degraded: Color(0xFF8A4B00),
          unknown: Color(0xFF4B5563),
          halted: Color(0xFF7C3E00),
          unsafe: Color(0xFFA0120A),
        );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    textTheme: buildMintTickTextTheme(scheme),
    materialTapTargetSize: MaterialTapTargetSize.padded,
    scaffoldBackgroundColor: scheme.surface,
    canvasColor: scheme.surface,
    // Sheets are page-level surfaces too; cards and accent colors stay unchanged.
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: dark ? MintTickPalette.nearBlack : scheme.surface,
    ),
    visualDensity: VisualDensity.standard,
    splashFactory: InkRipple.splashFactory,
    focusColor: scheme.primary.withValues(alpha: 0.18),
    hoverColor: scheme.primary.withValues(alpha: 0.07),
    extensions: <ThemeExtension<dynamic>>[
      TradingColors(
        healthy: statuses.healthy,
        degraded: statuses.degraded,
        unknown: statuses.unknown,
        halted: statuses.halted,
        unsafe: statuses.unsafe,
        panel: panel,
      ),
    ],
    appBarTheme: AppBarTheme(
      elevation: 0,
      scrolledUnderElevation: 0,
      toolbarHeight: 56,
      backgroundColor: scheme.surface,
      foregroundColor: scheme.onSurface,
      centerTitle: false,
      titleTextStyle: TextStyle(
        color: scheme.onSurface,
        fontSize: 18,
        fontWeight: FontWeight.w700,
        letterSpacing: -0.25,
      ),
    ),
    cardTheme: CardThemeData(
      color: panel,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.72)),
      ),
    ),
    listTileTheme: const ListTileThemeData(
      dense: false,
      minVerticalPadding: 8,
      contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 3),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: 68,
      elevation: 0,
      backgroundColor: scheme.surfaceContainerLow,
      indicatorColor: Colors.transparent,
      iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(
            color: states.contains(WidgetState.selected)
                ? scheme.primary
                : scheme.onSurfaceVariant,
          )),
      labelTextStyle: WidgetStateProperty.resolveWith((states) => TextStyle(
            color: states.contains(WidgetState.selected)
                ? scheme.onSurface
                : scheme.onSurfaceVariant,
            fontSize: 12,
            fontWeight: states.contains(WidgetState.selected)
                ? FontWeight.w700
                : FontWeight.w500,
          )),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
      fillColor: scheme.surfaceContainerLow,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: scheme.outlineVariant),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: scheme.outlineVariant),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: scheme.primary, width: 1.6),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(0, 48),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(0, 48),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        shape: WidgetStatePropertyAll(
          RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
      ),
    ),
    dividerColor: scheme.outlineVariant.withValues(alpha: 0.72),
  );
}
