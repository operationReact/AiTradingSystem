import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('end loop and create Draft PR', () {
    expect(File('../../docs/product/paper-automation-pr-tool-call-final-final.md').readAsStringSync(), contains('END LOOP'));
  });
}
