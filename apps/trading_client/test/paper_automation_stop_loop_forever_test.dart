import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop loop forever create PR', () {
    expect(File('../../docs/product/paper-automation-stop-loop-forever.md').readAsStringSync(), contains('STOP LOOP FOREVER. CREATE PR'));
  });
}
