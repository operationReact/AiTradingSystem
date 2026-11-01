import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation status does not claim unexecuted Flutter validation', () {
    final text = File('../../docs/product/paper-automation-ci-note.md').readAsStringSync();
    expect(text, contains('has not executed a local Maven/Flutter build'));
    expect(text, contains('Draft PR\'s exact-head GitHub Actions'));
  });
}
