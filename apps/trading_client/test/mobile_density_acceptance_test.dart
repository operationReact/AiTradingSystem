import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/core/session/operator_session.dart';
import 'package:trading_client/src/core/widgets/responsive_layout.dart';
import 'package:trading_client/src/features/operations_health/data/health_repository.dart';
import 'package:trading_client/src/features/overview/presentation/overview_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _UnusedDashboardRepository implements DashboardRepository {
  @override
  Future<DashboardSummary> fetchSummary() => throw StateError('dashboard fetch is not expected while signed out');
}

void main() {
  test('390px phone uses compact two-column product metrics', () {
    expect(ResponsiveLayout.classify(390), ResponsiveClass.mobile);
    expect(ResponsiveLayout.workspaceColumns(390), 2);
    expect(ResponsiveLayout.metricColumns(390), 2);
    expect(ResponsiveLayout.horizontalPadding(390), 12);
    expect(ResponsiveLayout.heroPadding(390), 14);
    expect(ResponsiveLayout.heroRadius(390), 18);
  });

  testWidgets('Home exposes account and quick access above the 390x844 fold', (tester) async {
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);

    await tester.pumpWidget(
      MaterialApp(
        theme: buildTheme(DesignStyle.neonFuturisticAi, Brightness.dark),
        home: OverviewPage(
          session: OperatorSession(),
          dashboardRepository: _UnusedDashboardRepository(),
        ),
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(find.text('See the market. Find the setup. Test it safely.'), findsOneWidget);
    expect(find.text('Sign in required'), findsOneWidget);
    expect(find.text('Quick access'), findsOneWidget);

    final accountTop = tester.getTopLeft(find.text('Sign in required')).dy;
    final quickAccessTop = tester.getTopLeft(find.text('Quick access')).dy;
    expect(accountTop, lessThan(430), reason: 'Account content should not be pushed deep below the hero on a normal phone.');
    expect(quickAccessTop, lessThan(650), reason: 'Quick access should remain visible within the first phone viewport.');
  });
}
