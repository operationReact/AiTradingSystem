import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation opens Draft now', () {
    final text = File('../../docs/product/paper-automation-pr-trigger-final.md').readAsStringSync();
    expect(text, contains('Open Draft PR now'));
  });
}
