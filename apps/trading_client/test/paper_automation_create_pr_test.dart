import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('create PR now', () {
    expect(File('../../docs/product/paper-automation-create-pr.md').readAsStringSync(), contains('CREATE PR NOW'));
  });
}
