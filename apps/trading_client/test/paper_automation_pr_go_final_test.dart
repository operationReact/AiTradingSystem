import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('go now', () {
    expect(File('../../docs/product/paper-automation-pr-go-final.md').readAsStringSync(), contains('GO NOW'));
  });
}
