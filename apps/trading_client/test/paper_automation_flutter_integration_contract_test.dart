import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Flutter integration contract covers customer primary surfaces', () {
    final text = File('../../docs/product/paper-automation-flutter-integration.md').readAsStringSync();
    for (final surface in <String>['Home', 'AI/Research', 'Portfolio', 'More']) {
      expect(text, contains('## $surface'));
    }
    expect(text, contains('authenticated account-scoped API state'));
    expect(text, contains('LIVE is disabled/Coming later'));
    expect(text, contains('Neon Futuristic AI primary'));
    expect(text, contains('Clean Modern secondary'));
  });
}
