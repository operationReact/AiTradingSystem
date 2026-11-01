import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation PR is Draft and not merge-ready', () {
    final text = File('../../docs/product/paper-automation-pr-create-final.md').readAsStringSync();
    expect(text, contains('Create Draft PR now'));
    expect(text, contains('Continue implementation after CI appears'));
    expect(text, contains('Do not merge'));
  });
}
