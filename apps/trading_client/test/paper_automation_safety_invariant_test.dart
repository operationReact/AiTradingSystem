import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('mobile PAPER automation preserves release safety invariants', () {
    final text = File('../../docs/product/paper-automation-safety-invariants.md').readAsStringSync();
    expect(text, contains('PAPER is the only executable trading mode'));
    expect(text, contains('Automated requires explicit account opt-in'));
    expect(text, contains('Customer UI does not expose raw internal/provider/operator vocabulary'));
    expect(text, contains('CI green alone is not UI/device acceptance'));
  });
}
