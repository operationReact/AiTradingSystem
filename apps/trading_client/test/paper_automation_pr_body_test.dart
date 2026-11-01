import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('PAPER automation Draft PR body keeps real Flutter acceptance pending', () {
    final text = File('../../docs/product/paper-automation-pr-body.md').readAsStringSync();
    expect(text, contains('Why this remains Draft'));
    expect(text, contains('Actual Flutter Home/AI/Portfolio/More controls and lifecycle UI are also still pending'));
    expect(text, contains('canonical Figma phone evidence'));
    expect(text, contains('PAPER only. LIVE unavailable. Broker submission unavailable'));
  });
}
