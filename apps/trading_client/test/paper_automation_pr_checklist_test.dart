import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation Draft checklist keeps mobile acceptance work open', () {
    final text = File('../../docs/product/paper-automation-pr-checklist.md').readAsStringSync();
    expect(text, contains('[ ] Implement actual Flutter mode controls/lifecycle surfaces'));
    expect(text, contains('[ ] Collect canonical Figma phone evidence'));
    expect(text, contains('[ ] Package Android APK'));
    expect(text, contains('DRAFT / DO NOT MERGE'));
  });
}
