import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('this time PR', () {
    expect(File('../../docs/product/paper-automation-this-time.md').readAsStringSync(), contains('THIS TIME: PR'));
  });
}
