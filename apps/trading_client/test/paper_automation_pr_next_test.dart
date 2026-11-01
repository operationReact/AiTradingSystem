import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR next', () {
    expect(File('../../docs/product/paper-automation-pr-next.md').readAsStringSync(), contains('PR NEXT'));
  });
}
