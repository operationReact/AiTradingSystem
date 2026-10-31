import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/theme/app_theme.dart';

double _luminance(Color color) => color.computeLuminance();

double _contrast(Color a, Color b) {
  final values = [_luminance(a), _luminance(b)]..sort();
  return (values.last + 0.05) / (values.first + 0.05);
}

void main() {
  for (final style in DesignStyle.values) {
    for (final brightness in Brightness.values) {
      test('${style.name} ${brightness.name} status colors meet 4.5:1 against panel', () {
        final theme = buildTheme(style, brightness);
        final colors = theme.extension<TradingColors>()!;
        final statuses = [
          colors.healthy,
          colors.degraded,
          colors.unknown,
          colors.halted,
          colors.unsafe,
        ];

        for (final status in statuses) {
          expect(
            _contrast(status, colors.panel),
            greaterThanOrEqualTo(4.5),
            reason: '${status.toARGB32().toRadixString(16)} must remain readable on ${colors.panel.toARGB32().toRadixString(16)}',
          );
        }
      });
    }
  }
}
