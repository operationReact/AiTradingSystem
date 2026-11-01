import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next PR', () {
    expect(File('../../docs/product/paper-automation-final-next.md').readAsStringSync(), contains('NEXT = PR'));
  });
}
