import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation Draft opening keeps integration pending', () {
    final text = File('../../docs/product/paper-automation-open-draft-now.md').readAsStringSync();
    expect(text, contains('Create the Draft PR now'));
    expect(text, contains('continue authoritative integration'));
    expect(text, contains('Do not merge'));
  });
}
