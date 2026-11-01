import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('enough PR', () {
    expect(File('../../docs/product/paper-automation-enough.md').readAsStringSync(), contains('ENOUGH. PR'));
  });
}
