import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class _UnusedHealthRepository implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() => throw StateError('health fetch not expected');
}

void main() {
  testWidgets('global workspace navigation exposes corrected production Flutter screens', (tester) async {
    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: _UnusedHealthRepository.new,
        session: OperatorSession(),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('global-workspace-navigation')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('workspace-navigation-sheet')), findsOneWidget);
    for (final label in [
      'Overview',
      'Instruments',
      'Market data',
      'Research',
      'Scenario & regime evidence',
      'PAPER backtests',
      'PAPER portfolio',
      'PAPER orders',
      'Risk & approvals',
      'News evidence',
      'Notifications',
      'Audit trail',
      'Trading settings',
      'System health',
    ]) {
      expect(find.text(label), findsOneWidget, reason: 'missing workspace destination $label');
    }

    await tester.tap(find.text('Scenario & regime evidence'));
    await tester.pumpAndSettle();
    expect(find.text('Scenario evidence lookup'), findsOneWidget);
    expect(find.byKey(const ValueKey('scenario-run-id')), findsOneWidget);
  });
}
