import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation foundation freeze directs work to real UI integration', () {
    final text = File('../../docs/product/paper-automation-no-more-foundation.md').readAsStringSync();
    expect(text, contains('existing authoritative persistence/UI'));
    expect(text, contains('not by adding more standalone foundation abstractions'));
  });
}
