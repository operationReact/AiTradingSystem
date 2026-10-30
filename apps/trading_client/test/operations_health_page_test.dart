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
        {'component': 'database', 'state': 'HEALTHY', 'asOf': '2026-10-30T10:00:00Z', 'ageSeconds': 0, 'reasonCode': 'READ_SUCCEEDED'},
        {'component': 'scanner', 'state': 'UNKNOWN', 'asOf': null, 'ageSeconds': null, 'reasonCode': 'NO_RUN_EVIDENCE'},
      ],
    });

TradingApp healthApp({
  required HealthRepository repository,
  required OperatorSession session,
}) =>
    TradingApp(
      repositoryFactory: () => repository,
      session: session,
      initialRoute: '/operations/health',
    );

void main() {
  testWidgets('does not call backend before explicit credential submission', (tester) async {
    final session = OperatorSession();
    final repository = FakeHealthRepository(() async => degradedSnapshot());
    await tester.pumpWidget(healthApp(repository: repository, session: session));
    expect(find.byKey(const ValueKey('health-empty')), findsOneWidget);
    expect(repository.calls, 0);
  });

  testWidgets('renders degraded and UNKNOWN evidence after explicit refresh', (tester) async {
    final session = OperatorSession();
    final repository = FakeHealthRepository(() async => degradedSnapshot());
    await tester.pumpWidget(healthApp(repository: repository, session: session));
    await tester.enterText(find.byKey(const ValueKey('operator-key-field')), 'development-key');
    await tester.tap(find.byKey(const ValueKey('connect-health')));
    await tester.pumpAndSettle();
    expect(repository.calls, 1);
    expect(find.text('DEGRADED'), findsWidgets);
    expect(find.text('UNKNOWN'), findsWidgets);
    expect(find.textContaining('NO_RUN_EVIDENCE'), findsOneWidget);
  });

  testWidgets('style switch does not issue another backend request', (tester) async {
    final session = OperatorSession();
    final repository = FakeHealthRepository(() async => degradedSnapshot());
    await tester.pumpWidget(healthApp(repository: repository, session: session));
    await tester.enterText(find.byKey(const ValueKey('operator-key-field')), 'development-key');
    await tester.tap(find.byKey(const ValueKey('connect-health')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Clean'));
    await tester.pumpAndSettle();
    expect(repository.calls, 1);
    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);
  });

  testWidgets('recoverable failure offers explicit retry', (tester) async {
    final session = OperatorSession();
    var attempt = 0;
    final repository = FakeHealthRepository(() async {
      attempt += 1;
      if (attempt == 1) throw const HealthTransportFailure('NETWORK_OR_PARSE_FAILURE', 'corr-1');
      return degradedSnapshot();
    });
    await tester.pumpWidget(healthApp(repository: repository, session: session));
    await tester.enterText(find.byKey(const ValueKey('operator-key-field')), 'development-key');
    await tester.tap(find.byKey(const ValueKey('connect-health')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('health-error')), findsOneWidget);
    expect(find.text('Correlation: corr-1'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('health-retry')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);
    expect(repository.calls, 2);
  });
}
