import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final tool is create pull request', () {
    expect(File('../../docs/product/paper-automation-final-tool.md').readAsStringSync(), contains('FINAL TOOL = create_pull_request'));
  });
}
