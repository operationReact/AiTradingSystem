import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('remaining PAPER automation work includes real Flutter broker-readonly and device acceptance', () {
    final text = File('../../docs/product/paper-automation-implementation-order.md').readAsStringSync();
    expect(text, contains('Flutter account mode controls'));
    expect(text, contains('Flutter AI/Research automated lifecycle'));
    expect(text, contains('Flutter Portfolio open/closed P&L/history states'));
    expect(text, contains('Angel One connect/read-only'));
    expect(text, contains('backend-connected Android physical-device acceptance'));
  });
}
