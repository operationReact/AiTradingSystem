import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation release evidence is not complete yet', () {
    final text = File('../../docs/product/paper-automation-release-evidence.md').readAsStringSync();
    expect(text, contains('Status: NOT YET RELEASE-ACCEPTED'));
    expect(text, contains('Actual Flutter controls/lifecycle screens'));
    expect(text, contains('Canonical Figma phone-size visual comparison'));
    expect(text, contains('Android APK packaging'));
  });
}
