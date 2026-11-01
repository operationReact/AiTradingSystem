import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation Draft readiness keeps safety explicit', () {
    final text = File('../../docs/product/paper-automation-ready-for-draft-pr.md').readAsStringSync();
    expect(text, contains('Open Draft PR'));
    expect(text, contains('Keep LIVE/broker submission disabled'));
    expect(text, contains('keep PR Draft'));
  });
}
