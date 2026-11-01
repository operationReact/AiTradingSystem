import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('opening PAPER automation Draft does not waive rendered UI blockers', () {
    final text = File('../../docs/product/paper-automation-open-pr-now.md').readAsStringSync();
    expect(text, contains('open the PR now as Draft'));
    expect(text, contains('does not waive any merge blocker'));
    expect(text, contains('rendered Flutter UI layers'));
  });
}
