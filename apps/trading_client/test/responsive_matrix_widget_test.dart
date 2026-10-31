import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

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

      expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
      for (final destination in ['home', 'markets', 'research', 'portfolio', 'more']) {
        expect(find.byKey(ValueKey('mobile-nav-$destination')), findsOneWidget);
      }
      expect(find.byKey(const ValueKey('global-design-style-control')), findsNothing);
      expect(find.byKey(const ValueKey('global-theme-mode-control')), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('768px mandatory adaptive viewport remains usable', (tester) async {
    await _pumpAt(tester, const Size(768, 1024));

    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget);
    expect(find.byKey(const ValueKey('mobile-nav-more')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('1024px mandatory viewport enters desktop product layout', (tester) async {
    await _pumpAt(tester, const Size(1024, 900));

    expect(find.text('AI Trading System'), findsOneWidget);
    expect(find.text('Control Center'), findsOneWidget);
    expect(find.byKey(const ValueKey('product-mobile-navigation')), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('1440px mandatory desktop viewport keeps product navigation reachable', (tester) async {
    await _pumpAt(tester, const Size(1440, 900));

    expect(find.text('Control Center'), findsOneWidget);
    expect(find.text('Strategies & Research'), findsOneWidget);
    expect(find.text('Portfolio & Orders'), findsOneWidget);
    expect(find.text('Learning & Operations'), findsOneWidget);
    expect(find.text('Settings'), findsOneWidget);
    expect(find.byKey(const ValueKey('global-theme-mode-control')), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
