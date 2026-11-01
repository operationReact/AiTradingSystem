import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('enforced next action is Draft PR', () {
    expect(File('../../docs/product/paper-automation-pr-final-marker-3.md').readAsStringSync(), contains('DRAFT PR'));
  });
}
