import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('ultimate stop PR', () {
    expect(File('../../docs/product/paper-automation-final-stop-4.md').readAsStringSync(), contains('ULTIMATE STOP. PR'));
  });
}
