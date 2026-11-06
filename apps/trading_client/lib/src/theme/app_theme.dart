import 'package:flutter/material.dart';

import '../brand/minttick_brand.dart';

enum DesignStyle { neonFuturisticAi, cleanModern }

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

  Color get neonCyan => MintTickPalette.mint;
  Color get neonViolet => MintTickPalette.teal;
  Color get neonMagenta => const Color(0xFF28F4B4);
  Color get neonNavy => MintTickPalette.navy;

  @override
  TradingColors copyWith({Color? healthy, Color? degraded, Color? unknown, Color? halted, Color? unsafe, Color? panel}) =>
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

ThemeData buildTheme(DesignStyle style, Brightness brightness) {
  final isDark = brightness == Brightness.dark;
  final neon = style == DesignStyle.neonFuturisticAi;
  const cyan = MintTickPalette.mint;
  const violet = MintTickPalette.teal;
  const magenta = Color(0xFF28F4B4);

  final seed = MintTickPalette.teal;
  final generated = ColorScheme.fromSeed(seedColor: seed, brightness: brightness);
  final scheme = neon
      ? generated.copyWith(
          primary: isDark ? cyan : MintTickPalette.readableGreen,
          secondary: isDark ? violet : const Color(0xFF007A70),
          tertiary: violet,
          surface: isDark ? MintTickPalette.navy : MintTickPalette.ice,
          surfaceContainer: isDark ? const Color(0xFF102019) : const Color(0xFFF0F7F4),
          surfaceContainerHigh: isDark ? const Color(0xFF182B22) : const Color(0xFFE6F1EC),
          surfaceContainerHighest: isDark ? const Color(0xFF203629) : const Color(0xFFDDEBE4),
          onSurface: isDark ? MintTickPalette.ice : MintTickPalette.navy,
          onSurfaceVariant: isDark ? const Color(0xFFB0C9BF) : const Color(0xFF52645C),
          outline: isDark ? cyan.withValues(alpha: 0.58) : const Color(0xFF7BA89A),
          outlineVariant: isDark ? cyan.withValues(alpha: 0.22) : const Color(0xFFC0D6CB),
        )
      : generated.copyWith(
          primary: isDark ? cyan : MintTickPalette.readableGreen,
          secondary: isDark ? violet : const Color(0xFF007A70),
          surface: isDark ? MintTickPalette.navy : MintTickPalette.ice,
          onSurface: isDark ? MintTickPalette.ice : MintTickPalette.navy,
        );

  final panel = neon
      ? (isDark ? const Color(0xFF102019) : const Color(0xFFF0F7F4))
      : (isDark ? const Color(0xFF171A1F) : const Color(0xFFF4F6F8));

  final statusColors = isDark
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
    scaffoldBackgroundColor: scheme.surface,
    canvasColor: scheme.surface,
    visualDensity: const VisualDensity(horizontal: -0.5, vertical: -0.5),
    focusColor: scheme.primary.withValues(alpha: 0.28),
    hoverColor: scheme.primary.withValues(alpha: 0.08),
    extensions: <ThemeExtension<dynamic>>[
      TradingColors(
        healthy: statusColors.healthy,
        degraded: statusColors.degraded,
        unknown: statusColors.unknown,
        halted: statusColors.halted,
        unsafe: statusColors.unsafe,
        panel: panel,
      ),
    ],
    appBarTheme: AppBarTheme(
      elevation: 0,
      scrolledUnderElevation: 0,
      toolbarHeight: 52,
      backgroundColor: scheme.surface.withValues(alpha: 0.96),
      foregroundColor: scheme.onSurface,
      centerTitle: false,
      titleTextStyle: TextStyle(
        color: scheme.onSurface,
        fontSize: 18,
        fontWeight: FontWeight.w800,
        letterSpacing: -0.2,
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: panel,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(neon ? 18 : 14),
        side: BorderSide(
          color: neon && isDark ? cyan.withValues(alpha: 0.28) : scheme.outlineVariant.withValues(alpha: 0.72),
        ),
      ),
    ),
    listTileTheme: const ListTileThemeData(
      dense: true,
      minVerticalPadding: 6,
      contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 2),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: 64,
      elevation: 0,
      backgroundColor: neon && isDark ? const Color(0xF2071020) : scheme.surfaceContainer,
      indicatorColor: neon ? cyan.withValues(alpha: isDark ? 0.16 : 0.12) : scheme.primaryContainer,
      iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(
            color: states.contains(WidgetState.selected) ? scheme.primary : scheme.onSurfaceVariant,
          )),
      labelTextStyle: WidgetStateProperty.resolveWith((states) => TextStyle(
            color: states.contains(WidgetState.selected) ? scheme.primary : scheme.onSurfaceVariant,
            fontSize: 10.5,
            fontWeight: states.contains(WidgetState.selected) ? FontWeight.w800 : FontWeight.w600,
          )),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      fillColor: scheme.surfaceContainerHigh.withValues(alpha: 0.72),
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(13), borderSide: BorderSide(color: scheme.outlineVariant)),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(13), borderSide: BorderSide(color: scheme.outlineVariant)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(13), borderSide: BorderSide(color: scheme.primary, width: 1.5)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(0, 44),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(0, 44),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 11),
      ),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(shape: WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)))),
    ),
    dividerColor: scheme.outlineVariant.withValues(alpha: 0.55),
  );
}
