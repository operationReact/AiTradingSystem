import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation visual acceptance covers primary customer surfaces', () {
    final text = File('../../docs/product/paper-automation-visual-acceptance.md').readAsStringSync();
    for (final surface in <String>['Home', 'AI/Research', 'PAPER trade', 'Portfolio', 'More/Settings']) {
      expect(text, contains(surface));
    }
    expect(text, contains('Neon Futuristic AI primary/default'));
    expect(text, contains('Clean Modern secondary'));
    expect(text, contains('LIVE visibly unavailable/coming later'));
  });
}
