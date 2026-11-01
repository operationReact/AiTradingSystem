import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR immediate', () {
    expect(File('../../docs/product/paper-automation-pr-immediate.md').readAsStringSync(), contains('PR IMMEDIATE'));
  });
}
