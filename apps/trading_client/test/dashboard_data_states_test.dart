import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/presentation/overview_page.dart';

class QueueDashboardRepository implements DashboardRepository {
  QueueDashboardRepository(this.responses);

  final List<Future<DashboardSummary> Function()> responses;
  int calls = 0;

  @override
  Future<DashboardSummary> fetchSummary() {
    final index = calls;
    calls += 1;
    return responses[index]();
  }
}

DashboardSummary summaryWith({
  String systemStatus = 'OK',
  bool cashReconciled = true,
  bool newOrdersHalted = false,
}) =>
    DashboardSummary(
      mode: 'PAPER',
      liveTradingEnabled: false,
      newOrdersHalted: newOrdersHalted,
      capital: 100000,
      availableCash: 90000,
      investedCapital: 10000,
      marketValue: 10000,
      equity: 100000,
      realizedPnl: 0,
      unrealizedPnl: 0,
      dailyPnl: 0,
      openPositions: 1,
      exposurePercent: 10,
      cashReconciled: cashReconciled,
      markPriceSource: 'TEST',
      systemStatus: systemStatus,
      asOf: DateTime.utc(2026, 10, 30, 12),
    );

OperatorSession authenticatedSession() => OperatorSession()..setOperatorKey('test-session');

Future<void> pumpOverview(
  WidgetTester tester,
  DashboardRepository repository, {
  OperatorSession? session,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      home: OverviewPage(
        session: session ?? authenticatedSession(),
        dashboardRepository: repository,
      ),
    ),
  );
  await tester.pump();
}

void main() {
  testWidgets('unauthenticated overview requires secure sign-in without requesting data', (tester) async {
    final repository = QueueDashboardRepository([() async => summaryWith()]);
    await pumpOverview(tester, repository, session: OperatorSession());
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('dashboard-sign-in-required')), findsOneWidget);
    expect(find.text('Secure sign-in is required'), findsOneWidget);
    expect(find.byKey(const ValueKey('dashboard-access-key')), findsNothing);
    expect(find.byKey(const ValueKey('dashboard-connect')), findsNothing);
    expect(repository.calls, 0);
  });

  testWidgets('shows loading while authenticated authoritative request is pending', (tester) async {
    final pending = Completer<DashboardSummary>();
    final repository = QueueDashboardRepository([() => pending.future]);
    await pumpOverview(tester, repository);
    await tester.pump();

    expect(find.byKey(const ValueKey('dashboard-state-loading')), findsOneWidget);
    pending.complete(summaryWith());
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('dashboard-state-ready')), findsOneWidget);
  });

  testWidgets('maps OK to ready and server STALE to stale', (tester) async {
    final repository = QueueDashboardRepository([
      () async => summaryWith(),
      () async => summaryWith(systemStatus: 'STALE'),
    ]);
    await pumpOverview(tester, repository);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('dashboard-state-ready')), findsOneWidget);

    final refresh = find.byKey(const ValueKey('dashboard-refresh'));
    await tester.ensureVisible(refresh);
    await tester.tap(refresh);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('dashboard-state-stale')), findsOneWidget);
  });

  testWidgets('maps halted and reconciliation failures to degraded', (tester) async {
    final repository = QueueDashboardRepository([
      () async => summaryWith(systemStatus: 'HALTED', newOrdersHalted: true),
    ]);
    await pumpOverview(tester, repository);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('dashboard-state-degraded')), findsOneWidget);
    expect(find.text('ORDERS HALTED'), findsOneWidget);
  });

  testWidgets('fails closed when the server returns an unrecognized status', (tester) async {
    final repository = QueueDashboardRepository([
      () async => summaryWith(systemStatus: 'UNRECOGNIZED_SERVER_STATUS'),
    ]);
    await pumpOverview(tester, repository);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('dashboard-state-unknown')), findsOneWidget);
    expect(find.text('Operating state UNKNOWN'), findsOneWidget);
  });

  testWidgets('shows an explicit error when no authoritative evidence was loaded', (tester) async {
    final repository = QueueDashboardRepository([
      () async => throw const DashboardTransportFailure('NETWORK_OR_PARSE_FAILURE', 'corr-1'),
    ]);
    await pumpOverview(tester, repository);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('dashboard-state-error')), findsOneWidget);
    expect(find.text('Unable to reach the trading service.'), findsOneWidget);
  });

  testWidgets('keeps last-known evidence visible but marks it stale after refresh failure', (tester) async {
    final repository = QueueDashboardRepository([
      () async => summaryWith(),
      () async => throw const DashboardTransportFailure('NETWORK_OR_PARSE_FAILURE', 'corr-2'),
    ]);
    await pumpOverview(tester, repository);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('dashboard-state-ready')), findsOneWidget);
    expect(find.text('₹100000.00'), findsOneWidget);

    final refresh = find.byKey(const ValueKey('dashboard-refresh'));
    await tester.ensureVisible(refresh);
    await tester.tap(refresh);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('dashboard-state-stale')), findsOneWidget);
    expect(find.text('Last-known state only'), findsOneWidget);
    expect(find.text('₹100000.00'), findsOneWidget);
    expect(repository.calls, 2);
  });
}
