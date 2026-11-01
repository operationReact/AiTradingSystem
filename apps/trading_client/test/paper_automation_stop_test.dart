import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop marker requires Draft PR', () {
    expect(File('../../docs/product/paper-automation-pr-final-marker-2.md').readAsStringSync(), contains('CREATE DRAFT PR'));
  });
}
