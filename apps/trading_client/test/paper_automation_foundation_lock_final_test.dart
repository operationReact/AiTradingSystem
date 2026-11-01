import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final PAPER automation foundation lock requires Draft PR', () {
    final text = File('../../docs/product/paper-automation-pr-trigger-lock-final.md').readAsStringSync();
    expect(text, contains('CREATE DRAFT PR'));
  });
}
