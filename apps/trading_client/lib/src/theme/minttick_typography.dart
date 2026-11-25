import 'package:flutter/material.dart';

/// Shared Material 3 type ramp for dense but readable trading workflows.
///
/// The approved brand uses Inter. Until a verified, licensed Inter asset is
/// bundled, Flutter intentionally uses the platform font rather than
/// displaying an unlicensed/improperly loaded lookalike.
///
/// All sizes are scalable logical pixels (sp via Flutter text scaling).
TextTheme buildMintTickTextTheme(ColorScheme scheme) => TextTheme(
      displayLarge: TextStyle(
        color: scheme.onSurface,
        fontSize: 45,
        fontWeight: FontWeight.w800,
        height: 1.08,
        letterSpacing: -1.3,
      ),
      displayMedium: TextStyle(
        color: scheme.onSurface,
        fontSize: 38,
        fontWeight: FontWeight.w800,
        height: 1.1,
        letterSpacing: -1.0,
      ),
      displaySmall: TextStyle(
        color: scheme.onSurface,
        fontSize: 32,
        fontWeight: FontWeight.w800,
        height: 1.14,
        letterSpacing: -0.8,
      ),
      headlineLarge: TextStyle(
        color: scheme.onSurface,
        fontSize: 28,
        fontWeight: FontWeight.w700,
        height: 1.18,
        letterSpacing: -0.6,
      ),
      headlineMedium: TextStyle(
        color: scheme.onSurface,
        fontSize: 24,
        fontWeight: FontWeight.w700,
        height: 1.2,
        letterSpacing: -0.45,
      ),
      headlineSmall: TextStyle(
        color: scheme.onSurface,
        fontSize: 21,
        fontWeight: FontWeight.w700,
        height: 1.23,
        letterSpacing: -0.3,
      ),
      titleLarge: TextStyle(
        color: scheme.onSurface,
        fontSize: 20,
        fontWeight: FontWeight.w700,
        height: 1.25,
        letterSpacing: -0.25,
      ),
      titleMedium: TextStyle(
        color: scheme.onSurface,
        fontSize: 17,
        fontWeight: FontWeight.w700,
        height: 1.28,
        letterSpacing: -0.1,
      ),
      titleSmall: TextStyle(
        color: scheme.onSurface,
        fontSize: 15,
        fontWeight: FontWeight.w600,
        height: 1.3,
      ),
      bodyLarge: TextStyle(
        color: scheme.onSurface,
        fontSize: 16,
        fontWeight: FontWeight.w400,
        height: 1.45,
      ),
      bodyMedium: TextStyle(
        color: scheme.onSurface,
        fontSize: 14,
        fontWeight: FontWeight.w400,
        height: 1.45,
      ),
      bodySmall: TextStyle(
        color: scheme.onSurfaceVariant,
        fontSize: 13,
        fontWeight: FontWeight.w400,
        height: 1.4,
      ),
      labelLarge: TextStyle(
        color: scheme.onSurface,
        fontSize: 14,
        fontWeight: FontWeight.w600,
        height: 1.3,
      ),
      labelMedium: TextStyle(
        color: scheme.onSurface,
        fontSize: 12.5,
        fontWeight: FontWeight.w600,
        height: 1.25,
      ),
      labelSmall: TextStyle(
        color: scheme.onSurfaceVariant,
        fontSize: 11.5,
        fontWeight: FontWeight.w600,
        height: 1.25,
        letterSpacing: 0.1,
      ),
    );
