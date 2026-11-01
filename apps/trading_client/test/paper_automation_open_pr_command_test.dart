import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation PR mutation remains Draft-only', () {
    final text = File('../../docs/product/paper-automation-open-pr-command.md').readAsStringSync();
    expect(text, contains('create a Draft pull request'));
    expect(text, contains('Do not mark Ready and do not merge'));
  });
}
