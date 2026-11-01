import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create PR tool call next', () {
    expect(File('../../docs/product/paper-automation-pr-action-execute-final.md').readAsStringSync(), contains('CREATE PR TOOL CALL NEXT'));
  });
}
