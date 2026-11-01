import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation Draft creation is not release action', () {
    final text = File('../../docs/product/paper-automation-final-pre-pr.md').readAsStringSync();
    expect(text, contains('create Draft PR'));
    expect(text, contains('No merge, deployment or LIVE action'));
  });
}
