import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation Draft PR follows foundation checkpoint', () {
    final text = File('../../docs/product/paper-automation-pr-trigger.md').readAsStringSync();
    expect(text, contains('Open the Draft PR after this commit'));
  });
}
