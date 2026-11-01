import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next tool is GitHub create pull request', () {
    expect(File('../../docs/product/paper-automation-next-tool.md').readAsStringSync(), contains('GitHub.create_pull_request'));
  });
}
