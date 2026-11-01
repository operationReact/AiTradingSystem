import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR now', () {
    expect(File('../../docs/product/paper-automation-stop-now.md').readAsStringSync(), contains('PR NOW'));
  });
}
