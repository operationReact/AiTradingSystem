import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('use create pull request next', () {
    expect(File('../../docs/product/paper-automation-final-instruction.md').readAsStringSync(), contains('USE create_pull_request NEXT'));
  });
}
