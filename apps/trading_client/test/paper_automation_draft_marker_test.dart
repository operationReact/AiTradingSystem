import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation remains Draft until definition of done', () {
    final text = File('../../docs/product/paper-automation-draft-marker.md').readAsStringSync();
    expect(text, contains('DRAFT'));
    expect(text, contains('Do not merge until'));
  });
}
