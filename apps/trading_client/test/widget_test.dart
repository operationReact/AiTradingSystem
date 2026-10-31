import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';
import 'package:trading_client/src/theme/app_theme.dart';

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

    final signalsCard = find.byKey(const ValueKey('workspace-/signals'));
    await tester.ensureVisible(signalsCard);
    await tester.pumpAndSettle();
    await tester.tap(signalsCard);
    await tester.pumpAndSettle();

    expect(find.text('Signals & research'), findsOneWidget);
    expect(find.text('Development operator session'), findsNothing);
  });

  testWidgets('appearance switching preserves current route and operator session', (tester) async {
    final session = OperatorSession()..setOperatorKey('session-key');
    await tester.pumpWidget(
      TradingApp(repositoryFactory: SmokeHealthRepository.new, session: session),
    );

    final signalsCard = find.byKey(const ValueKey('workspace-/signals'));
    await tester.ensureVisible(signalsCard);
    await tester.tap(signalsCard);
    await tester.pumpAndSettle();

    expect(find.text('Signals & research'), findsOneWidget);

    await tester.tap(find.text('Clean'));
    await tester.pumpAndSettle();

    final styleControl = tester.widget<SegmentedButton<DesignStyle>>(
      find.byKey(const ValueKey('global-design-style-control')),
    );
    expect(styleControl.selected, {DesignStyle.cleanModern});
    expect(find.text('Signals & research'), findsOneWidget);
    expect(session.operatorKey, 'session-key');
  });

  testWidgets('appearance switching does not clear unsent form state', (tester) async {
    final session = OperatorSession();
    await tester.pumpWidget(
      TradingApp(repositoryFactory: SmokeHealthRepository.new, session: session),
    );

    final fieldFinder = find.byKey(const ValueKey('dashboard-access-key'));
    await tester.ensureVisible(fieldFinder);
    await tester.enterText(fieldFinder, 'local-draft-key');

    await tester.tap(find.text('Clean'));
    await tester.pumpAndSettle();

    final field = tester.widget<TextField>(fieldFinder);
    expect(field.controller?.text, 'local-draft-key');
    expect(session.operatorKey, isNull);
  });

  testWidgets('global appearance overlay does not block underlying page controls', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    final session = OperatorSession();
    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: SmokeHealthRepository.new,
        session: session,
        initialRoute: '/operations/health',
      ),
    );
    await tester.pumpAndSettle();

    final field = find.byKey(const ValueKey('operator-key-field'));
    final connect = find.byKey(const ValueKey('connect-health'));
    await tester.enterText(field, 'native-test-key');
    await tester.ensureVisible(connect);
    await tester.tap(connect);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);
    expect(session.operatorKey, 'native-test-key');
  });
}
