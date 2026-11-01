import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop creating files create PR', () {
    expect(File('../../docs/product/paper-automation-stop-creating-files.md').readAsStringSync(), contains('STOP CREATING FILES. CREATE PR'));
  });
}
