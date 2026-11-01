import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('mobile controls remain preferences rather than trading authority', () {
    final text = File('../../docs/product/paper-automation-security-boundary.md').readAsStringSync();
    expect(text, contains('Client-side PAPER/LIVE or Manual/Automated controls are requests/preferences'));
    expect(text, contains('not authority to bypass risk or release gates'));
    expect(text, contains('Angel One credentials/tokens must never be exposed'));
  });
}
