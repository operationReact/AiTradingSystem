import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('draft create pull request now', () {
    final text = File('../../docs/product/paper-automation-pr-tool-call-final.md').readAsStringSync();
    expect(text, contains('create_pull_request'));
    expect(text, contains('DRAFT=true'));
  });
}
