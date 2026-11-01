import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('customer acceptance scenarios cover manual automated risk emergency lifecycle and live boundary', () {
    final text = File('../../docs/product/paper-automation-acceptance-scenarios.md').readAsStringSync();
    for (final heading in <String>[
      'Manual PAPER', 'Automated PAPER', 'Risk rejection', 'Emergency stop',
      'Target/stop lifecycle', 'Restart/reconnect', 'LIVE',
    ]) {
      expect(text, contains('## $heading'));
    }
    expect(text, contains('No customer action in this release can submit a broker order'));
  });
}
