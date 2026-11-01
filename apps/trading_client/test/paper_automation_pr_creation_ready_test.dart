import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next PAPER automation step is Draft PR and real integration', () {
    final text = File('../../docs/product/paper-automation-pr-creation-ready.md').readAsStringSync();
    expect(text, contains('Draft PR creation is the next repository mutation'));
    expect(text, contains('exact-head CI and authoritative-service inspection'));
  });
}
