import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';
import 'package:trading_client/src/features/paper_runtime/data/paper_runtime_repository.dart';
import 'package:trading_client/src/presentation/overview_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _GuestRuntime implements PaperRuntimeRepository {
  int fetchCount = 0;

  @override
  Future<PaperRuntimeState> fetch() async {
    fetchCount++;
    throw StateError('Guest runtime must never be fetched.');
  }

  @override
  Future<PaperRuntimeState> setEmergencyStop({required bool halted}) async =>
      throw StateError('Unauthenticated PAPER control mutation is forbidden.');
}

class _GuestDashboard implements DashboardRepository {
  int fetchCount = 0;

  @override
  Future<DashboardSummary> fetchSummary() async {
    fetchCount++;
    throw StateError('Guest dashboard must never be fetched.');
  }
}

void main() {
  for (final brightness in Brightness.values) {
    for (final width in [320.0, 390.0]) {
      testWidgets('Guest Home shows one sign-in panel at ${width.toInt()}dp in ${brightness.name}', (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = Size(width, 844);
        addTearDown(() {
          tester.view.resetPhysicalSize();
          tester.view.resetDevicePixelRatio();
        });

        final runtime = _GuestRuntime();
        final dashboard = _GuestDashboard();
        await tester.pumpWidget(MaterialApp(
          theme: buildTheme(brightness),
          home: OverviewPage(
            session: OperatorSession(),
            dashboardRepository: dashboard,
            runtimeRepository: runtime,
          ),
        ));
        await tester.pumpAndSettle();

        expect(find.byKey(const ValueKey('dashboard-sign-in-required')), findsOneWidget);
        expect(find.byKey(const ValueKey('paper-runtime-sign-in')), findsNothing);
        expect(find.text('Sign in to view PAPER automation.'), findsNothing);
        expect(find.byKey(const ValueKey('dashboard-secure-sign-in')), findsOneWidget);
        expect(runtime.fetchCount, 0);
        expect(dashboard.fetchCount, 0);
        expect(tester.takeException(), isNull);
      });
    }
  }
}
