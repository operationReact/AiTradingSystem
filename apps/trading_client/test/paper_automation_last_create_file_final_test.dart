import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create pull request next', () {
    expect(File('../../docs/product/paper-automation-last-create-file-final.md').readAsStringSync(), contains('CREATE_PULL_REQUEST NEXT'));
  });
}
