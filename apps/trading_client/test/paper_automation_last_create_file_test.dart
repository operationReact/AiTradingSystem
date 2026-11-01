import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next create pull request', () {
    final text = File('../../docs/product/paper-automation-last-create-file.md').readAsStringSync();
    expect(text, contains('THIS IS THE LAST create_file'));
    expect(text, contains('NEXT = create_pull_request'));
  });
}
