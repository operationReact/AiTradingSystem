import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('do GitHub create pull request', () {
    expect(File('../../docs/product/paper-automation-next-tool-final.md').readAsStringSync(), contains('DO IT: GitHub.create_pull_request'));
  });
}
