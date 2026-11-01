import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('do PR', () {
    expect(File('../../docs/product/paper-automation-pr-do.md').readAsStringSync(), contains('DO PR'));
  });
}
