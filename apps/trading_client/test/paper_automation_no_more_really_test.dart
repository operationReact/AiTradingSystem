import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('no more really PR', () {
    expect(File('../../docs/product/paper-automation-no-more-really.md').readAsStringSync(), contains('NO MORE REALLY. PR'));
  });
}
