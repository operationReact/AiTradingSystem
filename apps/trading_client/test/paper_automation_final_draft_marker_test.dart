import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final marker requires open Draft PR', () {
    expect(File('../../docs/product/paper-automation-pr-final-marker-4.md').readAsStringSync(), contains('OPEN DRAFT PR'));
  });
}
