import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('execute Draft PR creation now', () {
    expect(File('../../docs/product/paper-automation-pr-action-execute.md').readAsStringSync(), contains('EXECUTE DRAFT PR CREATION NOW'));
  });
}
