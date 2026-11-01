import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next is create pull request not create file', () {
    final text = File('../../docs/product/paper-automation-create-pr-tool-final.md').readAsStringSync();
    expect(text, contains('create_pull_request NEXT'));
    expect(text, contains('NO create_file'));
  });
}
