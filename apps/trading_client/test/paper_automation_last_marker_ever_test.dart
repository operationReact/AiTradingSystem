import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('last marker ever create PR', () {
    expect(File('../../docs/product/paper-automation-last-marker-ever.md').readAsStringSync(), contains('LAST MARKER EVER. CREATE PR'));
  });
}
