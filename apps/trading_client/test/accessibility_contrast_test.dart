import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/theme/app_theme.dart';

double _contrast(Color a, Color b) {
  final luminances = [a.computeLuminance(), b.computeLuminance()]..sort();
  return (luminances.last + 0.05) / (luminances.first + 0.05);
}

void main() {
  for (final brightness in Brightness.values) {
    test('${brightness.name} status and core text meet 4.5:1 contrast', () {
      final theme = buildTheme(brightness);
      final scheme = theme.colorScheme;
      final colors = theme.extension<TradingColors>()!;
      for (final status in [
        colors.healthy, colors.degraded, colors.unknown, colors.halted, colors.unsafe,
      ]) {
        expect(_contrast(status, colors.panel), greaterThanOrEqualTo(4.5),
          reason: 'Risk state must remain readable on the component background');
      }
      expect(_contrast(scheme.primary, scheme.surface), greaterThanOrEqualTo(4.5),
          reason: 'MintTick green text/icons must remain readable on the page');
      expect(_contrast(scheme.onPrimary, scheme.primary), greaterThanOrEqualTo(4.5),
          reason: 'Primary action labels must remain readable');
      expect(_contrast(scheme.onSurface, scheme.surface), greaterThanOrEqualTo(4.5));
      expect(_contrast(scheme.onSurfaceVariant, scheme.surface), greaterThanOrEqualTo(4.5));
    });
  }
}
