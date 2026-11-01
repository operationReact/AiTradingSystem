import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation moving head invalidates earlier UI acceptance evidence', () {
    final text = File('../../docs/product/paper-automation-head-policy.md').readAsStringSync();
    expect(text, contains('latest exact head'));
    expect(text, contains('historical evidence only'));
    expect(text, contains('affected gates must be evaluated again'));
  });
}
