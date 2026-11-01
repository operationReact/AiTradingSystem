import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('end loop now', () {
    expect(File('../../docs/product/paper-automation-end-loop.md').readAsStringSync(), contains('END LOOP NOW'));
  });
}
