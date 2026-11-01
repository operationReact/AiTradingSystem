import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('absolute stop create PR', () {
    expect(File('../../docs/product/paper-automation-pr-tool-call-stop-final-final.md').readAsStringSync(), contains('ABSOLUTE STOP. CREATE PR'));
  });
}
