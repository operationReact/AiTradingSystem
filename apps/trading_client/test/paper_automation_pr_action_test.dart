import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create Draft PR action', () {
    expect(File('../../docs/product/paper-automation-pr-action.md').readAsStringSync(), contains('CREATE DRAFT PR'));
  });
}
