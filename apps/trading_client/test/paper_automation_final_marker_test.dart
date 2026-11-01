import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('final PAPER automation marker points to Draft PR', () {
    expect(File('../../docs/product/paper-automation-pr-final-marker.md').readAsStringSync(), contains('DRAFT PR NEXT'));
  });
}
