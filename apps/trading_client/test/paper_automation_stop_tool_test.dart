import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop create file tool', () {
    expect(File('../../docs/product/paper-automation-stop-tool.md').readAsStringSync(), contains('STOP create_file TOOL'));
  });
}
