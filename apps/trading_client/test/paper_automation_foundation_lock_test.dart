import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation foundation lock points to Draft PR', () {
    final text = File('../../docs/product/paper-automation-pr-trigger-lock.md').readAsStringSync();
    expect(text, contains('Next action: Draft PR creation'));
  });
}
