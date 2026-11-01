import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation foundation completion does not imply mobile release completion', () {
    final text = File('../../docs/product/paper-automation-foundation-complete.md').readAsStringSync();
    expect(text, contains('Foundation coding checkpoint: COMPLETE'));
    expect(text, contains('Product/release slice: IN PROGRESS'));
    expect(text, contains('real Flutter implementation'));
  });
}
