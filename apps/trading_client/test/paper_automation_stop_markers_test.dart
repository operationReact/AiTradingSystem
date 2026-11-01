import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop markers create PR', () {
    expect(File('../../docs/product/paper-automation-this-is-ridiculous.md').readAsStringSync(), contains('STOP MARKERS. CREATE PR'));
  });
}
