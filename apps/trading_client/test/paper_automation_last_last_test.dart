import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create pull request', () {
    expect(File('../../docs/product/paper-automation-last-last.md').readAsStringSync(), contains('create_pull_request'));
  });
}
