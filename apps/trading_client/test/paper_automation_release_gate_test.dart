import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation release gate keeps LIVE broker submission disabled', () {
    final gate = File('../../docs/product/paper-automation-release-gate.md');
    expect(gate.existsSync(), isTrue);

    final text = gate.readAsStringSync();
    expect(text, contains('Trading mode: `PAPER` or `LIVE`'));
    expect(text, contains('Execution mode: `MANUAL` or `AUTOMATED`'));
    expect(text, contains('Every trade path must pass the same server-authoritative risk evaluation'));
    expect(text, contains('emergency-stop state'));
    expect(text, contains('idempotent simulated orders'));
    expect(text, contains('LIVE broker submission remains disabled'));
    expect(text, isNot(contains('enable LIVE broker order submission')));
  });
}
