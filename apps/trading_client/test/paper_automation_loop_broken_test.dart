import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('next call is create pull request', () {
    expect(File('../../docs/product/paper-automation-loop-broken.md').readAsStringSync(), contains('NEXT CALL IS create_pull_request'));
  });
}
