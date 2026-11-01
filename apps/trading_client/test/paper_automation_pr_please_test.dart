import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR please', () {
    expect(File('../../docs/product/paper-automation-pr-please.md').readAsStringSync(), contains('PR PLEASE'));
  });
}
