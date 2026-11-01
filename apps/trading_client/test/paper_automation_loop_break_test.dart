import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('break loop create pull request', () {
    expect(File('../../docs/product/paper-automation-loop-break.md').readAsStringSync(), contains('BREAK LOOP. CREATE PULL REQUEST'));
  });
}
