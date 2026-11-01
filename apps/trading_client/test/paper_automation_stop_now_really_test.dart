import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('stop now really create PR', () {
    expect(File('../../docs/product/paper-automation-stop-now-really.md').readAsStringSync(), contains('STOP NOW REALLY. CREATE PR'));
  });
}
