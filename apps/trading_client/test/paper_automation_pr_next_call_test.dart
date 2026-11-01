import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next call create pull request not create file', () {
    final text = File('../../docs/product/paper-automation-pr-next-call.md').readAsStringSync();
    expect(text, contains('NEXT CALL create_pull_request'));
    expect(text, contains('NOT create_file'));
  });
}
