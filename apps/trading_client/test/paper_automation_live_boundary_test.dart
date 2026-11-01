import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('LIVE remains visibly future-only and broker-disabled', () {
    final text = File('../../docs/product/paper-automation-live-boundary.md').readAsStringSync();
    expect(text, contains('liveAvailable = false'));
    expect(text, contains('brokerSubmissionAvailable = false'));
    expect(text, contains('selecting/requesting LIVE fails closed'));
    expect(text, contains('Nothing in this branch constitutes that approval'));
  });
}
