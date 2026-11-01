import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final end create Draft PR', () {
    expect(File('../../docs/product/paper-automation-end-final.md').readAsStringSync(), contains('FINAL END. CREATE DRAFT PR'));
  });
}
