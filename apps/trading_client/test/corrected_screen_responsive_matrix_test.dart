import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';

class _UnusedHealthRepository implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() => throw StateError('health fetch not expected without authentication');
}

const _mandatoryWidths = <double>[320, 375, 411, 768, 1024, 1440];
const _correctedRoutes = <String>[
  '/instruments', '/market', '/research', '/scenarios', '/backtests', '/portfolio', '/orders', '/risk', '/news', '/notifications', '/audit', '/settings', '/operations/health',
];

Future<void> _pumpRoute(WidgetTester tester, String route, double width) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = Size(width, width < 600 ? 844 : 900);
  await tester.pumpWidget(
    TradingApp(
      repositoryFactory: _UnusedHealthRepository.new,
      session: OperatorSession(),
      initialRoute: route,
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('all routes render without overflow at mandatory widths', (tester) async {
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    for (final width in _mandatoryWidths) {
      for (final route in _correctedRoutes) {
        await _pumpRoute(tester, route, width);

        expect(tester.takeException(), isNull, reason: 'Flutter exception/overflow at width $width on route $route');
        expect(find.byKey(const ValueKey('global-design-style-control')), findsNothing);
        expect(find.byKey(const ValueKey('global-theme-mode-control')), findsNothing);
        expect(find.byKey(const ValueKey('global-workspace-navigation')), findsNothing);

        if (width < 900) {
          expect(find.byKey(const ValueKey('product-mobile-navigation')), findsOneWidget, reason: 'mobile product navigation missing at width $width on $route');
        } else {
          expect(find.text('Home'), findsOneWidget);
          expect(find.text('AI Research'), findsWidgets);
          expect(find.byKey(const ValueKey('product-mobile-navigation')), findsNothing);
        }

        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      }
    }
  });
}
