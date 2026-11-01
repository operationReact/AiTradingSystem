import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR only', () {
    expect(File('../../docs/product/paper-automation-pr-only.md').readAsStringSync(), contains('PR ONLY'));
  });
}
