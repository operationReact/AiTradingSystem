import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('endless loop end PR', () {
    expect(File('../../docs/product/paper-automation-pr-endless.md').readAsStringSync(), contains('ENDLESS LOOP END. PR'));
  });
}
