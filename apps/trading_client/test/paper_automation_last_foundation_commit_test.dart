import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation foundation loop is closed', () {
    final text = File('../../docs/product/paper-automation-last-foundation-commit.md').readAsStringSync();
    expect(text, contains('Draft PR creation or integration/CI-driven work'));
  });
}
