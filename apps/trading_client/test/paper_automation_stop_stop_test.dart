import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop stop PR', () {
    expect(File('../../docs/product/paper-automation-stop-stop.md').readAsStringSync(), contains('STOP STOP. PR'));
  });
}
