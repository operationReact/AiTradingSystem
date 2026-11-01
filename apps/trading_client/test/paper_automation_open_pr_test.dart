import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation opens Draft only', () {
    final text = File('../../docs/product/paper-automation-open-pr.md').readAsStringSync();
    expect(text, contains('Open Draft PR now'));
    expect(text, contains('Do not mark Ready or merge'));
  });
}
