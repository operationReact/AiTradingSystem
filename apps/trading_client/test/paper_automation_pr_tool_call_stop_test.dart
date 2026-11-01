import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop writes create PR', () {
    expect(File('../../docs/product/paper-automation-pr-tool-call-stop.md').readAsStringSync(), contains('STOP WRITES. CREATE PR'));
  });
}
