import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('command create Draft PR', () {
    expect(File('../../docs/product/paper-automation-pr-final-command.md').readAsStringSync(), contains('COMMAND: CREATE DRAFT PR'));
  });
}
