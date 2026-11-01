import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Flutter is not PAPER ledger or risk authority', () {
    final text = File('../../docs/product/paper-automation-data-authority.md').readAsStringSync();
    expect(text, contains('authoritative customer state must come from the existing persisted backend services'));
    expect(text, contains('Flutter renders and requests changes; it does not become the ledger or risk authority'));
    expect(text, contains('reread authoritative backend state'));
  });
}
