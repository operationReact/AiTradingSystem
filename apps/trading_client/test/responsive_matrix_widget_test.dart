import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/core/session/operator_session.dart';
import 'package:trading_client/src/features/operations_health/data/health_repository.dart';
import 'package:trading_client/src/features/operations_health/domain/health_snapshot.dart';

class _NeverHealthRepository implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() => throw StateError('health fetch not expected');
}

Future<void> _pumpAt(WidgetTester tester, Size size) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
  addTearDown(() {
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });

  await tester.pumpWidget(
    TradingApp(
      repositoryFactory: _NeverHealthRepository.new,
      session: OperatorSession(),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  for (final width in [320.0, 375.0, 411.0]) {
    testWidgets('${width.toInt()}px mandatory mobile viewport remains usable', (tester) async {
      await _pumpAt(tester, Size(width, 844));

      expect(find.byKey(const ValueKey('workspace-grid-mobile')), findsOneWidget);
      expect(find.byKey(const ValueKey('global-design-style-control')), findsOneWidget);
      expect(find.byKey(const ValueKey('dashboard-state-empty')), findsOneWidget);
      expect(find.byKey(const ValueKey('dashboard-access-key')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('768px mandatory adaptive viewport remains usable', (tester) async {
    await _pumpAt(tester, const Size(768, 1024));

    expect(find.byKey(const ValueKey('workspace-grid-adaptive')), findsOneWidget);
    expect(find.byKey(const ValueKey('workspace-grid-mobile')), findsNothing);
    expect(find.byKey(const ValueKey('dashboard-access-key')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('1024px mandatory viewport enters desktop layout', (tester) async {
    await _pumpAt(tester, const Size(1024, 900));

    expect(find.byKey(const ValueKey('workspace-grid-desktop')), findsOneWidget);
    expect(find.byKey(const ValueKey('workspace-grid-adaptive')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('1440px mandatory desktop viewport keeps primary controls reachable', (tester) async {
    await _pumpAt(tester, const Size(1440, 900));

    expect(find.byKey(const ValueKey('workspace-grid-desktop')), findsOneWidget);
    expect(find.byKey(const ValueKey('dashboard-access-key')), findsOneWidget);
    expect(find.byKey(const ValueKey('dashboard-connect')), findsOneWidget);
    expect(find.byKey(const ValueKey('global-theme-mode-control')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
