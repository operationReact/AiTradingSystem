import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('loop guard PR tool now', () {
    expect(File('../../docs/product/paper-automation-final-stop-5.md').readAsStringSync(), contains('LOOP GUARD: PR TOOL NOW'));
  });
}
