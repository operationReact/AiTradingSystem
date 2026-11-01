import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation copy uses customer language', () {
    final copy = File('../../docs/product/paper-automation-ui-copy.md').readAsStringSync();
    for (final phrase in <String>[
      'Risk protected',
      'Trading paused',
      'AI opportunity found',
      'Checking risk',
      'PAPER order accepted',
      'Position open',
      'Profit',
      'Loss',
    ]) {
      expect(copy, contains(phrase));
    }
  });
}
