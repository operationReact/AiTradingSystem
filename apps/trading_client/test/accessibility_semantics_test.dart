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
  testWidgets('customer Home exposes semantic headings and labelled product actions', (tester) async {
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
        find.bySemanticsLabel('Markets. Search stocks and explore recent price action.'),
        findsOneWidget,
      );
      expect(
        find.bySemanticsLabel('AI Research. Discover ranked opportunities with entry, stop and target levels.'),
        findsOneWidget,
      );

      final hero = tester.getSemantics(find.text('See the market. Find the setup. Test it safely.'));
      final quickAccess = tester.getSemantics(find.text('Quick access'));
      final signIn = tester.getSemantics(find.text('Sign in required'));
      expect(hero.getSemanticsData().flagsCollection.isHeader, isTrue);
      expect(quickAccess.getSemanticsData().flagsCollection.isHeader, isTrue);
      expect(signIn.getSemanticsData().flagsCollection.isHeader, isTrue);
    } finally {
      semantics.dispose();
    }
  });

  testWidgets('unauthenticated Home exposes secure sign-in without developer credentials', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: OverviewPage(
          session: OperatorSession(),
          dashboardRepository: _RejectingDashboardRepository(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('dashboard-sign-in-required')), findsOneWidget);
    expect(find.text('Secure sign-in is required'), findsOneWidget);
    expect(find.byKey(const ValueKey('dashboard-access-key')), findsNothing);
    expect(find.byKey(const ValueKey('dashboard-connect')), findsNothing);
    expect(find.textContaining('operator access-key'), findsNothing);
  });
}
