import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation checkpoint remains Draft until real Flutter and persistence integration', () {
    final text = File('../../docs/product/paper-automation-current-cursor.md').readAsStringSync();
    expect(text, contains('integration checkpoint, not a merge-ready release'));
    expect(text, contains('Implement actual Flutter controls and lifecycle state'));
    expect(text, contains('Run exact-head gates and visual/APK acceptance'));
    expect(text, contains('Keep Draft'));
  });
}
