import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('last tool marker PR next', () {
    expect(File('../../docs/product/paper-automation-last-tool-marker.md').readAsStringSync(), contains('LAST TOOL MARKER. PR NEXT'));
  });
}
