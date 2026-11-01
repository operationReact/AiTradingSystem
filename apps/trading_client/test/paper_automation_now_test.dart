import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('now PR', () {
    expect(File('../../docs/product/paper-automation-now.md').readAsStringSync(), contains('NOW: PR'));
  });
}
