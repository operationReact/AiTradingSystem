import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation CI cursor rejects stale green evidence', () {
    final text = File('../../docs/product/paper-automation-ci-cursor.md').readAsStringSync();
    expect(text, contains('exact PR head'));
    expect(text, contains('not merge authorization'));
    expect(text, contains('Do not use workflow evidence from PR #206 or an earlier head'));
  });
}
