import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('actual final create PR', () {
    expect(File('../../docs/product/paper-automation-actual-final.md').readAsStringSync(), contains('ACTUAL FINAL. CREATE PR'));
  });
}
