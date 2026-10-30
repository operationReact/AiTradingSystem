import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class _NoopHealthRepository implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() => throw StateError('health fetch not expected in golden');
}

Future<void> _pumpGolden(
  WidgetTester tester, {
  required Size viewport,
  required String designLabel,
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = viewport;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });

  await tester.pumpWidget(
    TradingApp(
      repositoryFactory: _NoopHealthRepository.new,
      session: OperatorSession(),
    ),
  );
  await tester.pumpAndSettle();

  expect(find.text('AiTradingSystem'), findsOneWidget, reason: designLabel);
  expect(find.byKey(const ValueKey('dashboard-state-empty')), findsOneWidget);
}

void main() {
  testWidgets('Neon dark mobile 390x844 overview visual baseline', (tester) async {
    await _pumpGolden(
      tester,
      viewport: const Size(390, 844),
      designLabel: 'canonical mobile',
    );

    await expectLater(
      find.byType(MaterialApp),
      matchesGoldenFile('goldens/overview_neon_dark_390x844.png'),
    );
  });

  testWidgets('Neon dark desktop 1440x900 overview visual baseline', (tester) async {
    await _pumpGolden(
      tester,
      viewport: const Size(1440, 900),
      designLabel: 'canonical desktop',
    );

    await expectLater(
      find.byType(MaterialApp),
      matchesGoldenFile('goldens/overview_neon_dark_1440x900.png'),
    );
  });
}
