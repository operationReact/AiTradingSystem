import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('finally create Draft PR', () {
    expect(File('../../docs/product/paper-automation-create-pr-finally.md').readAsStringSync(), contains('FINALLY CREATE DRAFT PR'));
  });
}
