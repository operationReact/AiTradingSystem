import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR next final', () {
    expect(File('../../docs/product/paper-automation-pr-next-final.md').readAsStringSync(), contains('PR NEXT FINAL'));
  });
}
