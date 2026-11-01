import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next PAPER automation actions keep UI CI and device evidence pending', () {
    final text = File('../../docs/product/paper-automation-next-pr-actions.md').readAsStringSync();
    expect(text, contains('Draft against `dev`'));
    expect(text, contains('Implement actual Flutter mode controls and lifecycle state'));
    expect(text, contains('Collect visual/APK/device evidence'));
    expect(text, contains('LIVE/broker submission disabled'));
  });
}
