import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('really stop PR', () {
    expect(File('../../docs/product/paper-automation-really-stop.md').readAsStringSync(), contains('REALLY STOP. PR'));
  });
}
