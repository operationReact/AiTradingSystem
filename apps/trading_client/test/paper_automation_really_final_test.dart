import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('really final PR', () {
    expect(File('../../docs/product/paper-automation-really-final.md').readAsStringSync(), contains('REALLY FINAL. PR'));
  });
}
