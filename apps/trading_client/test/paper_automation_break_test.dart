import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('break PR', () {
    expect(File('../../docs/product/paper-automation-break.md').readAsStringSync(), contains('BREAK. PR'));
  });
}
