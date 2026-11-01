import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation checkpoint keeps UI and device acceptance pending', () {
    final text = File('../../docs/product/paper-automation-notion-update-draft.md').readAsStringSync();
    expect(text, contains('IN PROGRESS'));
    expect(text, contains('actual Flutter controls/lifecycle screens'));
    expect(text, contains('canonical Figma phone evidence'));
    expect(text, contains('Android APK packaging remain required'));
  });
}
