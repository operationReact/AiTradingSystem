import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('last write create Draft PR', () {
    expect(File('../../docs/product/paper-automation-last.md').readAsStringSync(), contains('LAST WRITE. CREATE DRAFT PR'));
  });
}
