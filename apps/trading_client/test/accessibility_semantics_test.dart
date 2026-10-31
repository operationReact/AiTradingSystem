import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/presentation/overview_page.dart';

class _RejectingDashboardRepository implements DashboardRepository {
  @override
  Future<DashboardSummary> fetchSummary() async {
    throw const DashboardHttpFailure('AUTHENTICATION_REJECTED', 'corr-a11y', 401);
  }
}

void main() {
  testWidgets('overview exposes semantic headings and labelled workspace actions', (tester) async {
    final semantics = tester.ensureSemantics();
    try {
      await tester.pumpWidget(
        MaterialApp(
          home: OverviewPage(
            session: OperatorSession(),
            dashboardRepository: _RejectingDashboardRepository(),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.bySemanticsLabel('Market intelligence. Monitor market context, research inputs, instruments, and data quality.'),
        findsOneWidget,
      );
      expect(
        find.bySemanticsLabel('Signals. Review generated candidates and supporting evidence before action.'),
        findsOneWidget,
      );

      final hero = tester.getSemantics(find.text('Research. Validate. Operate with control.'));
      final workspace = tester.getSemantics(find.text('Workspace'));
      final operator = tester.getSemantics(find.text('Operator access'));
      expect(hero.getSemanticsData().flagsCollection.isHeader, isTrue);
      expect(workspace.getSemanticsData().flagsCollection.isHeader, isTrue);
      expect(operator.getSemanticsData().flagsCollection.isHeader, isTrue);
    } finally {
      semantics.dispose();
    }
  });

  testWidgets('access-key authentication error is associated with the input', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: OverviewPage(
          session: OperatorSession(),
          dashboardRepository: _RejectingDashboardRepository(),
        ),
      ),
    );

    final accessKey = find.byKey(const ValueKey('dashboard-access-key'));
    final connect = find.byKey(const ValueKey('dashboard-connect'));

    await tester.ensureVisible(accessKey);
    await tester.enterText(accessKey, 'wrong-key');
    await tester.ensureVisible(connect);
    await tester.pumpAndSettle();
    await tester.tap(connect);
    await tester.pumpAndSettle();

    expect(find.text('The access key was rejected.'), findsWidgets);
    final field = tester.widget<TextField>(accessKey);
    expect(field.decoration?.errorText, 'The access key was rejected.');
  });
}
