import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final lock PR', () {
    expect(File('../../docs/product/paper-automation-final-lock.md').readAsStringSync(), contains('FINAL LOCK. PR'));
  });
}
