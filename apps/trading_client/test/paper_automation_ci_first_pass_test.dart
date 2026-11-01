import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('first PAPER automation CI pass cannot replace product acceptance', () {
    final text = File('../../docs/product/paper-automation-ci-first-pass.md').readAsStringSync();
    expect(text, contains('fix path/copy/contract defects rather than weakening gates'));
    expect(text, contains('do not rerun unchanged failures'));
    expect(text, contains('do not merge even if CI is green'));
  });
}
