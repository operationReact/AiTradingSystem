import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final command create pull request', () {
    expect(File('../../docs/product/paper-automation-final-command.md').readAsStringSync(), contains('FINAL COMMAND: CREATE PULL REQUEST'));
  });
}
