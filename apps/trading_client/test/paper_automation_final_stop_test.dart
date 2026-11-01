import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final stop create PR', () {
    expect(File('../../docs/product/paper-automation-final-stop.md').readAsStringSync(), contains('FINAL STOP. CREATE PR'));
  });
}
