import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation UI acceptance covers customer controls and lifecycle', () {
    final text = File('../../docs/product/paper-automation-ui-acceptance-checklist.md').readAsStringSync();
    expect(text, contains('Home shows PAPER + Manual/Automated'));
    expect(text, contains('AI/Research clearly distinguishes Manual View & Trade'));
    expect(text, contains('Portfolio shows open position + unrealized P&L'));
    expect(text, contains('LIVE is visibly unavailable/Coming later'));
    expect(text, contains('Angel One is presented as connected/read-only account data'));
    expect(text, contains('Phone-size screenshots are compared with canonical Figma'));
  });
}
