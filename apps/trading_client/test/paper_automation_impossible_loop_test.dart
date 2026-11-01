import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('terminate loop create PR', () {
    expect(File('../../docs/product/paper-automation-impossible-loop.md').readAsStringSync(), contains('TERMINATE LOOP: CREATE PR'));
  });
}
