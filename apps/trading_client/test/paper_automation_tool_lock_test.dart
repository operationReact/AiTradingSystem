import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('tool lock is create pull request', () {
    expect(File('../../docs/product/paper-automation-tool-lock.md').readAsStringSync(), contains('create_pull_request'));
  });
}
