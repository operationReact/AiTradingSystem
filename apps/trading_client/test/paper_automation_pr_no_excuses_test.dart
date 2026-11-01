import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('no excuses create PR', () {
    expect(File('../../docs/product/paper-automation-pr-no-excuses.md').readAsStringSync(), contains('NO EXCUSES. CREATE PR'));
  });
}
