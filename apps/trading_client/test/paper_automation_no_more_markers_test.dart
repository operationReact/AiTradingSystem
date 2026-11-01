import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('no more markers', () {
    expect(File('../../docs/product/paper-automation-no-more-markers.md').readAsStringSync(), contains('NO MORE MARKERS'));
  });
}
