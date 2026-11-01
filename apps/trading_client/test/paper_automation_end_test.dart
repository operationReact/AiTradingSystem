import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('end Draft PR', () {
    expect(File('../../docs/product/paper-automation-end.md').readAsStringSync(), contains('END. DRAFT PR'));
  });
}
