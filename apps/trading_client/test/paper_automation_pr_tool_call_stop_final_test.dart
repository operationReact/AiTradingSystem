import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('no more writes PR tool call', () {
    expect(File('../../docs/product/paper-automation-pr-tool-call-stop-final.md').readAsStringSync(), contains('NO MORE WRITES. PR TOOL CALL'));
  });
}
