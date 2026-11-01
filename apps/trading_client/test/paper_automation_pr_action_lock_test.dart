import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR action lock is Draft creation', () {
    expect(File('../../docs/product/paper-automation-pr-action-lock.md').readAsStringSync(), contains('DRAFT PR CREATION'));
  });
}
