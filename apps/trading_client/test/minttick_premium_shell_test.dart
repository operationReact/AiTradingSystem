import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/brand/minttick_brand.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/domain/health_snapshot.dart';
import 'package:trading_client/src/features/overview/presentation/overview_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _NoDashboard implements DashboardRepository {
  @override
  Future<DashboardSummary> fetchSummary() =>
      throw StateError('Guest Home must not request account data.');
}

class _NoHealth implements HealthRepository {
  @override
  Future<HealthSnapshot> fetch() =>
      throw StateError('Health fetch not expected.');
}

void main() {
  for (final brightness in [Brightness.dark, Brightness.light]) {
    testWidgets('Home uses the shared MintTick logo in ${brightness.name} mode', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(390, 844);
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(DesignStyle.neonFuturisticAi, brightness),
        home: OverviewPage(
          session: OperatorSession(),
          dashboardRepository: _NoDashboard(),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byType(MintTickLockup), findsOneWidget);
      expect(find.byType(MintTickMark), findsOneWidget);
      expect(find.byIcon(Icons.query_stats_rounded), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('Bottom navigation labels remain legible at 320dp', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(320, 740);
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
    await tester.pumpWidget(TradingApp(
      repositoryFactory: _NoHealth.new,
      session: OperatorSession(),
    ));
    await tester.pumpAndSettle();

    for (final name in ['home', 'markets', 'research', 'portfolio', 'more']) {
      final nav = find.byKey(ValueKey('mobile-nav-$name'));
      expect(nav, findsOneWidget);
      final texts = find.descendant(of: nav, matching: find.byType(Text));
      expect(texts, findsOneWidget);
      final label = tester.widget<Text>(texts);
      expect(label.style?.fontSize, greaterThanOrEqualTo(11.5));
    }
    expect(tester.takeException(), isNull);
  });
}
