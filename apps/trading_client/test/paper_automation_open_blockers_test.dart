import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation UI release blockers remain visible', () {
    final text = File('../../docs/product/paper-automation-open-blockers.md').readAsStringSync();
    expect(text, contains('Real Secure Sign In'));
    expect(text, contains('Actual Flutter implementation'));
    expect(text, contains('Angel One read-only integration'));
    expect(text, contains('Canonical Figma visual evidence'));
    expect(text, contains('Android real-backend/device acceptance'));
  });
}
