import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('mobile product contract keeps PAPER automation customer safe', () {
    final contract = File('../../docs/product/paper-automation-api-contract.md');
    expect(contract.existsSync(), isTrue);
    final text = contract.readAsStringSync();

    expect(text, contains('"tradingMode": "PAPER"'));
    expect(text, contains('"executionMode": "MANUAL"'));
    expect(text, contains('"liveAvailable": false'));
    expect(text, contains('must not manufacture an authoritative AI opportunity'));
    expect(text, contains('LIVE broker submission remains unavailable'));
    expect(text, contains('Angel One is read-only'));
  });
}
