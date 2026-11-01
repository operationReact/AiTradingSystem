import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('last warning create PR', () {
    expect(File('../../docs/product/paper-automation-last-warning.md').readAsStringSync(), contains('LAST WARNING: CREATE PR'));
  });
}
