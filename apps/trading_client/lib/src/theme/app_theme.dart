import 'package:flutter/material.dart';

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
  final seed = style == DesignStyle.neonFuturisticAi
      ? const Color(0xFF7C5CFC)
      : const Color(0xFF1E6FD9);
  final scheme = ColorScheme.fromSeed(seedColor: seed, brightness: brightness);
  final panel = style == DesignStyle.neonFuturisticAi
      ? (isDark ? const Color(0xFF151225) : const Color(0xFFF1EEFF))
      : (isDark ? const Color(0xFF171A1F) : const Color(0xFFF4F6F8));
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: style == DesignStyle.neonFuturisticAi && isDark
        ? const Color(0xFF090712)
        : scheme.surface,
    extensions: <ThemeExtension<dynamic>>[
      TradingColors(
        healthy: const Color(0xFF16865B),
        degraded: const Color(0xFFD17B00),
        unknown: const Color(0xFF6B7280),
        halted: const Color(0xFFB45309),
        unsafe: const Color(0xFFB42318),
        panel: panel,
      ),
    ],
    cardTheme: CardThemeData(
      elevation: style == DesignStyle.neonFuturisticAi ? 1 : 0,
      color: panel,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
    inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder()),
  );
}
