import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create Draft PR now', () {
    expect(File('../../docs/product/paper-automation-absolutely-final.md').readAsStringSync(), contains('CREATE DRAFT PR NOW'));
  });
}
