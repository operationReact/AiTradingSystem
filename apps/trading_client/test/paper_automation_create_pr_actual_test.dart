import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('actually create PR', () {
    expect(File('../../docs/product/paper-automation-create-pr-actual.md').readAsStringSync(), contains('ACTUALLY CREATE PR'));
  });
}
