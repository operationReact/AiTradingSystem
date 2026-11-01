import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('switch tool to create pull request', () {
    expect(File('../../docs/product/paper-automation-switch-tool.md').readAsStringSync(), contains('SWITCH TOOL -> create_pull_request'));
  });
}
