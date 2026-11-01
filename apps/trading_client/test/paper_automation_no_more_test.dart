import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('open Draft PR', () {
    expect(File('../../docs/product/paper-automation-no-more.md').readAsStringSync(), contains('OPEN DRAFT PR'));
  });
}
