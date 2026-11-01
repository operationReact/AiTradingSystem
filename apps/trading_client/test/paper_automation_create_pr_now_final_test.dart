import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create Draft PR now period', () {
    expect(File('../../docs/product/paper-automation-create-pr-now-final.md').readAsStringSync(), contains('CREATE DRAFT PR NOW. PERIOD'));
  });
}
