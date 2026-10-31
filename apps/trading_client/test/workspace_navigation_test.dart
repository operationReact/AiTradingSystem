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
    final sheetScrollable = find.descendant(
      of: find.byKey(const ValueKey('workspace-navigation-sheet')),
      matching: find.byType(Scrollable),
    ).first;
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
      // The sheet is a lazily built ListView, so lower destinations must be scrolled into the build.
      await tester.scrollUntilVisible(find.text(label), 200, scrollable: sheetScrollable);
      expect(find.text(label), findsOneWidget, reason: 'missing workspace destination $label');
    }

    await tester.scrollUntilVisible(find.text('Scenario & regime evidence'), -200, scrollable: sheetScrollable);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Scenario & regime evidence'));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('workspace-navigation-sheet')), findsNothing, reason: 'destination tap did not close the sheet');
    expect(find.text('Scenario evidence lookup'), findsOneWidget);
    expect(find.byKey(const ValueKey('scenario-run-id')), findsOneWidget);
  });
}
