import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class _NoHealth implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() =>
      throw StateError('The guest flow must not fetch account health.');
}

Future<void> _pumpGuest(
  WidgetTester tester,
  String route, {
  double width = 390,
}) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = Size(width, 844);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  await tester.pumpWidget(TradingApp(
    repositoryFactory: _NoHealth.new,
    session: OperatorSession(),
    initialRoute: route,
  ));
  await tester.pumpAndSettle();
}

void main() {
  for (final width in [320.0, 390.0, 411.0]) {
    testWidgets('Sign-in has no trading tab bar at ${width.toInt()}dp', (tester) async {
      await _pumpGuest(tester, '/sign-in', width: width);
      expect(find.text('Welcome to MintTick'), findsOneWidget);
      expect(find.byKey(const ValueKey('product-mobile-navigation')), findsNothing);
      expect(find.byKey(const ValueKey('secure-sign-in-continue')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('Guest Markets remains usable without requesting catalogue credentials', (tester) async {
    await _pumpGuest(tester, '/market');
    expect(find.byKey(const ValueKey('market-guest-sign-in')), findsOneWidget);
    expect(find.byKey(const ValueKey('market-stock-list')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('market-guest-sign-in')));
    await tester.pumpAndSettle();
    expect(find.text('Welcome to MintTick'), findsOneWidget);
    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Guest Research offers real sign-in route and no fake candidates', (tester) async {
    await _pumpGuest(tester, '/research');
    expect(find.byKey(const ValueKey('research-guest-sign-in')), findsOneWidget);
    expect(find.byKey(const ValueKey('research-run-scan')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('research-guest-sign-in')));
    await tester.pumpAndSettle();
    expect(find.text('Welcome to MintTick'), findsOneWidget);
    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Guest Portfolio is account-gated without fabricated balance', (tester) async {
    await _pumpGuest(tester, '/portfolio');
    expect(find.byKey(const ValueKey('paper-portfolio-error')), findsOneWidget);
    expect(find.byKey(const ValueKey('portfolio-state-action')), findsOneWidget);
    expect(find.text('Account overview'), findsNothing);
    expect(find.byKey(const ValueKey('paper-portfolio-summary')), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
