import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('done writing PR', () {
    expect(File('../../docs/product/paper-automation-done-writing.md').readAsStringSync(), contains('DONE WRITING. PR'));
  });
}
