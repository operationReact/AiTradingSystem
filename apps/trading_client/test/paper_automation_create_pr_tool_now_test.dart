import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create pull request tool now', () {
    expect(File('../../docs/product/paper-automation-create-pr-tool-now.md').readAsStringSync(), contains('CREATE_PULL_REQUEST TOOL NOW'));
  });
}
