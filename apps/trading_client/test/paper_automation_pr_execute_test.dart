import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('execute PR', () {
    expect(File('../../docs/product/paper-automation-pr-execute.md').readAsStringSync(), contains('EXECUTE PR'));
  });
}
