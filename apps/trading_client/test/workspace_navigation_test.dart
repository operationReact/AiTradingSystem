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
  testWidgets('mobile product navigation exposes the approved trading workspaces', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(
      TradingApp(
        repositoryFactory: _UnusedHealthRepository.new,
        session: OperatorSession(),
      ),
    );
    await tester.pump();

    final navigation = find.byKey(const ValueKey('product-mobile-navigation'));
    expect(navigation, findsOneWidget);
    for (final label in ['Home', 'Markets', 'Research', 'Portfolio', 'More']) {
      expect(find.text(label), findsOneWidget, reason: 'missing primary destination $label');
    }

    await tester.tap(find.text('More'));
    await tester.pumpAndSettle();

    expect(find.text('Workspace'), findsOneWidget);
    for (final label in [
      'Instruments',
      'Scenario & regime',
      'PAPER backtests',
      'PAPER orders',
      'Risk & approvals',
      'News evidence',
      'Notifications',
      'Audit trail',
      'System health',
      'Appearance',
    ]) {
      expect(find.text(label), findsOneWidget, reason: 'missing workspace destination $label');
    }

    await tester.tap(find.text('Scenario & regime'));
    await tester.pumpAndSettle();
    expect(find.text('Scenario evidence lookup'), findsOneWidget);
    expect(find.byKey(const ValueKey('scenario-run-id')), findsOneWidget);
    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
  });
}
