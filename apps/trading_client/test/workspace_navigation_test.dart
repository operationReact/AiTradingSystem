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
  testWidgets('mobile product navigation exposes customer trading destinations', (tester) async {
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

    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
    for (final destination in ['home', 'markets', 'research', 'portfolio', 'more']) {
      expect(find.byKey(ValueKey('mobile-nav-$destination')), findsOneWidget);
    }

    await tester.tap(find.byKey(const ValueKey('mobile-nav-more')));
    await tester.pumpAndSettle();

    expect(find.text('More'), findsWidgets);
    for (final label in ['PAPER orders', 'Risk controls', 'Backtests', 'Notifications', 'Appearance']) {
      expect(find.text(label), findsOneWidget, reason: 'missing customer destination $label');
    }

    for (final internalLabel in ['Scenario & regime', 'Audit trail', 'System health', 'News evidence']) {
      expect(find.text(internalLabel), findsNothing, reason: 'internal destination leaked into customer More sheet: $internalLabel');
    }

    await tester.tap(find.text('PAPER orders'));
    await tester.pumpAndSettle();
    expect(find.text('Practice a trade'), findsOneWidget);
    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
  });
}
