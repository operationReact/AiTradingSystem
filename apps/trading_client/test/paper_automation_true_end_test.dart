import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('true end create Draft PR', () {
    expect(File('../../docs/product/paper-automation-true-end.md').readAsStringSync(), contains('TRUE END. CREATE DRAFT PR'));
  });
}
