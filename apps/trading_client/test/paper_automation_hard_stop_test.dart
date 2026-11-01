import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('hard stop create PR', () {
    expect(File('../../docs/product/paper-automation-final-stop-3.md').readAsStringSync(), contains('HARD STOP. CREATE PR'));
  });
}
