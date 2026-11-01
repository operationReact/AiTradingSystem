import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('definitive stop PR', () {
    expect(File('../../docs/product/paper-automation-final-stop-2.md').readAsStringSync(), contains('DEFINITIVE STOP. PR'));
  });
}
