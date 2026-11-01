import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation Draft-open readiness is not release readiness', () {
    final text = File('../../docs/product/paper-automation-pr-ready-to-open.md').readAsStringSync();
    expect(text, contains('Ready to open Draft PR: YES'));
    expect(text, contains('Ready to merge: NO'));
    expect(text, contains('Phase complete: NO'));
    expect(text, contains('LIVE broker submission enabled: NO'));
  });
}
