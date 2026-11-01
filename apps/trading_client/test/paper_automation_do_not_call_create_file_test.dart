import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('call create pull request instead', () {
    final text = File('../../docs/product/paper-automation-do-not-call-create-file.md').readAsStringSync();
    expect(text, contains('DO NOT CALL create_file AGAIN'));
    expect(text, contains('CALL create_pull_request'));
  });
}
