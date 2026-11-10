import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class _NoHealth implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() =>
      throw StateError('Navigation tests must not request health');
}

void main() {
  Future<void> gestureBack(WidgetTester tester) async {
    // Exercise Android/system back dispatch rather than any UI arrow.
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
  }

  for (final width in [320.0, 390.0]) {
    testWidgets('Android back traverses tabs then confirms exit at ${width.toInt()}dp',
        (tester) async {
      tester.view.physicalSize = Size(width, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(TradingApp(
        repositoryFactory: _NoHealth.new,
        session: OperatorSession(),
      ));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('mobile-nav-markets')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('market-guest-sign-in')),
          findsOneWidget);

      await gestureBack(tester);
      expect(find.byKey(const ValueKey('market-guest-sign-in')),
          findsNothing);
      expect(find.byKey(const ValueKey('minttick-home-exit-guard')),
          findsOneWidget);

      await gestureBack(tester);
      expect(find.text('Exit MintTick?'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('navigation-stay')));
      await tester.pumpAndSettle();
      expect(find.text('Exit MintTick?'), findsNothing);
      expect(find.byKey(const ValueKey('minttick-home-exit-guard')),
          findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('system back closes More sheet before popping a tab',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(TradingApp(
      repositoryFactory: _NoHealth.new,
      session: OperatorSession(),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('mobile-nav-markets')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('mobile-nav-more')));
    await tester.pumpAndSettle();
    expect(find.text('PAPER orders'), findsOneWidget);

    await gestureBack(tester);
    expect(find.text('PAPER orders'), findsNothing);
    expect(find.byKey(const ValueKey('market-guest-sign-in')), findsOneWidget);

    await gestureBack(tester);
    expect(find.byKey(const ValueKey('minttick-home-exit-guard')),
        findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('system back returns from Mind details before leaving Mind',
      (tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
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
    expect(find.byKey(const ValueKey('mind-screen-intelligence-hub')),
        findsOneWidget);

    await gestureBack(tester);
    expect(find.byKey(const ValueKey('mind-original-overview')),
        findsOneWidget);
    expect(find.byKey(const ValueKey('mobile-nav-mind')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
