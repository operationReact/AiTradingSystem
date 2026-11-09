import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

class _NoHealth implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() =>
      throw StateError('Mind navigation should not fetch health.');
}

void main() {
  for (final width in [320.0, 390.0, 412.0]) {
    testWidgets('verified customer retains all six primary tabs at ${width.toInt()}dp', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final session = OperatorSession()..markCustomerAuthenticated();
      await tester.pumpWidget(TradingApp(
        repositoryFactory: _NoHealth.new,
        session: session,
        initialRoute: '/mind',
      ));
      await tester.pumpAndSettle();

      for (final tab in ['home', 'rooms', 'markets', 'mind', 'portfolio', 'more']) {
        expect(find.byKey(ValueKey('mobile-nav-$tab')), findsOneWidget);
      }
      expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
      expect(find.byType(MintickMindWorkspacePage), findsOneWidget);
      expect(find.byKey(const ValueKey('mind-original-overview')), findsOneWidget);
      expect(find.byTooltip('All MintTick Mind screens'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('Mind route picker opens an intelligence page and keeps app navigation', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(TradingApp(
      repositoryFactory: _NoHealth.new,
      session: OperatorSession()..markCustomerAuthenticated(),
      initialRoute: '/mind',
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byTooltip('All MintTick Mind screens'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Decision Intelligence').last);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('mind-screen-intelligence-hub')), findsOneWidget);
    expect(find.byKey(const ValueKey('mobile-nav-mind')), findsOneWidget);
    expect(find.byKey(const ValueKey('mobile-nav-more')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
