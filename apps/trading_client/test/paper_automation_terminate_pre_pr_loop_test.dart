import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create PAPER automation PR now', () {
    expect(File('../../docs/product/paper-automation-pr-final-marker-5.md').readAsStringSync(), contains('CREATE PR'));
  });
}
