import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR is next', () {
    expect(File('../../docs/product/paper-automation-pr-final-marker-6.md').readAsStringSync(), contains('PR'));
  });
}
