import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create PR no more', () {
    expect(File('../../docs/product/paper-automation-create-pr-no-more.md').readAsStringSync(), contains('CREATE PR. NO MORE'));
  });
}
