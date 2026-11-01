import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('opening PAPER automation Draft does not waive UI visual or device blockers', () {
    final text = File('../../docs/product/paper-automation-pr-open-ready.md').readAsStringSync();
    expect(text, contains('open a Draft PR for CI/review'));
    expect(text, contains('does not mean it is ready to merge'));
    expect(text, contains('actual Flutter UI'));
    expect(text, contains('Figma phone review'));
    expect(text, contains('APK/device acceptance'));
  });
}
