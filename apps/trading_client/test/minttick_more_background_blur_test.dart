import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class _NoHealth implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() =>
      throw StateError('More sheet must not request health data');
}

void main() {
  for (final width in [320.0, 390.0]) {
    testWidgets('More blurs any previous page at ${width.toInt()}dp',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
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

      await tester.tap(find.byKey(const ValueKey('mobile-nav-more')));
      await tester.pumpAndSettle();

      // The modal barrier filters the visible route, while a separate layer
      // filters the bottom bar, which is outside the Navigator.
      expect(find.byKey(const ValueKey('more-background-blur')),
          findsOneWidget);
      expect(find.byKey(const ValueKey('more-navigation-blur')),
          findsOneWidget);
      expect(find.text('PAPER orders'), findsOneWidget);
      expect(find.byKey(const ValueKey('market-guest-sign-in')),
          findsOneWidget);
      expect(tester.takeException(), isNull);

      // Tapping outside the sheet must restore the original sharp page.
      await tester.tapAt(const Offset(15, 90));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('more-background-blur')),
          findsNothing);
      expect(find.byKey(const ValueKey('more-navigation-blur')),
          findsNothing);
      expect(find.byKey(const ValueKey('market-guest-sign-in')),
          findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('More keeps customer destinations sharp and Back removes blur',
      (tester) async {
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
    await tester.tap(find.byKey(const ValueKey('mobile-nav-more')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('more-background-blur')),
        findsOneWidget);
    expect(find.text('Connected brokers'), findsOneWidget);
    expect(find.text('MintTick Mind'), findsWidgets);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('more-background-blur')),
        findsNothing);
    expect(find.byKey(const ValueKey('more-navigation-blur')),
        findsNothing);
    expect(find.byKey(const ValueKey('mobile-nav-mind')),
        findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
