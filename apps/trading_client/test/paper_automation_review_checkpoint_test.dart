import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation checkpoint requires rendered UI rather than contracts only', () {
    final text = File('../../docs/product/paper-automation-review-checkpoint.md').readAsStringSync();
    expect(text, contains('FOUNDATION IMPLEMENTED / INTEGRATION PENDING / DRAFT ONLY'));
    expect(text, contains('documentation-driven Flutter contract tests with actual rendered UI acceptance'));
    expect(text, contains('Flutter screens implement, not merely document'));
  });
}
