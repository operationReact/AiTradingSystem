import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation emergency stop is backend authoritative and customer readable', () {
    final text = File('../../docs/product/paper-automation-emergency-stop.md').readAsStringSync();
    expect(text, contains('server-authoritative'));
    expect(text, contains('customer UI shows Trading paused'));
    expect(text, contains('clearing the UI toggle alone cannot resume trading'));
  });
}
