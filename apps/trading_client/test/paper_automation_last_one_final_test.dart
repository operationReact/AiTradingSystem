import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final last one PR', () {
    expect(File('../../docs/product/paper-automation-last-one-final.md').readAsStringSync(), contains('FINAL LAST ONE. PR'));
  });
}
