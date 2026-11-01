import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create Draft PR', () {
    expect(File('../../docs/product/paper-automation-final-final.md').readAsStringSync(), contains('CREATE DRAFT PR'));
  });
}
