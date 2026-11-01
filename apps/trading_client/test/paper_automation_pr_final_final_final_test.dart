import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PR final final final', () {
    expect(File('../../docs/product/paper-automation-pr-final-final-final.md').readAsStringSync(), contains('PR FINAL FINAL FINAL'));
  });
}
