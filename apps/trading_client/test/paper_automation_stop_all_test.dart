import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop all PR', () {
    expect(File('../../docs/product/paper-automation-stop-all.md').readAsStringSync(), contains('STOP ALL. PR'));
  });
}
