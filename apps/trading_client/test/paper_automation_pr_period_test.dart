import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR period', () {
    expect(File('../../docs/product/paper-automation-pr-period.md').readAsStringSync(), contains('PR'));
  });
}
