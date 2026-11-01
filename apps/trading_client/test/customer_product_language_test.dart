import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('primary customer surfaces do not expose internal platform vocabulary', () {
    const customerSurfacePaths = <String>[
      'lib/src/presentation/overview_page.dart',
      'lib/src/presentation/market_data_page.dart',
      'lib/src/presentation/research_page.dart',
      'lib/src/presentation/paper_portfolio_page.dart',
      'lib/src/presentation/paper_orders_page.dart',
    ];

    const forbiddenTerms = <String>[
      'authoritative',
      'provenance',
      'reconciliation',
      'system health',
      'audit',
      'sync catalogue',
      'operator access',
      'access key',
      'AUTHENTICATION_REQUIRED',
    ];

    final violations = <String>[];
    for (final path in customerSurfacePaths) {
      final file = File(path);
      expect(file.existsSync(), isTrue, reason: 'Missing customer surface: $path');
      final source = file.readAsStringSync().toLowerCase();
      for (final term in forbiddenTerms) {
        if (source.contains(term.toLowerCase())) {
          violations.add('$path -> $term');
        }
      }
    }

    expect(
      violations,
      isEmpty,
      reason: 'Customer UI must use product language; move operational plumbing to advanced/admin surfaces. Violations: ${violations.join(', ')}',
    );
  });
}
