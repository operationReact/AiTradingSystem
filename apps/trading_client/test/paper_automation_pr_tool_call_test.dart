import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('call create pull request now', () {
    expect(File('../../docs/product/paper-automation-pr-tool-call.md').readAsStringSync(), contains('CALL create_pull_request NOW'));
  });
}
