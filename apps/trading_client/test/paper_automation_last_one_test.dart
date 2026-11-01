import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('last one PR', () {
    expect(File('../../docs/product/paper-automation-last-one.md').readAsStringSync(), contains('LAST ONE. PR'));
  });
}
