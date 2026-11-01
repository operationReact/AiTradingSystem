import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('no create file create pull request', () {
    expect(File('../../docs/product/paper-automation-no-create-file.md').readAsStringSync(), contains('NO CREATE_FILE. CREATE_PULL_REQUEST'));
  });
}
