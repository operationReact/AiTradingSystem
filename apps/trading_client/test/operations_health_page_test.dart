import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class FakeHealthRepository implements HealthRepository {
  FakeHealthRepository(this.result);
  final Future<HealthSnapshot> Function() result;
  int calls = 0;

  @override
  Future<HealthSnapshot> fetch() {
    calls += 1;
    return result();
  }
}

HealthSnapshot degradedSnapshot() => HealthSnapshot.fromJson({
      'checkedAt': '2026-10-30T10:00:00Z',
      'state': 'DEGRADED',
      'executionMode': 'PAPER',
      'liveAvailable': false,
      'emergencyStopActive': null,
      'components': [
        {
          'component': 'database',
          'state': 'HEALTHY',
          'asOf': '2026-10-30T10:00:00Z',
          'ageSeconds': 0,
          'reasonCode': 'READ_SUCCEEDED'
        },
        {
          'component': 'scanner',
          'state': 'UNKNOWN',
          'asOf': null,
          'ageSeconds': null,
          'reasonCode': 'NO_RUN_EVIDENCE'
        },
      ],
    });

TradingApp healthApp({required HealthRepository repository, required OperatorSession session}) => TradingApp(
      repositoryFactory: () => repository,
      session: session,
      initialRoute: '/operations/health',
    );

void main() {
  testWidgets('unauthenticated health stays fail closed without exposing operator credentials', (tester) async {
    final session = OperatorSession();
    final repository = FakeHealthRepository(() async => degradedSnapshot());
    await tester.pumpWidget(healthApp(repository: repository, session: session));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('health-sign-in-required')), findsWidgets);
    expect(find.text('Sign in to view system health'), findsWidgets);
    expect(find.byKey(const ValueKey('operator-key-field')), findsNothing);
    expect(find.text('Development operator session'), findsNothing);
    expect(repository.calls, 0);
  });

  testWidgets('authenticated session renders health state without raw backend reason codes', (tester) async {
    final session = OperatorSession()..setOperatorKey('test-session');
    final repository = FakeHealthRepository(() async => degradedSnapshot());
    await tester.pumpWidget(healthApp(repository: repository, session: session));
    await tester.pumpAndSettle();

    expect(repository.calls, greaterThanOrEqualTo(1));
    expect(find.text('DEGRADED'), findsWidgets);
    expect(find.text('UNKNOWN'), findsWidgets);
    expect(find.textContaining('NO_RUN_EVIDENCE'), findsNothing);
    expect(find.textContaining('READ_SUCCEEDED'), findsNothing);
  });

  testWidgets('summary metrics expose stable product accessibility labels', (tester) async {
    final semantics = tester.ensureSemantics();
    try {
      final session = OperatorSession()..setOperatorKey('test-session');
      final repository = FakeHealthRepository(() async => degradedSnapshot());
      await tester.pumpWidget(healthApp(repository: repository, session: session));
      await tester.pumpAndSettle();

      expect(find.bySemanticsLabel('Overall DEGRADED'), findsWidgets);
      expect(find.bySemanticsLabel('Execution PAPER'), findsWidgets);
      expect(find.bySemanticsLabel('Live trading LOCKED'), findsWidgets);
      expect(find.bySemanticsLabel('Emergency stop UNKNOWN'), findsWidgets);
    } finally {
      semantics.dispose();
    }
  });

  testWidgets('health page has no duplicate appearance controls or development-session UI', (tester) async {
    final session = OperatorSession()..setOperatorKey('test-session');
    final repository = FakeHealthRepository(() async => degradedSnapshot());
    await tester.pumpWidget(healthApp(repository: repository, session: session));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('design-style-control')), findsNothing);
    expect(find.byKey(const ValueKey('theme-mode-control')), findsNothing);
    expect(find.text('Development operator session'), findsNothing);
    expect(repository.calls, greaterThanOrEqualTo(1));
    expect(find.byKey(const ValueKey('health-summary')), findsWidgets);
  });

  testWidgets('recoverable failure uses customer copy and offers retry', (tester) async {
    final session = OperatorSession()..setOperatorKey('test-session');
    var attempt = 0;
    final repository = FakeHealthRepository(() async {
      attempt += 1;
      if (attempt <= 2) {
        throw const HealthTransportFailure('NETWORK_OR_PARSE_FAILURE', 'corr-1');
      }
      return degradedSnapshot();
    });

    await tester.pumpWidget(healthApp(repository: repository, session: session));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('health-error')), findsWidgets);
    expect(find.text('System health is temporarily unavailable'), findsWidgets);
    expect(find.text('NETWORK_OR_PARSE_FAILURE'), findsNothing);
    expect(find.text('Reference corr-1'), findsWidgets);

    await tester.tap(find.byKey(const ValueKey('health-retry')).last);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('health-summary')), findsWidgets);
    expect(repository.calls, greaterThanOrEqualTo(3));
  });
}
