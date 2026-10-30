import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class SmokeHealthRepository implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() async => HealthSnapshot.fromJson({
        'checkedAt': '2026-10-30T10:00:00Z',
        'state': 'HEALTHY',
        'executionMode': 'PAPER',
        'liveAvailable': false,
        'emergencyStopActive': false,
        'components': const [],
      });
}

void main() {
  testWidgets('boots into the product workspace without framework-facing copy', (tester) async {
    final session = OperatorSession();
    await tester.pumpWidget(
      TradingApp(repositoryFactory: SmokeHealthRepository.new, session: session),
    );

    expect(find.text('AiTradingSystem'), findsOneWidget);
    expect(find.text('Research. Validate. Operate with control.'), findsOneWidget);
    expect(find.textContaining('Flutter'), findsNothing);
    expect(find.text('Development operator session'), findsNothing);
    expect(find.text('Market intelligence'), findsOneWidget);
    expect(find.text('Signals'), findsOneWidget);
    expect(find.text('Portfolio'), findsOneWidget);
    expect(find.text('Risk controls'), findsOneWidget);
  });

  testWidgets('workspace cards navigate to product destinations', (tester) async {
    final session = OperatorSession();
    await tester.pumpWidget(
      TradingApp(repositoryFactory: SmokeHealthRepository.new, session: session),
    );

    await tester.tap(find.byKey(const ValueKey('workspace-/signals')));
    await tester.pumpAndSettle();

    expect(find.text('Signals'), findsWidgets);
    expect(find.text('Candidate research, provenance, and supporting evidence'), findsOneWidget);
    expect(find.text('Development operator session'), findsNothing);
  });
}
