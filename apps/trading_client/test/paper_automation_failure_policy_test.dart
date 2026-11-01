import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('automation failures fail closed with customer-safe UI', () {
    final text = File('../../docs/product/paper-automation-failure-policy.md').readAsStringSync();
    expect(text, contains('prefer no new trade over guessing'));
    expect(text, contains('without exposing raw internal codes'));
    expect(text, contains('must not invent a second recovery policy'));
  });
}
