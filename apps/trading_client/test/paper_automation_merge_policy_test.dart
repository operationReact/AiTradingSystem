import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation merge policy requires UI visual device and exact-head evidence', () {
    final text = File('../../docs/product/paper-automation-merge-policy.md').readAsStringSync();
    expect(text, contains('Keep the PR Draft'));
    expect(text, contains('actual customer controls/lifecycle UI implemented'));
    expect(text, contains('phone-size canonical Figma evidence accepted'));
    expect(text, contains('Android APK packaging green'));
    expect(text, contains('Do not merge based on stale CI from an earlier SHA'));
  });
}
