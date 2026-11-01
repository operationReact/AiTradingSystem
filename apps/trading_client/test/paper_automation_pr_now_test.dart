import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next PAPER automation action is Draft PR creation', () {
    final text = File('../../docs/product/paper-automation-pr-now.md').readAsStringSync();
    expect(text, contains('Draft PR creation is now the next action'));
  });
}
