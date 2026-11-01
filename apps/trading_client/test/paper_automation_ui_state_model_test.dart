import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('mobile PAPER automation state model is customer readable', () {
    final text = File('../../docs/product/paper-automation-ui-state-model.md').readAsStringSync();
    for (final state in <String>[
      'PAPER', 'Manual', 'Automated', 'Risk protected', 'Trading paused',
      'Watching for opportunities', 'Checking risk', 'Filled', 'Position open', 'Closed',
      'Unrealized P&L', 'Realized P&L',
    ]) {
      expect(text, contains(state));
    }
    expect(text, contains('Do not expose implementation states'));
  });
}
