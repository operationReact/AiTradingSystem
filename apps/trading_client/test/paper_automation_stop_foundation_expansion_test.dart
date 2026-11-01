import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next PAPER automation value is real Flutter integration', () {
    final text = File('../../docs/product/paper-automation-stop-foundation-expansion.md').readAsStringSync();
    expect(text, contains('real Flutter screens'));
    expect(text, contains('then validating them'));
  });
}
