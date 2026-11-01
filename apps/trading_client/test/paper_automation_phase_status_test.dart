import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation phase remains in progress until mobile acceptance completes', () {
    final text = File('../../docs/product/paper-automation-phase-status.md').readAsStringSync();
    expect(text, contains('IN PROGRESS — FOUNDATION CHECKPOINT'));
    expect(text, contains('actual Flutter UI'));
    expect(text, contains('Figma phone evidence'));
    expect(text, contains('real-backend APK/device acceptance'));
  });
}
