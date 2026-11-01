import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation review distinguishes contracts from real Flutter UI', () {
    final text = File('../../docs/product/paper-automation-review-notes.md').readAsStringSync();
    expect(text, contains('Documentation/copy contracts are not UI implementation'));
    expect(text, contains('Actual Flutter Home/AI/Portfolio/More screens'));
    expect(text, contains('canonical Figma phone-size review'));
  });
}
