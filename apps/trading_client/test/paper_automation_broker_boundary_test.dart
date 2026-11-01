import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Angel One remains read-only while PAPER automation is independent', () {
    final text = File('../../docs/product/paper-automation-broker-boundary.md').readAsStringSync();
    expect(text, contains('read funds'));
    expect(text, contains('read holdings'));
    expect(text, contains('read positions'));
    expect(text, contains('read order book'));
    expect(text, contains('Not allowed in this release'));
    expect(text, contains('PAPER automation must remain fully testable without an Angel One account connected'));
  });
}
