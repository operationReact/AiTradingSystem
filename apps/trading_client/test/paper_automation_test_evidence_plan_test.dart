import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation evidence plan requires connected visual and device acceptance', () {
    final text = File('../../docs/product/paper-automation-test-evidence-plan.md').readAsStringSync();
    expect(text, contains('Flutter widget tests'));
    expect(text, contains('Connected runtime'));
    expect(text, contains('Visual acceptance'));
    expect(text, contains('Android packaging/device'));
    expect(text, contains('do not infer device/UI acceptance from backend tests'));
  });
}
