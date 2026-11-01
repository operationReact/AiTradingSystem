import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR tool now', () {
    expect(File('../../docs/product/paper-automation-pr-tool-now.md').readAsStringSync(), contains('PR TOOL NOW'));
  });
}
