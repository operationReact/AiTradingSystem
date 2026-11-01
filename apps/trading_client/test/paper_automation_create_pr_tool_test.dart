import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next tool must create pull request', () {
    expect(File('../../docs/product/paper-automation-create-pr-tool.md').readAsStringSync(), contains('create_pull_request'));
  });
}
