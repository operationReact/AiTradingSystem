import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR next', () {
    expect(File('../../docs/product/paper-automation-last-create-file-final-final.md').readAsStringSync(), contains('PR NEXT'));
  });
}
