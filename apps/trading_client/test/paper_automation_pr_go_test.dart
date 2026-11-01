import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('go Draft PR', () {
    expect(File('../../docs/product/paper-automation-pr-go.md').readAsStringSync(), contains('GO: DRAFT PR'));
  });
}
